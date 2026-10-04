package com.paulscode.lightningfork.ui.receive

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.paulscode.lightningfork.data.AmountUnit
import com.paulscode.lightningfork.data.SettingsStore
import com.paulscode.lightningfork.net.AddressResponse
import com.paulscode.lightningfork.net.ApiException
import com.paulscode.lightningfork.net.InvoiceRequest
import com.paulscode.lightningfork.net.InvoiceResponse
import com.paulscode.lightningfork.net.NodeApi
import com.paulscode.lightningfork.util.Format
import com.paulscode.lightningfork.wallet.WalletRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.math.BigDecimal

enum class ReceiveTab { Lightning, Onchain }

data class ReceiveUi(
    val tab: ReceiveTab = ReceiveTab.Lightning,
    val unit: AmountUnit = AmountUnit.Sats,
    // Lightning
    val amountText: String = "",
    val memo: String = "",
    val creating: Boolean = false,
    val invoice: InvoiceResponse? = null,
    /** open, settled, expired or canceled. */
    val invoiceState: String = "open",
    val paidSat: Long = 0,
    // On-chain
    val address: AddressResponse? = null,
    val loadingAddress: Boolean = false,
    val onchainAmountText: String = "",
    /** Unconfirmed sats that arrived while the screen was open. */
    val incomingSat: Long = 0,
    val error: String? = null,
) {
    /** What the on-chain QR code carries: the address, with an amount if one is set. */
    val onchainUri: String?
        get() {
            val a = address?.address ?: return null
            val sats = Format.parseAmount(onchainAmountText, unit)?.takeIf { it > 0 }
                ?: return "bitcoin:" + a.uppercase()
            val btc = BigDecimal(sats).movePointLeft(8).stripTrailingZeros().toPlainString()
            return "bitcoin:$a?amount=$btc"
        }
}

class ReceiveViewModel(
    private val api: NodeApi,
    private val wallet: WalletRepository,
    private val settings: SettingsStore,
) : ViewModel() {
    private val _ui = MutableStateFlow(
        ReceiveUi(
            tab = if (settings.receiveTab == "onchain") ReceiveTab.Onchain else ReceiveTab.Lightning,
            unit = settings.unit,
        )
    )
    val ui: StateFlow<ReceiveUi> = _ui

    private var watcher: Job? = null

    init {
        if (_ui.value.tab == ReceiveTab.Onchain) loadAddress(fresh = false)
        watchIncoming()
    }

    fun selectTab(tab: ReceiveTab) {
        settings.receiveTab = if (tab == ReceiveTab.Onchain) "onchain" else "lightning"
        _ui.update { it.copy(tab = tab, error = null) }
        if (tab == ReceiveTab.Onchain && _ui.value.address == null) loadAddress(fresh = false)
    }

    fun toggleUnit() {
        val s = _ui.value
        val next = if (s.unit == AmountUnit.Sats) AmountUnit.Btc else AmountUnit.Sats
        fun convert(t: String) = Format.parseAmount(t, s.unit)?.let { Format.editable(it, next) } ?: ""
        settings.unit = next
        _ui.update { it.copy(unit = next, amountText = convert(s.amountText), onchainAmountText = convert(s.onchainAmountText)) }
    }

    fun onAmountText(t: String) = _ui.update { it.copy(amountText = t, error = null) }
    fun onMemo(t: String) = _ui.update { it.copy(memo = t.take(200)) }
    fun onOnchainAmountText(t: String) = _ui.update { it.copy(onchainAmountText = t) }

    fun createInvoice() {
        val s = _ui.value
        if (s.creating) return
        val amount = if (s.amountText.isBlank()) null else Format.parseAmount(s.amountText, s.unit)
        if (s.amountText.isNotBlank() && (amount == null || amount <= 0)) {
            _ui.update { it.copy(error = "That amount isn't valid.") }
            return
        }
        _ui.update { it.copy(creating = true, error = null) }
        viewModelScope.launch {
            try {
                val inv = api.createInvoice(InvoiceRequest(amountSat = amount, memo = s.memo.ifBlank { null }))
                _ui.update { it.copy(creating = false, invoice = inv, invoiceState = "open", paidSat = 0) }
                watchInvoice(inv)
            } catch (e: ApiException) {
                _ui.update { it.copy(creating = false, error = e.message) }
            } catch (e: Exception) {
                _ui.update { it.copy(creating = false, error = "Can't reach your node to make an invoice.") }
            }
        }
    }

    /** Back to the form, for another invoice. */
    fun newInvoice() {
        watcher?.cancel()
        _ui.update { it.copy(invoice = null, invoiceState = "open", paidSat = 0, amountText = "", memo = "") }
    }

    private fun watchInvoice(inv: InvoiceResponse) {
        watcher?.cancel()
        watcher = viewModelScope.launch {
            var failures = 0
            while (isActive) {
                delay(if (failures > 0) 6000 else 2500)
                // Not while the app is in the background.
                wallet.foreground.first { it }
                // Past its expiry an invoice can't be paid; stop asking.
                if (inv.expiresAt > 0 && System.currentTimeMillis() / 1000 > inv.expiresAt + 30) {
                    _ui.update { it.copy(invoiceState = "expired") }
                    break
                }
                try {
                    val st = api.invoiceStatus(inv.paymentHash)
                    failures = 0
                    if (st.state != _ui.value.invoiceState) {
                        _ui.update { it.copy(invoiceState = st.state, paidSat = st.amountPaidSat) }
                    }
                    if (st.state == "settled") {
                        wallet.refresh()
                        break
                    }
                    if (st.state == "expired" || st.state == "canceled") break
                } catch (e: Exception) {
                    failures++
                }
            }
        }
    }

    fun loadAddress(fresh: Boolean) {
        if (_ui.value.loadingAddress) return
        _ui.update { it.copy(loadingAddress = true, error = null) }
        viewModelScope.launch {
            try {
                val a = api.address(fresh)
                _ui.update { it.copy(address = a, loadingAddress = false) }
            } catch (e: ApiException) {
                _ui.update { it.copy(loadingAddress = false, error = e.message) }
            } catch (e: Exception) {
                _ui.update { it.copy(loadingAddress = false, error = "Can't reach your node to get an address.") }
            }
        }
    }

    /**
     * While receiving on-chain, notice a payment arriving: the unconfirmed
     * balance rising. The baseline comes only from a refresh made after the
     * screen opened (not the numbers cached from an earlier run), and follows
     * the balance down when something confirms, so an old amount or this
     * wallet's own change is not taken for a payment.
     */
    private fun watchIncoming() {
        val openedAt = System.currentTimeMillis()
        viewModelScope.launch {
            var base: Long? = null
            wallet.state.collect { st ->
                if (st.updatedAtMs < openedAt) return@collect
                val unconfirmed = st.wallet?.onchain?.unconfirmedSat ?: return@collect
                val b = minOf(base ?: unconfirmed, unconfirmed)
                base = b
                val incoming = unconfirmed - b
                if (incoming != _ui.value.incomingSat) _ui.update { it.copy(incomingSat = incoming) }
            }
        }
        // A fresh refresh now, rather than waiting for the next poll.
        viewModelScope.launch { wallet.refresh() }
    }
}
