package com.vaultplayer.data

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

data class MediaRow(
    val id: Long,
    val aliasName: String,
    val mediaType: String,
    val durationMs: Long?,
    val isFavorite: Boolean,
    val rootId: Long,
    val relativePath: String,
    val tagsCsv: String?
)

data class TagCount(val id: Long, val name: String, val cnt: Int)

data class BackupRow(
    val id: Long,
    val vol: String,
    val p: String,
    val s: Long,
    val t: String,
    val a: String,
    val f: Boolean,
    val pos: Long,
    val tags: String?,
    val pls: String?
)

private const val ROW =
    "m.id AS id, m.aliasName AS aliasName, m.mediaType AS mediaType, m.durationMs AS durationMs, " +
    "m.isFavorite AS isFavorite, m.rootId AS rootId, m.relativePath AS relativePath, " +
    "(SELECT GROUP_CONCAT(g.name, ',') FROM media_tag t JOIN tag g ON g.id = t.tagId WHERE t.mediaId = m.id) AS tagsCsv"

private const val FILTER =
    "m.isMissing = 0 AND m.rootId IN (:roots) AND (:type = '' OR m.mediaType = :type) " +
    "AND (:untagged = 0 OR NOT EXISTS (SELECT 1 FROM media_tag t WHERE t.mediaId = m.id)) " +
    "AND (:q = '' OR m.aliasName LIKE '%' || :q || '%' OR m.id IN (SELECT t.mediaId FROM media_tag t " +
    "WHERE t.tagId IN (SELECT g.id FROM tag g WHERE g.name LIKE '%' || :q || '%')))"

private const val BY_TAGS =
    "m.isMissing = 0 AND m.rootId IN (:roots) AND ((:allMode = 0 AND EXISTS (SELECT 1 FROM media_tag t WHERE t.mediaId = m.id AND t.tagId IN (:tagIds))) " +
    "OR (:allMode = 1 AND (SELECT COUNT(DISTINCT t.tagId) FROM media_tag t WHERE t.mediaId = m.id AND t.tagId IN (:tagIds)) = :n))"

@Dao
interface VaultDao {
    // ---- nguồn
    @Query("SELECT * FROM source_root ORDER BY id") suspend fun roots(): List<SourceRoot>
    @Query("SELECT * FROM source_root ORDER BY id") fun rootsFlow(): Flow<List<SourceRoot>>
    @Query("SELECT * FROM source_root WHERE id = :id") suspend fun root(id: Long): SourceRoot?
    @Insert suspend fun insertRoot(r: SourceRoot): Long
    @Update suspend fun updateRoot(r: SourceRoot)
    @Query("DELETE FROM source_root WHERE id = :id") suspend fun deleteRootRow(id: Long)

    // ---- media
    @Query("SELECT * FROM media_entry WHERE id = :id") suspend fun media(id: Long): MediaEntry?
    @Query("SELECT * FROM media_entry WHERE id IN (:ids)") suspend fun mediaList(ids: List<Long>): List<MediaEntry>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertMediaList(l: List<MediaEntry>): List<Long>
    @Query("SELECT * FROM media_entry WHERE rootId = :rootId AND dirKey = :dirKey") suspend fun entriesInDir(rootId: Long, dirKey: String): List<MediaEntry>
    @Query("SELECT COUNT(*) FROM media_entry WHERE rootId = :rootId AND isMissing = 0") suspend fun countActive(rootId: Long): Int
    @Query("SELECT COUNT(*) FROM media_entry") suspend fun countMedia(): Int
    @Query("SELECT COUNT(*) FROM media_entry WHERE rootId = :rootId AND isMissing = 1") suspend fun countMissing(rootId: Long): Int
    @Query("SELECT id FROM media_entry WHERE rootId = :rootId AND isMissing = 1") suspend fun missingIds(rootId: Long): List<Long>
    @Query("SELECT id FROM media_entry WHERE rootId = :rootId") suspend fun idsOfRoot(rootId: Long): List<Long>

