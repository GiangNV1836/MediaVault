package com.vaultplayer.storage

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.provider.DocumentsContract
import androidx.core.content.ContextCompat
import com.vaultplayer.data.MediaEntry
import com.vaultplayer.data.Repo
import com.vaultplayer.data.SourceRoot
import com.vaultplayer.data.VaultDao
import com.vaultplayer.util.nameKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

sealed class SourceEvent {
    data class Attached(val rootId: Long) : SourceEvent()
    data class Detached(val rootId: Long) : SourceEvent()
}

/**
 * Quản lý nguồn ngoài (USB OTG / thẻ nhớ trong đầu đọc OTG). Phát hiện cắm/rút dựa trên TRẠNG THÁI VOLUME,
 * không dựa vào USB_DEVICE_ATTACHED/DETACHED (rút thẻ khỏi đầu đọc không tạo sự kiện USB).
 */
class SourceManager(
    private val app: Context,
    private val dao: VaultDao,
    private val repo: Repo,
    private val scope: CoroutineScope,
    val scanner: Scanner
) {
    private val cr = app.contentResolver
    val roots: StateFlow<List<SourceRoot>> = dao.rootsFlow().stateIn(scope, SharingStarted.Eagerly, emptyList())
    val availableRoots = MutableStateFlow<Set<Long>>(emptySet())
    val events = MutableSharedFlow<SourceEvent>(extraBufferCapacity = 32)
    val progress = MutableStateFlow<Map<Long, ScanProgress>>(emptyMap())
    val outcomes = MutableStateFlow<Map<Long, ScanOutcome>>(emptyMap())

    private val jobs = ConcurrentHashMap<Long, Job>()
    private val mutexes = ConcurrentHashMap<Long, Mutex>()
    private val refreshMutex = Mutex()
    private var poll: Job? = null
    private val flagsRw = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

    // ---- tiện ích
    fun rootById(id: Long): SourceRoot? = roots.value.firstOrNull { it.id == id }
    fun label(rootId: Long): String = "Nguồn " + (roots.value.indexOfFirst { it.id == rootId } + 1)
    fun docUri(m: MediaEntry): Uri? {
        val r = rootById(m.rootId) ?: return null
        return DocUtil.docUri(Uri.parse(r.treeUri), m.documentId)
    }

    fun isReadable(root: SourceRoot): Boolean = try {
        val tree = Uri.parse(root.treeUri)
        val c = cr.query(DocUtil.docUri(tree, DocUtil.rootDocId(tree)), arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)
        c?.use { it.moveToFirst() } ?: false
    } catch (e: Exception) { false }

    private fun isListable(root: SourceRoot): Boolean = try {
        val tree = Uri.parse(root.treeUri)
        DocUtil.list(cr, tree, DocUtil.rootDocId(tree)); true
    } catch (e: Exception) { false }

    // ---- vòng đời
    fun start() {
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED); addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_EJECT); addAction(Intent.ACTION_MEDIA_BAD_REMOVAL)
            addAction(Intent.ACTION_MEDIA_REMOVED)
            addDataScheme("file")
        }
        ContextCompat.registerReceiver(app, object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) { triggerRefresh() }
        }, f, ContextCompat.RECEIVER_EXPORTED)
        if (Build.VERSION.SDK_INT >= 30) {
            val sm = app.getSystemService(StorageManager::class.java)
            sm.registerStorageVolumeCallback(app.mainExecutor, object : StorageManager.StorageVolumeCallback() {
                override fun onStateChanged(volume: StorageVolume) { triggerRefresh() }
            })
        }
        triggerRefresh()
    }

    fun triggerRefresh() { scope.launch { refresh() } }

    /** Kiểm tra nhẹ khả năng truy cập mỗi 3-5 giây, chỉ khi app ở foreground. */
    fun onForeground() {
        poll?.cancel()
        poll = scope.launch { while (true) { refresh(); delay(3500) } }
    }

    fun onBackground() { poll?.cancel(); poll = null }

    suspend fun refresh() = refreshMutex.withLock {
        val list = dao.roots()
        val now = withContext(Dispatchers.IO) { list.filter { isReadable(it) }.map { it.id }.toSet() }
        val old = availableRoots.value
        if (now == old) return@withLock
        availableRoots.value = now                       // ẩn dữ liệu ngay (lọc ở tầng SQL)
        for (id in old - now) { jobs.remove(id)?.cancel(); events.tryEmit(SourceEvent.Detached(id)) }
        for (id in now - old) { events.tryEmit(SourceEvent.Attached(id)); onAttached(id) }
    }

    private fun onAttached(id: Long) {
        jobs[id]?.cancel()
        jobs[id] = scope.launch {
            // Chờ volume mount ổn định: thử lại có backoff tối đa ~15 giây.
            var wait = 1000L; var total = 0L; var ok = false
            while (total <= 15_000L) {
                val r = dao.root(id) ?: return@launch
                if (isListable(r)) { ok = true; break }
                delay(wait); total += wait; wait = minOf(wait * 2, 4000L)
            }
            if (!ok) return@launch
            val root = dao.root(id) ?: return@launch
            val overdue = System.currentTimeMillis() - root.lastFullScanAt > 30L * 24 * 3600 * 1000
            runScan(id, overdue)
        }
    }

    // ---- quét / nhập
    private fun setProgress(p: ScanProgress) { progress.update { it + (p.rootId to p) } }

    suspend fun runScan(rootId: Long, full: Boolean) {
        val m = mutexes.getOrPut(rootId) { Mutex() }
        if (!m.tryLock()) return                         // đang quét: gom lại, không quét chồng
        try {
            val root = dao.root(rootId) ?: return
            val firstImportAfterScan = full && root.lastFullScanAt == 0L && dao.countActive(rootId) == 0
            val mode = if (full) "đầy đủ" else "nhanh"
            setProgress(ScanProgress(rootId, true, 0, mode))
            val out = try {
                scanner.scan(root, full) { d -> if (d % 20 == 0) setProgress(ScanProgress(rootId, true, d, mode)) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { ScanOutcome.Failed("Lỗi khi quét") }
            outcomes.update { it + (rootId to out) }
            if (firstImportAfterScan && out is ScanOutcome.Done && dao.countPending(rootId) > 0) {
                val latestRoot = dao.root(rootId) ?: return
                scanner.importPending(latestRoot) { done, total ->
                    setProgress(ScanProgress(rootId, true, done, "nhập $done/$total"))
                }
            }
        } finally {
            setProgress(ScanProgress(rootId, false))
            m.unlock()
        }
    }

    fun scanNow(rootId: Long, full: Boolean) {
        if (rootId !in availableRoots.value) return
        if (jobs[rootId]?.isActive == true) return
        jobs[rootId] = scope.launch { runScan(rootId, full) }
    }

    fun importNow(rootId: Long) {
        if (rootId !in availableRoots.value || jobs[rootId]?.isActive == true) return
        jobs[rootId] = scope.launch {
            val root = dao.root(rootId) ?: return@launch
            try {
                scanner.importPending(root) { d, t -> setProgress(ScanProgress(rootId, true, d, "nhập $d/$t")) }
            } finally { setProgress(ScanProgress(rootId, false)) }
        }
    }

    fun confirmStaged() { scope.launch { scanner.confirmStaged() } }

    // ---- thêm / xóa nguồn
    private fun isUnder(child: String, parent: String) = parent.isEmpty() || child == parent || child.startsWith("$parent/")

    /** Trả về thông báo lỗi trung tính, hoặc null nếu thành công. */
    suspend fun addRoot(uri: Uri): String? = withContext(Dispatchers.IO) {
        val docId = try { DocumentsContract.getTreeDocumentId(uri) } catch (e: Exception) { return@withContext "Thư mục không hợp lệ" }
        val vol = docId.substringBefore(':')
        if (uri.authority != "com.android.externalstorage.documents" || vol == "primary" || vol == "home") {
            return@withContext "Chỉ hỗ trợ bộ nhớ ngoài"
        }
        try { cr.takePersistableUriPermission(uri, flagsRw) } catch (e: SecurityException) {
            try { cr.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            catch (e2: SecurityException) { return@withContext "Không thể lưu quyền truy cập" }
        }
        val base = docId.substringAfter(':')
        val existing = dao.roots().filter { it.volumeId == vol }
        for (e in existing) {
            val eb = DocumentsContract.getTreeDocumentId(Uri.parse(e.treeUri)).substringAfter(':')
            if (isUnder(base, eb)) return@withContext "Nguồn này đã có"
        }
        val newId = dao.insertRoot(SourceRoot(treeUri = uri.toString(), volumeId = vol, addedAt = System.currentTimeMillis()))
        // Chọn thư mục cha: gộp các nguồn con đã có vào nguồn mới (đổi tiền tố đường dẫn).
        for (e in existing) {
            val eb = DocumentsContract.getTreeDocumentId(Uri.parse(e.treeUri)).substringAfter(':')
            if (isUnder(eb, base)) {
                val rel = eb.removePrefix(base).trimStart('/')
                dao.reparentMedia(e.id, newId, rel, nameKey(rel))
                dao.deletePendingRoot(e.id); dao.deleteDirStatesRoot(e.id); dao.deleteThumbRoot(e.id)
                dao.deleteRootRow(e.id)
                runCatching { cr.releasePersistableUriPermission(Uri.parse(e.treeUri), flagsRw) }
            }
        }
        refresh()
        null
    }

    suspend fun removeRoot(rootId: Long) {
        val r = dao.root(rootId) ?: return
        jobs.remove(rootId)?.cancel()
        repo.deleteRootData(rootId)
        runCatching { cr.releasePersistableUriPermission(Uri.parse(r.treeUri), flagsRw) }
        refresh()
    }
}
