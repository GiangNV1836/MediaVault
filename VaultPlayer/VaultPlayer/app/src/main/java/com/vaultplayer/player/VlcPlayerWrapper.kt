package com.vaultplayer.player

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import java.util.concurrent.CompletableFuture

/** Một thể hiện LibVLC dùng chung (khởi tạo plugin chậm). Giải phóng sau 15 giây không còn ai dùng. */
object VlcHolder {
    private var inst: LibVLC? = null
    private var users = 0
    private val h = Handler(Looper.getMainLooper())
    private val io = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "vlc-holder").apply { isDaemon = true }
    }
    private val releaser = Runnable {
        io.execute {
            synchronized(VlcHolder) {
                if (users == 0) {
                    runCatching { inst?.release() }
                    inst = null
                }
            }
        }
    }

    @Synchronized
    fun acquire(ctx: Context): LibVLC {
        h.removeCallbacks(releaser)
        val current = inst
            ?: LibVLC(ctx.applicationContext, arrayListOf("--quiet", "--no-stats", "--no-sub-autodetect-file", "--no-osd"))
                .also { inst = it }
        users++
        return current
    }

    @Synchronized
    fun done() {
        users = maxOf(0, users - 1)
        if (users == 0) h.postDelayed(releaser, 15_000)
    }

    fun dispose(player: MediaPlayer?, fd: ParcelFileDescriptor?, attached: Boolean): CompletableFuture<Unit> {
        val completed = CompletableFuture<Unit>()
        io.execute {
            runCatching { player?.stop() }
            val release = Runnable {
                if (attached) runCatching { player?.detachViews() }
                io.execute {
                    runCatching { player?.release() }
                    done()
                    runCatching { fd?.close() }
                    completed.complete(Unit)
                }
            }
            if (attached) h.post(release) else release()
        }
        return completed
    }
}

/**
 * Backend dự phòng LibVLC. Chế độ giải mã được chọn rõ ràng; không tự chuyển sang phần mềm khi chậm hiện hình.
 * Mở file bằng FileDescriptor từ SAF. Không log, không hiển thị metadata.
 */
class VlcPlayerWrapper(private val ctx: Context) {
    private var libVlc: LibVLC? = null
    private var mp: MediaPlayer? = null
    private var pfd: ParcelFileDescriptor? = null
    private var layout: VLCVideoLayout? = null
    private var uri: Uri? = null
    private var startMs = 0L
    private var seeked = false
    @Volatile private var released = false
    private var aspect = 0
    @Volatile private var starting = false
    private val handler = Handler(Looper.getMainLooper())

    var onEnded: () -> Unit = {}
    var onError: () -> Unit = {}

    val time: Long get() = mp?.time ?: 0L
    val length: Long get() = mp?.length ?: 0L
    val isPlaying: Boolean get() = mp?.isPlaying ?: false
    val isLoading: Boolean get() = starting

    fun attach(l: VLCVideoLayout) { layout = l }

    fun start(uri: Uri, startMs: Long, softwareDecode: Boolean = false) {
        this.uri = uri
        this.startMs = startMs
        play(softwareDecode)
    }

    private val io = java.util.concurrent.Executors.newSingleThreadExecutor()
    @Volatile private var generation = 0
    private var cleanup: CompletableFuture<Unit>? = null

