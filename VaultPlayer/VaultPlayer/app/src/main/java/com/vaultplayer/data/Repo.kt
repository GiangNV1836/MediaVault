package com.vaultplayer.data

import android.os.Build
import androidx.room.withTransaction
import com.vaultplayer.util.Prefs
import com.vaultplayer.util.sanitizeName
import java.util.UUID

const val SEP = "\u001F"

class Repo(val db: AppDatabase, val dao: VaultDao, private val prefs: Prefs) {
    @Volatile private var dev: DeviceInfo? = null

    suspend fun deviceInfo(): DeviceInfo {
        dev?.let { return it }
        val d = dao.device() ?: DeviceInfo(
            1, UUID.randomUUID().toString(), (Build.MANUFACTURER + " " + Build.MODEL).trim()
        ).also { dao.setDevice(it) }
        dev = d
        return d
    }

    /** Khi khôi phục kiểu "Thay thế thiết bị cũ": nhận lại deviceId của backup. */
    suspend fun adoptDeviceId(id: String) {
        val cur = deviceInfo()
        val d = cur.copy(deviceId = id)
        dao.setDevice(d)
        dev = d
    }

    suspend fun media(id: Long): MediaEntry? = dao.media(id)

    suspend fun setFavorite(ids: List<Long>, fav: Boolean) = db.withTransaction {
        ids.chunked(800).forEach { dao.setFav(it, fav) }
    }

    suspend fun attachTag(ids: List<Long>, tagId: Long) = db.withTransaction {
        ids.chunked(500).forEach { c -> dao.insertLinks(c.map { MediaTag(it, tagId) }) }
    }

    suspend fun detachTag(ids: List<Long>, tagId: Long) = db.withTransaction {
        ids.chunked(800).forEach { dao.deleteLinks(it, tagId) }
    }

    suspend fun getOrCreateTag(raw: String): Tag? {
        val name = sanitizeName(raw).lowercase()
        if (name.isEmpty()) return null
        dao.tagByName(name)?.let { return it }
        dao.insertTag(Tag(name = name))
        return dao.tagByName(name)
    }

    suspend fun createTagAndAttach(raw: String, ids: List<Long>): Tag? {
        val t = getOrCreateTag(raw) ?: return null
        attachTag(ids, t.id)
        return t
    }

