package com.vaultplayer.backup

import android.net.Uri
import android.util.Base64
import android.util.JsonReader
import android.util.JsonWriter
import androidx.room.InvalidationTracker
import androidx.room.withTransaction
import com.vaultplayer.Vault
import com.vaultplayer.data.*
import com.vaultplayer.security.Kdf
import com.vaultplayer.security.KeystoreAes
import com.vaultplayer.storage.PlaybackState
import com.vaultplayer.storage.RootFiles
import com.vaultplayer.util.LowPrio
import com.vaultplayer.util.hash8
import com.vaultplayer.util.nameKey
import com.vaultplayer.util.sha256
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.security.SecureRandom
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.CipherOutputStream
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class BackupFile(
    val rootId: Long, val uri: Uri, val name: String, val hash8: String, val kind: String,
    val lastModified: Long, val size: Long, val own: Boolean
)

data class BackupStatus(val running: Boolean = false, val lastAt: Long = 0, val error: Boolean = false)
data class PeekInfo(val deviceId: String, val label: String, val createdAt: Long)
enum class RestoreMode { MERGE, REPLACE }

/**
 * Backup mã hóa (AES-GCM, khóa từ mật khẩu qua PBKDF2) ghi lên MỌI nguồn ngoài đang cắm.
 * Tên file theo thiết bị: ".vp_<hash8(deviceId)>.dat" (hiện tại) và ".bak" (bản trước).
 * Khôi phục khớp theo (volumeId, relativePath chuẩn hóa, dung lượng), KHÔNG theo URI.
 */
class BackupManager(private val v: Vault) {
    private val MAGIC = "VPB1".toByteArray()
    private val NAME_RE = Regex("^\\.vp_([0-9a-f]{8})\\.(dat|bak)$")
    private val AUTO_ALIAS = Regex("^(Clip|Ảnh) #\\d+$")
    private val cr get() = v.app.contentResolver
    private val dao get() = v.dao
    private val prefs get() = v.prefs
    private val run = Mutex()

    @Volatile var dirty = false
    val status = MutableStateFlow(BackupStatus(lastAt = v.prefs.lastBackupAt))
    val ownNewer = MutableStateFlow(false)
    val warnings = MutableStateFlow<List<String>>(emptyList())
    private var ownCheckTick = 0

    fun start() {
        dirty = prefs.lastChangeAt > prefs.lastBackupAt
        v.db.invalidationTracker.addObserver(object : InvalidationTracker.Observer("media_entry", "tag", "media_tag", "playlist", "playlist_item") {
            override fun onInvalidated(tables: Set<String>) { dirty = true; prefs.lastChangeAt = System.currentTimeMillis() }
        })
        v.scope.launch { loop() }
    }

    // ---- mật khẩu
    suspend fun setPassword(pass: CharArray): Boolean = withContext(Dispatchers.Default) {
        if (pass.size < 8) return@withContext false
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val key = Kdf.pbkdf2(pass, salt)
        prefs.backupSalt = Base64.encodeToString(salt, Base64.NO_WRAP)
        prefs.backupKeyWrapped = Base64.encodeToString(KeystoreAes.encrypt(KeystoreAes.MASTER, key), Base64.NO_WRAP)
        prefs.backupPassSet = true
        dirty = true
        true
    }

    private fun currentKey(): Pair<ByteArray, ByteArray>? {
        if (!prefs.backupPassSet || prefs.backupKeyWrapped.isEmpty()) return null
        return try {
            val salt = Base64.decode(prefs.backupSalt, Base64.NO_WRAP)
            val key = KeystoreAes.decrypt(KeystoreAes.MASTER, Base64.decode(prefs.backupKeyWrapped, Base64.NO_WRAP))
            salt to key
        } catch (e: Exception) { null }
    }

    // ---- lịch tự động
    private fun intervalMs(): Long = when {
        prefs.lastBackupSize < 5L * 1024 * 1024 -> 5 * 60_000L
        prefs.lastBackupSize < 20L * 1024 * 1024 -> 15 * 60_000L
        else -> 30 * 60_000L
    }

    private suspend fun loop() {
        while (true) {
            delay(30_000)
            try {
                if (++ownCheckTick % 4 == 0) checkOwnNewer()
                refreshWarnings()
                if (shouldAuto()) runBackup(false)
            } catch (e: CancellationException) { throw e } catch (e: Exception) { /* bỏ qua */ }
        }
    }

