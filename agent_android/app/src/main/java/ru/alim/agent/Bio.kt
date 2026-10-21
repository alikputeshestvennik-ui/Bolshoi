package ru.alim.agent

import android.app.Activity
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal

/** Подтверждение отпечатком пальца (или PIN телефона) — для переводов денег. */
object Bio {
    fun auth(act: Activity, title: String, done: (Boolean) -> Unit) {
        if (Build.VERSION.SDK_INT < 28) { done(false); return }
        val exec = act.mainExecutor
        var finished = false
        fun end(ok: Boolean) { if (!finished) { finished = true; done(ok) } }
        val b = BiometricPrompt.Builder(act).setTitle(title).setSubtitle("Деньги уйдут сразу после подтверждения")
        when {
            Build.VERSION.SDK_INT >= 30 ->
                b.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            Build.VERSION.SDK_INT >= 29 -> @Suppress("DEPRECATION") b.setDeviceCredentialAllowed(true)
            else -> b.setNegativeButton("Отмена", exec) { _, _ -> end(false) }
        }
        runCatching {
            b.build().authenticate(CancellationSignal(), exec, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) = end(true)
                override fun onAuthenticationError(code: Int, msg: CharSequence?) = end(false)
            })
        }.onFailure { end(false) }
    }
}
