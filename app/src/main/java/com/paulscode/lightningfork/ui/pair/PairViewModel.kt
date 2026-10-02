package com.paulscode.lightningfork.ui.pair

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.paulscode.lightningfork.net.PairingPayload
import com.paulscode.lightningfork.net.TorController
import com.paulscode.lightningfork.pairing.PairPhase
import com.paulscode.lightningfork.pairing.PairResult
import com.paulscode.lightningfork.pairing.PairingCoordinator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PairUi(
    val scanning: Boolean = false,
    val payload: PairingPayload? = null,
    val label: String = "",
    val phase: PairPhase? = null,
    val error: String? = null,
    val recoverable: Boolean = true,
    val done: Boolean = false,
    val alias: String = "",
    /** The code came from a link, not from the user's own scan or paste. */
    val fromLink: Boolean = false,
)

class PairViewModel(
    private val coordinator: PairingCoordinator,
    val tor: TorController,
    defaultLabel: String,
) : ViewModel() {
    private val _ui = MutableStateFlow(PairUi(label = defaultLabel))
    val ui: StateFlow<PairUi> = _ui

    fun startScan() = _ui.update { it.copy(scanning = true, error = null) }
    fun stopScan() = _ui.update { it.copy(scanning = false) }

    /** A scanned code; anything that isn't a pairing code is ignored and scanning goes on. */
    fun onScanned(text: String) {
        if (_ui.value.payload != null) return
        val payload = coordinator.parsePayload(text) ?: return
        accept(payload)
    }

    /**
     * A code from a lightningfork://pair link. Any page or app can open one,
     * so it never replaces a code the user is already looking at, and the
     * confirm screen says where it came from.
     */
    fun onLinked(text: String) {
        if (_ui.value.payload != null || _ui.value.phase != null) return
        onPasted(text)
        if (_ui.value.payload != null) _ui.update { it.copy(fromLink = true) }
    }

    /** Pasted text; says so when it isn't a pairing code. */
    fun onPasted(text: String) {
        val payload = coordinator.parsePayload(text)
        if (payload == null) {
            _ui.update { it.copy(scanning = false, error = "That isn't a pairing code. Open the dashboard's menu, choose Mobile app, and scan the code shown there.") }
        } else {
            accept(payload)
        }
    }

    private fun accept(payload: PairingPayload) {
        if (coordinator.isExpired(payload)) {
            _ui.update { it.copy(scanning = false, error = "That code has expired. Make a new one in the dashboard.") }
            return
        }
        _ui.update { it.copy(scanning = false, payload = payload, error = null, fromLink = false) }
    }

    fun onLabel(v: String) = _ui.update { it.copy(label = v.take(64)) }

    fun reset() = _ui.update { it.copy(payload = null, phase = null, error = null, recoverable = true, fromLink = false) }

    fun pair() {
        val payload = _ui.value.payload ?: return
        if (_ui.value.phase != null) return
        val label = _ui.value.label.ifBlank { "Android phone" }
        _ui.update { it.copy(error = null, phase = PairPhase.Reaching) }
        viewModelScope.launch {
            when (val r = coordinator.pair(payload, label) { phase -> _ui.update { it.copy(phase = phase) } }) {
                is PairResult.Success -> _ui.update { it.copy(done = true, phase = PairPhase.Done, alias = r.alias) }
                is PairResult.Failure -> _ui.update { it.copy(error = r.message, recoverable = r.recoverable, phase = null) }
            }
        }
    }
}