    private suspend fun shouldAuto(): Boolean =
        prefs.backupAuto && prefs.backupPassSet && dirty && !status.value.running &&
            v.sources.availableRoots.value.isNotEmpty() && PlaybackState.rootId.value == null &&
            v.sources.progress.value.values.none { it.running } && !ownNewer.value &&
            System.currentTimeMillis() - prefs.lastBackupAt >= intervalMs() && !v.repo.isEmptyData()

    /** Gọi khi app xuống nền: backup một lần nếu chế độ tự động và có thay đổi. */
    fun onBackground() {
        v.scope.launch {
            if (prefs.backupAuto && prefs.backupPassSet && dirty && !ownNewer.value && !v.repo.isEmptyData() &&
                v.sources.availableRoots.value.isNotEmpty() && PlaybackState.rootId.value == null) runBackup(false)
        }
    }

    suspend fun refreshWarnings() {
        val has = dao.countMedia() > 0
        val w = ArrayList<String>()
        if (has && !prefs.backupPassSet) w.add("Chưa đặt mật khẩu backup")
        else if (has && prefs.lastBackupAt == 0L) w.add("Chưa có bản backup nào")
        else if (has && prefs.lastChangeAt - prefs.lastBackupAt > 7L * 24 * 3600 * 1000) w.add("Bản backup gần nhất đã cũ")
        if (!prefs.backupAuto && dirty && has) w.add("Có thay đổi chưa backup")
        warnings.value = w
    }

