package com.vaultplayer.thumb

import android.net.Uri
import androidx.room.withTransaction
import com.vaultplayer.Vault
import com.vaultplayer.data.MediaEntry
import com.vaultplayer.data.PackInfo
import com.vaultplayer.data.SourceRoot
import com.vaultplayer.data.ThumbIndex
import com.vaultplayer.security.KeystoreAes
import com.vaultplayer.storage.ChildDoc
import com.vaultplayer.storage.PlaybackState
import com.vaultplayer.storage.RootFiles
import com.vaultplayer.util.LowPrio
import com.vaultplayer.util.hash8
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Gói thumbnail mã hóa trên nguồn ngoài (lớp TÙY CHỌN: ứng dụng chạy đầy đủ khi không có gói).
 * Tệp: ".vp_t_<hash8(deviceId)>.dat". Header 128 byte (magic + KCV). Mỗi khối: len(4) + iv(12) + ciphertext(AES-GCM, khóa Keystore).
 * Ghi append-only theo lô; chỉ mục (offset/length) nằm trong Room. Không bao giờ ghi đè vùng đã ghi (trừ nén lại).
 */
class PackStore(private val v: Vault) {
    private val cr get() = v.app.contentResolver
    private val dao get() = v.dao
    private val HEADER = 128
    private val MAGIC = "VPT1".toByteArray()

    private class Block(val mediaId: Long, val frame: Int, val fp: String, val jpeg: ByteArray)

    private val docs = ConcurrentHashMap<Long, Uri>()
    private val noPack: MutableSet<Long> = ConcurrentHashMap.newKeySet()
    private val queues = ConcurrentHashMap<Long, MutableList<Block>>()
    private val timers = ConcurrentHashMap<Long, Job>()
    private val writeMutex = Mutex()

    suspend fun packName(): String = ".vp_t_" + hash8(v.repo.deviceInfo().deviceId) + ".dat"
    private fun aad(mediaId: Long, frame: Int, fp: String) = "$mediaId:$frame:$fp".toByteArray()

    private fun headerBytes(): ByteArray {
        val kcv = KeystoreAes.encrypt(KeystoreAes.THUMB, "VPKCV".toByteArray(), "hdr".toByteArray())
        val b = ByteArray(HEADER)
        MAGIC.copyInto(b, 0); b[4] = 1; b[5] = kcv.size.toByte(); kcv.copyInto(b, 6)
        return b
    }

    private fun headerOk(uri: Uri): Boolean = try {
        cr.openInputStream(uri)?.use { s ->
            val b = ByteArray(HEADER)
            var n = 0
            while (n < HEADER) { val r = s.read(b, n, HEADER - n); if (r < 0) break; n += r }
            n == HEADER && b.copyOfRange(0, 4).contentEquals(MAGIC) &&
                String(KeystoreAes.decrypt(KeystoreAes.THUMB, b.copyOfRange(6, 6 + (b[5].toInt() and 0xFF)), "hdr".toByteArray())) == "VPKCV"
        } ?: false
    } catch (e: Exception) { false }

    /** Tìm/tạo gói. KCV không khớp (đã cài lại app/khóa mất) => coi là gói cũ của chính thiết bị này và tạo gói mới thay thế. */
    private suspend fun ensure(root: SourceRoot): Uri? {
        val name = packName()
        var uri = docs[root.id] ?: RootFiles.find(cr, root, name)
        if (uri != null && docs[root.id] == null && !headerOk(uri)) {
            RootFiles.delete(cr, uri)
            dao.deleteThumbRoot(root.id)
            dao.setPackInfo(PackInfo(root.id, HEADER.toLong(), 0))
            uri = null
        }
        if (uri == null) {
            uri = RootFiles.create(cr, root, name) ?: return null
            if (!RootFiles.writeAll(cr, uri, headerBytes())) return null
            dao.deleteThumbRoot(root.id)
            dao.setPackInfo(PackInfo(root.id, HEADER.toLong(), 0))
        }
        docs[root.id] = uri
        noPack.remove(root.id)
        return uri
    }

