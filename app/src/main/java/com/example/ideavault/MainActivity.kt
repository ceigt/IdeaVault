package com.example.ideavault

import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel

class MainActivity : FragmentActivity() {
    private val sessionState by viewModels<SessionLockState>()
    private var authAvailable by mutableStateOf(true)
    private var authMessage by mutableStateOf("")
    private var lockTimeoutMillis by mutableStateOf(AppLockPreferences.DEFAULT_TIMEOUT_MILLIS)
    private lateinit var appLockPreferences: AppLockPreferences
    private var authenticationPromptShowing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        appLockPreferences = AppLockPreferences(this)
        lockTimeoutMillis = appLockPreferences.timeoutMillis()
        authMessage = getString(R.string.lock_message)

        setContent {
            IdeaVaultTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    if (sessionState.unlocked) {
                        NotesApp(
                            lockTimeoutMillis = lockTimeoutMillis,
                            onLockTimeoutChanged = { timeoutMillis ->
                                appLockPreferences.setTimeoutMillis(timeoutMillis)
                                lockTimeoutMillis = timeoutMillis
                            },
                        )
                    } else {
                        LockScreen(
                            message = authMessage,
                            canAuthenticate = authAvailable,
                            onUnlock = ::authenticate,
                            onUnsafeContinue = {
                                sessionState.hasAuthenticated = true
                                sessionState.unlocked = true
                            },
                        )
                    }
                }
            }
        }
        if (!sessionState.hasAuthenticated) authenticate()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations && sessionState.hasAuthenticated) {
            sessionState.backgroundedAtElapsedRealtime = SystemClock.elapsedRealtime()
            if (lockTimeoutMillis == AppLockPreferences.TIMEOUT_IMMEDIATELY) {
                sessionState.unlocked = false
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (!sessionState.hasAuthenticated) return

        val backgroundedAt = sessionState.backgroundedAtElapsedRealtime ?: return
        sessionState.backgroundedAtElapsedRealtime = null
        val timedOut = SystemClock.elapsedRealtime() - backgroundedAt >= lockTimeoutMillis
        if (timedOut) sessionState.unlocked = false
        if (!sessionState.unlocked) authenticate()
    }

    private fun authenticate() {
        if (authenticationPromptShowing) return
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        val status = BiometricManager.from(this).canAuthenticate(authenticators)
        if (status != BiometricManager.BIOMETRIC_SUCCESS) {
            authAvailable = false
            authMessage = getString(R.string.auth_unavailable_message)
            return
        }

        authAvailable = true
        authenticationPromptShowing = true
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    authenticationPromptShowing = false
                    sessionState.hasAuthenticated = true
                    sessionState.unlocked = true
                    sessionState.backgroundedAtElapsedRealtime = null
                    authMessage = getString(R.string.lock_message)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    authenticationPromptShowing = false
                    if (errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
                        errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON
                    ) authMessage = errString.toString()
                }

                override fun onAuthenticationFailed() {
                    authMessage = getString(R.string.auth_failed_message)
                }
            },
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(getString(R.string.unlock_app_title))
                .setSubtitle(getString(R.string.auth_prompt_subtitle))
                .setAllowedAuthenticators(authenticators)
                .build(),
        )
    }
}

class SessionLockState : ViewModel() {
    var unlocked by mutableStateOf(false)
    var hasAuthenticated = false
    var backgroundedAtElapsedRealtime: Long? = null
}
