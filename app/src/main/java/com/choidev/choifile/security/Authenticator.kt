package com.choidev.choifile.security

import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * BiometricPrompt wrapper: strong biometrics, with the device PIN/pattern/password as fallback.
 * BIOMETRIC_STRONG | DEVICE_CREDENTIAL is not supported by the platform on API 28-29, so versions
 * below 30 accept weak biometrics plus device credential instead.
 */
class Authenticator(private val activity: FragmentActivity) {
    private val allowed = if (Build.VERSION.SDK_INT >= 30) BIOMETRIC_STRONG or DEVICE_CREDENTIAL else BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    private fun status() = BiometricManager.from(activity).canAuthenticate(allowed)

    /** True when a prompt can really be shown (biometric enrolled or a screen lock is set). */
    fun isAvailable(): Boolean = status() == BiometricManager.BIOMETRIC_SUCCESS

    /** The device has no screen lock and no biometrics at all, so there is nothing to verify against. */
    private fun nothingEnrolled(): Boolean = status() == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED

    /**
     * Shows the prompt; [onResult] gets true only on success.
     * If the device has no screen lock at all there is nothing to authenticate against: with [strict] false
     * that counts as success (so users are never locked out of their own data after removing the screen lock),
     * with [strict] true (used when turning protection on) it fails. Any other unavailability fails.
     */
    fun authenticate(title: String, subtitle: String?, strict: Boolean = false, onResult: (Boolean) -> Unit) {
        if (!isAvailable()) { onResult(!strict && nothingEnrolled()); return }
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onResult(true)
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onResult(false)
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setAllowedAuthenticators(allowed)
            .apply { if (subtitle != null) setSubtitle(subtitle) }
            .build()
        prompt.authenticate(info)
    }
}
