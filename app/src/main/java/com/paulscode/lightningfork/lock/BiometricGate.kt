package com.paulscode.lightningfork.lock

import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/** Biometric / device-credential unlock, degrading gracefully across API levels. */
object BiometricGate {

    // On 30+ we can combine a biometric with the device PIN/pattern as fallback;
    // below that the combination isn't supported, so require a biometric.
    private val authenticators: Int
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            BIOMETRIC_STRONG or DEVICE_CREDENTIAL
        } else {
            BIOMETRIC_WEAK
        }

    /** Whether any usable credential exists to gate the app with. */
    fun isAvailable(activity: FragmentActivity): Boolean =
        BiometricManager.from(activity).canAuthenticate(authenticators) ==
            BiometricManager.BIOMETRIC_SUCCESS

    fun prompt(
        activity: FragmentActivity,
        onSuccess: () -> Unit,
        onFailure: (String) -> Unit,
    ) {
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) =
                    onSuccess()

                override fun onAuthenticationError(code: Int, msg: CharSequence) =
                    onFailure(msg.toString())
            },
        )
        val builder = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Lightning Fork")
            .setAllowedAuthenticators(authenticators)
        // A negative button is required when device credential isn't among the
        // allowed authenticators (older APIs).
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            builder.setNegativeButtonText("Cancel")
        }
        prompt.authenticate(builder.build())
    }
}
