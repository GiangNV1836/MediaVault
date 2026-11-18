package com.vaultplayer.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.vaultplayer.data.MediaEntry
import com.vaultplayer.data.Tag
import com.vaultplayer.player.VlcPlayerWrapper
import com.vaultplayer.storage.PlaybackState
import com.vaultplayer.util.fmtDuration
import com.vaultplayer.viewer.ImageDecode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.videolan.libvlc.util.VLCVideoLayout

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Màn hình phát/xem toàn màn hình. CHỈ hiện tên ảo + tag (KHÔNG BAO GIỜ hiện tên file gốc, kể cả Admin).
 * Video: phát qua LibVLC, vuốt bị tắt (dùng nút Trước/Tiếp). Ảnh: vuốt trái/phải, zoom, kéo.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun PlayerHost(vm: MainViewModel) {
    val ctx = LocalContext.current
    val activity = ctx.findActivity()
    val view = LocalView.current
    val q = vm.queue
    if (q.isEmpty()) { LaunchedEffect(Unit) { vm.screen.value = Screen.MAIN }; return }

    val pager = rememberPagerState(initialPage = vm.startIndex.coerceIn(0, q.lastIndex)) { q.size }
    val scope = rememberCoroutineScope()
    var zoomed by remember { mutableStateOf(false) }
    var controls by remember { mutableStateOf(true) }
    var rotLocked by remember { mutableStateOf(false) }
    var aspect by remember { mutableIntStateOf(0) }
    var tagIds by remember { mutableStateOf<List<Long>?>(null) }

    val curId = q[pager.currentPage.coerceIn(0, q.lastIndex)]
    val entry by produceState<MediaEntry?>(null, curId) { value = vm.repo.media(curId) }
    val tags by produceState(emptyList<Tag>(), curId, tagIds) { value = vm.dao.tagsOfMedia(curId) }
    var fav by remember(curId) { mutableStateOf(false) }
    LaunchedEffect(entry) { fav = entry?.isFavorite ?: false }

    // Toàn màn hình (ẩn thanh hệ thống)
    DisposableEffect(Unit) {
        val w = activity?.window
        val c = w?.let { WindowCompat.getInsetsController(it, view) }
        c?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        c?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            c?.show(WindowInsetsCompat.Type.systemBars())
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            PlaybackState.rootId.value = null
        }
    }

    fun go(d: Int) {
        scope.launch {
            val t = (pager.currentPage + d).coerceIn(0, q.lastIndex)
            if (t != pager.currentPage) pager.animateScrollToPage(t)
        }
    }

    // Trình chiếu: ảnh tự chuyển sau N giây (chỉ khi phát từ playlist/yêu thích).
    LaunchedEffect(pager.currentPage, entry?.id) {
        val e = entry
        val sec = vm.vault.prefs.slideSec
        if (vm.slideshow && e != null && e.id == curId && e.mediaType == "IMAGE" && sec > 0) {
            delay(sec * 1000L)
            if (pager.currentPage < q.lastIndex && !zoomed) pager.animateScrollToPage(pager.currentPage + 1)
        }
    }

    // Mục lỗi mở file dù nguồn còn đọc được => đã bị xóa: đánh dấu mất file và về danh sách.
    suspend fun checkGone(m: MediaEntry) { if (vm.markIfGone(m)) vm.screen.value = Screen.MAIN }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = pager,
            userScrollEnabled = entry?.mediaType != "VIDEO" && !zoomed,
            beyondViewportPageCount = 1,
            key = { q[it] },
            modifier = Modifier.fillMaxSize()
        ) { page ->
            val id = q[page]
            val e by produceState<MediaEntry?>(null, id) { value = vm.repo.media(id) }
            Box(Modifier.fillMaxSize()) {
                val m = e
                if (m != null) {
                    if (m.mediaType == "IMAGE") {
                        ImagePage(vm, m, active = page == pager.currentPage,
                            onZoom = { z -> if (page == pager.currentPage) zoomed = z },
                            onTap = { controls = !controls }, onFailed = { checkGone(m) })
                    } else if (page == pager.currentPage && !pager.isScrollInProgress) {
                        VideoPage(vm, m, controls, aspect, bottomInset = 72.dp,
                            onToggle = { controls = !controls },
                            onEnded = { if (vm.slideshow) go(1) },
                            onFailed = { checkGone(m) })
                    }
                }
            }
        }

        // Thanh trên: quay lại + tên ảo + tag
        AnimatedVisibility(controls, Modifier.align(Alignment.TopCenter)) {
            Row(
                Modifier.fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)))
                    .statusBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { vm.screen.value = Screen.MAIN }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Quay lại", tint = Color.White) }
                Column(Modifier.weight(1f)) {
                    Text(entry?.aliasName ?: "", color = Color.White, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                    if (tags.isNotEmpty()) TagChips(tags.joinToString(",") { it.name }, max = 5)
                }
                Text("${pager.currentPage + 1}/${q.size}", color = Color.White, style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.width(8.dp))
            }
        }

        // Thanh dưới: hành động
        AnimatedVisibility(controls, Modifier.align(Alignment.BottomCenter)) {
            Row(
                Modifier.fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))))
                    .navigationBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { go(-1) }, enabled = pager.currentPage > 0) { Icon(Icons.Default.SkipPrevious, "Trước", tint = Color.White) }
                IconButton(onClick = { vm.toggleFavorite(curId, fav); fav = !fav }) {
                    Icon(if (fav) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "Yêu thích", tint = if (fav) Color(0xFFFF5252) else Color.White)
                }
                IconButton(onClick = { tagIds = listOf(curId) }) { Icon(Icons.AutoMirrored.Filled.Label, "Gắn tag", tint = Color.White) }
                if (entry?.mediaType == "VIDEO") {
                    IconButton(onClick = { aspect = (aspect + 1) % 4 }) { Icon(Icons.Default.AspectRatio, "Tỷ lệ khung hình", tint = Color.White) }
                }
                IconButton(onClick = {
                    rotLocked = !rotLocked
                    activity?.requestedOrientation = if (rotLocked) ActivityInfo.SCREEN_ORIENTATION_LOCKED else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                }) { Icon(if (rotLocked) Icons.Default.ScreenLockRotation else Icons.Default.ScreenRotation, "Khóa xoay", tint = Color.White) }
                IconButton(onClick = { go(1) }, enabled = pager.currentPage < q.lastIndex) { Icon(Icons.Default.SkipNext, "Tiếp theo", tint = Color.White) }
            }
        }
    }
    tagIds?.let { TagSheet(vm, it) { tagIds = null } }
}