    @Query("UPDATE media_entry SET fileSize = :size, lastModified = :lm, documentId = :doc, relativePath = :rel, pathKey = :pk, dirKey = :dk, mediaType = :type, lastPositionMs = 0, durationMs = NULL, width = NULL, height = NULL, isMissing = 0 WHERE id = :id")
    suspend fun applyEdit(id: Long, size: Long, lm: Long, doc: String, rel: String, pk: String, dk: String, type: String)
    @Query("UPDATE media_entry SET relativePath = :rel, pathKey = :pk, dirKey = :dk, documentId = :doc, fileSize = :size, lastModified = :lm, isMissing = 0 WHERE id = :id")
    suspend fun relink(id: Long, rel: String, pk: String, dk: String, doc: String, size: Long, lm: Long)
    @Query("UPDATE media_entry SET documentId = :doc WHERE id = :id") suspend fun setDocId(id: Long, doc: String)
    @Query("UPDATE media_entry SET isMissing = 1 WHERE id IN (:ids)") suspend fun markMissing(ids: List<Long>)
    @Query("UPDATE media_entry SET isMissing = 0 WHERE id IN (:ids)") suspend fun unmarkMissing(ids: List<Long>)
    @Query("UPDATE media_entry SET isFavorite = :fav WHERE id IN (:ids)") suspend fun setFav(ids: List<Long>, fav: Boolean)
    @Query("UPDATE media_entry SET lastPositionMs = :pos WHERE id = :id") suspend fun setPos(id: Long, pos: Long)
    @Query("UPDATE media_entry SET durationMs = :dur WHERE id = :id") suspend fun setDuration(id: Long, dur: Long)
    @Query("UPDATE media_entry SET aliasName = :alias WHERE id = :id") suspend fun setAlias(id: Long, alias: String)
    @Query("SELECT COUNT(*) FROM media_entry WHERE aliasName = :alias") suspend fun aliasExists(alias: String): Int
    @Query("DELETE FROM media_entry WHERE id IN (:ids)") suspend fun deleteMediaIds(ids: List<Long>)
    @Query("DELETE FROM media_tag WHERE mediaId IN (:ids)") suspend fun deleteLinksOfMedia(ids: List<Long>)
    @Query("DELETE FROM playlist_item WHERE mediaId IN (:ids)") suspend fun deletePlaylistItemsOfMedia(ids: List<Long>)
    @Query("DELETE FROM thumb_index WHERE mediaId IN (:ids)") suspend fun deleteThumbOfMedia(ids: List<Long>)
    @Query("UPDATE media_entry SET isFavorite = 0") suspend fun clearFavorites()

    // ---- danh sách
    @Query("SELECT $ROW FROM media_entry m WHERE $FILTER ORDER BY m.id")
    fun pageAll(roots: List<Long>, type: String, untagged: Int, q: String): PagingSource<Int, MediaRow>
    @Query("SELECT m.id FROM media_entry m WHERE $FILTER ORDER BY m.id")
    suspend fun idsAll(roots: List<Long>, type: String, untagged: Int, q: String): List<Long>
    @Query("SELECT $ROW FROM media_entry m WHERE m.isMissing = 0 AND m.rootId IN (:roots) AND m.isFavorite = 1 ORDER BY m.id")
    fun pageFav(roots: List<Long>): PagingSource<Int, MediaRow>
    @Query("SELECT m.id FROM media_entry m WHERE m.isMissing = 0 AND m.rootId IN (:roots) AND m.isFavorite = 1 ORDER BY m.id")
    suspend fun idsFav(roots: List<Long>): List<Long>
    @Query("SELECT m.id FROM media_entry m WHERE $BY_TAGS ORDER BY m.id")
    suspend fun idsByTags(roots: List<Long>, tagIds: List<Long>, allMode: Int, n: Int): List<Long>
    @Query("SELECT COUNT(*) FROM media_entry m WHERE $BY_TAGS")
    suspend fun countByTags(roots: List<Long>, tagIds: List<Long>, allMode: Int, n: Int): Int
    @Query("SELECT m.id FROM playlist_item pi JOIN media_entry m ON m.id = pi.mediaId WHERE pi.playlistId = :p AND m.isMissing = 0 AND m.rootId IN (:roots) ORDER BY pi.position")
    suspend fun idsOfPlaylist(p: Long, roots: List<Long>): List<Long>
    @Query("SELECT COUNT(*) FROM playlist_item pi JOIN media_entry m ON m.id = pi.mediaId WHERE pi.playlistId = :p AND m.isMissing = 0 AND m.rootId IN (:roots)")
    suspend fun countOfPlaylist(p: Long, roots: List<Long>): Int

