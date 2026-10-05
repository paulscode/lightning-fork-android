package com.paulscode.lightningfork.ui.send

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.paulscode.lightningfork.R
import com.paulscode.lightningfork.data.AmountUnit
import com.paulscode.lightningfork.data.SettingsStore
import com.paulscode.lightningfork.net.ApiException
import com.paulscode.lightningfork.net.FeesResponse
import com.paulscode.lightningfork.net.NodeApi
import com.paulscode.lightningfork.net.OnchainEstimate
import com.paulscode.lightningfork.net.OnchainEstimateRequest
import com.paulscode.lightningfork.net.PaymentTarget
import com.paulscode.lightningfork.net.PendingSend
import com.paulscode.lightningfork.ui.text.UiText
import com.paulscode.lightningfork.util.Format
import com.paulscode.lightningfork.wallet.WalletRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
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
    /** Paid from the node's own bridge: nothing spent here. */
    val fromOwnBridge: Boolean = false,
    /** ...and its SHA256 node's routing fee. */
    val sha256FeeSat: Long = 0,
)

data class SendUi(
    val step: SendStep = SendStep.Input,
    val input: String = "",
    val decoding: Boolean = false,
    val inputError: UiText? = null,
    val target: PaymentTarget? = null,
    /** For a request that offers both, whether the user chose on-chain. */
    val payOnchain: Boolean = false,
    val unit: AmountUnit = AmountUnit.Sats,
    val amountText: String = "",
    val sendAll: Boolean = false,
    val payerNote: String = "",
    val fees: FeesResponse? = null,
    val feesError: UiText? = null,
    val feeLevel: FeeLevel = FeeLevel.Medium,
    val estimate: OnchainEstimate? = null,
    val estimating: Boolean = false,
    val estimateError: UiText? = null,
    val result: SendResult? = null,
    val error: UiText? = null,
    /** The failure left it unknown whether the payment went through. */
    val uncertain: Boolean = false,
    /** Uncertain, as a Bitcoin invoice the service is still paying. */
    val onItsWay: Boolean = false,
    /** Whether trying again makes sense after a refusal. */
    val retryable: Boolean = true,
    /** What is being sent, or asked about, is a Bitcoin invoice. */
    val sendingBitcoinInvoice: Boolean = false,
    /** ...paid from the node's own bridge, with no service. */
    val sendingFromOwnBridge: Boolean = false,
    /** A Bitcoin invoice's price is being read anew. */
    val repricing: Boolean = false,
    /** Why the review is shown again, such as a new price. */
    val reviewNotice: UiText? = null,
    /** Asking about a payment sent before, not sending one. */
    val checking: Boolean = false,
    /** Asking again on its own while a SHA256 invoice is on its way. */
    val autoChecking: Boolean = false,
    /** The proof of a SHA256 invoice paid already, when the node had it. */
    val proof: String? = null,
    /** How long a payment the service holds can stay held, in hours. */
    val maxHoldHours: Long? = null,
    /** When trying again can help, after a wait (epoch ms). */
    val retryAtMs: Long? = null,
    /** Where to fix what stopped the payment. */
    val hint: UiText? = null,
    /** The service paid the SHA256 invoice and did not collect. */
    val needsOperator: Boolean = false,
    /** Refused as paid already: not a failure. */
    val alreadyPaid: Boolean = false,
    /** An unfinished payment from more than a day ago: not asked about, which could send it now. */
    val tooOldToCheck: Boolean = false,
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
    val bitcoinInvoiceBlocker: UiText?
        get() {
            val t = active ?: return null
            if (!t.isBitcoinInvoice) return null
            if (!t.payable || t.estimate == null) {
                return SendRules.blockerLabel(t.messageCode ?: t.estimate?.refusalCode)
            }
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
    private var pollJob: Job? = null
    /** An ask made on its own while a SHA256 invoice is on its way. */
    private var quietJob: Job? = null
    /** Bumped by every ask the user makes, so an ask made on its own meanwhile is not shown over it. */
    private var generation = 0
    private var pollUntilMs = 0L
    private var requestId = NodeApi.newRequestId()
    private var lastSent: PendingSend? = null

    init {
        val pending = if (resume) settings.pendingSend else null
        if (pending != null) {
            // A send whose outcome the app never heard: ask about it now.
            requestId = pending.onchain?.requestId ?: pending.pay?.requestId ?: pending.bitcoinInvoice?.requestId ?: requestId
            if (SendRules.tooOldToCheck(pending, System.currentTimeMillis())) {
                // The node keeps a request's outcome for a day; asked later,
                // one it never got would be sent now, at a fee or a price
                // from then. Said, not sent: the user checks their activity.
                lastSent = pending
                _ui.update {
                    it.copy(
                        step = SendStep.Failed,
                        error = UiText.of(R.string.send_too_old_to_check),
                        uncertain = true,
                        tooOldToCheck = true,
                    )
                }
            } else {
                execute(pending, again = true)
            }
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
                        _ui.update { it.copy(decoding = false, inputError = target.message?.let(UiText::raw) ?: UiText.of(R.string.send_cant_pay_from_wallet)) }
                    target.ours ->
                        _ui.update { it.copy(decoding = false, inputError = UiText.of(R.string.send_own_node)) }
                    target.expired ->
                        _ui.update { it.copy(decoding = false, inputError = UiText.of(R.string.send_request_expired_ask_new)) }
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
                _ui.update { it.copy(decoding = false, inputError = decodeError(e)) }
            } catch (e: Exception) {
                _ui.update { it.copy(decoding = false, inputError = UiText.of(R.string.send_cant_reach_node_to_read)) }
            }
        }
    }

    /**
     * A dashboard too old to pay SHA256 invoices says the recipient has not
     * upgraded; it is the user's own dashboard that is behind.
     */
    private fun decodeError(e: ApiException): UiText {
        // A dashboard that pays SHA256 invoices never says this to an app
        // that declares it can show them; only one too old to know them
        // does, and without a code.
        return if (e.code == null && e.message.contains("has not upgraded")) {
            UiText.of(R.string.send_dashboard_too_old)
        } else {
            UiText.raw(e.message)
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
        _ui.update { it.copy(unit = next, amountText = sats?.let { v -> Format.editable(v, next) } ?: "") }
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
                _ui.update { it.copy(feesError = (e as? ApiException)?.message?.let(UiText::raw) ?: UiText.of(R.string.send_cant_get_fee_rates)) }
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
                _ui.update { it.copy(estimate = null, estimateError = UiText.raw(e.message), estimating = false) }
            } catch (e: Exception) {
                if (estimateJob !== coroutineContext[Job]) return@launch
                _ui.update { it.copy(estimating = false, estimateError = UiText.of(R.string.send_cant_work_out_fee)) }
            }
        }
        estimateJob = job
    }

    /** Why the current review can't be sent yet, or null when it can. */
    fun blocker(s: SendUi = _ui.value): UiText? {
        val t = s.active ?: return UiText.of(R.string.send_blocker_nothing)
        val w = wallet.state.value.wallet
        // Expiry by the clock now, not when the request was read.
        val expiresAt = t.expiresAt
        if (t.expired || (expiresAt != null && expiresAt > 0 && expiresAt <= System.currentTimeMillis() / 1000)) {
            return UiText.of(R.string.send_blocker_expired)
        }
        s.bitcoinInvoiceBlocker?.let { return it }
        // Paid from the node's own bridge: nothing is spent here, so there
        // is no amount or balance of this wallet to check; the dashboard
        // checked the bridge's node can pay it.
        if (SendRules.paysFromOwnBridge(t)) return null
        if (s.onchain && s.estimateError != null) return UiText.of(R.string.send_blocker_cant_send_yet)
        val amount = s.amountSat
        if (amount == null || amount <= 0) return if (s.onchain && s.sendAll) UiText.of(R.string.send_blocker_working_out_fee) else UiText.of(R.string.send_blocker_enter_amount)
        if (s.onchain) {
            if (s.satPerVbyte == null) return UiText.of(R.string.send_blocker_waiting_for_rates)
            if (s.estimating) return UiText.of(R.string.send_blocker_working_out_fee)
            val total = s.estimate?.totalSat ?: return UiText.of(R.string.send_blocker_working_out_fee)
            if (w != null && total > w.onchain.confirmedSat) return UiText.of(R.string.send_blocker_over_onchain)
        } else if (w != null && amount > w.lightning.outboundSat) {
            return UiText.of(R.string.send_blocker_over_lightning)
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
     * twice. Kept on the phone until the outcome is known. [quiet]: asking on
     * its own while a SHA256 invoice is on its way, without leaving that
     * screen.
     */
    private fun execute(pending: PendingSend, again: Boolean = false, quiet: Boolean = false) {
        lastSent = pending
        settings.pendingSend = pending
        val bitcoin = pending.bitcoinInvoice != null
        if (!quiet) {
            generation++
            pollJob?.cancel()
            quietJob?.cancel()
            quietJob = null
            _ui.update {
                it.copy(
                    step = SendStep.Sending,
                    error = null,
                    uncertain = false,
                    onItsWay = false,
                    retryable = true,
                    sendingBitcoinInvoice = bitcoin,
                    sendingFromOwnBridge = pending.fromOwnBridge,
                    repricing = false,
                    reviewNotice = null,
                    checking = again,
                    autoChecking = false,
                    proof = null,
                    retryAtMs = null,
                    hint = null,
                    needsOperator = false,
                    alreadyPaid = false,
                )
            }
        }
        val gen = generation
        // An answer to an ask made on its own, after the user asked anew:
        // theirs is the one on screen.
        fun stale() = quiet && gen != generation
        val job = viewModelScope.launch {
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
                            fromOwnBridge = res.bitcoinInvoice?.source == com.paulscode.lightningfork.net.OWN_BRIDGE || pending.fromOwnBridge,
                            sha256FeeSat = res.bitcoinInvoice?.sha256FeeSat ?: 0,
                        )
                        "failed" -> throw ApiException(400, PAYMENT_FAILED, code = PAYMENT_FAILED_CODE)
                        else -> throw java.io.IOException("payment ${res.status}")
                    }
                } else {
                    val res = api.pay(if (again) pending.pay!!.copy(resume = true) else pending.pay!!)
                    when (res.status) {
                        "succeeded" -> SendResult(true, res.amountSat.takeIf { it > 0 } ?: pending.amountSat, res.feeSat, res.preimage)
                        "failed" -> throw ApiException(400, PAYMENT_FAILED, code = PAYMENT_FAILED_CODE)
                        else -> throw java.io.IOException("payment ${res.status}")
                    }
                }
                if (stale()) return@launch
                settings.pendingSend = null
                pollJob?.cancel()
                _ui.update { it.copy(step = SendStep.Done, result = result, autoChecking = false, checking = false) }
                wallet.refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                if (stale()) return@launch
                // Asking again, only the node's verdict on the payment settles
                // it: an error on the way (5xx, a removed key, a reused id)
                // leaves it as unknown as before, under the same id.
                if (SendRules.settles(e, again, bitcoin)) {
                    // The node said no: nothing moved, and a new attempt is a
                    // new payment.
                    settings.pendingSend = null
                    pollJob?.cancel()
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
                            error = errorText(e),
                            uncertain = false,
                            onItsWay = false,
                            autoChecking = false,
                            checking = false,
                            retryable = !bitcoin || SendRules.retryable(e.code),
                            proof = e.details?.preimage?.takeIf { p -> p.isNotBlank() },
                            retryAtMs = e.details?.retryAfterSeconds?.let { s -> System.currentTimeMillis() + s * 1000 },
                            hint = if (bitcoin) SendRules.dashboardHint(e.code) else null,
                            needsOperator = bitcoin && e.code == SendRules.NEEDS_OPERATOR,
                            alreadyPaid = bitcoin && e.code == SendRules.ALREADY_PAID,
                        )
                    }
                } else {
                    // A cut-off call or a server error: the money may have
                    // moved. Asking again with the same id is safe. A SHA256
                    // invoice may simply still be being paid, and is then
                    // asked about again on its own for a while.
                    val onItsWay = SendRules.onItsWay(e, bitcoin)
                    _ui.update {
                        it.copy(
                            step = SendStep.Failed,
                            error = errorText(e),
                            uncertain = true,
                            onItsWay = onItsWay,
                            checking = false,
                            maxHoldHours = e.details?.maxHoldHours ?: it.maxHoldHours,
                        )
                    }
                    if (onItsWay) keepChecking(pending) else _ui.update { it.copy(autoChecking = false) }
                }
            } catch (e: com.paulscode.lightningfork.net.KeyUnavailableException) {
                if (stale()) return@launch
                // Nothing was sent now; on a re-ask, the first may have been.
                if (again) {
                    _ui.update { it.copy(step = SendStep.Failed, error = keyText(e), uncertain = true, autoChecking = false, checking = false) }
                } else {
                    settings.pendingSend = null
                    _ui.update { it.copy(step = SendStep.Failed, error = keyText(e), uncertain = false, checking = false) }
                }
            } catch (e: Exception) {
                if (stale()) return@launch
                if (quiet && _ui.value.onItsWay) {
                    // Asked on its own and not answered: it is still on its
                    // way as far as anyone knows. Asked again later.
                    keepChecking(pending)
                    return@launch
                }
                _ui.update {
                    it.copy(
                        step = SendStep.Failed,
                        error = UiText.of(R.string.send_lost_touch),
                        uncertain = true,
                        onItsWay = false,
                        autoChecking = false,
                        checking = false,
                    )
                }
            }
        }
        if (quiet) quietJob = job
    }

    /**
     * While a SHA256 invoice is on its way, asks about it again every few
     * seconds, for a quarter of an hour; Check again stays there for after.
     */
    private fun keepChecking(pending: PendingSend) {
        val now = System.currentTimeMillis()
        if (pollJob?.isActive != true && !_ui.value.autoChecking) pollUntilMs = now + POLL_FOR_MS
        if (now >= pollUntilMs) {
            _ui.update { it.copy(autoChecking = false) }
            return
        }
        _ui.update { it.copy(autoChecking = true) }
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            delay(POLL_EVERY_MS)
            // Not while the app is in the background: no asks over Tor
            // nobody is looking at; it asks again on coming back.
            wallet.foreground.first { it }
            if (_ui.value.step == SendStep.Failed && _ui.value.onItsWay) execute(pending, again = true, quiet = true)
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
                if (!target.isBitcoinInvoice) {
                    failRepricing(notice, target.message?.let(UiText::raw) ?: UiText.of(R.string.send_cant_pay_from_wallet))
                    return@launch
                }
                requestId = NodeApi.newRequestId()
                _ui.update {
                    it.copy(
                        step = SendStep.Review,
                        repricing = false,
                        target = target,
                        payOnchain = false,
                        error = null,
                        reviewNotice = notice?.let(UiText::raw),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failRepricing(notice, (e as? ApiException)?.message?.let(UiText::raw) ?: UiText.of(R.string.send_cant_reach_node_for_price))
            }
        }
    }

    /** The new price could not be had: [notice], why it was asked for, and [why] not. */
    private fun failRepricing(notice: String?, why: UiText) {
        _ui.update {
            it.copy(
                step = SendStep.Failed,
                repricing = false,
                error = if (notice == null) why else UiText.Joined(listOf(UiText.raw(notice), why)),
                uncertain = false,
                hint = null,
                retryAtMs = null,
            )
        }
    }

    /** The node's own words, or the app's when it said the payment failed. */
    private fun errorText(e: ApiException): UiText =
        if (e.code == PAYMENT_FAILED_CODE) UiText.of(R.string.send_payment_failed) else UiText.raw(e.message)

    private fun keyText(e: com.paulscode.lightningfork.net.KeyUnavailableException): UiText =
        UiText.of(if (e.lost) R.string.app_key_lost else R.string.app_key_unavailable)

    /**
     * After a failure: an uncertain send is asked about again, unchanged; a
     * refused one is tried anew. A refused Bitcoin invoice first gets its
     * price again, since it may have moved.
     */
    fun retry() {
        val s = _ui.value
        if (s.step != SendStep.Failed) return
        if (s.retryAtMs != null && System.currentTimeMillis() < s.retryAtMs) return
        if (s.tooOldToCheck) return
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

    /** Whether there is a payment to try again: the one reviewed, or the one checked from Home. */
    fun canRetry(s: SendUi = _ui.value): Boolean =
        s.retryable && (s.target != null || lastSent?.bitcoinInvoice != null)

    private companion object {
        const val POLL_EVERY_MS = 8_000L
        const val POLL_FOR_MS = 15 * 60_000L

        /** The app's own word for a payment the node says failed; shown as send_payment_failed. */
        const val PAYMENT_FAILED = "The payment failed."

        /** The node answered a payment as failed: the app's own code for it, never the node's. */
        const val PAYMENT_FAILED_CODE = "app_payment_failed"
    }
}