@Composable
private fun VideoPage(
    vm: MainViewModel, m: MediaEntry, controls: Boolean, aspect: Int, bottomInset: Dp,
    onToggle: () -> Unit, onEnded: () -> Unit, onFailed: suspend () -> Unit
) {
    val ctx = LocalContext.current
    val player = remember(m.id) { VlcPlayerWrapper(ctx) }
    var pos by remember(m.id) { mutableLongStateOf(0L) }
    var len by remember(m.id) { mutableLongStateOf(0L) }
    var playing by remember(m.id) { mutableStateOf(false) }
    var error by remember(m.id) { mutableStateOf(false) }
    var dragging by remember(m.id) { mutableStateOf(false) }
    var dragVal by remember(m.id) { mutableFloatStateOf(0f) }
    var showRestart by remember(m.id) { mutableStateOf(m.lastPositionMs > 5000) }
    var widthPx by remember { mutableIntStateOf(1) }
    val compact = isCompactWidth()

    // Nhớ vị trí xem dở: ghi khi tạm dừng/dừng/xuống nền/chuyển mục (không ghi liên tục). <5s hoặc >95% => 0.
    val persist: () -> Unit = {
        val p = player.time
        val l = player.length
        if (p > 0 || l > 0) {
            val v = if (p < 5000 || (l > 0 && p > l * 0.95)) 0L else p
            vm.vault.scope.launch { vm.repo.setPosition(m.id, v) }
        }
    }

    player.onEnded = { vm.vault.scope.launch { vm.repo.setPosition(m.id, 0L) }; onEnded() }
    player.onError = { error = true }

    LaunchedEffect(m.id) {
        val uri = vm.sources.docUri(m)
        if (uri == null) error = true else player.start(uri, m.lastPositionMs)
    }
    LaunchedEffect(aspect) { player.setAspect(aspect) }
    LaunchedEffect(player) {
        var was = false
        while (true) {
            pos = player.time; len = player.length
            val p = player.isPlaying
            if (was && !p) persist()
            was = p; playing = p
            PlaybackState.rootId.value = if (p) m.rootId else null     // nhường băng thông: hoãn quét/backup khi đang phát
            delay(250)
        }
    }
    LaunchedEffect(len) { if (len > 0 && m.durationMs == null) vm.repo.setDuration(m.id, len) }
    LaunchedEffect(error) { if (error) onFailed() }
    LaunchedEffect(showRestart) { if (showRestart) { delay(6000); showRestart = false } }

    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, player) {
        val obs = LifecycleEventObserver { _, ev ->
            if (ev == Lifecycle.Event.ON_STOP) { persist(); if (player.isPlaying) player.toggle() }   // không phát nền
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs); persist(); player.release(); PlaybackState.rootId.value = null }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { c -> VLCVideoLayout(c).also { player.attach(it); it.keepScreenOn = true } },
            modifier = Modifier.fillMaxSize()
        )
        // Lớp bắt cử chỉ phía trên (VLCVideoLayout không cần nhận chạm): chạm = hiện/ẩn điều khiển, chạm đúp = ±10 giây.
        Box(
            Modifier.fillMaxSize()
                .pointerInput(m.id) { detectTapGestures(onTap = { onToggle() }, onDoubleTap = { o -> if (o.x < size.width / 2) player.seekBy(-10_000) else player.seekBy(10_000) }) }
        )
        if (error) Text("Không phát được mục này", color = Color.White, modifier = Modifier.align(Alignment.Center))
        if (showRestart) {
            AssistChip(
                onClick = { player.seekTo(0); showRestart = false },
                label = { Text("Phát từ đầu") },
                modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(start = 12.dp, top = 56.dp)
            )
        }
        AnimatedVisibility(controls, Modifier.align(Alignment.BottomCenter).padding(bottom = bottomInset)) {
            val big = if (compact) 56.dp else 72.dp
            Column(Modifier.fillMaxWidth().padding(horizontal = if (compact) 12.dp else 32.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(fmtDuration(if (dragging) dragVal.toLong() else pos), color = Color.White, style = MaterialTheme.typography.labelMedium)
                    val max = len.coerceAtLeast(1L).toFloat()
                    Slider(
                        value = (if (dragging) dragVal else pos.toFloat()).coerceIn(0f, max),
                        onValueChange = { dragging = true; dragVal = it },
                        onValueChangeFinished = { player.seekTo(dragVal.toLong()); dragging = false },
                        valueRange = 0f..max,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                    )
                    Text(fmtDuration(len), color = Color.White, style = MaterialTheme.typography.labelMedium)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { player.seekBy(-10_000) }) { Icon(Icons.Default.Replay10, "Lùi 10 giây", tint = Color.White) }
                    IconButton(onClick = { player.toggle() }, modifier = Modifier.size(big)) {
                        Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (playing) "Tạm dừng" else "Phát", tint = Color.White, modifier = Modifier.size(big - 12.dp))
                    }
                    IconButton(onClick = { player.seekBy(10_000) }) { Icon(Icons.Default.Forward10, "Tiến 10 giây", tint = Color.White) }
                }
            }
        }
    }
}