    // ---- ghi backup
    suspend fun runBackup(manual: Boolean): Boolean = withContext(LowPrio.dispatcher) {
        if (!run.tryLock()) return@withContext false
        try {
            status.update { it.copy(running = true) }
            val (salt, key) = currentKey() ?: return@withContext false
            if (!manual && v.repo.isEmptyData()) return@withContext false        // không tự backup khi database trống
            val dev = v.repo.deviceInfo()
            dirty = false                                                         // thay đổi trong lúc dựng sẽ đặt lại cờ
            val data = buildSnapshot(dev, salt, key)
            val roots = v.sources.roots.value.filter { it.id in v.sources.availableRoots.value }
            var ok = false
            for (r in roots) if (writeToRoot(r, hash8(dev.deviceId), data)) ok = true
            if (ok) { prefs.lastBackupAt = System.currentTimeMillis(); prefs.lastBackupSize = data.size.toLong() } else dirty = true
            status.value = BackupStatus(false, prefs.lastBackupAt, !ok)
            refreshWarnings()
            ok
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { dirty = true; false }
        finally { status.update { it.copy(running = false) }; run.unlock() }
    }

    private fun writeToRoot(root: SourceRoot, h8: String, bytes: ByteArray): Boolean {
        val children = RootFiles.listRoot(cr, root)
        fun find(n: String): Uri? = children.firstOrNull { it.name == n }?.let { RootFiles.uriOf(root, it) }
        val dat = ".vp_$h8.dat"
        val bak = ".vp_$h8.bak"
        val tmpName = ".vp_$h8.tmp"
        find(tmpName)?.let { RootFiles.delete(cr, it) }
        val tmp = RootFiles.create(cr, root, tmpName) ?: return false
        if (!RootFiles.writeAll(cr, tmp, bytes)) { RootFiles.delete(cr, tmp); return false }
        val back = RootFiles.readAll(cr, tmp)                                     // kiểm tra toàn vẹn
        if (back == null || !sha256(back).contentEquals(sha256(bytes))) { RootFiles.delete(cr, tmp); return false }
        find(bak)?.let { RootFiles.delete(cr, it) }
        find(dat)?.let { RootFiles.rename(cr, it, bak) }
        return RootFiles.rename(cr, tmp, dat) != null
    }

    private suspend fun buildSnapshot(dev: DeviceInfo, salt: ByteArray, key: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        bos.write(MAGIC); bos.write(salt); bos.write(iv)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        c.updateAAD(MAGIC + salt)
        val w = JsonWriter(OutputStreamWriter(GZIPOutputStream(CipherOutputStream(bos, c)), Charsets.UTF_8))
        w.beginObject()
        w.name("v").value(1L)
        w.name("deviceId").value(dev.deviceId)
        w.name("label").value(dev.deviceLabel)
        w.name("createdAt").value(System.currentTimeMillis())
        w.name("counter").value(dao.counter("alias") ?: 1L)
        w.name("tags").beginArray()
        for (t in dao.allTags()) w.value(t.name)
        w.endArray()
        w.name("playlists").beginArray()
        for (p in dao.allPlaylists()) {
            w.beginObject(); w.name("n").value(p.name); w.name("t").value(p.type)
            w.name("f").value(p.tagFilter); w.name("m").value(p.matchMode); w.endObject()
        }
        w.endArray()
        w.name("media").beginArray()
        var after = 0L
        while (true) {
            val rows = dao.backupChunk(after, 2000)
            if (rows.isEmpty()) break
            for (r in rows) {
                w.beginObject()
                w.name("vol").value(r.vol); w.name("p").value(r.p); w.name("s").value(r.s)
                w.name("t").value(r.t); w.name("a").value(r.a); w.name("f").value(r.f); w.name("pos").value(r.pos)
                if (!r.tags.isNullOrEmpty()) w.name("tags").value(r.tags)
                if (!r.pls.isNullOrEmpty()) w.name("pls").value(r.pls)
                w.endObject()
            }
            after = rows.last().id
        }
        w.endArray()
        w.endObject()
        w.close()
        return bos.toByteArray()
    }

    // ---- liệt kê / xóa backup trên nguồn
    suspend fun listBackups(): List<BackupFile> = withContext(Dispatchers.IO) {
        val mine = hash8(v.repo.deviceInfo().deviceId)
        val out = ArrayList<BackupFile>()
        for (r in v.sources.roots.value.filter { it.id in v.sources.availableRoots.value }) {
            for (c in RootFiles.listRoot(cr, r)) {
                val m = NAME_RE.matchEntire(c.name) ?: continue
                out.add(BackupFile(r.id, RootFiles.uriOf(r, c), c.name, m.groupValues[1], m.groupValues[2], c.lastMod, c.size, m.groupValues[1] == mine))
            }
        }
        out.sortedByDescending { it.lastModified }
    }

    private suspend fun checkOwnNewer() {
        val newer = listBackups().any {
            it.own && it.kind == "dat" && it.lastModified > prefs.lastBackupAt + 60_000L && it.lastModified > prefs.backupAckedMtime
        }
        ownNewer.value = newer
    }

    /** Admin chọn "Giữ bản trong máy": cho phép ghi đè bản trên nguồn. */
    suspend fun ackOwnNewer() {
        prefs.backupAckedMtime = listBackups().filter { it.own }.maxOfOrNull { it.lastModified } ?: System.currentTimeMillis()
        ownNewer.value = false
    }

    /** Chỉ xóa file khớp tên đã biết VÀ magic của app. */
    suspend fun deleteBackup(f: BackupFile): Boolean = withContext(Dispatchers.IO) {
        if (!NAME_RE.matches(f.name)) return@withContext false
        val head = try { cr.openInputStream(f.uri)?.use { s -> ByteArray(4).also { s.read(it) } } } catch (e: Exception) { null }
        if (head == null || !head.contentEquals(MAGIC)) return@withContext false
        RootFiles.delete(cr, f.uri)
    }

    // ---- khôi phục
    suspend fun readFile(f: BackupFile): ByteArray? = withContext(Dispatchers.IO) { RootFiles.readAll(cr, f.uri) }

    private fun open(bytes: ByteArray, pass: CharArray): JsonReader? {
        if (bytes.size < 4 + 16 + 12 + 16 || !bytes.copyOfRange(0, 4).contentEquals(MAGIC)) return null
        val salt = bytes.copyOfRange(4, 20)
        val iv = bytes.copyOfRange(20, 32)
        return try {
            val key = Kdf.pbkdf2(pass, salt)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            c.updateAAD(MAGIC + salt)
            val plain = c.doFinal(bytes, 32, bytes.size - 32)
            JsonReader(InputStreamReader(GZIPInputStream(ByteArrayInputStream(plain)), Charsets.UTF_8))
        } catch (e: Exception) { null }
    }

    /** Đọc phần đầu (thiết bị, thời gian) sau khi nhập mật khẩu; sai mật khẩu trả null. */
    suspend fun peek(bytes: ByteArray, pass: CharArray): PeekInfo? = withContext(Dispatchers.IO) {
        val r = open(bytes, pass) ?: return@withContext null
        try {
            var id = ""; var label = ""; var at = 0L
            r.beginObject()
            while (r.hasNext()) {
                when (r.nextName()) {
                    "deviceId" -> id = r.nextString()
                    "label" -> label = r.nextString()
                    "createdAt" -> at = r.nextLong()
                    "counter" -> r.skipValue()
                    else -> break
                }
            }
            PeekInfo(id, label, at)
        } catch (e: Exception) { null } finally { runCatching { r.close() } }
    }

    private class Item(val vol: String, val p: String, val s: Long, val t: String, val a: String, val f: Boolean, val pos: Long, val tags: String, val pls: String)

    suspend fun restore(bytes: ByteArray, pass: CharArray, mode: RestoreMode, adoptId: Boolean): Boolean = withContext(Dispatchers.IO) {
        val r = open(bytes, pass) ?: return@withContext false
        try {
            var deviceId = ""; var counter = 0L
            if (mode == RestoreMode.REPLACE) {
                v.db.withTransaction {
                    dao.clearLinks(); dao.clearTags(); dao.clearPlaylistItems(); dao.clearPlaylists(); dao.clearFavorites(); dao.clearStaging()
                }
            }
            r.beginObject()
            while (r.hasNext()) {
                when (r.nextName()) {
                    "deviceId" -> deviceId = r.nextString()
                    "counter" -> counter = r.nextLong()
                    "tags" -> { r.beginArray(); while (r.hasNext()) v.repo.getOrCreateTag(r.nextString()); r.endArray() }
                    "playlists" -> {
                        r.beginArray()
                        while (r.hasNext()) {
                            var n = ""; var t = "MANUAL"; var f = ""; var m = "ANY"
                            r.beginObject()
                            while (r.hasNext()) when (r.nextName()) {
                                "n" -> n = r.nextString(); "t" -> t = r.nextString()
                                "f" -> f = r.nextString(); "m" -> m = r.nextString(); else -> r.skipValue()
                            }
                            r.endObject()
                            if (n.isNotEmpty() && dao.playlistByName(n) == null) dao.insertPlaylist(Playlist(name = n, type = t, tagFilter = f, matchMode = m))
                        }
                        r.endArray()
                    }
                    "media" -> {
                        r.beginArray()
                        val batch = ArrayList<Item>()
                        while (r.hasNext()) {
                            var vol = ""; var p = ""; var s = 0L; var t = "VIDEO"; var a = ""; var f = false; var pos = 0L; var tags = ""; var pls = ""
                            r.beginObject()
                            while (r.hasNext()) when (r.nextName()) {
                                "vol" -> vol = r.nextString(); "p" -> p = r.nextString(); "s" -> s = r.nextLong()
                                "t" -> t = r.nextString(); "a" -> a = r.nextString(); "f" -> f = r.nextBoolean()
                                "pos" -> pos = r.nextLong(); "tags" -> tags = r.nextString(); "pls" -> pls = r.nextString()
                                else -> r.skipValue()
                            }
                            r.endObject()
                            batch.add(Item(vol, p, s, t, a, f, pos, tags, pls))
                            if (batch.size >= 300) { applyBatch(batch, mode); batch.clear() }
                        }
                        applyBatch(batch, mode)
                        r.endArray()
                    }
                    else -> r.skipValue()
                }
            }
            r.endObject()
            dao.setCounter(Counter("alias", maxOf(dao.counter("alias") ?: 1L, counter)))
            if (adoptId && deviceId.isNotEmpty()) v.repo.adoptDeviceId(deviceId)
            dirty = true
            true
        } catch (e: Exception) { false } finally { runCatching { r.close() } }
    }

    private suspend fun applyBatch(items: List<Item>, mode: RestoreMode) {
        if (items.isEmpty()) return
        v.db.withTransaction { for (it in items) applyItem(it, mode) }
    }

    private suspend fun applyItem(i: Item, mode: RestoreMode) {
        val pk = nameKey(i.p)
        val local = dao.findByKey(i.vol, pk, i.s)
        if (local == null) {
            dao.insertStaging(RestoreStaging(volumeId = i.vol, pathKey = pk, fileSize = i.s, alias = i.a, mediaType = i.t, isFavorite = i.f, pos = i.pos, tags = i.tags, playlists = i.pls))
            return
        }
        val auto = AUTO_ALIAS.matches(local.aliasName)
        if ((mode == RestoreMode.REPLACE || auto) && i.a.isNotEmpty() && i.a != local.aliasName && dao.aliasExists(i.a) == 0) dao.setAlias(local.id, i.a)
        if (i.f && !local.isFavorite) dao.setFav(listOf(local.id), true)
        if (mode == RestoreMode.REPLACE || i.pos > local.lastPositionMs) dao.setPos(local.id, i.pos)
        i.tags.split(SEP).filter { it.isNotEmpty() }.forEach { tn ->
            val t = v.repo.getOrCreateTag(tn)
            if (t != null) dao.insertLinks(listOf(MediaTag(local.id, t.id)))
        }
        i.pls.split(SEP).filter { it.isNotEmpty() }.forEach { pl ->
            val parts = pl.split("\u001E")
            val p = dao.playlistByName(parts[0])
            if (p != null) dao.insertPlaylistItems(listOf(PlaylistItem(p.id, local.id, parts.getOrNull(1)?.toIntOrNull() ?: dao.nextPos(p.id))))
        }
    }
}
