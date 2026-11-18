package com.vaultplayer.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.withFrameNanos
import org.videolan.libvlc.util.VLCVideoLayout

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private enum class DecoderMode { AUTO, ANDROID, VLC_HARDWARE, SOFTWARE }
private enum class PlaybackBackend { MEDIA3, VLC }

/**
 * Màn hình phát/xem toàn màn hình. CHỈ hiện tên ảo + tag (KHÔNG BAO GIỜ hiện tên file gốc, kể cả Admin).
 * Video ưu tiên Media3, có LibVLC dự phòng; vuốt trái/phải chuyển mục. Ảnh hỗ trợ vuốt, zoom và kéo.
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
    var decoderMode by remember(curId) { mutableStateOf(DecoderMode.AUTO) }
    var menuExpanded by remember(curId) { mutableStateOf(false) }
    LaunchedEffect(entry) { fav = entry?.isFavorite ?: false }

    // Ẩn status bar khi phát nhưng giữ thanh điều hướng để luôn thao tác được nút hệ thống.
    DisposableEffect(Unit) {
        val w = activity?.window
        val c = w?.let { WindowCompat.getInsetsController(it, view) }
        c?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        c?.hide(WindowInsetsCompat.Type.statusBars())
        onDispose {
            c?.show(WindowInsetsCompat.Type.statusBars())
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

    val screenBackground = if (entry?.mediaType == "VIDEO") {
        MaterialTheme.colorScheme.background
    } else {
        Color.Black
    }
    Box(Modifier.fillMaxSize().background(screenBackground)) {
        HorizontalPager(
            state = pager,
            userScrollEnabled = !zoomed,
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
                        VideoPage(vm, m, controls, decoderMode, aspect, bottomInset = 12.dp,
                            onToggle = { controls = !controls },
                            onPrevious = { go(-1) },
                            onNext = { go(1) },
                            canPrevious = pager.currentPage > 0,
                            canNext = pager.currentPage < q.lastIndex,
                            isFavorite = fav,
                            onFavorite = { vm.toggleFavorite(curId, fav); fav = !fav },
                            onTags = { tagIds = listOf(curId) },
                            onDecoderModeChange = { decoderMode = it },
                            onEnded = { if (vm.slideshow) go(1) },
                            onFailed = { checkGone(m) })
                    }
                }
            }
        }

        // Header dạng thẻ nổi, giữ tên ảo và các thao tác trong vùng chạm rõ ràng.
        AnimatedVisibility(controls, Modifier.align(Alignment.TopCenter)) {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 6.dp)) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    tonalElevation = 8.dp,
                    shadowElevation = 10.dp
                ) {
                    Row(
                        Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { vm.screen.value = Screen.MAIN }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Quay lại", tint = MaterialTheme.colorScheme.onSurface)
                        }
                        Column(Modifier.weight(1f).padding(start = 4.dp)) {
                            Text(entry?.aliasName ?: "", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                            if (tags.isNotEmpty()) TagChips(tags.joinToString(",") { it.name }, max = 3)
                        }
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceContainerHigh
                        ) {
                            Text(
                                "${pager.currentPage + 1}/${q.size}",
                                Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                        Box {
                            IconButton(onClick = { menuExpanded = true }) {
                                Icon(Icons.Default.MoreVert, "Thao tác khác", tint = MaterialTheme.colorScheme.onSurface)
                            }
                            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                                DropdownMenuItem(
                                    text = { Text(if (fav) "Bỏ yêu thích" else "Yêu thích") },
                                    leadingIcon = { Icon(if (fav) Icons.Default.Favorite else Icons.Default.FavoriteBorder, null) },
                                    onClick = {
                                        vm.toggleFavorite(curId, fav)
                                        fav = !fav
                                        menuExpanded = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Gắn tag") },
                                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.Label, null) },
                                    onClick = { tagIds = listOf(curId); menuExpanded = false }
                                )
                                if (entry?.mediaType == "VIDEO") {
                                    HorizontalDivider()
                                    DropdownMenuItem(
                                        text = { Text("Tự động · Android, dự phòng VLC") },
                                        leadingIcon = { if (decoderMode == DecoderMode.AUTO) Icon(Icons.Default.Check, null) },
                                        onClick = { decoderMode = DecoderMode.AUTO; menuExpanded = false }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Android · MediaCodec") },
                                        leadingIcon = { if (decoderMode == DecoderMode.ANDROID) Icon(Icons.Default.Check, null) },
                                        onClick = { decoderMode = DecoderMode.ANDROID; menuExpanded = false }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("LibVLC · tăng tốc phần cứng") },
                                        leadingIcon = { if (decoderMode == DecoderMode.VLC_HARDWARE) Icon(Icons.Default.Check, null) },
                                        onClick = { decoderMode = DecoderMode.VLC_HARDWARE; menuExpanded = false }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("LibVLC · giải mã phần mềm") },
                                        leadingIcon = { if (decoderMode == DecoderMode.SOFTWARE) Icon(Icons.Default.Check, null) },
                                        onClick = { decoderMode = DecoderMode.SOFTWARE; menuExpanded = false }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(if (aspect == 0) "Khung hình: vừa khít" else "Khung hình: lấp đầy") },
                                        leadingIcon = { Icon(Icons.Default.AspectRatio, null) },
                                        onClick = { aspect = 1 - aspect; menuExpanded = false }
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text(if (rotLocked) "Mở khóa xoay" else "Khóa xoay") },
                                    leadingIcon = { Icon(if (rotLocked) Icons.Default.ScreenLockRotation else Icons.Default.ScreenRotation, null) },
                                    onClick = {
                                        rotLocked = !rotLocked
                                        activity?.requestedOrientation = if (rotLocked) ActivityInfo.SCREEN_ORIENTATION_LOCKED else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                                        menuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        // Ảnh dùng thanh thao tác riêng; video có cụm tua/phát ngay dưới thanh thời gian.
        AnimatedVisibility(controls && entry?.mediaType != "VIDEO", Modifier.align(Alignment.BottomCenter)) {
            Row(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    tonalElevation = 8.dp,
                    shadowElevation = 12.dp
                ) {
                    Row(
                        Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { go(-1) }, enabled = pager.currentPage > 0) {
                            Icon(Icons.Default.SkipPrevious, "Trước", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { vm.toggleFavorite(curId, fav); fav = !fav }) {
                            Icon(if (fav) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "Yêu thích", tint = if (fav) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { tagIds = listOf(curId) }) { Icon(Icons.AutoMirrored.Filled.Label, "Gắn tag", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                        IconButton(onClick = { go(1) }, enabled = pager.currentPage < q.lastIndex) { Icon(Icons.Default.SkipNext, "Tiếp theo", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        }
    }
    tagIds?.let { TagSheet(vm, it) { tagIds = null } }
}

@Composable
private fun VideoPage(
    vm: MainViewModel, m: MediaEntry, controls: Boolean, decoderMode: DecoderMode, aspect: Int, bottomInset: Dp,
    onToggle: () -> Unit, onPrevious: () -> Unit, onNext: () -> Unit,
    canPrevious: Boolean, canNext: Boolean, isFavorite: Boolean,
    onFavorite: () -> Unit, onTags: () -> Unit, onDecoderModeChange: (DecoderMode) -> Unit,
    onEnded: () -> Unit, onFailed: suspend () -> Unit
) {
    val ctx = LocalContext.current
    val exo = remember(m.id) {
        val renderers = DefaultRenderersFactory(ctx).setEnableDecoderFallback(false)
        ExoPlayer.Builder(ctx, renderers).build()
    }
    val vlc = remember(m.id) { VlcPlayerWrapper(ctx) }
    var backend by remember(m.id) { mutableStateOf(PlaybackBackend.MEDIA3) }
    var mediaUri by remember(m.id) { mutableStateOf<Uri?>(null) }
    var requestedPosition by remember(m.id) { mutableLongStateOf(m.lastPositionMs) }
    var previousMode by remember(m.id) { mutableStateOf(decoderMode) }
    var fallbackAttempted by remember(m.id) { mutableStateOf(false) }
    var pos by remember(m.id) { mutableLongStateOf(0L) }
    var len by remember(m.id) { mutableLongStateOf(0L) }
    var playing by remember(m.id) { mutableStateOf(false) }
    var loading by remember(m.id) { mutableStateOf(true) }
    var error by remember(m.id) { mutableStateOf(false) }
    var ended by remember(m.id) { mutableStateOf(false) }
    var dragging by remember(m.id) { mutableStateOf(false) }
    var fastForwarding by remember(m.id) { mutableStateOf(false) }
    var dragVal by remember(m.id) { mutableFloatStateOf(0f) }
    var seekFeedbackSeconds by remember(m.id) { mutableIntStateOf(0) }
    var seekFeedbackTrigger by remember(m.id) { mutableIntStateOf(0) }
    var showRestart by remember(m.id) { mutableStateOf(m.lastPositionMs > 5000) }
    val compact = isCompactWidth()
    val portrait = LocalContext.current.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
    val safeTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val safeBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val currentBackend by rememberUpdatedState(backend)
    val currentMode by rememberUpdatedState(decoderMode)
    val currentOnEnded by rememberUpdatedState(onEnded)

    fun currentPosition(): Long = when (backend) {
        PlaybackBackend.MEDIA3 -> exo.currentPosition.coerceAtLeast(0L)
        PlaybackBackend.VLC -> vlc.time.coerceAtLeast(0L)
    }

    fun currentLength(): Long = when (backend) {
        PlaybackBackend.MEDIA3 -> exo.duration.takeIf { it > 0L && it != C.TIME_UNSET } ?: 0L
        PlaybackBackend.VLC -> vlc.length.coerceAtLeast(0L)
    }

    fun seekTo(position: Long) {
        ended = false
        val target = position.coerceAtLeast(0L).let { if (len > 0) it.coerceAtMost(len) else it }
        when (backend) {
            PlaybackBackend.MEDIA3 -> exo.seekTo(target)
            PlaybackBackend.VLC -> vlc.seekTo(target)
        }
    }

    fun seekBy(delta: Long) = seekTo(currentPosition() + delta)
    fun togglePlayback() {
        val replay = ended || (backend == PlaybackBackend.MEDIA3 && exo.playbackState == Player.STATE_ENDED)
        ended = false
        when (backend) {
            PlaybackBackend.MEDIA3 -> if (exo.isPlaying) exo.pause() else {
                if (replay) exo.seekTo(0)
                exo.play()
            }
            PlaybackBackend.VLC -> if (vlc.isPlaying) vlc.toggle() else if (replay) {
                mediaUri?.let { vlc.start(it, 0L, softwareDecode = decoderMode == DecoderMode.SOFTWARE) }
                    ?: vlc.toggle()
            } else vlc.toggle()
        }
    }

    // Nhớ vị trí xem dở: ghi khi tạm dừng/dừng/xuống nền/chuyển mục (không ghi liên tục). <5s hoặc >95% => 0.
    val persist: () -> Unit = {
        val p = currentPosition()
        val l = currentLength()
        if (p > 0 || l > 0) {
            val v = if (p < 5000 || (l > 0 && p > l * 0.95)) 0L else p
            vm.vault.scope.launch { vm.repo.setPosition(m.id, v) }
        }
    }

    vlc.onEnded = {
        ended = true
        vm.vault.scope.launch { vm.repo.setPosition(m.id, 0L) }
        currentOnEnded()
    }
    vlc.onError = {
        loading = false
        PlaybackState.rootId.value = null
        error = true
    }

    LaunchedEffect(m.id) {
        PlaybackState.rootId.value = m.rootId
        mediaUri = vm.sources.docUri(m)
        if (mediaUri == null) {
            loading = false
            error = true
        } else {
            backend = if (decoderMode == DecoderMode.VLC_HARDWARE || decoderMode == DecoderMode.SOFTWARE) PlaybackBackend.VLC else PlaybackBackend.MEDIA3
        }
    }

    LaunchedEffect(mediaUri, decoderMode, backend, requestedPosition) {
        val uri = mediaUri ?: return@LaunchedEffect
        if (previousMode != decoderMode) {
            requestedPosition = if (error) requestedPosition else currentPosition()
            previousMode = decoderMode
            fallbackAttempted = false
            ended = false
            error = false
            loading = true
            backend = if (decoderMode == DecoderMode.VLC_HARDWARE || decoderMode == DecoderMode.SOFTWARE) PlaybackBackend.VLC else PlaybackBackend.MEDIA3
            return@LaunchedEffect
        }
        error = false
        ended = false
        loading = true
        when (backend) {
            PlaybackBackend.MEDIA3 -> {
                vlc.stopPlayback()
                exo.stop()
                exo.setMediaItem(MediaItem.fromUri(uri), requestedPosition)
                exo.prepare()
                exo.playWhenReady = true
            }
            PlaybackBackend.VLC -> {
                exo.pause()
                exo.stop()
                withFrameNanos { }
                vlc.start(uri, requestedPosition, softwareDecode = decoderMode == DecoderMode.SOFTWARE)
            }
        }
    }

    DisposableEffect(exo, m.id) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (currentBackend == PlaybackBackend.MEDIA3) {
                    loading = state == Player.STATE_BUFFERING
                    if (state == Player.STATE_ENDED) {
                        ended = true
                        vm.vault.scope.launch { vm.repo.setPosition(m.id, 0L) }
                        currentOnEnded()
                    }
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (currentBackend == PlaybackBackend.MEDIA3) playing = isPlaying
            }

            override fun onPlayerError(errorInfo: PlaybackException) {
                if (currentBackend == PlaybackBackend.MEDIA3 &&
                    currentMode == DecoderMode.AUTO &&
                    !fallbackAttempted
                ) {
                    fallbackAttempted = true
                    requestedPosition = exo.currentPosition.takeIf { it > 0L } ?: requestedPosition
                    loading = true
                    error = false
                    backend = PlaybackBackend.VLC
                } else if (currentBackend == PlaybackBackend.MEDIA3) {
                    loading = false
                    error = true
                    PlaybackState.rootId.value = null
                }
            }
        }
        exo.addListener(listener)
        onDispose { exo.removeListener(listener) }
    }

    LaunchedEffect(backend, decoderMode, exo, vlc) {
        var wasPlaying = false
        while (true) {
            pos = currentPosition()
            len = currentLength()
            val isActivePlaying = when (backend) {
                PlaybackBackend.MEDIA3 -> exo.isPlaying
                PlaybackBackend.VLC -> vlc.isPlaying
            }
            val isActiveLoading = when (backend) {
                PlaybackBackend.MEDIA3 -> exo.playbackState == Player.STATE_BUFFERING
                PlaybackBackend.VLC -> vlc.isLoading
            }
            loading = isActiveLoading
            if (wasPlaying && !isActivePlaying && !ended) persist()
            wasPlaying = isActivePlaying
            playing = isActivePlaying
            PlaybackState.rootId.value = if (isActivePlaying || isActiveLoading) m.rootId else null
            delay(400)
        }
    }

    LaunchedEffect(len) { if (len > 0 && m.durationMs == null) vm.repo.setDuration(m.id, len) }
    LaunchedEffect(aspect) { vlc.setAspect(aspect) }
    LaunchedEffect(error) { if (error) onFailed() }
    LaunchedEffect(showRestart) { if (showRestart) { delay(6000); showRestart = false } }
    LaunchedEffect(seekFeedbackTrigger) {
        if (seekFeedbackTrigger > 0) {
            delay(700)
            seekFeedbackSeconds = 0
        }
    }
    LaunchedEffect(controls, playing, dragging) {
        if (controls && playing && !dragging && !portrait) {
            delay(3500)
            onToggle()
        }
    }

    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, exo, vlc) {
        val obs = LifecycleEventObserver { _, ev ->
            if (ev == Lifecycle.Event.ON_STOP) {
                persist()
                if (exo.isPlaying) exo.pause()
                if (vlc.isPlaying) vlc.toggle()
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose {
            owner.lifecycle.removeObserver(obs)
            persist()
            exo.release()
            vlc.release()
            PlaybackState.rootId.value = null
        }
    }

    Box(Modifier.fillMaxSize()) {
        val videoViewport = Modifier.fillMaxSize().padding(
            top = if (portrait && controls) safeTop + 64.dp else 0.dp,
            bottom = if (portrait && controls) 190.dp + bottomInset + safeBottom else 0.dp
        )
        if (backend == PlaybackBackend.MEDIA3) {
            AndroidView(
                factory = { c ->
                    PlayerView(c).apply {
                        player = exo
                        useController = false
                        resizeMode = if (aspect == 1) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
                        setShutterBackgroundColor(android.graphics.Color.BLACK)
                        keepScreenOn = true
                    }
                },
                update = {
                    it.player = exo
                    it.resizeMode = if (aspect == 1) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
                },
                onRelease = { it.player = null },
                modifier = videoViewport
            )
        } else {
            AndroidView(
                factory = { c -> VLCVideoLayout(c).also { vlc.attach(it); it.keepScreenOn = true } },
                update = { vlc.attach(it) },
                modifier = videoViewport
            )
        }

        Box(
            Modifier.fillMaxSize()
                .pointerInput(m.id, backend) {
                    detectTapGestures(
                        onPress = {
                            var fastForwardStarted = false
                            coroutineScope {
                                val holdJob = launch {
                                    delay(viewConfiguration.longPressTimeoutMillis)
                                    if (backend == PlaybackBackend.MEDIA3) {
                                        exo.playbackParameters = PlaybackParameters(2f)
                                    } else {
                                        vlc.setRate(2f)
                                    }
                                    fastForwardStarted = true
                                    fastForwarding = true
                                }
                                tryAwaitRelease()
                                holdJob.cancel()
                                if (fastForwardStarted) {
                                    if (backend == PlaybackBackend.MEDIA3) {
                                        exo.playbackParameters = PlaybackParameters(1f)
                                    } else {
                                        vlc.setRate(1f)
                                    }
                                    fastForwarding = false
                                }
                            }
                        },
                        onTap = { onToggle() },
                        onDoubleTap = { o ->
                            val delta = if (o.x < size.width / 2) -5_000L else 5_000L
                            seekBy(delta)
                            seekFeedbackSeconds = if (delta < 0) -5 else 5
                            seekFeedbackTrigger++
                        },
                        onLongPress = {}
                    )
                }
        )
        if (fastForwarding) {
            Surface(
                modifier = Modifier.align(Alignment.Center),
                color = Color.Black.copy(alpha = 0.65f),
                shape = MaterialTheme.shapes.large
            ) {
                Text("2×", Modifier.padding(horizontal = 20.dp, vertical = 10.dp), color = Color.White, style = MaterialTheme.typography.titleLarge)
            }
        }
        AnimatedVisibility(
            visible = seekFeedbackSeconds != 0,
            modifier = Modifier.align(Alignment.Center)
        ) {
            Surface(
                color = Color.Black.copy(alpha = 0.62f),
                shape = RoundedCornerShape(24.dp)
            ) {
                Row(
                    Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (seekFeedbackSeconds < 0) Icons.Default.Replay5 else Icons.Default.Forward5,
                        null,
                        tint = Color.White
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${if (seekFeedbackSeconds > 0) "+" else ""}${seekFeedbackSeconds}s",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
        }
        if (loading && !error && seekFeedbackSeconds == 0) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator(color = Color.White)
                Spacer(Modifier.height(12.dp))
                Text("Đang mở video…", color = Color.White, style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (error) {
            Column(
                Modifier.align(Alignment.Center).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Không phát được mục này", color = Color.White)
                if (decoderMode != DecoderMode.SOFTWARE) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { onDecoderModeChange(DecoderMode.SOFTWARE) }) {
                        Text("Thử giải mã phần mềm")
                    }
                }
            }
        }
        if (showRestart) {
            AssistChip(
                onClick = { seekTo(0); showRestart = false },
                label = { Text("Phát từ đầu") },
                modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(start = 12.dp, top = 56.dp)
            )
        }
        AnimatedVisibility(
            controls,
            Modifier.align(Alignment.BottomCenter).padding(
                bottom = bottomInset + safeBottom
            )
        ) {
            val panelShape = RoundedCornerShape(24.dp)
            Surface(
                modifier = Modifier.widthIn(max = if (portrait) 560.dp else 820.dp)
                    .fillMaxWidth()
                    .padding(horizontal = if (compact) 12.dp else 32.dp),
                shape = panelShape,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.68f),
                tonalElevation = 3.dp,
                shadowElevation = 8.dp,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
            ) {
                Column(
                    Modifier.background(
                        Brush.verticalGradient(
                            listOf(
                                MaterialTheme.colorScheme.surface.copy(alpha = 0.12f),
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
                            )
                        ),
                        panelShape
                    ).padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    if (portrait) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    if (backend == PlaybackBackend.MEDIA3) "ĐANG PHÁT · MEDIA3" else "ĐANG PHÁT · VLC",
                                    color = MaterialTheme.colorScheme.primary,
                                    style = MaterialTheme.typography.labelSmall
                                )
                                Text(
                                    when (decoderMode) {
                                        DecoderMode.AUTO -> "Tự động"
                                        DecoderMode.ANDROID -> "MediaCodec · Android"
                                        DecoderMode.VLC_HARDWARE -> "LibVLC · phần cứng"
                                        DecoderMode.SOFTWARE -> "LibVLC · phần mềm"
                                    },
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1
                                )
                            }
                            IconButton(onClick = onFavorite) {
                                Icon(
                                    if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                    "Yêu thích",
                                    tint = if (isFavorite) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = onTags) {
                                Icon(Icons.AutoMirrored.Filled.Label, "Gắn tag", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                    }

                    val max = len.coerceAtLeast(1L).toFloat()
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            fmtDuration(if (dragging) dragVal.toLong() else pos),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.widthIn(min = 34.dp)
                        )
                        Slider(
                            value = (if (dragging) dragVal else pos.toFloat()).coerceIn(0f, max),
                            onValueChange = { dragging = true; dragVal = it },
                            onValueChangeFinished = { seekTo(dragVal.toLong()); dragging = false },
                            valueRange = 0f..max,
                            modifier = Modifier.weight(1f)
                                .height(48.dp)
                                .padding(horizontal = 8.dp)
                                .graphicsLayer { scaleY = 0.55f },
                            colors = SliderDefaults.colors(
                                activeTrackColor = MaterialTheme.colorScheme.primary,
                                inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f),
                                thumbColor = MaterialTheme.colorScheme.primary
                            )
                        )
                        Text(
                            fmtDuration(len),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.widthIn(min = 34.dp)
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val narrow = maxWidth < 320.dp
                        val big = if (narrow) 48.dp else if (compact) 54.dp else 60.dp
                        val sideButton = if (narrow) 40.dp else if (compact) 42.dp else 46.dp
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = onPrevious, enabled = canPrevious,
                                modifier = Modifier.size(sideButton).background(
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f), CircleShape
                                )
                            ) {
                                Icon(Icons.Default.SkipPrevious, "Trước", tint = MaterialTheme.colorScheme.onSurface.copy(alpha = if (canPrevious) 1f else 0.4f))
                            }
                            IconButton(
                                onClick = { seekBy(-5_000) },
                                modifier = Modifier.size(sideButton).background(
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f), CircleShape
                                )
                            ) { Icon(Icons.Default.Replay5, "Lùi 5 giây", tint = MaterialTheme.colorScheme.onSurface) }
                            IconButton(
                                onClick = { togglePlayback() },
                                modifier = Modifier.size(big).background(MaterialTheme.colorScheme.primary, CircleShape)
                            ) {
                                Icon(
                                    if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    if (playing) "Tạm dừng" else "Phát",
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(big - 20.dp)
                                )
                            }
                            IconButton(
                                onClick = { seekBy(5_000) },
                                modifier = Modifier.size(sideButton).background(
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f), CircleShape
                                )
                            ) { Icon(Icons.Default.Forward5, "Tiến 5 giây", tint = MaterialTheme.colorScheme.onSurface) }
                            IconButton(
                                onClick = onNext, enabled = canNext,
                                modifier = Modifier.size(sideButton).background(
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f), CircleShape
                                )
                            ) {
                                Icon(Icons.Default.SkipNext, "Tiếp theo", tint = MaterialTheme.colorScheme.onSurface.copy(alpha = if (canNext) 1f else 0.4f))
                            }
                        }
                    }
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