    suspend fun read(m: MediaEntry, frame: Int): ByteArray? {
        val idx = dao.thumbIdx(m.id, frame) ?: return null
        if (idx.fingerprint != ThumbRepository.fp(m) || m.rootId in noPack) return null
        val root = v.sources.rootById(m.rootId) ?: return null
        val uri = docs[root.id] ?: RootFiles.find(cr, root, packName())?.also { docs[root.id] = it }
        if (uri == null) { noPack.add(root.id); return null }
        return try {
            cr.openFileDescriptor(uri, "r")?.use { pfd ->
                val ch = FileInputStream(pfd.fileDescriptor).channel
                val buf = ByteBuffer.allocate(idx.length)
                var pos = idx.packOffset
                while (buf.hasRemaining()) { val n = ch.read(buf, pos); if (n < 0) return null; pos += n }
                val arr = buf.array()
                if (ByteBuffer.wrap(arr, 0, 4).int != idx.length - 4) return null
                KeystoreAes.decrypt(KeystoreAes.THUMB, arr.copyOfRange(4, arr.size), aad(m.id, frame, idx.fingerprint))
            }
        } catch (e: Exception) { null }   // GCM lỗi/thiếu khối: bỏ qua, ThumbRepository tạo lại
    }

    fun enqueue(m: MediaEntry, frame: Int, jpeg: ByteArray) {
        val list = queues.getOrPut(m.rootId) { Collections.synchronizedList(ArrayList()) }
        list.add(Block(m.id, frame, ThumbRepository.fp(m), jpeg))
        if (list.size >= 50) v.scope.launch { flush(m.rootId) }
        else if (timers[m.rootId]?.isActive != true) {
            timers[m.rootId] = v.scope.launch { delay(20_000); flush(m.rootId) }
        }
    }

    suspend fun flushAll() { queues.keys.toList().forEach { flush(it) } }

    suspend fun flush(rootId: Long) = withContext(LowPrio.dispatcher) {
        val q = queues[rootId] ?: return@withContext
        if (q.isEmpty()) return@withContext
        val root = v.sources.rootById(rootId) ?: return@withContext
        if (rootId !in v.sources.availableRoots.value) return@withContext        // chờ lần sau
        while (PlaybackState.rootId.value == rootId) delay(1000)               // không ghi khi đang phát từ nguồn này
        writeMutex.withLock {
            val batch = synchronized(q) { val c = ArrayList(q); q.clear(); c }
            if (batch.isEmpty()) return@withLock
            // Không ghi khi nguồn gần đầy; bỏ qua im lặng (tiếp tục RAM-only).
            val avail = RootFiles.availableBytes(cr, root)
            if (avail != null && avail < 200L * 1024 * 1024) return@withLock
            val cap = v.prefs.packCapMb * 1024L * 1024L
            var info = dao.packInfo(rootId) ?: PackInfo(rootId, HEADER.toLong(), 0)
            if (info.totalBytes > cap) { compact(root, cap); info = dao.packInfo(rootId) ?: info }
            val uri = ensure(root) ?: return@withLock
            try {
                cr.openFileDescriptor(uri, "rw")?.use { pfd ->
                    val ch = FileOutputStream(pfd.fileDescriptor).channel
                    var pos = ch.size()
                    val rows = ArrayList<ThumbIndex>()
                    for (b in batch) {
                        val enc = KeystoreAes.encrypt(KeystoreAes.THUMB, b.jpeg, aad(b.mediaId, b.frame, b.fp))
                        val bb = ByteBuffer.allocate(4 + enc.size)
                        bb.putInt(enc.size); bb.put(enc); bb.flip()
                        val start = pos
                        while (bb.hasRemaining()) pos += ch.write(bb, pos)
                        rows.add(ThumbIndex(b.mediaId, b.frame, rootId, start, 4 + enc.size, b.fp))
                    }
                    pfd.fileDescriptor.sync()                                   // đồng bộ xuống thẻ trước khi lập chỉ mục
                    val old = dao.thumbRowsFor(rows.map { it.mediaId }.distinct())
                        .filter { o -> rows.any { it.mediaId == o.mediaId && it.frameIndex == o.frameIndex } }
                    v.db.withTransaction {
                        dao.putThumbIdx(rows)
                        dao.setPackInfo(PackInfo(rootId, pos, (dao.packInfo(rootId)?.wastedBytes ?: 0L) + old.sumOf { it.length.toLong() }))
                    }
                }
            } catch (e: Exception) { docs.remove(rootId) /* rút giữa chừng: phần đuôi chưa lập chỉ mục bị bỏ qua, không hỏng khối cũ */ }
        }
    }

