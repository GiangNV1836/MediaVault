package com.vaultplayer.ui

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import com.vaultplayer.data.MediaRow
import com.vaultplayer.util.fmtDuration
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun TagChip(name: String) {
    val c = tagColor(name)
    Surface(shape = RoundedCornerShape(50), color = c.copy(alpha = 0.16f)) {
        Text(name, color = c, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
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
 * Giữ ngón tay (video đã hiện hình): xem trước động 4 khung lật vòng ~0,5 giây.
 * Hình chỉ nằm trong RAM (xem ThumbRepository).
 */
@Composable
fun ThumbBox(
    vm: MainViewModel, id: Long, isVideo: Boolean, show: Boolean, manual: Boolean,
    onToggleShow: () -> Unit, onTap: () -> Unit, modifier: Modifier = Modifier
) {
    var preview by remember(id) { mutableStateOf<List<ImageBitmap>>(emptyList()) }
    var pIdx by remember(id) { mutableIntStateOf(-1) }
    val bmp by produceState<ImageBitmap?>(null, id, show) {
        value = if (show) {
            if (!manual) delay(180)                       // tự hiện: chỉ bắt đầu khi cuộn chậm lại
            vm.thumbs.get(id)?.asImageBitmap()
        } else null
    }
    LaunchedEffect(preview) {
        if (preview.size > 1) { var i = 0; while (true) { pIdx = i % preview.size; delay(500); i++ } } else pIdx = -1
    }
    val img: ImageBitmap? = if (pIdx >= 0 && pIdx < preview.size) preview[pIdx] else bmp

    Box(
        modifier.aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
            .pointerInput(id, show) {
                detectTapGestures(
                    onTap = { onTap() },
                    onPress = {
                        if (show && isVideo) {
                            coroutineScope {
                                val j = launch { delay(450); preview = vm.thumbs.frames(id).map { it.asImageBitmap() } }
                                tryAwaitRelease()
                                j.cancel()
                                preview = emptyList()
                            }
                        }
                    }
                )
            }
    ) {
        Crossfade(img, label = "thumb") { b ->
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
    shown: Boolean, autoShow: Boolean, onOpen: () -> Unit, onEditAlias: () -> Unit
) {
    val isVideo = row.mediaType == "VIDEO"
    BoxWithConstraints {
        val thumbWidth = (maxWidth * 0.36f).coerceIn(112.dp, 168.dp)
        ElevatedCard(
            modifier = Modifier.fillMaxWidth().combinedClickable(
                onClick = { if (selectionMode) vm.toggleSelect(row.id) else onOpen() },
                onLongClick = { vm.toggleSelect(row.id) }
            ),
            colors = CardDefaults.elevatedCardColors(
                containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow
            )
        ) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                ThumbBox(
                    vm, row.id, isVideo, show = autoShow || shown, manual = !autoShow,
                    onToggleShow = { vm.toggleShown(row.id) },
                    onTap = { if (selectionMode) vm.toggleSelect(row.id) else onOpen() },
                    modifier = Modifier.width(thumbWidth)
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(row.aliasName, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (isVideo) Icons.Default.Movie else Icons.Default.Image, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.outline)
                        Spacer(Modifier.width(4.dp))
                        Text(
                            if (isVideo) (row.durationMs?.let { fmtDuration(it) } ?: "Video") else "Ảnh",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline
                        )
                    }
                    if (admin) {
                        // Chỉ Admin thấy đường dẫn thật; màn phát không bao giờ hiển thị.
                        Text(row.relativePath, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.height(6.dp))
                    TagChips(row.tagsCsv)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (selectionMode) {
                        Checkbox(checked = selected, onCheckedChange = { vm.toggleSelect(row.id) })
                    } else {
                        IconButton(onClick = { vm.toggleFavorite(row.id, row.isFavorite) }) {
                            Icon(
                                if (row.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "Yêu thích",
                                tint = if (row.isFavorite) Color(0xFFE53935) else MaterialTheme.colorScheme.outline
                            )
                        }
                        if (admin) IconButton(onClick = onEditAlias) { Icon(Icons.Default.Edit, "Sửa tên ảo", Modifier.size(20.dp)) }
                    }
                }
            }
        }
    }
}
