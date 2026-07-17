package com.example.ideavault

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
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

class MainActivity : FragmentActivity() {
    private var unlocked by mutableStateOf(false)
    private var authAvailable by mutableStateOf(true)
    private var authMessage by mutableStateOf("请验证身份以查看笔记")
    private var hasAuthenticated = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        setContent {
            IdeaVaultTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    if (unlocked) {
                        NotesApp()
                    } else {
                        LockScreen(
                            message = authMessage,
                            canAuthenticate = authAvailable,
                            onUnlock = ::authenticate,
                            onUnsafeContinue = { unlocked = true },
                        )
                    }
                }
            }
        }
        authenticate()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations && hasAuthenticated) unlocked = false
    }

    override fun onStart() {
        super.onStart()
        if (hasAuthenticated && !unlocked) authenticate()
    }

    private fun authenticate() {
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        val status = BiometricManager.from(this).canAuthenticate(authenticators)
        if (status != BiometricManager.BIOMETRIC_SUCCESS) {
            authAvailable = false
            authMessage = "设备尚未设置安全锁屏，无法启用应用锁"
            return
        }

        authAvailable = true
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    hasAuthenticated = true
                    unlocked = true
                    authMessage = "请验证身份以查看笔记"
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
                        errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON
                    ) authMessage = errString.toString()
                }

                override fun onAuthenticationFailed() {
                    authMessage = "验证失败，请重试"
                }
            },
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("解锁灵感记事")
                .setSubtitle("使用生物识别或设备锁屏凭据")
                .setAllowedAuthenticators(authenticators)
                .build(),
        )
    }
}