    /** Mở file và chuẩn bị LibVLC/MediaPlayer ở luồng nền; chỉ gắn surface trên luồng giao diện. */
    private fun play(sw: Boolean) {
        if (released) return
        releasePlayer()
        starting = true
        val u = uri ?: run { starting = false; return }
        val gen = ++generation
        val previousCleanup = cleanup
        io.execute {
            try {
                previousCleanup?.get()
            } catch (e: Exception) {
                handler.post {
                    if (!released && gen == generation) {
                        starting = false
                        onError()
                    }
                }
                return@execute
            }
            if (released || gen != generation) return@execute
            val fd = try {
                ctx.contentResolver.openFileDescriptor(u, "r")
            } catch (e: Exception) {
                null
            }
            if (fd == null) {
                handler.post {
                    if (!released && gen == generation) {
                        starting = false
                        onError()
                    }
                }
                return@execute
            }

            var vlc: LibVLC? = null
            var player: MediaPlayer? = null
            var media: Media? = null
            try {
                val acquired = VlcHolder.acquire(ctx)
                vlc = acquired
                val createdPlayer = MediaPlayer(acquired)
                player = createdPlayer
                val openedMedia = Media(acquired, fd.fileDescriptor)
                media = openedMedia
                openedMedia.setHWDecoderEnabled(!sw, !sw)
                if (sw) openedMedia.addOption(":avcodec-hw=none")
                openedMedia.addOption(":file-caching=1000")
                createdPlayer.setMedia(openedMedia)
            } catch (e: Exception) {
                runCatching { player?.release() }
                if (vlc != null) VlcHolder.done()
                runCatching { fd.close() }
                handler.post {
                    if (!released && gen == generation) {
                        starting = false
                        onError()
                    }
                }
                return@execute
            } finally {
                media?.release()
            }

            val preparedVlc = vlc
            val preparedPlayer = player
            handler.post {
                if (released || gen != generation) {
                    cleanup = VlcHolder.dispose(preparedPlayer, fd, attached = false)
                    return@post
                }
                val l = layout
                if (l == null || preparedVlc == null || preparedPlayer == null) {
                    cleanup = VlcHolder.dispose(preparedPlayer, fd, attached = false)
                    if (l == null && !released) {
                        starting = false
                        onError()
                    }
                    return@post
                }
                pfd = fd
                libVlc = preparedVlc
                mp = preparedPlayer
                try {
                    preparedPlayer.attachViews(l, null, false, false)
                    applyAspect()
                    preparedPlayer.setEventListener { ev ->
                        when (ev.type) {
                            MediaPlayer.Event.Playing -> {
                                starting = false
                                if (!seeked && startMs > 0) {
                                    preparedPlayer.setTime(startMs)
                                    seeked = true
                                }
                            }
                            MediaPlayer.Event.EndReached -> handler.post { onEnded() }
                            MediaPlayer.Event.EncounteredError -> handler.post {
                                if (mp === preparedPlayer) {
                                    starting = false
                                    onError()
                                }
                            }
                            else -> {}
                        }
                    }
                    preparedPlayer.play()
                } catch (e: Exception) {
                    starting = false
                    runCatching { preparedPlayer.setEventListener(null) }
                    mp = null
                    libVlc = null
                    pfd = null
                    cleanup = VlcHolder.dispose(preparedPlayer, fd, attached = true)
                    onError()
                }
            }
        }
    }

    fun toggle() { mp?.let { if (it.isPlaying) it.pause() else it.play() } }
    fun stopPlayback() {
        generation++
        releasePlayer()
    }
    fun setRate(rate: Float) { mp?.setRate(rate) }
    fun seekTo(ms: Long) { mp?.setTime(ms.coerceAtLeast(0L)) }
    fun seekBy(delta: Long) { mp?.let { it.setTime((it.time + delta).coerceIn(0L, maxOf(it.length, 0L))) } }

    /** 0: vừa khít, 1: lấp đầy, 2: 16:9, 3: 4:3 */
    fun setAspect(mode: Int) { aspect = mode; applyAspect() }

    private fun applyAspect() {
        val t = when (aspect) {
            1 -> MediaPlayer.ScaleType.SURFACE_FILL
            2 -> MediaPlayer.ScaleType.SURFACE_16_9
            3 -> MediaPlayer.ScaleType.SURFACE_4_3
            else -> MediaPlayer.ScaleType.SURFACE_BEST_FIT
        }
        mp?.setVideoScale(t)
    }

    private fun releasePlayer() {
        handler.removeCallbacksAndMessages(null)
        starting = false
        val oldPlayer = mp
        val hadVlc = libVlc != null
        val oldFd = pfd
        mp = null
        libVlc = null
        pfd = null
        if (oldPlayer != null) runCatching { oldPlayer.setEventListener(null) }
        if (oldPlayer != null || hadVlc || oldFd != null) {
            cleanup = VlcHolder.dispose(oldPlayer, oldFd, attached = oldPlayer != null)
        }
    }

    fun release() {
        released = true
        starting = false
        generation++
        releasePlayer()
        layout = null
        io.shutdown()
    }
}
