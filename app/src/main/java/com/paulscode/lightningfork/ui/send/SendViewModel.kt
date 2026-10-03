package com.paulscode.lightningfork.ui.send

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.paulscode.lightningfork.data.AmountUnit
import com.paulscode.lightningfork.data.SettingsStore
import com.paulscode.lightningfork.net.ApiException
import com.paulscode.lightningfork.net.FeesResponse
import com.paulscode.lightningfork.net.NodeApi
import com.paulscode.lightningfork.net.OnchainEstimate
import com.paulscode.lightningfork.net.OnchainEstimateRequest
import com.paulscode.lightningfork.net.PaymentTarget
import com.paulscode.lightningfork.net.PendingSend
import com.paulscode.lightningfork.util.Format
import com.paulscode.lightningfork.wallet.WalletRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SendStep { Input, Review, Sending, Done, Failed }
enum class FeeLevel { Low, Medium, High }

data class SendResult(
    val lightning: Boolean,
    val amountSat: Long,
    val feeSat: Long,
    /** The preimage of a Lightning payment, the txid of an on-chain one. */
    val reference: String,
    /** A Bitcoin invoice paid: its amount, in Bitcoin. */
    val bitcoinAmountSat: Long? = null,
)

data class SendUi(
    val step: SendStep = SendStep.Input,
    val input: String = "",
    val decoding: Boolean = false,
    val inputError: String? = null,
    val target: PaymentTarget? = null,
    /** For a request that offers both, whether the user chose on-chain. */
    val payOnchain: Boolean = false,
    val unit: AmountUnit = AmountUnit.Sats,
    val amountText: String = "",
    val sendAll: Boolean = false,
    val payerNote: String = "",
    val fees: FeesResponse? = null,
    val feesError: String? = null,
    val feeLevel: FeeLevel = FeeLevel.Medium,
    val estimate: OnchainEstimate? = null,
    val estimating: Boolean = false,
    val estimateError: String? = null,
    val result: SendResult? = null,
    val error: String? = null,
    /** The failure left it unknown whether the payment went through. */
    val uncertain: Boolean = false,
    /** Uncertain, as a Bitcoin invoice the service is still paying. */
    val onItsWay: Boolean = false,
    /** Whether trying again makes sense after a refusal. */
    val retryable: Boolean = true,
    /** What is being sent, or asked about, is a Bitcoin invoice. */
    val sendingBitcoinInvoice: Boolean = false,
    /** A Bitcoin invoice's price is being read anew. */
    val repricing: Boolean = false,
    /** Why the review is shown again, such as a new price. */
    val reviewNotice: String? = null,
) {
    /** What will actually be paid: the request, or its on-chain alternative. */
    val active: PaymentTarget?
        get() = if (payOnchain && target?.fallback != null) target.fallback else target

    val onchain: Boolean get() = active?.kind == "onchain"

    val bitcoinInvoice: Boolean get() = active?.isBitcoinInvoice == true

    /**
     * Why a Bitcoin invoice can't be paid, by the node's own word; null when
     * it can, or this is not one.
     */
    val bitcoinInvoiceBlocker: String?
        get() {
            val t = active ?: return null
            if (!t.isBitcoinInvoice) return null
            if (!t.payable || t.estimate == null) return "Can't pay this now"
            return null
        }

    /**
     * The amount to send, in sats, if one is known. For a SHA256 invoice,
     * the most it can cost here: the service's ceiling and the routing to it.
     */
    val amountSat: Long?
        get() {
            val t = active ?: return null
            if (t.isBitcoinInvoice) return t.estimate?.let { it.maxIncomingSat + it.routingFeeLimitSat }
            if (onchain && sendAll) return estimate?.amountSat
            return if (t.amountEditable) Format.parseAmount(amountText, unit)?.takeIf { it > 0 } else t.amountSat
        }

    val satPerVbyte: Long?
        get() = fees?.let {
            when (feeLevel) {
                FeeLevel.Low -> it.low.satPerVbyte
                FeeLevel.Medium -> it.medium.satPerVbyte
                FeeLevel.High -> it.high.satPerVbyte
            }
        }
}

