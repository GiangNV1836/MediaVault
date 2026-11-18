package com.vaultplayer

import android.app.Application
import android.content.Intent
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.vaultplayer.backup.BackupManager
import com.vaultplayer.data.*
import com.vaultplayer.security.AdminSession
import com.vaultplayer.security.KeystoreAes
import com.vaultplayer.security.PinManager
import com.vaultplayer.storage.Scanner
import com.vaultplayer.storage.SourceManager
import com.vaultplayer.thumb.PackStore
import com.vaultplayer.thumb.ThumbRepository
import com.vaultplayer.util.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.system.exitProcess

sealed class DbState {
    object Loading : DbState()
    object Ready : DbState()
    object Error : DbState()
}

/** Bộ định vị dịch vụ của ứng dụng (một thể hiện duy nhất). Database mở ở luồng nền. */
class Vault(val app: Application) {
    val prefs = Prefs(app)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val state = MutableStateFlow<DbState>(DbState.Loading)
    val pin = PinManager(prefs)
    val admin = AdminSession(prefs, scope)

    lateinit var db: AppDatabase
    lateinit var dao: VaultDao
    lateinit var repo: Repo
    lateinit var thumbs: ThumbRepository
    lateinit var packs: PackStore
    lateinit var scanner: Scanner
    lateinit var sources: SourceManager
    lateinit var backup: BackupManager

    fun start() { scope.launch(Dispatchers.IO) { open() } }

    private suspend fun open() {
        try {
            db = buildDb(app)
            db.openHelper.writableDatabase            // ép mở ngay: sai khóa/hỏng file sẽ ném lỗi ở đây
            dao = db.dao()
            repo = Repo(db, dao, prefs)
            thumbs = ThumbRepository(this)
            packs = PackStore(this)
            scanner = Scanner(app, db, dao, { thumbs.invalidate(it) }, { sources.isReadable(it) })
            sources = SourceManager(app, dao, repo, scope, scanner)
            backup = BackupManager(this)
            state.value = DbState.Ready
            withContext(Dispatchers.Main) { hookLifecycle() }
            sources.start()
            backup.start()
        } catch (t: Throwable) {
            // KHÔNG tự xóa database: giữ nguyên file, báo trung tính, để người dùng quyết định.
            state.value = DbState.Error
        }
    }

    private fun hookLifecycle() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) { sources.onForeground() }
            override fun onStop(owner: LifecycleOwner) {
                sources.onBackground()
                admin.lock()
                backup.onBackground()
                scope.launch { packs.flushAll() }
            }
        })
    }

    /** Dọn bản ghi mất file (Admin, đã xác nhận). Trả về số bản ghi đã xóa. */
    suspend fun cleanup(rootId: Long): Int {
        val ids = dao.missingIds(rootId)
        thumbs.invalidate(ids)
        repo.deleteMedia(ids)
        return ids.size
    }

    private fun eraseKeysAndFiles() {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        app.contentResolver.persistedUriPermissions.forEach { runCatching { app.contentResolver.releasePersistableUriPermission(it.uri, flags) } }
        app.deleteDatabase("vault.db")
        DbKey.clear(app)
        KeystoreAes.delete(KeystoreAes.MASTER)
        KeystoreAes.delete(KeystoreAes.THUMB)
        prefs.clearAll()
    }

    /**
     * Xóa dữ liệu ứng dụng: database, khóa DB và thumbKey (mọi gói thumbnail trên nguồn thành không đọc được),
     * quyền thư mục. Nếu nguồn đang cắm thì xóa luôn file gói thumbnail của thiết bị này.
     * KHÔNG đụng file backup trên nguồn. Sau đó ứng dụng đóng để khởi động sạch.
     */
    suspend fun wipeAll() {
        withContext(Dispatchers.IO) {
            sources.roots.value.filter { it.id in sources.availableRoots.value }.forEach { runCatching { packs.deleteOwn(it) } }
            runCatching { db.close() }
            eraseKeysAndFiles()
        }
        exitProcess(0)
    }

    /** Database lỗi: người dùng xác nhận tạo mới (xóa file hỏng). */
    fun recreateAfterError() {
        eraseKeysAndFiles()
        exitProcess(0)
    }
}