    /** Các tag mà TẤT CẢ mục trong ids đều có. */
    suspend fun commonTagIds(ids: List<Long>): Set<Long> {
        if (ids.isEmpty()) return emptySet()
        val pairs = ids.chunked(800).flatMap { dao.mediaTagPairs(it) }
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            val byTag = pairs.groupBy { it.tagId }
            byTag.filter { (_, v) -> v.map { it.mediaId }.toSet().size == ids.size }.keys
        }
    }

    suspend fun deleteTag(tagId: Long) = db.withTransaction {
        val name = dao.allTags().firstOrNull { it.id == tagId }?.name
        dao.deleteLinksOfTag(tagId)
        dao.deleteTag(tagId)
        if (name != null) {
            dao.allPlaylists().filter { it.type == "TAG_FILTER" }.forEach { p ->
                val names = p.tagFilter.split(SEP).filter { it.isNotEmpty() && it != name }
                dao.updatePlaylist(p.copy(tagFilter = names.joinToString(SEP)))
            }
        }
    }

    /** Admin: đổi tên hoặc gộp tag. Trả về false nếu tên không hợp lệ. */
    suspend fun renameTag(tagId: Long, raw: String): Boolean {
        val newName = sanitizeName(raw).lowercase()
        if (newName.isEmpty()) return false
        db.withTransaction {
            val old = dao.allTags().firstOrNull { it.id == tagId } ?: return@withTransaction
            val target = dao.tagByName(newName)
            if (target != null && target.id != tagId) {
                dao.moveLinks(tagId, target.id)
                dao.deleteLinksOfTag(tagId)
                dao.deleteTag(tagId)
            } else {
                dao.renameTag(tagId, newName)
            }
            dao.allPlaylists().filter { it.type == "TAG_FILTER" }.forEach { p ->
                val names = p.tagFilter.split(SEP).filter { it.isNotEmpty() }.map { if (it == old.name) newName else it }.distinct()
                dao.updatePlaylist(p.copy(tagFilter = names.joinToString(SEP)))
            }
        }
        return true
    }

    suspend fun createPlaylist(raw: String, type: String, tagNames: List<String>, mode: String): Long? {
        val name = sanitizeName(raw)
        if (name.isEmpty() || dao.playlistByName(name) != null) return null
        return dao.insertPlaylist(Playlist(name = name, type = type, tagFilter = tagNames.joinToString(SEP), matchMode = mode))
    }

    suspend fun addToPlaylist(pid: Long, ids: List<Long>) {
        var pos = dao.nextPos(pid)
        ids.chunked(500).forEach { c ->
            dao.insertPlaylistItems(c.map { PlaylistItem(pid, it, pos++) })
        }
    }

    suspend fun renamePlaylist(id: Long, raw: String): Boolean {
        val name = sanitizeName(raw)
        val p = dao.playlist(id) ?: return false
        if (name.isEmpty() || (dao.playlistByName(name)?.id ?: id) != id) return false
        dao.updatePlaylist(p.copy(name = name))
        return true
    }

    suspend fun deletePlaylist(id: Long) = db.withTransaction {
        dao.deletePlaylistItems(id)
        dao.deletePlaylist(id)
    }

    suspend fun playlistCount(p: Playlist, roots: List<Long>): Int {
        if (roots.isEmpty()) return 0
        if (p.type == "MANUAL") return dao.countOfPlaylist(p.id, roots)
        val ids = dao.tagIdsByNames(p.tagFilter.split(SEP).filter { it.isNotEmpty() })
        if (ids.isEmpty()) return 0
        return dao.countByTags(roots, ids, if (p.matchMode == "ALL") 1 else 0, ids.size)
    }

    suspend fun playlistIds(p: Playlist, roots: List<Long>): List<Long> {
        if (roots.isEmpty()) return emptyList()
        if (p.type == "MANUAL") return dao.idsOfPlaylist(p.id, roots)
        val ids = dao.tagIdsByNames(p.tagFilter.split(SEP).filter { it.isNotEmpty() })
        if (ids.isEmpty()) return emptyList()
        return dao.idsByTags(roots, ids, if (p.matchMode == "ALL") 1 else 0, ids.size)
    }

    suspend fun setPosition(id: Long, pos: Long) = dao.setPos(id, pos)
    suspend fun setDuration(id: Long, dur: Long) = dao.setDuration(id, dur)

    suspend fun updateAlias(id: Long, raw: String): Boolean {
        val alias = sanitizeName(raw)
        if (alias.isEmpty() || dao.aliasExists(alias) > 0) return false
        return runCatching { dao.setAlias(id, alias) }.isSuccess
    }

    /** Xóa hẳn các bản ghi theo id (cùng liên kết tag, playlist, chỉ mục thumbnail). */
    suspend fun deleteMedia(ids: List<Long>) {
        ids.chunked(500).forEach { c ->
            db.withTransaction {
                dao.deleteLinksOfMedia(c)
                dao.deletePlaylistItemsOfMedia(c)
                dao.deleteThumbOfMedia(c)
                dao.deleteMediaIds(c)
            }
        }
    }

    suspend fun deleteRootData(rootId: Long) {
        deleteMedia(dao.idsOfRoot(rootId))
        dao.deletePendingRoot(rootId)
        dao.deleteDirStatesRoot(rootId)
        dao.deleteThumbRoot(rootId)
        dao.deleteRootRow(rootId)
    }

    suspend fun isEmptyData(): Boolean =
        dao.countMedia() == 0 && dao.allTags().isEmpty() && dao.countPlaylists() == 0
}