    /** Nén lại: chép các khối còn sống (bỏ khối cũ nhất theo thứ tự ghi nếu vượt trần) sang file mới rồi thay file cũ. */
    private suspend fun compact(root: SourceRoot, cap: Long): Boolean {
        val rows = dao.thumbRows(root.id)                                       // sắp theo offset: cũ nhất trước
        val target = (cap * 0.8).toLong()
        var live = HEADER.toLong() + rows.sumOf { it.length.toLong() }
        var i = 0
        val drop = ArrayList<ThumbIndex>()
        while (live > target && i < rows.size) { live -= rows[i].length; drop.add(rows[i]); i++ }
        val kept = rows.drop(i)
        val name = packName()
        val old = docs[root.id] ?: RootFiles.find(cr, root, name) ?: return false
        val tmp = RootFiles.create(cr, root, "$name.tmp") ?: return false
        val newRows = ArrayList<ThumbIndex>()
        var pos = HEADER.toLong()
        try {
            val ok = cr.openFileDescriptor(old, "r")?.use { src ->
                cr.openFileDescriptor(tmp, "rw")?.use { dst ->
                    val inCh = FileInputStream(src.fileDescriptor).channel
                    val outCh = FileOutputStream(dst.fileDescriptor).channel
                    outCh.write(ByteBuffer.wrap(headerBytes()), 0)
                    for (r in kept) {
                        val bb = ByteBuffer.allocate(r.length)
                        var p = r.packOffset
                        while (bb.hasRemaining()) { val n = inCh.read(bb, p); if (n < 0) break; p += n }
                        if (bb.hasRemaining()) continue                         // khối hỏng: bỏ
                        bb.flip()
                        var wp = pos
                        while (bb.hasRemaining()) wp += outCh.write(bb, wp)
                        newRows.add(r.copy(packOffset = pos)); pos += r.length
                    }
                    dst.fileDescriptor.sync()
                    true
                }
            } ?: false
            if (!ok) { RootFiles.delete(cr, tmp); return false }
        } catch (e: Exception) { RootFiles.delete(cr, tmp); return false }
        // Thay file cũ. Nếu bị gián đoạn giữa chừng, khối đọc lỗi sẽ tự được tạo lại.
        RootFiles.delete(cr, old)
        val renamed = RootFiles.rename(cr, tmp, name) ?: return false
        docs[root.id] = renamed
        val keys = newRows.map { it.mediaId to it.frameIndex }.toSet()
        val lost = kept.filter { (it.mediaId to it.frameIndex) !in keys }
        v.db.withTransaction {
            dao.deleteThumbIdx(drop + lost)
            dao.putThumbIdx(newRows)
            dao.setPackInfo(PackInfo(root.id, pos, 0))
        }
        return true
    }

    // ---- Admin
    suspend fun otherPacks(root: SourceRoot): List<ChildDoc> {
        val own = packName()
        return RootFiles.listRoot(cr, root).filter { !it.isDir && it.name.startsWith(".vp_t_") && it.name != own }
    }

    fun deleteDoc(root: SourceRoot, c: ChildDoc): Boolean = RootFiles.delete(cr, RootFiles.uriOf(root, c))

    suspend fun deleteOwn(root: SourceRoot) {
        RootFiles.find(cr, root, packName())?.let { RootFiles.delete(cr, it) }
        dao.deleteThumbRoot(root.id)
        dao.setPackInfo(PackInfo(root.id, 0, 0))
        docs.remove(root.id)
        queues.remove(root.id)
    }
}
