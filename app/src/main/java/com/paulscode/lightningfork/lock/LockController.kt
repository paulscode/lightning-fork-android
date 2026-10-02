package com.paulscode.lightningfork.lock

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The app lock. The app starts locked; a biometric or the screen lock opens
 * it, and it locks again after [timeoutMs] in the background. It sits in
 * front of the balances and payments; the device key itself is protected by
 * the Keystore, usable only while the phone is unlocked.
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
