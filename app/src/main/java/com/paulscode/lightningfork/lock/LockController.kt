package com.paulscode.lightningfork.lock

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * App-lock state (plan §10). The app starts locked; unlocking (via biometric or
 * device credential) reveals the vault and lets the e2e key be used. Re-locks
 * when the app has been in the background longer than [timeoutMs]. The device's
 * own keyguard already gates the keystore (setUnlockedDeviceRequired); this is an
 * additional per-app gate.
 */
class LockController(private val timeoutMs: Long = 60_000) {
    private val _locked = MutableStateFlow(true)
    val locked: StateFlow<Boolean> = _locked

    @Volatile private var backgroundedAt = 0L

    fun unlock() { _locked.value = false }

    fun onStop() { backgroundedAt = SystemClock.elapsedRealtime() }

    fun onStart() {
        if (!_locked.value && SystemClock.elapsedRealtime() - backgroundedAt > timeoutMs) {
            _locked.value = true
        }
    }

    /** No lockable credential on the device → don't strand the user behind a gate. */
    fun disable() { _locked.value = false }
}