    // ---- tag
    @Query("SELECT g.id AS id, g.name AS name, COUNT(m.id) AS cnt FROM tag g JOIN media_tag t ON t.tagId = g.id JOIN media_entry m ON m.id = t.mediaId WHERE m.isMissing = 0 AND m.rootId IN (:roots) GROUP BY g.id ORDER BY g.name COLLATE NOCASE")
    fun tagCounts(roots: List<Long>): Flow<List<TagCount>>
    @Query("SELECT * FROM tag WHERE name = :n") suspend fun tagByName(n: String): Tag?
    @Query("SELECT * FROM tag") suspend fun allTags(): List<Tag>
    @Query("SELECT id FROM tag WHERE name IN (:names)") suspend fun tagIdsByNames(names: List<String>): List<Long>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertTag(t: Tag): Long
    @Query("DELETE FROM tag WHERE id = :id") suspend fun deleteTag(id: Long)
    @Query("DELETE FROM media_tag WHERE tagId = :id") suspend fun deleteLinksOfTag(id: Long)
    @Query("UPDATE OR IGNORE media_tag SET tagId = :to WHERE tagId = :from") suspend fun moveLinks(from: Long, to: Long)
    @Query("UPDATE tag SET name = :name WHERE id = :id") suspend fun renameTag(id: Long, name: String)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertLinks(l: List<MediaTag>)
    @Query("DELETE FROM media_tag WHERE tagId = :tagId AND mediaId IN (:mediaIds)") suspend fun deleteLinks(mediaIds: List<Long>, tagId: Long)
    @Query("SELECT * FROM media_tag WHERE mediaId IN (:ids)") suspend fun mediaTagPairs(ids: List<Long>): List<MediaTag>
    @Query("SELECT g.* FROM tag g JOIN media_tag t ON t.tagId = g.id WHERE t.mediaId = :id ORDER BY g.name COLLATE NOCASE")
    suspend fun tagsOfMedia(id: Long): List<Tag>
    @Query("DELETE FROM tag") suspend fun clearTags()
    @Query("DELETE FROM media_tag") suspend fun clearLinks()

    // ---- playlist
    @Query("SELECT * FROM playlist ORDER BY name COLLATE NOCASE") fun playlistsFlow(): Flow<List<Playlist>>
    @Query("SELECT * FROM playlist") suspend fun allPlaylists(): List<Playlist>
    @Query("SELECT * FROM playlist WHERE id = :id") suspend fun playlist(id: Long): Playlist?
    @Query("SELECT * FROM playlist WHERE name = :n") suspend fun playlistByName(n: String): Playlist?
    @Insert suspend fun insertPlaylist(p: Playlist): Long
    @Update suspend fun updatePlaylist(p: Playlist)
    @Query("DELETE FROM playlist WHERE id = :id") suspend fun deletePlaylist(id: Long)
    @Query("DELETE FROM playlist_item WHERE playlistId = :id") suspend fun deletePlaylistItems(id: Long)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertPlaylistItems(l: List<PlaylistItem>)
    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM playlist_item WHERE playlistId = :p") suspend fun nextPos(p: Long): Int
    @Query("SELECT COUNT(*) FROM playlist") suspend fun countPlaylists(): Int
    @Query("DELETE FROM playlist") suspend fun clearPlaylists()
    @Query("DELETE FROM playlist_item") suspend fun clearPlaylistItems()

    // ---- nhập mục mới
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertPending(l: List<PendingEntry>)
    @Query("DELETE FROM pending_entry WHERE rootId = :rootId AND dirKey = :dirKey") suspend fun deletePendingInDir(rootId: Long, dirKey: String)
    @Query("SELECT COUNT(*) FROM pending_entry WHERE rootId = :rootId") suspend fun countPending(rootId: Long): Int
    @Query("SELECT * FROM pending_entry WHERE rootId = :rootId ORDER BY relativePath COLLATE NOCASE LIMIT :limit")
    suspend fun pendingBatch(rootId: Long, limit: Int): List<PendingEntry>
    @Query("DELETE FROM pending_entry WHERE id IN (:ids)") suspend fun deletePending(ids: List<Long>)
    @Query("DELETE FROM pending_entry WHERE rootId = :rootId") suspend fun deletePendingRoot(rootId: Long)