class SendViewModel(
    private val api: NodeApi,
    private val wallet: WalletRepository,
    private val settings: SettingsStore,
    prefill: String?,
    resume: Boolean = false,
) : ViewModel() {
    private val _ui = MutableStateFlow(SendUi(unit = settings.unit))
    val ui: StateFlow<SendUi> = _ui

    private var estimateJob: Job? = null
    private var requestId = NodeApi.newRequestId()
    private var lastSent: PendingSend? = null

    init {
        val pending = if (resume) settings.pendingSend else null
        if (pending != null) {
            // A send whose outcome the app never heard: ask about it now.
            requestId = pending.onchain?.requestId ?: pending.pay?.requestId ?: pending.bitcoinInvoice?.requestId ?: requestId
            execute(pending, again = true)
        } else if (!prefill.isNullOrBlank()) {
            submit(prefill)
        }
        // A sweep's amount follows the balance it sweeps.
        viewModelScope.launch {
            var last: Long? = null
            wallet.state.collect { st ->
                val confirmed = st.wallet?.onchain?.confirmedSat ?: return@collect
                if (last != null && confirmed != last && _ui.value.step == SendStep.Review && _ui.value.onchain && _ui.value.sendAll) {
                    _ui.update { it.copy(estimate = null) }
                    scheduleEstimate()
                }
                last = confirmed
            }
        }
    }

    fun onInput(text: String) = _ui.update { it.copy(input = text, inputError = null) }

    /** Decode [text] (or what was typed) and move to review if it can be paid. */
    fun submit(text: String? = null) {
        val input = (text ?: _ui.value.input).trim()
        if (input.isEmpty() || _ui.value.decoding) return
        estimateJob?.cancel()
        estimateJob = null
        _ui.update { it.copy(input = input, decoding = true, inputError = null, estimate = null, estimating = false, estimateError = null, reviewNotice = null) }
        viewModelScope.launch {
            try {
                val target = api.decode(input)
                when {
                    target.kind == "unsupported" ->
                        _ui.update { it.copy(decoding = false, inputError = target.message ?: "This can't be paid from this wallet.") }
                    target.ours ->
                        _ui.update { it.copy(decoding = false, inputError = "That was made by your own node. To move funds between your balances, open or close a channel in the dashboard.") }
                    target.expired ->
                        _ui.update { it.copy(decoding = false, inputError = "This request has expired. Ask for a new one.") }
                    else -> {
                        requestId = NodeApi.newRequestId()
                        _ui.update {
                            it.copy(
                                decoding = false,
                                step = SendStep.Review,
                                target = target,
                                payOnchain = false,
                                amountText = "",
                                sendAll = false,
                                payerNote = "",
                                estimate = null,
                                estimateError = null,
                                error = null,
                            )
                        }
                        if (target.kind == "onchain") loadFees()
                    }
                }
            } catch (e: ApiException) {
                _ui.update { it.copy(decoding = false, inputError = e.message) }
            } catch (e: Exception) {
                _ui.update { it.copy(decoding = false, inputError = "Can't reach your node to read this. Try again.") }
            }
        }
    }

    /** A scanned code: ignored while one is being read, so the camera's repeats don't stack. */
    fun onScanned(text: String) {
        if (_ui.value.decoding || _ui.value.step != SendStep.Input) return
        submit(text)
    }

    fun backToInput() {
        estimateJob?.cancel()
        estimateJob = null
        _ui.update { it.copy(step = SendStep.Input, target = null, error = null, estimate = null, estimating = false, estimateError = null, reviewNotice = null) }
    }

    fun choosePayOnchain(onchain: Boolean) {
        _ui.update { it.copy(payOnchain = onchain, estimate = null, estimateError = null, error = null) }
        requestId = NodeApi.newRequestId()
        if (onchain && _ui.value.fees == null) loadFees() else scheduleEstimate()
    }

    // Any change to what is sent drops the estimate for the old one at once,
    // so the screen never shows one amount with another's fee.
    fun onAmountText(text: String) {
        _ui.update { it.copy(amountText = text, error = null, estimate = null, estimateError = null) }
        scheduleEstimate()
    }

    fun toggleUnit() {
        val s = _ui.value
        val next = if (s.unit == AmountUnit.Sats) AmountUnit.Btc else AmountUnit.Sats
        val sats = Format.parseAmount(s.amountText, s.unit)
        settings.unit = next
        _ui.update { it.copy(unit = next, amountText = sats?.let { v -> Format.amount(v, next).replace(",", "") } ?: "") }
    }

    fun onSendAll(all: Boolean) {
        _ui.update { it.copy(sendAll = all, error = null, estimate = null, estimateError = null) }
        scheduleEstimate()
    }

    fun onPayerNote(text: String) = _ui.update { it.copy(payerNote = text.take(200)) }

    fun onFeeLevel(level: FeeLevel) {
        _ui.update { it.copy(feeLevel = level, estimate = null, estimateError = null) }
        scheduleEstimate()
    }

    fun reloadFees() {
        _ui.update { it.copy(feesError = null) }
        loadFees()
    }

    private fun loadFees() {
        viewModelScope.launch {
            try {
                val fees = api.fees()
                _ui.update { it.copy(fees = fees, feesError = null) }
                scheduleEstimate()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(feesError = (e as? ApiException)?.message ?: "Can't get fee rates from your node.") }
            }
        }
    }

    private fun scheduleEstimate() {
        estimateJob?.cancel()
        _ui.update { it.copy(estimate = null) }
        val s = _ui.value
        val t = s.active
        if (t == null || t.kind != "onchain") return
        val rate = s.satPerVbyte ?: return
        val amount = if (s.sendAll) null else (if (t.amountEditable) Format.parseAmount(s.amountText, s.unit) else t.amountSat)
        if (!s.sendAll && (amount == null || amount <= 0)) {
            _ui.update { it.copy(estimate = null, estimateError = null, estimating = false) }
            return
        }
        val job = viewModelScope.launch {
            delay(350)
            _ui.update { it.copy(estimating = true) }
            try {
                val est = api.estimateOnchain(
                    OnchainEstimateRequest(address = t.address ?: t.request, amountSat = amount, sendAll = s.sendAll, satPerVbyte = rate)
                )
                // A newer estimate was asked for meanwhile: this one is stale.
                if (estimateJob !== coroutineContext[Job]) return@launch
                _ui.update { it.copy(estimate = est, estimateError = null, estimating = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                if (estimateJob !== coroutineContext[Job]) return@launch
                _ui.update { it.copy(estimate = null, estimateError = e.message, estimating = false) }
            } catch (e: Exception) {
                if (estimateJob !== coroutineContext[Job]) return@launch
                _ui.update { it.copy(estimating = false, estimateError = "Couldn't work out the fee. Check the connection.") }
            }
        }
        estimateJob = job
    }

    /** Why the current review can't be sent yet, or null when it can. */
    fun blocker(s: SendUi = _ui.value): String? {
        val t = s.active ?: return "Nothing to pay"
        val w = wallet.state.value.wallet
        // Expiry by the clock now, not when the request was read.
        val expiresAt = t.expiresAt
        if (t.expired || (expiresAt != null && expiresAt > 0 && expiresAt <= System.currentTimeMillis() / 1000)) {
            return "This request has expired"
        }
        s.bitcoinInvoiceBlocker?.let { return it }
        if (s.onchain && s.estimateError != null) return "Can't send this yet"
        val amount = s.amountSat
        if (amount == null || amount <= 0) return if (s.onchain && s.sendAll) "Working out the fee…" else "Enter an amount"
        if (s.onchain) {
            if (s.satPerVbyte == null) return "Waiting for fee rates"
            if (s.estimating) return "Working out the fee…"
            val total = s.estimate?.totalSat ?: return "Working out the fee…"
            if (w != null && total > w.onchain.confirmedSat) return "More than your on-chain balance"
        } else if (w != null && amount > w.lightning.outboundSat) {
            return "More than your Lightning balance"
        }
        return null
    }

    fun send() {
        val s = _ui.value
        // One send per tap: a second tap while it runs does nothing.
        if (s.step != SendStep.Review) return
        if (blocker(s) != null) return
        val pending = SendRules.pendingFor(s, requestId, System.currentTimeMillis()) ?: return
        execute(pending)
    }

    /**
     * Sends [pending], or asks again about it: the same request with the same
     * id, which the node answers with the first outcome instead of sending
     * twice. Kept on the phone until the outcome is known.
     */
    private fun execute(pending: PendingSend, again: Boolean = false) {
        lastSent = pending
        settings.pendingSend = pending
        val bitcoin = pending.bitcoinInvoice != null
        _ui.update {
            it.copy(
                step = SendStep.Sending,
                error = null,
                uncertain = false,
                onItsWay = false,
                retryable = true,
                sendingBitcoinInvoice = bitcoin,
                repricing = false,
                reviewNotice = null,
            )
        }
        viewModelScope.launch {
            try {
                val bitcoinRequest = SendRules.bitcoinInvoiceRequest(pending, again)
                val result = if (pending.onchain != null) {
                    val res = api.sendOnchain(if (again) pending.onchain.copy(resume = true) else pending.onchain)
                    SendResult(false, pending.amountSat, pending.feeSat, res.txid)
                } else if (bitcoinRequest != null) {
                    val res = api.payBitcoinInvoice(bitcoinRequest)
                    when (res.status) {
                        "succeeded" -> SendResult(
                            true,
                            res.amountSat.takeIf { it > 0 } ?: pending.amountSat,
                            res.feeSat,
                            res.preimage,
                            bitcoinAmountSat = res.bitcoinInvoice?.amountSat?.takeIf { it > 0 } ?: pending.bitcoinAmountSat,
                        )
                        "failed" -> throw ApiException(400, "The payment failed.")
                        else -> throw java.io.IOException("payment ${res.status}")
                    }
                } else {
                    val res = api.pay(if (again) pending.pay!!.copy(resume = true) else pending.pay!!)
                    when (res.status) {
                        "succeeded" -> SendResult(true, res.amountSat.takeIf { it > 0 } ?: pending.amountSat, res.feeSat, res.preimage)
                        "failed" -> throw ApiException(400, "The payment failed.")
                        else -> throw java.io.IOException("payment ${res.status}")
                    }
                }
                settings.pendingSend = null
                _ui.update { it.copy(step = SendStep.Done, result = result) }
                wallet.refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                // Asking again, only the node's verdict on the payment settles
                // it: an error on the way (5xx, a removed key, a reused id)
                // leaves it as unknown as before, under the same id.
                if (SendRules.settles(e, again, bitcoin)) {
                    // The node said no: nothing moved, and a new attempt is a
                    // new payment.
                    settings.pendingSend = null
                    requestId = NodeApi.newRequestId()
                    if (bitcoin && e.code == SendRules.PRICE_CHANGED) {
                        // The service asks more than was agreed: the new
                        // price, for the user to agree to or not.
                        reprice(pending.bitcoinInvoice!!.request, e.message)
                        return@launch
                    }
                    _ui.update {
                        it.copy(
                            step = SendStep.Failed,
                            error = e.message,
                            uncertain = false,
                            retryable = !(bitcoin && e.code == SendRules.ALREADY_PAID),
                        )
                    }
                } else {
                    // A cut-off call or a server error: the money may have
                    // moved. Asking again with the same id is safe. A Bitcoin
                    // invoice may simply still be being paid.
                    _ui.update {
                        it.copy(step = SendStep.Failed, error = e.message, uncertain = true, onItsWay = SendRules.onItsWay(e, bitcoin))
                    }
                }
            } catch (e: com.paulscode.lightningfork.net.KeyUnavailableException) {
                // Nothing was sent now; on a re-ask, the first may have been.
                if (again) {
                    _ui.update { it.copy(step = SendStep.Failed, error = e.message, uncertain = true) }
                } else {
                    settings.pendingSend = null
                    _ui.update { it.copy(step = SendStep.Failed, error = e.message, uncertain = false) }
                }
            } catch (e: Exception) {
                _ui.update {
                    it.copy(
                        step = SendStep.Failed,
                        error = "Lost touch with your node before it answered. The payment may still go through.",
                        uncertain = true,
                    )
                }
            }
        }
    }

    /**
     * Reads a Bitcoin invoice's price anew and shows it for the user to agree
     * to again, with [notice] saying why. Nothing is paid until they do.
     */
    private fun reprice(request: String, notice: String?) {
        _ui.update { it.copy(step = SendStep.Sending, repricing = true, error = null, uncertain = false, onItsWay = false) }
        viewModelScope.launch {
            try {
                val target = api.decode(request)
                if (!target.isBitcoinInvoice) throw ApiException(400, target.message ?: "This can't be paid from this wallet.")
                requestId = NodeApi.newRequestId()
                _ui.update {
                    it.copy(
                        step = SendStep.Review,
                        repricing = false,
                        target = target,
                        payOnchain = false,
                        error = null,
                        reviewNotice = notice,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val why = (e as? ApiException)?.message ?: "Can't reach your node to get the new price."
                _ui.update {
                    it.copy(
                        step = SendStep.Failed,
                        repricing = false,
                        error = listOfNotNull(notice, why).joinToString(" "),
                        uncertain = false,
                    )
                }
            }
        }
    }

    /**
     * After a failure: an uncertain send is asked about again, unchanged; a
     * refused one is tried anew. A refused Bitcoin invoice first gets its
     * price again, since it may have moved.
     */
    fun retry() {
        val s = _ui.value
        if (s.step != SendStep.Failed) return
        val sent = lastSent
        val bitcoinRequest = sent?.bitcoinInvoice?.request ?: s.target?.takeIf { it.isBitcoinInvoice }?.request
        if (s.uncertain && sent != null) {
            execute(sent, again = true)
        } else if (bitcoinRequest != null) {
            if (s.retryable) reprice(bitcoinRequest, null)
        } else if (s.target != null) {
            _ui.update { it.copy(step = SendStep.Review, error = null) }
            send()
        }
    }

    /** Forget an uncertain send (the user checked their activity). */
    fun dismissUncertain() {
        settings.pendingSend = null
    }

    fun backToReview() = _ui.update { it.copy(step = SendStep.Review, error = null, reviewNotice = null) }
}
