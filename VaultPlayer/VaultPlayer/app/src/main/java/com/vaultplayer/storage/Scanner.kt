package com.vaultplayer.storage

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.room.withTransaction
import com.vaultplayer.data.*
import com.vaultplayer.util.LowPrio
import com.vaultplayer.util.joinPath
import com.vaultplayer.util.mediaTypeOf
import com.vaultplayer.util.nameKey
import com.vaultplayer.util.sameTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.util.UUID

/** Nguồn đang phát video: quét/backup/ghi gói thumbnail tạm dừng cho nguồn này. */
object PlaybackState { val rootId = MutableStateFlow<Long?>(null) }

data class ScanProgress(val rootId: Long, val running: Boolean, val dirs: Int = 0, val mode: String = "")

sealed class ScanOutcome {
    data class Done(val files: Int, val dirs: Int, val ms: Long) : ScanOutcome()
    data class NeedsConfirm(val missing: Int, val newFiles: Int) : ScanOutcome()
    data class Failed(val reason: String) : ScanOutcome()
}

class Staged(val rootId: Long, val mode: String) {
    class Edit(val e: MediaEntry, val size: Long, val lm: Long, val doc: String, val rel: String, val type: String)
    class Relink(val e: MediaEntry, val p: PendingEntry)

    val edits = ArrayList<Edit>()
    val restored = ArrayList<Long>()
    val docUpdates = ArrayList<Pair<Long, String>>()
    val missing = ArrayList<Long>()
    val relinks = ArrayList<Relink>()
    val newFiles = ArrayList<PendingEntry>()
    val clearPendingDirs = HashSet<String>()
    val dirStates = HashMap<String, DirState>()
    val deadDirs = ArrayList<String>()
    var files = 0
    var dirs = 0
}