@Composable
private fun ImagePage(
    vm: MainViewModel, m: MediaEntry, active: Boolean,
    onZoom: (Boolean) -> Unit, onTap: () -> Unit, onFailed: suspend () -> Unit
) {
    val cr = LocalContext.current.contentResolver
    var base by remember(m.id) { mutableStateOf<ImageBitmap?>(null) }
    var hi by remember(m.id) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(m.id) { mutableStateOf(false) }
    var scale by remember(m.id) { mutableFloatStateOf(1f) }
    var offset by remember(m.id) { mutableStateOf(Offset.Zero) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val maxSide = maxOf(constraints.maxWidth, constraints.maxHeight)
        // Mặc định giải mã theo kích thước màn hình (tránh OutOfMemory); chỉ khi zoom mới giải mã cao hơn, chỉ cho trang hiện tại.
        LaunchedEffect(m.id) {
            val uri = vm.sources.docUri(m)
            val b = if (uri == null) null else withContext(Dispatchers.IO) { ImageDecode.decode(cr, uri, maxSide) }
            if (b == null) { failed = true; onFailed() } else base = b.asImageBitmap()
        }
        val hiRes by remember { derivedStateOf { scale > 1.5f } }   // chỉ đổi khi vượt ngưỡng, không theo từng khung hình
        LaunchedEffect(hiRes, active) {
            if (active && hiRes && hi == null) {
                val uri = vm.sources.docUri(m) ?: return@LaunchedEffect
                val side = minOf(4096, maxSide * 2)
                val b = withContext(Dispatchers.IO) { ImageDecode.decode(cr, uri, side) }
                if (b != null) hi = b.asImageBitmap()
            }
        }
        LaunchedEffect(active) { if (!active) { scale = 1f; offset = Offset.Zero; hi = null } }

        val img = hi ?: base
        if (img != null) {
            Image(
                bitmap = img, contentDescription = null, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
                    .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y }
                    .pointerInput(m.id) {
                        // Pinch luôn xử lý; kéo một ngón chỉ khi đã zoom (nếu không để Pager vuốt chuyển ảnh).
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            do {
                                val ev = awaitPointerEvent()
                                val zoom = ev.calculateZoom()
                                val pan = ev.calculatePan()
                                if (ev.changes.size > 1 || scale > 1.001f) {
                                    val ns = (scale * zoom).coerceIn(1f, 6f)
                                    val mx = size.width * (ns - 1f) / 2f
                                    val my = size.height * (ns - 1f) / 2f
                                    offset = if (ns <= 1.001f) Offset.Zero
                                    else Offset((offset.x + pan.x).coerceIn(-mx, mx), (offset.y + pan.y).coerceIn(-my, my))
                                    scale = ns
                                    onZoom(ns > 1.001f)
                                    ev.changes.forEach { if (it.positionChanged()) it.consume() }
                                }
                            } while (ev.changes.any { it.pressed })
                        }
                    }
                    .pointerInput(m.id) {
                        detectTapGestures(
                            onTap = { onTap() },
                            onDoubleTap = {
                                if (scale > 1.01f) { scale = 1f; offset = Offset.Zero; onZoom(false) } else { scale = 2.5f; onZoom(true) }
                            }
                        )
                    }
            )
        } else if (failed) {
            Text("Không hiển thị được ảnh", color = Color.White, modifier = Modifier.align(Alignment.Center))
        } else {
            CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
        }
    }
}
