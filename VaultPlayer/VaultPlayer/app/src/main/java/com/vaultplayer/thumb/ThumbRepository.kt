package com.vaultplayer.thumb

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.LruCache
import com.vaultplayer.Vault
import com.vaultplayer.data.MediaEntry
import com.vaultplayer.viewer.ImageDecode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Hình xem trước: CHỈ nằm trong RAM trên điện thoại (không ghi file nào).
 * Nơi lưu bền duy nhất là gói mã hóa trên nguồn ngoài (PackStore). Tra cứu: RAM -> gói -> tạo từ file gốc.
 */
class ThumbRepository(private val v: Vault) {
    companion object {
        const val THUMB_W = 240
        val FRACTIONS = doubleArrayOf(0.10, 0.35, 0.60, 0.85)
        fun fp(m: MediaEntry) = "${m.fileSize}:${m.lastModified}"
    }

    private fun maxBytes(): Int {
        val byPref = v.prefs.cacheMb * 1024 * 1024L
        val byHeap = Runtime.getRuntime().maxMemory() / 6
        return minOf(byPref, byHeap).coerceAtLeast(4L * 1024 * 1024).toInt()
    }

    private val cache = object : LruCache<Long, Bitmap>(maxBytes()) {
        override fun sizeOf(key: Long, value: Bitmap): Int = value.byteCount
    }
    private val keyRoot = ConcurrentHashMap<Long, Long>()
    private val gate = Semaphore(2)

    fun applyCacheSize() { cache.resize(maxBytes()) }

    /** Hệ thống báo thiếu RAM. UI_HIDDEN (xuống nền thông thường) được bỏ qua có chủ đích. */
    fun onTrim(level: Int) {
        when {
            level >= 60 || level == 15 -> cache.evictAll()
            level == 40 || level == 10 -> cache.trimToSize(cache.maxSize() / 2)
        }
    }

    fun clearRoot(rootId: Long) {
        keyRoot.entries.filter { it.value == rootId }.forEach { cache.remove(it.key); keyRoot.remove(it.key) }
    }

    fun clearAll() { cache.evictAll(); keyRoot.clear() }

    suspend fun get(id: Long, frame: Int = 0): Bitmap? {
        val key = id * 4 + frame
        cache.get(key)?.let { return it }
        val m = v.dao.media(id) ?: return null
        if (m.rootId !in v.sources.availableRoots.value) return null
        val bmp = gate.withPermit {
            cache.get(key) ?: withContext(Dispatchers.IO) { fromPack(m, frame) ?: generate(m, frame) }
        } ?: return null
        cache.put(key, bmp)
        keyRoot[key] = m.rootId
        return bmp
    }

    /** Xem trước động: 4 khung (khung đầu hiện ngay, các khung sau tạo lười). */
    suspend fun frames(id: Long): List<Bitmap> = (0 until 4).mapNotNull { get(id, it) }

    private suspend fun fromPack(m: MediaEntry, frame: Int): Bitmap? {
        val jpeg = v.packs.read(m, frame) ?: return null
        return try {
            val o = android.graphics.BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
            android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, o)
        } catch (e: Exception) { null }
    }

    private suspend fun generate(m: MediaEntry, frame: Int): Bitmap? {
        val uri = v.sources.docUri(m) ?: return null
        val cr = v.app.contentResolver
        val raw: Bitmap? = if (m.mediaType == "IMAGE") {
            if (frame != 0) return null
            ImageDecode.decodeToWidth(cr, uri, THUMB_W)
        } else videoFrame(m, frame)
        raw ?: return null
        val small: Bitmap = (try { raw.copy(Bitmap.Config.RGB_565, false) } catch (e: Exception) { null }) ?: raw
        if (small !== raw) raw.recycle()
        val bos = ByteArrayOutputStream()
        small.compress(Bitmap.CompressFormat.JPEG, 70, bos)
        v.packs.enqueue(m, frame, bos.toByteArray())
        return small
    }

    private suspend fun videoFrame(m: MediaEntry, frame: Int): Bitmap? {
        val uri = v.sources.docUri(m) ?: return null
        val r = MediaMetadataRetriever()
        return try {
            v.app.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                r.setDataSource(pfd.fileDescriptor)
                val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                var w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                var h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                if (rot == 90 || rot == 270) { val t = w; w = h; h = t }
                if (dur > 0 && m.durationMs == null) v.dao.setDuration(m.id, dur)
                val tUs = (dur * FRACTIONS[frame.coerceIn(0, 3)] * 1000).toLong()
                // Khung gần nhất (keyframe) để nhanh. Nếu codec máy không giải mã được thì trả null -> ô giữ chỗ.
                // Phương án dự phòng (chưa làm): chụp khung từ LibVLC trong lúc phát.
                if (w > 0 && h > 0) {
                    r.getScaledFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, THUMB_W, maxOf(1, THUMB_W * h / w))
                } else r.getFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }
        } catch (e: Exception) { null } finally { runCatching { r.release() } }
    }

    /** Mục bị sửa/dọn: bỏ khỏi cache RAM và chỉ mục gói (khối cũ thành rác, nén lại sau). */
    suspend fun invalidate(ids: List<Long>) {
        if (ids.isEmpty()) return
        ids.forEach { id -> for (f in 0..3) { cache.remove(id * 4 + f); keyRoot.remove(id * 4 + f) } }
        ids.chunked(800).forEach { chunk ->
            val rows = v.dao.thumbRowsFor(chunk)
            rows.groupBy { it.rootId }.forEach { (rootId, rs) -> v.dao.addWasted(rootId, rs.sumOf { it.length.toLong() }) }
            v.dao.deleteThumbOfMedia(chunk)
        }
    }
}
