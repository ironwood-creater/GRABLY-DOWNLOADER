package com.grabbly.downloader.util

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal

/**
 * Unlocks the private folder with the phone's own lock: fingerprint, face, PIN, pattern or
 * password. Grabbly never sees or stores any of them.
 */
object DeviceLock {

    /** False when the phone has no screen lock set up, so there's nothing to unlock with. */
    fun isAvailable(context: Context): Boolean =
        context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

    /** Asks for the fingerprint, face or phone PIN; calls [onSuccess] once the user is verified. */
    fun unlock(activity: Activity, onSuccess: () -> Unit, onError: (String?) -> Unit = {}) {
        val builder = BiometricPrompt.Builder(activity)
            .setTitle("Open private folder")
            .setSubtitle("Use your fingerprint, face or phone PIN")
        if (Build.VERSION.SDK_INT >= 30) {
            builder.setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL,
            )
        } else {
            @Suppress("DEPRECATION")
            builder.setDeviceCredentialAllowed(true)
        }
        val prompt = try {
            builder.build()
        } catch (e: IllegalArgumentException) {
            onError(null)
            return
        }
        prompt.authenticate(
            CancellationSignal(),
            activity.mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    val cancelled = errorCode == BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED ||
                        errorCode == BiometricPrompt.BIOMETRIC_ERROR_CANCELED
                    onError(if (cancelled) null else errString.toString())
                }
            },
        )
    }
}
