package com.vaultplayer.security

import android.os.SystemClock
import android.util.Base64
import com.vaultplayer.util.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.security.SecureRandom

class PinManager(private val prefs: Prefs) {
    val isSet: Boolean get() = prefs.pinHash.isNotEmpty()

    private fun hash(pin: String, salt: ByteArray): ByteArray = Kdf.pbkdf2(pin.toCharArray(), salt, 50_000)

    /** PIN tối thiểu 4 chữ số, không được là 1234 (mã khởi tạo). */
    fun set(pin: String): Boolean {
        if (pin.length < 4 || pin.length > 12 || !pin.all { it.isDigit() } || pin == "1234") return false
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        prefs.pinSalt = Base64.encodeToString(salt, Base64.NO_WRAP)
        prefs.pinHash = Base64.encodeToString(hash(pin, salt), Base64.NO_WRAP)
        prefs.pinFails = 0
        prefs.pinLockUntil = 0L
        return true
    }

    fun lockRemainingMs(): Long = (prefs.pinLockUntil - System.currentTimeMillis()).coerceAtLeast(0L)

    fun verify(pin: String): Boolean {
        if (!isSet || lockRemainingMs() > 0) return false
        val salt = Base64.decode(prefs.pinSalt, Base64.NO_WRAP)
        val expected = Base64.decode(prefs.pinHash, Base64.NO_WRAP)
        val ok = MessageDigest.isEqual(hash(pin, salt), expected)
        if (ok) {
            prefs.pinFails = 0
        } else {
            val f = prefs.pinFails + 1
            if (f >= 5) { prefs.pinFails = 0; prefs.pinLockUntil = System.currentTimeMillis() + 60_000L }
            else prefs.pinFails = f
        }
        return ok
    }
}

class AdminSession(private val prefs: Prefs, scope: CoroutineScope) {
    val isAdmin = MutableStateFlow(false)
    @Volatile private var last = SystemClock.elapsedRealtime()

    fun touch() { last = SystemClock.elapsedRealtime() }
    fun unlock() { isAdmin.value = true; touch() }
    fun lock() { isAdmin.value = false }

    init {
        scope.launch {
            while (true) {
                delay(5000)
                if (isAdmin.value && SystemClock.elapsedRealtime() - last > prefs.adminIdleMin * 60_000L) lock()
            }
        }
    }
}
