package com.vaultplayer

import android.os.Bundle
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.vaultplayer.ui.RootContent
import com.vaultplayer.ui.VaultTheme

class MainActivity : FragmentActivity() {
    private val vault get() = (application as VaultApp).vault

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Chặn chụp/ghi màn hình và ẩn nội dung ở màn hình Recents.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        val canBio = BiometricManager.from(this)
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS
        setContent {
            VaultTheme { RootContent(vault, canBio) { cb -> promptBiometric(cb) } }
        }
    }

    private fun promptBiometric(onOk: () -> Unit) {
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Mở khóa Admin")
            .setNegativeButtonText("Dùng PIN")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
            .build()
        BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { onOk() }
        }).authenticate(info)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        vault.admin.touch()       // đặt lại bộ đếm tự khóa Admin
        return super.dispatchTouchEvent(ev)
    }
}
