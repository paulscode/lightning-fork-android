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
import com.paulscode.lightningfork.net.OnchainSendRequest
import com.paulscode.lightningfork.net.PayRequest
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
) {
    /** What will actually be paid: the request, or its on-chain alternative. */
    val active: PaymentTarget?
        get() = if (payOnchain && target?.fallback != null) target.fallback else target

    val onchain: Boolean get() = active?.kind == "onchain"

    /** The amount to send, in sats, if one is known. */
    val amountSat: Long?
        get() {
            val t = active ?: return null
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
            requestId = pending.onchain?.requestId ?: pending.pay?.requestId ?: requestId
            execute(pending)
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
        _ui.update { it.copy(input = input, decoding = true, inputError = null) }
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

    fun backToInput() = _ui.update { it.copy(step = SendStep.Input, target = null, error = null, estimate = null) }

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
        val t = s.active ?: return
        if (blocker(s) != null) return
        val amount = s.amountSat ?: return
        val pending = if (t.kind == "onchain") {
            PendingSend(
                onchain = OnchainSendRequest(
                    address = t.address ?: t.request,
                    amountSat = if (s.sendAll) null else amount,
                    sendAll = s.sendAll,
                    satPerVbyte = s.satPerVbyte ?: return,
                    requestId = requestId,
                ),
                amountSat = amount,
                feeSat = s.estimate?.feeSat ?: 0,
                startedAtMs = System.currentTimeMillis(),
            )
        } else {
            PendingSend(
                pay = PayRequest(
                    request = t.request,
                    amountSat = if (t.amountEditable) amount else null,
                    payerNote = s.payerNote.takeIf { it.isNotBlank() && t.kind == "offer" },
                    requestId = requestId,
                ),
                amountSat = amount,
                startedAtMs = System.currentTimeMillis(),
            )
        }
        execute(pending)
    }

    /**
     * Sends [pending], or asks again about it: the same request with the same
     * id, which the node answers with the first outcome instead of sending
     * twice. Kept on the phone until the outcome is known.
     */
    private fun execute(pending: PendingSend) {
        lastSent = pending
        settings.pendingSend = pending
        _ui.update { it.copy(step = SendStep.Sending, error = null, uncertain = false) }
        viewModelScope.launch {
            try {
                val result = if (pending.onchain != null) {
                    val res = api.sendOnchain(pending.onchain)
                    SendResult(false, pending.amountSat, pending.feeSat, res.txid)
                } else {
                    val res = api.pay(pending.pay!!)
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
                if (definite(e.status)) {
                    // The node said no: nothing moved, and a new attempt is a
                    // new payment.
                    settings.pendingSend = null
                    requestId = NodeApi.newRequestId()
                    _ui.update { it.copy(step = SendStep.Failed, error = e.message, uncertain = false) }
                } else {
                    // A cut-off call or a server error: the money may have
                    // moved. Asking again with the same id is safe.
                    _ui.update { it.copy(step = SendStep.Failed, error = e.message, uncertain = true) }
                }
            } catch (e: com.paulscode.lightningfork.net.KeyUnavailableException) {
                // Nothing was sent.
                settings.pendingSend = null
                _ui.update { it.copy(step = SendStep.Failed, error = e.message, uncertain = false) }
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
     * The node's own refusal, as opposed to an error on the way. 503 is the
     * node saying LND is not answering, before anything was sent; 502 and 504
     * are a cut-off.
     */
    private fun definite(status: Int) =
        (status in 400..499 && status != 408 && status != 425 && status != 429) || status == 503

    /** After a failure: an uncertain send is asked about again, unchanged; a refused one is tried anew. */
    fun retry() {
        val s = _ui.value
        val sent = lastSent
        if (s.uncertain && sent != null) {
            execute(sent)
        } else {
            _ui.update { it.copy(step = SendStep.Review, error = null) }
            send()
        }
    }

    /** Forget an uncertain send (the user checked their activity). */
    fun dismissUncertain() {
        settings.pendingSend = null
    }

    fun backToReview() = _ui.update { it.copy(step = SendStep.Review, error = null) }
}
