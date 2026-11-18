package com.vaultplayer.ui

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.vaultplayer.Vault
import com.vaultplayer.VaultApp
import com.vaultplayer.data.*
import com.vaultplayer.storage.PlaybackState
import com.vaultplayer.storage.SourceEvent
import com.vaultplayer.util.throttleLatest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

enum class Filter(val type: String) { ALL(""), VIDEO("VIDEO"), IMAGE("IMAGE"), UNTAGGED("") }
enum class Screen { MAIN, PLAYER, SETTINGS }
data class PlaylistUi(val p: Playlist, val count: Int)

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class MainViewModel(app: Application) : AndroidViewModel(app) {
    val vault: Vault = (app as VaultApp).vault
    val dao = vault.dao
    val repo = vault.repo
    val sources = vault.sources
    val thumbs = vault.thumbs
    val admin = vault.admin

    val screen = MutableStateFlow(Screen.MAIN)
    val tab = MutableStateFlow(0)
    val query = MutableStateFlow("")
    val filter = MutableStateFlow(Filter.ALL)
    val selection = MutableStateFlow<Set<Long>>(emptySet())
    val shown = MutableStateFlow<Set<Long>>(emptySet())
    val selTags = MutableStateFlow<Set<Long>>(emptySet())
    val matchAll = MutableStateFlow(false)
    val message = MutableSharedFlow<String>(extraBufferCapacity = 8)
    private val plTick = MutableStateFlow(0)

    // Hàng đợi phát hiện tại (chỉ chứa id, không chứa tên).
    var queue by mutableStateOf<List<Long>>(emptyList())
    var startIndex by mutableIntStateOf(0)
    var slideshow by mutableStateOf(false)

    val roots: StateFlow<List<Long>> = sources.availableRoots.map { it.sorted() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, sources.availableRoots.value.sorted())
    val hasAnyRoot: StateFlow<Boolean> = sources.roots.map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, sources.roots.value.isNotEmpty())
    val pendingCount: StateFlow<Int> = combine(sources.roots, sources.availableRoots) { rs, av ->
        rs.filter { it.id in av }.sumOf { it.pendingNewCount }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    val allPaging: Flow<PagingData<MediaRow>> =
        combine(roots, filter, query.debounce(300)) { r, f, q -> Triple(r, f, q.trim()) }
            .flatMapLatest { (r, f, q) ->
                Pager(PagingConfig(pageSize = 40, enablePlaceholders = false)) {
                    dao.pageAll(r, f.type, if (f == Filter.UNTAGGED) 1 else 0, q)
                }.flow
            }.cachedIn(viewModelScope)

    val favPaging: Flow<PagingData<MediaRow>> = roots.flatMapLatest { r ->
        Pager(PagingConfig(pageSize = 40, enablePlaceholders = false)) { dao.pageFav(r) }.flow
    }.cachedIn(viewModelScope)

    // Chỉ tag/playlist còn ít nhất một mục khả dụng mới hiện.
    val tagCounts: StateFlow<List<TagCount>> = roots.flatMapLatest { dao.tagCounts(it).throttleLatest(400) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val tagManagementCounts: StateFlow<List<TagCount>> = roots.flatMapLatest { dao.tagManagementCounts(it).throttleLatest(400) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val playlists: StateFlow<List<PlaylistUi>> =
        combine(dao.playlistsFlow(), roots, tagCounts, plTick) { pls, r, _, _ ->
            pls.mapNotNull { p -> repo.playlistCount(p, r).let { c -> if (c > 0) PlaylistUi(p, c) else null } }
        }.throttleLatest(400).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        viewModelScope.launch {
            sources.events.collect { ev -> if (ev is SourceEvent.Detached) onDetached(ev.rootId) }
        }
    }

    /** Nguồn bị rút: dọn mọi trạng thái giao diện liên quan, khóa Admin, xóa cache RAM của nguồn. */
    private fun onDetached(rootId: Long) {
        query.value = ""
        selection.value = emptySet()
        shown.value = emptySet()
        selTags.value = emptySet()
        filter.value = Filter.ALL
        queue = emptyList()
        screen.value = Screen.MAIN
        PlaybackState.rootId.value = null
        admin.lock()
        thumbs.clearRoot(rootId)
    }

    fun toast(s: String) { message.tryEmit(s) }

    // ---- phát
    fun open(ids: List<Long>, start: Int, slide: Boolean) {
        if (ids.isEmpty()) return
        queue = ids
        startIndex = start.coerceIn(0, ids.lastIndex)
        slideshow = slide
        screen.value = Screen.PLAYER
    }

    fun openFromAll(startId: Long) = viewModelScope.launch {
        val ids = dao.idsAll(roots.value, filter.value.type, if (filter.value == Filter.UNTAGGED) 1 else 0, query.value.trim())
        open(ids, ids.indexOf(startId).coerceAtLeast(0), false)
    }

    fun openFromFav(startId: Long) = viewModelScope.launch {
        val ids = dao.idsFav(roots.value)
        open(ids, ids.indexOf(startId).coerceAtLeast(0), false)
    }

    fun playFavorites(shuffle: Boolean) = viewModelScope.launch {
        val ids = dao.idsFav(roots.value).let { if (shuffle) it.shuffled() else it }
        open(ids, 0, true)
    }

    fun playTags(shuffle: Boolean = false) = viewModelScope.launch {
        val t = selTags.value.toList()
        if (t.isEmpty()) return@launch
        val ids = dao.idsByTags(roots.value, t, if (matchAll.value) 1 else 0, t.size).let { if (shuffle) it.shuffled() else it }
        open(ids, 0, true)
    }

    fun playPlaylist(p: Playlist, shuffle: Boolean = false) = viewModelScope.launch {
        val ids = repo.playlistIds(p, roots.value).let { if (shuffle) it.shuffled() else it }
        open(ids, 0, true)
    }

    fun openFromPlaylist(p: Playlist, startId: Long) = viewModelScope.launch {
        val ids = repo.playlistIds(p, roots.value)
        open(ids, ids.indexOf(startId).coerceAtLeast(0), false)
    }

    fun playlistPaging(p: Playlist): Flow<PagingData<MediaRow>> =
        roots.flatMapLatest { rootIds ->
            flow {
                val current = dao.playlist(p.id) ?: p
                if (current.type == "MANUAL") {
                    emitAll(
                        Pager(PagingConfig(pageSize = 40, enablePlaceholders = false)) {
                            dao.pagePlaylist(current.id, rootIds)
                        }.flow
                    )
                } else {
                    val tagIds = dao.tagIdsByNames(current.tagFilter.split(SEP).filter { it.isNotEmpty() })
                    if (tagIds.isEmpty()) {
                        emit(PagingData.empty())
                    } else {
                        emitAll(
                            Pager(PagingConfig(pageSize = 40, enablePlaceholders = false)) {
                                dao.pageByTags(rootIds, tagIds, if (current.matchMode == "ALL") 1 else 0, tagIds.size)
                            }.flow
                        )
                    }
                }
            }
        }.cachedIn(viewModelScope)

    // ---- chọn nhiều
    fun toggleSelect(id: Long) { selection.update { if (id in it) it - id else it + id } }
    fun clearSelection() { selection.value = emptySet() }
    fun selectAll() = viewModelScope.launch {
        selection.value = when (tab.value) {
            0 -> dao.idsAll(roots.value, filter.value.type, if (filter.value == Filter.UNTAGGED) 1 else 0, query.value.trim())
            3 -> dao.idsFav(roots.value)
            else -> emptyList()
        }.toSet()
    }

    fun toggleShown(id: Long) { shown.update { if (id in it) it - id else it + id } }

    // ---- yêu thích / tag / playlist (mọi người dùng)
    fun toggleFavorite(id: Long, current: Boolean) = viewModelScope.launch { repo.setFavorite(listOf(id), !current) }
    fun favoriteSelected(fav: Boolean) = viewModelScope.launch { repo.setFavorite(selection.value.toList(), fav); toast("Đã cập nhật") }

    fun createTag(name: String, ids: List<Long>, onDone: (Tag) -> Unit) = viewModelScope.launch {
        val t = repo.createTagAndAttach(name, ids)
        if (t != null) onDone(t) else toast("Tên tag không hợp lệ")
    }

    suspend fun commonTags(ids: List<Long>): Set<Long> = repo.commonTagIds(ids)
    fun setTag(ids: List<Long>, tagId: Long, on: Boolean) = viewModelScope.launch {
        if (on) repo.attachTag(ids, tagId) else repo.detachTag(ids, tagId)
    }
    fun deleteTag(id: Long) = viewModelScope.launch {
        repo.deleteTag(id); selTags.update { it - id }; toast("Đã xóa tag")
    }
    fun renameTag(id: Long, name: String) = viewModelScope.launch {
        toast(if (repo.renameTag(id, name)) "Đã cập nhật tag" else "Tên tag không hợp lệ")
    }

    fun savePlaylistFromTags(name: String) = viewModelScope.launch {
        val names = tagCounts.value.filter { it.id in selTags.value }.map { it.name }
        val id = repo.createPlaylist(name, "TAG_FILTER", names, if (matchAll.value) "ALL" else "ANY")
        toast(if (id != null) "Đã lưu playlist" else "Tên playlist không hợp lệ hoặc đã có")
    }

    fun createManual(name: String, ids: List<Long>) = viewModelScope.launch {
        val id = repo.createPlaylist(name, "MANUAL", emptyList(), "ANY")
        if (id == null) { toast("Tên playlist không hợp lệ hoặc đã có"); return@launch }
        repo.addToPlaylist(id, ids); plTick.update { it + 1 }; toast("Đã thêm vào playlist")
    }

    fun addToPlaylist(pid: Long, ids: List<Long>) = viewModelScope.launch {
        repo.addToPlaylist(pid, ids); plTick.update { it + 1 }; toast("Đã thêm vào playlist")
    }
    fun renamePlaylist(id: Long, name: String) = viewModelScope.launch {
        toast(if (repo.renamePlaylist(id, name)) "Đã đổi tên" else "Tên playlist không hợp lệ hoặc đã có")
    }
    fun deletePlaylist(id: Long) = viewModelScope.launch { repo.deletePlaylist(id); toast("Đã xóa playlist") }

    /** Mở file lỗi dù nguồn vẫn đọc được => file đã bị xóa: đánh dấu isMissing (ẩn khỏi mọi màn hình). */
    suspend fun markIfGone(m: MediaEntry): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val uri = sources.docUri(m) ?: return@withContext false
        val root = sources.rootById(m.rootId) ?: return@withContext false
        if (m.rootId !in sources.availableRoots.value || !sources.isReadable(root)) return@withContext false
        val ok = try {
            getApplication<Application>().contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
        } catch (e: Exception) { false }
        if (!ok) dao.markMissing(listOf(m.id))
        !ok
    }

    // ---- Admin
    fun editAlias(id: Long, alias: String) = viewModelScope.launch {
        toast(if (repo.updateAlias(id, alias)) "Đã đổi tên ảo" else "Tên không hợp lệ hoặc đã tồn tại")
    }

    fun importAll() {
        sources.roots.value.filter { it.id in sources.availableRoots.value && it.pendingNewCount > 0 }
            .forEach { sources.importNow(it.id) }
    }

    fun addRoot(uri: Uri) = viewModelScope.launch { toast(sources.addRoot(uri) ?: "Đã thêm nguồn") }
}