class Scanner(
    ctx: Context,
    private val db: AppDatabase,
    private val dao: VaultDao,
    private val thumbsInvalidate: suspend (List<Long>) -> Unit,
    private val isReadable: (SourceRoot) -> Boolean
) {
    private val cr: ContentResolver = ctx.contentResolver
    /** Thay đổi lớn đang chờ Admin xác nhận. */
    val staged = MutableStateFlow<Staged?>(null)

    private val skipDirs = setOf("system volume information", "lost.dir", "android", "\$recycle.bin", "recycler")
    private fun skipDir(name: String) = name.startsWith(".") || nameKey(name) in skipDirs

    private class Node(val docId: String, val rel: String, val key: String, val parent: String, val depth: Int, val lm: Long, val fresh: Boolean)

    /**
     * "Quét" = chỉ đọc danh sách tên/dung lượng/ngày sửa (KHÔNG đọc nội dung file, KHÔNG tạo thumbnail).
     * Quét nhanh: thư mục đã biết chỉ hỏi metadata của chính nó; chỉ liệt kê nội dung khi ngày sửa đổi.
     * Ngày sửa của thư mục KHÔNG lan lên thư mục cha nên không bao giờ bỏ qua cả nhánh.
     */
    suspend fun scan(root: SourceRoot, full: Boolean, progress: (Int) -> Unit): ScanOutcome = withContext(LowPrio.dispatcher) {
        val t0 = SystemClock.elapsedRealtime()
        val tree = Uri.parse(root.treeUri)
        val known = dao.dirStates(root.id).associateBy { it.dirKey }
        val kidsOfKnown = known.values.groupBy { it.parentKey }
        val visited = HashSet<String>()
        val failed = ArrayList<String>()
        val orphans = ArrayList<MediaEntry>()
        val st = Staged(root.id, if (full) "đầy đủ" else "nhanh")

        val stack = ArrayList<Node>()
        stack.add(Node(DocUtil.rootDocId(tree), "", "", "\u0000", 0, 0L, false))

        while (stack.isNotEmpty()) {
            ensureActive()
            while (PlaybackState.rootId.value == root.id) delay(1000)   // nhường băng thông cho video đang phát
            val n = stack.removeAt(stack.size - 1)
            if (n.depth > 32) continue
            val ks = known[n.key]

            // --- quét nhanh: thư mục đã biết và không đổi => không liệt kê nội dung
            if (!full && ks != null && ks.lastModified != 0L) {
                var curLm = n.lm
                if (!n.fresh) {
                    val meta: ChildDoc? = try {
                        DocUtil.meta(cr, tree, n.docId)
                    } catch (e: Exception) {
                        if (DocUtil.isGone(e) && isReadable(root)) null else { failed.add(n.key); continue }
                    }
                    if (meta == null) continue          // thư mục không còn: không đánh dấu visited
                    curLm = meta.lastMod
                }
                if (curLm != 0L && curLm == ks.lastModified) {
                    visited.add(n.key)
                    st.dirs++; progress(st.dirs)
                    kidsOfKnown[n.key]?.forEach { c ->
                        stack.add(Node(c.documentId, c.relativeDirPath, c.dirKey, n.key, n.depth + 1, c.lastModified, false))
                    }
                    continue
                }
            }

            // --- liệt kê nội dung thư mục
            val kids = try { DocUtil.list(cr, tree, n.docId) } catch (e: Exception) { failed.add(n.key); continue }
            visited.add(n.key)
            st.dirs++; progress(st.dirs)
            st.dirStates[n.key] = DirState(root.id, n.key, n.rel, n.parent, n.docId, n.lm)
            st.clearPendingDirs.add(n.key)

            val dbEntries = dao.entriesInDir(root.id, n.key)
            val byKey = HashMap<String, MediaEntry>(dbEntries.size * 2 + 1)
            for (e in dbEntries) byKey[nameKey(e.relativePath.substringAfterLast('/'))] = e
            val seen = HashSet<String>()

            for (k in kids) {
                if (k.isDir) {
                    if (skipDir(k.name)) continue
                    stack.add(Node(k.docId, joinPath(n.rel, k.name), joinPath(n.key, nameKey(k.name)), n.key, n.depth + 1, k.lastMod, true))
                    continue
                }
                val type = mediaTypeOf(k.name, k.mime) ?: continue
                st.files++
                val nk = nameKey(k.name)
                seen.add(nk)
                val e = byKey[nk]
                val rel = joinPath(n.rel, k.name)
                if (e != null) {
                    val changed = e.mediaType != type || e.fileSize != k.size || !sameTime(e.lastModified, k.lastMod)
                    if (changed) st.edits.add(Staged.Edit(e, k.size, k.lastMod, k.docId, rel, type))
                    else {
                        if (e.isMissing) st.restored.add(e.id)
                        if (e.documentId != k.docId) st.docUpdates.add(e.id to k.docId)
                    }
                } else {
                    st.newFiles.add(PendingEntry(
                        rootId = root.id, relativePath = rel, pathKey = joinPath(n.key, nk), dirKey = n.key,
                        documentId = k.docId, mediaType = type, fileSize = k.size, lastModified = k.lastMod
                    ))
                }
            }
            for (e in dbEntries) {
                if (!e.isMissing && nameKey(e.relativePath.substringAfterLast('/')) !in seen) orphans.add(e)
            }
        }

        // Thư mục đã biết nhưng không còn được tham chiếu = đã bị xóa.
        // Chỉ kết luận khi cả cây duyệt xong không lỗi.
        if (failed.isEmpty()) {
            for (dk in known.keys) {
                if (dk !in visited) {
                    st.deadDirs.add(dk)
                    dao.entriesInDir(root.id, dk).filter { !it.isMissing }.forEach { orphans.add(it) }
                }
            }
        }

        // Ghép lại tệp bị đổi tên/di chuyển TRƯỚC khi xét ngưỡng. Tệp cũ không còn nên không thể đối chiếu nội dung;
        // dùng (loại + dung lượng + ngày sửa) và chỉ ghép khi duy nhất hai chiều.
        val candidates = HashMap<Long, List<PendingEntry>>()
        val reverse = HashMap<PendingEntry, Int>()
        val newBySize = st.newFiles.groupBy { it.fileSize }
        for (o in orphans) {
            val c = (newBySize[o.fileSize] ?: emptyList()).filter { it.mediaType == o.mediaType && sameTime(it.lastModified, o.lastModified) }
            candidates[o.id] = c
            c.forEach { reverse[it] = (reverse[it] ?: 0) + 1 }
        }
        val relinkedNew = HashSet<PendingEntry>()
        for (o in orphans) {
            val c = candidates[o.id] ?: continue
            if (c.size == 1 && reverse[c[0]] == 1) {
                st.relinks.add(Staged.Relink(o, c[0])); relinkedNew.add(c[0])
            } else st.missing.add(o.id)
        }
        if (relinkedNew.isNotEmpty()) st.newFiles.removeAll(relinkedNew)

        val active = dao.countActive(root.id)
        if (active > 0 && st.missing.size >= active && st.edits.isEmpty() && st.restored.isEmpty() && st.relinks.isEmpty()) {
            return@withContext ScanOutcome.Failed("Không đọc được nội dung nguồn")
        }
        if (active > 0 && st.missing.size * 2 > active) {
            staged.value = st
            return@withContext ScanOutcome.NeedsConfirm(st.missing.size, st.newFiles.size)
        }
        apply(st, full, t0)
        ScanOutcome.Done(dao.countActive(root.id), st.dirs, SystemClock.elapsedRealtime() - t0)
    }

    suspend fun confirmStaged() {
        val st = staged.value ?: return
        staged.value = null
        apply(st, st.mode == "đầy đủ", SystemClock.elapsedRealtime())
    }

    fun discardStaged() { staged.value = null }

    private fun aliasGroupCode(groupIndex: Int): String {
        var value = groupIndex + 1
        val code = StringBuilder()
        while (value > 0) {
            val digit = (value - 1) % 26
            code.append(('A'.code + digit).toChar())
            value = (value - 1) / 26
        }
        return code.reverse().toString()
    }

    private suspend fun renumberAliases() {
        val entries = dao.aliasEntries()
        if (entries.isEmpty()) return
        db.withTransaction {
            entries.forEachIndexed { index, entry ->
                var temporary: String
                do {
                    temporary = "__mv_renumber_${index}_${UUID.randomUUID()}"
                } while (dao.aliasExists(temporary) > 0)
                dao.setAlias(entry.id, temporary)
            }
            var groupIndex = -1
            var currentRootId: Long? = null
            var itemIndex = 0
            entries.forEach { entry ->
                if (entry.rootId != currentRootId) {
                    currentRootId = entry.rootId
                    groupIndex++
                    itemIndex = 0
                }
                itemIndex++
                dao.setAlias(entry.id, "${aliasGroupCode(groupIndex)}$itemIndex")
            }
        }
    }

    private suspend fun apply(st: Staged, full: Boolean, t0: Long) = withContext(LowPrio.dispatcher) {
        val rootId = st.rootId
        st.edits.chunked(500).forEach { b ->
            db.withTransaction { b.forEach { dao.applyEdit(it.e.id, it.size, it.lm, it.doc, it.rel, joinPath(it.e.dirKey, nameKey(it.rel.substringAfterLast('/'))), it.e.dirKey, it.type) } }
        }
        thumbsInvalidate(st.edits.map { it.e.id })
        st.restored.chunked(800).forEach { dao.unmarkMissing(it) }
        st.docUpdates.chunked(500).forEach { b -> db.withTransaction { b.forEach { dao.setDocId(it.first, it.second) } } }
        st.relinks.chunked(500).forEach { b ->
            db.withTransaction { b.forEach { r -> dao.relink(r.e.id, r.p.relativePath, r.p.pathKey, r.p.dirKey, r.p.documentId, r.p.fileSize, r.p.lastModified) } }
        }
        st.missing.chunked(800).forEach { dao.markMissing(it) }
        st.clearPendingDirs.chunked(300).forEach { b -> db.withTransaction { b.forEach { dao.deletePendingInDir(rootId, it) } } }
        st.newFiles.chunked(500).forEach { dao.insertPending(it) }
        st.dirStates.values.toList().chunked(500).forEach { dao.upsertDirStates(it) }
        st.deadDirs.chunked(500).forEach { dao.deleteDirStates(rootId, it) }
        val now = System.currentTimeMillis()
        val r = dao.root(rootId) ?: return@withContext
        dao.updateRoot(r.copy(
            lastScanAt = now,
            lastFullScanAt = if (full) now else r.lastFullScanAt,
            pendingNewCount = dao.countPending(rootId),
            lastFileCount = dao.countActive(rootId),
            lastDirCount = st.dirs,
            lastScanMs = SystemClock.elapsedRealtime() - t0,
            lastScanMode = st.mode
        ))
        renumberAliases()
    }

    /** Nhập theo lô; sau cùng đánh lại tên ảo cho toàn thư viện, giữ các dữ liệu backup còn lại. */
    suspend fun importPending(root: SourceRoot, progress: (Int, Int) -> Unit) = withContext(LowPrio.dispatcher) {
        val total = dao.countPending(root.id)
        var done = 0
        while (true) {
            ensureActive()
            val batch = dao.pendingBatch(root.id, 500)
            if (batch.isEmpty()) break
            db.withTransaction {
                val rows = ArrayList<MediaEntry>()
                val stagings = ArrayList<RestoreStaging?>()
                var provisionalNumber = dao.countMedia().toLong() + 1L
                for (p in batch) {
                    val stg = dao.findStaging(root.volumeId, p.pathKey, p.fileSize)
                    var alias: String
                    do {
                        alias = "A$provisionalNumber"
                        provisionalNumber++
                    } while (dao.aliasExists(alias) > 0 || rows.any { it.aliasName == alias })
                    rows.add(MediaEntry(
                        rootId = root.id, mediaType = p.mediaType, relativePath = p.relativePath, pathKey = p.pathKey,
                        dirKey = p.dirKey, documentId = p.documentId, aliasName = alias, isFavorite = stg?.isFavorite ?: false,
                        fileSize = p.fileSize, lastModified = p.lastModified, lastPositionMs = stg?.pos ?: 0L
                    ))
                    stagings.add(stg)
                }
                val ids = dao.insertMediaList(rows)
                for (i in ids.indices) {
                    val stg = stagings[i] ?: continue
                    val mid = ids[i]
                    if (mid <= 0) continue
                    stg.tags.split(SEP).filter { it.isNotEmpty() }.forEach { tn ->
                        val t = dao.tagByName(tn) ?: run { dao.insertTag(Tag(name = tn)); dao.tagByName(tn) }
                        if (t != null) dao.insertLinks(listOf(MediaTag(mid, t.id)))
                    }
                    stg.playlists.split(SEP).filter { it.isNotEmpty() }.forEach { pl ->
                        val parts = pl.split("\u001E")
                        val p = dao.playlistByName(parts[0])
                        if (p != null) dao.insertPlaylistItems(listOf(PlaylistItem(p.id, mid, parts.getOrNull(1)?.toIntOrNull() ?: dao.nextPos(p.id))))
                    }
                    dao.deleteStaging(stg.id)
                }
                dao.deletePending(batch.map { it.id })
            }
            done += batch.size
            progress(done, total)
            delay(20)                      // nhường nhịp cho giao diện giữa các lô
        }
        renumberAliases()
        val r = dao.root(root.id)
        if (r != null) dao.updateRoot(r.copy(pendingNewCount = dao.countPending(root.id), lastFileCount = dao.countActive(root.id)))
    }
}
