package com.vaultplayer.ui

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import com.vaultplayer.data.MediaRow
import com.vaultplayer.util.fmtDuration
import kotlinx.coroutines.delay

@Composable
fun TagChip(name: String, compact: Boolean = false) {
    val c = tagColor(name)
    Surface(shape = RoundedCornerShape(50), color = c.copy(alpha = 0.16f)) {
        Text(name, color = c, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = if (compact) 64.dp else 112.dp).padding(horizontal = 8.dp, vertical = 2.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagChips(csv: String?, max: Int = 4) {
    val tags = csv?.split(',')?.filter { it.isNotEmpty() } ?: emptyList()
    if (tags.isEmpty()) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        tags.take(max).forEach { TagChip(it) }
        if (tags.size > max) TagChip("+${tags.size - max}")
    }
}

/**
 * Ô hình xem trước: mặc định ô xám + con mắt. Bấm con mắt mới tải hình của đúng thẻ này.
 * Hình chỉ nằm trong RAM (xem ThumbRepository).
 */
@Composable
fun ThumbBox(
    vm: MainViewModel, id: Long, isVideo: Boolean, show: Boolean, manual: Boolean,
    onToggleShow: () -> Unit, modifier: Modifier = Modifier
) {
    val bmp by produceState<ImageBitmap?>(null, id, show) {
        value = if (show) {
            if (!manual) delay(180)                       // tự hiện: chỉ bắt đầu khi cuộn chậm lại
            vm.thumbs.get(id)?.asImageBitmap()
        } else null
    }

    Box(
        modifier.clip(RoundedCornerShape(11.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        Crossfade(bmp, label = "thumb") { b ->
            if (b != null) Image(b, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Icon(
                if (isVideo) Icons.Default.Movie else Icons.Default.Image, null,
                Modifier.align(Alignment.Center).size(32.dp), tint = MaterialTheme.colorScheme.outline
            )
        }
        if (manual) IconButton(
            onClick = onToggleShow,
            modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp).size(32.dp).background(Color.Black.copy(alpha = 0.45f), CircleShape)
        ) {
            Icon(if (show) Icons.Default.VisibilityOff else Icons.Default.Visibility, "Hình xem trước", tint = Color.White, modifier = Modifier.size(18.dp))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaCard(
    vm: MainViewModel, row: MediaRow, admin: Boolean, selectionMode: Boolean, selected: Boolean,
    shown: Boolean, autoShow: Boolean, listMode: Boolean, onOpen: () -> Unit,
    onFavorite: () -> Unit, onEditAlias: () -> Unit
) {
    val isVideo = row.mediaType == "VIDEO"
    val cardModifier = Modifier.fillMaxWidth().combinedClickable(
        onClick = { if (selectionMode) vm.toggleSelect(row.id) else onOpen() },
        onLongClick = { vm.toggleSelect(row.id) }
    )

    if (listMode) {
        Row(
            cardModifier
                .clip(RoundedCornerShape(12.dp))
                .background(
                    if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                    else Color.Transparent,
                    RoundedCornerShape(12.dp)
                )
                .then(if (selected) Modifier.border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp)) else Modifier)
                .padding(vertical = 5.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MediaThumbnail(
                vm, row, isVideo, shown, autoShow, selectionMode, selected,
                selectionOverlay = false,
                onFavorite = onFavorite,
                modifier = Modifier.size(width = 108.dp, height = 72.dp)
            )
            Spacer(Modifier.width(10.dp))
            MediaMetadata(row, admin, listMode, selected, Modifier.weight(1f))
            if (!selectionMode) {
                IconButton(onClick = onFavorite, modifier = Modifier.size(36.dp)) {
                    Icon(
                        if (row.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        "Yêu thích",
                        Modifier.size(18.dp),
                        tint = if (row.isFavorite) Color(0xFFFF5C7A) else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (!selectionMode && admin) {
                IconButton(onClick = onEditAlias, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Edit, "Sửa tên ảo", Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (selectionMode) {
                Checkbox(checked = selected, onCheckedChange = { vm.toggleSelect(row.id) })
            }
        }
    } else {
        val shape = RoundedCornerShape(14.dp)
        Column(
            cardModifier
                .clip(shape)
                .background(
                    if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                    else MaterialTheme.colorScheme.surfaceContainerLow
                )
                .border(
                    1.dp,
                    if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                    shape
                )
        ) {
            MediaThumbnail(
                vm, row, isVideo, shown, autoShow, selectionMode, selected,
                selectionOverlay = true,
                onFavorite = onFavorite,
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)
            )
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                else MaterialTheme.colorScheme.surfaceContainerLow
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MediaMetadata(row, admin, listMode, selected, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun MediaThumbnail(
    vm: MainViewModel,
    row: MediaRow,
    isVideo: Boolean,
    shown: Boolean,
    autoShow: Boolean,
    selectionMode: Boolean,
    selected: Boolean,
    selectionOverlay: Boolean,
    onFavorite: () -> Unit,
    modifier: Modifier
) {
    Box {
        ThumbBox(
            vm, row.id, isVideo, show = autoShow || shown, manual = !autoShow,
            onToggleShow = { vm.toggleShown(row.id) },
            modifier = modifier
        )
        if (selectionMode && selectionOverlay) {
            Checkbox(
                checked = selected,
                onCheckedChange = { vm.toggleSelect(row.id) },
                modifier = Modifier.align(Alignment.TopEnd).padding(2.dp).size(40.dp),
                colors = CheckboxDefaults.colors(
                    checkedColor = MaterialTheme.colorScheme.primary,
                    uncheckedColor = Color.White
                )
            )
        } else if (!selectionMode && selectionOverlay) {
            IconButton(
                onClick = onFavorite,
                modifier = Modifier.align(Alignment.TopEnd).padding(2.dp).size(36.dp)
                    .background(Color.Black.copy(alpha = 0.38f), CircleShape)
            ) {
                Icon(
                    if (row.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    "Yêu thích",
                    tint = if (row.isFavorite) Color(0xFFFF5C7A) else Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun MediaMetadata(
    row: MediaRow,
    admin: Boolean,
    listMode: Boolean,
    selected: Boolean,
    modifier: Modifier
) {
    val duration = if (row.mediaType == "VIDEO") row.durationMs?.let { fmtDuration(it) } ?: "Video" else "Ảnh"
    val tags = row.tagsCsv?.split(',')?.filter { it.isNotBlank() }.orEmpty()
    Column(modifier) {
        Text(
            row.aliasName,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(duration, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            if (tags.isNotEmpty()) {
                Text("  ·  ", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                Text(
                    tags.first() + if (tags.size > 1) " +${tags.size - 1}" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        if (admin && listMode) {
            Text(row.relativePath, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