    // ---- thư mục
    @Query("SELECT * FROM dir_state WHERE rootId = :rootId") suspend fun dirStates(rootId: Long): List<DirState>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertDirStates(l: List<DirState>)
    @Query("DELETE FROM dir_state WHERE rootId = :rootId AND dirKey IN (:keys)") suspend fun deleteDirStates(rootId: Long, keys: List<String>)
    @Query("DELETE FROM dir_state WHERE rootId = :rootId") suspend fun deleteDirStatesRoot(rootId: Long)

    // ---- bộ đếm, thiết bị
    @Query("SELECT value FROM counter WHERE name = :n") suspend fun counter(n: String): Long?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun setCounter(c: Counter)
    @Query("SELECT * FROM device_info WHERE id = 1") suspend fun device(): DeviceInfo?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun setDevice(d: DeviceInfo)

    // ---- thumbnail
    @Query("SELECT * FROM thumb_index WHERE mediaId = :m AND frameIndex = :f") suspend fun thumbIdx(m: Long, f: Int): ThumbIndex?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putThumbIdx(l: List<ThumbIndex>)
    @Delete suspend fun deleteThumbIdx(l: List<ThumbIndex>)
    @Query("SELECT * FROM thumb_index WHERE rootId = :rootId ORDER BY packOffset") suspend fun thumbRows(rootId: Long): List<ThumbIndex>
    @Query("SELECT * FROM thumb_index WHERE mediaId IN (:ids)") suspend fun thumbRowsFor(ids: List<Long>): List<ThumbIndex>
    @Query("DELETE FROM thumb_index WHERE rootId = :rootId") suspend fun deleteThumbRoot(rootId: Long)
    @Query("SELECT * FROM pack_info WHERE rootId = :rootId") suspend fun packInfo(rootId: Long): PackInfo?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun setPackInfo(p: PackInfo)
    @Query("UPDATE pack_info SET wastedBytes = wastedBytes + :n WHERE rootId = :rootId") suspend fun addWasted(rootId: Long, n: Long)

    // ---- khôi phục / backup
    @Insert suspend fun insertStaging(s: RestoreStaging)
    @Query("SELECT * FROM restore_staging WHERE volumeId = :vol AND pathKey = :pk AND fileSize = :size LIMIT 1")
    suspend fun findStaging(vol: String, pk: String, size: Long): RestoreStaging?
    @Query("DELETE FROM restore_staging WHERE id = :id") suspend fun deleteStaging(id: Long)
    @Query("DELETE FROM restore_staging") suspend fun clearStaging()

    @Query("SELECT m.* FROM media_entry m JOIN source_root r ON r.id = m.rootId WHERE r.volumeId = :vol AND m.pathKey = :pk AND m.fileSize = :size LIMIT 1")
    suspend fun findByKey(vol: String, pk: String, size: Long): MediaEntry?

    @Query(
        "SELECT m.id AS id, r.volumeId AS vol, m.relativePath AS p, m.fileSize AS s, m.mediaType AS t, m.aliasName AS a, " +
        "m.isFavorite AS f, m.lastPositionMs AS pos, " +
        "(SELECT GROUP_CONCAT(g.name, char(31)) FROM media_tag x JOIN tag g ON g.id = x.tagId WHERE x.mediaId = m.id) AS tags, " +
        "(SELECT GROUP_CONCAT(p2.name || char(30) || pi.position, char(31)) FROM playlist_item pi JOIN playlist p2 ON p2.id = pi.playlistId WHERE pi.mediaId = m.id) AS pls " +
        "FROM media_entry m JOIN source_root r ON r.id = m.rootId WHERE m.id > :after ORDER BY m.id LIMIT :limit"
    )
    suspend fun backupChunk(after: Long, limit: Int): List<BackupRow>

    // ---- gộp thư mục con vào thư mục cha
    @Query(
        "UPDATE media_entry SET rootId = :to, " +
        "relativePath = CASE WHEN :relPrefix = '' THEN relativePath ELSE :relPrefix || '/' || relativePath END, " +
        "pathKey = CASE WHEN :keyPrefix = '' THEN pathKey ELSE :keyPrefix || '/' || pathKey END, " +
        "dirKey = CASE WHEN :keyPrefix = '' THEN dirKey WHEN dirKey = '' THEN :keyPrefix ELSE :keyPrefix || '/' || dirKey END " +
        "WHERE rootId = :from"
    )
    suspend fun reparentMedia(from: Long, to: Long, relPrefix: String, keyPrefix: String)
}
