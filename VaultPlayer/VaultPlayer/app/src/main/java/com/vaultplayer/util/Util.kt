package com.vaultplayer.util

import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

fun nfc(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFC)
fun nameKey(s: String): String = nfc(s).lowercase(Locale.ROOT)
fun joinPath(a: String, b: String): String = if (a.isEmpty()) b else "$a/$b"
fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)
fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }
fun hash8(s: String): String = hex(sha256(s.toByteArray())).take(8)

fun fmtDuration(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

/** Dung sai 2 giây (FAT). Thiếu LAST_MODIFIED (0) thì coi là khớp, chỉ so dung lượng. */
fun sameTime(a: Long, b: Long): Boolean = a == 0L || b == 0L || kotlin.math.abs(a - b) <= 2000L

val VIDEO_EXT = setOf("mp4", "mkv", "avi", "webm", "ts")
val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp", "bmp", "gif", "heic", "heif", "avif")

fun mediaTypeOf(name: String, mime: String): String? {
    if (name.startsWith(".")) return null
    val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    return when {
        ext in VIDEO_EXT -> "VIDEO"
        ext in IMAGE_EXT -> "IMAGE"
        mime.startsWith("video/") -> "VIDEO"
        mime.startsWith("image/") -> "IMAGE"
        else -> null
    }
}

fun sanitizeName(s: String): String =
    s.filter { it >= ' ' && it != ',' && it != '\u007f' }.trim().take(40)
