package com.vaultplayer.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "source_root", indices = [Index("volumeId")])
data class SourceRoot(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val treeUri: String,
    val volumeId: String,
    val addedAt: Long,
    val lastScanAt: Long = 0,
    val lastFullScanAt: Long = 0,
    val pendingNewCount: Int = 0,
    val lastFileCount: Int = 0,
    val lastDirCount: Int = 0,
    val lastScanMs: Long = 0,
    val lastScanMode: String = ""
)

/** pathKey/dirKey: dạng chuẩn hóa (NFC, chữ thường) để so khớp không phân biệt hoa/thường. */
@Entity(
    tableName = "media_entry",
    indices = [
        Index(value = ["rootId", "pathKey"], unique = true),
        Index(value = ["rootId", "dirKey"]),
        Index(value = ["aliasName"], unique = true),
        Index(value = ["isMissing"]),
        Index(value = ["mediaType"]),
        Index(value = ["isFavorite"])
    ]
)
data class MediaEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val rootId: Long,
    val mediaType: String,          // VIDEO | IMAGE
    val relativePath: String,
    val pathKey: String,
    val dirKey: String,
    val documentId: String,
    val aliasName: String,
    val isFavorite: Boolean = false,
    val fileSize: Long,
    val lastModified: Long,
    val durationMs: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    val lastPositionMs: Long = 0,   // thông tin xem DUY NHẤT, không có dấu thời gian
    val isMissing: Boolean = false
)

@Entity(
    tableName = "pending_entry",
    indices = [Index(value = ["rootId", "pathKey"], unique = true), Index(value = ["rootId", "dirKey"])]
)
data class PendingEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val rootId: Long,
    val relativePath: String,
    val pathKey: String,
    val dirKey: String,
    val documentId: String,
    val mediaType: String,
    val fileSize: Long,
    val lastModified: Long
)

@Entity(tableName = "dir_state", primaryKeys = ["rootId", "dirKey"])
data class DirState(
    val rootId: Long,
    val dirKey: String,
    val relativeDirPath: String,
    val parentKey: String,
    val documentId: String,
    val lastModified: Long
)

@Entity(tableName = "tag", indices = [Index(value = ["name"], unique = true)])
data class Tag(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String)

@Entity(tableName = "media_tag", primaryKeys = ["mediaId", "tagId"], indices = [Index("tagId")])
data class MediaTag(val mediaId: Long, val tagId: Long)

/** type: MANUAL | TAG_FILTER. tagFilter: tên tag nối bằng \u001F. matchMode: ANY | ALL. */
@Entity(tableName = "playlist")
data class Playlist(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: String,
    val tagFilter: String = "",
    val matchMode: String = "ANY"
)

@Entity(tableName = "playlist_item", primaryKeys = ["playlistId", "mediaId"], indices = [Index("mediaId")])
data class PlaylistItem(val playlistId: Long, val mediaId: Long, val position: Int)

@Entity(tableName = "device_info")
data class DeviceInfo(@PrimaryKey val id: Int = 1, val deviceId: String, val deviceLabel: String)

@Entity(tableName = "counter")
data class Counter(@PrimaryKey val name: String, val value: Long)

@Entity(tableName = "thumb_index", primaryKeys = ["mediaId", "frameIndex"], indices = [Index("rootId")])
data class ThumbIndex(
    val mediaId: Long,
    val frameIndex: Int,
    val rootId: Long,
    val packOffset: Long,
    val length: Int,
    val fingerprint: String
)

@Entity(tableName = "pack_info")
data class PackInfo(@PrimaryKey val rootId: Long, val totalBytes: Long, val wastedBytes: Long)

/** Dữ liệu từ backup chưa khớp được file cục bộ; áp dụng khi file xuất hiện (nhập mục mới). */
@Entity(tableName = "restore_staging", indices = [Index(value = ["volumeId", "pathKey"])])
data class RestoreStaging(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val volumeId: String,
    val pathKey: String,
    val fileSize: Long,
    val alias: String,
    val mediaType: String,
    val isFavorite: Boolean,
    val pos: Long,
    val tags: String,        // tên tag nối bằng \u001F
    val playlists: String    // "tên\u001Evị trí" nối bằng \u001F
)
