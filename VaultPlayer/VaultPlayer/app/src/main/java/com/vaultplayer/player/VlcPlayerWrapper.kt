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

/** Một thể hiện LibVLC dùng chung (khởi tạo plugin chậm). Giải phóng sau 15 giây không còn ai dùng. */
object VlcHolder {
    private var inst: LibVLC? = null
    private var users = 0
    private val h = Handler(Looper.getMainLooper())
    private val releaser = Runnable {
        synchronized(VlcHolder) {
            if (users == 0) { runCatching { inst?.release() }; inst = null }
        }
    }

    @Synchronized
    fun acquire(ctx: Context): LibVLC {
        h.removeCallbacks(releaser)
        users++
        return inst ?: LibVLC(ctx.applicationContext, arrayListOf("--quiet", "--no-stats", "--no-sub-autodetect-file", "--no-osd")).also { inst = it }
    }

    @Synchronized
    fun done() {
        users = maxOf(0, users - 1)
        if (users == 0) h.postDelayed(releaser, 15_000)
    }
}

/**
 * Bọc LibVLC: giải mã phần cứng (HW) trước; lỗi codec/không có hình thì tạo lại player ở chế độ phần mềm (SW)
 * và phát tiếp từ vị trí hiện tại. Mở file bằng FileDescriptor từ SAF. Không log, không hiển thị metadata.
 */
class VlcPlayerWrapper(private val ctx: Context) {
    private var libVlc: LibVLC? = null
    private var mp: MediaPlayer? = null
    private var pfd: ParcelFileDescriptor? = null
    private var layout: VLCVideoLayout? = null
    private var uri: Uri? = null
    private var startMs = 0L
    private var seeked = false
    private var swTried = false
    private var voutCount = 0
    private var released = false
    private var aspect = 0
    private val handler = Handler(Looper.getMainLooper())

    var onEnded: () -> Unit = {}
    var onError: () -> Unit = {}

    val time: Long get() = mp?.time ?: 0L
    val length: Long get() = mp?.length ?: 0L
    val isPlaying: Boolean get() = mp?.isPlaying ?: false

    fun attach(l: VLCVideoLayout) { layout = l }

    fun start(uri: Uri, startMs: Long) {
        this.uri = uri
        this.startMs = startMs
        swTried = false
        play(false)
    }

    private val io = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var generation = 0

    /** Mở FileDescriptor ở luồng nền rồi mới tạo player ở luồng chính. */
    private fun play(sw: Boolean) {
        if (released) return
        releasePlayer()
        val u = uri ?: return
        val gen = ++generation
        io.execute {
            val fd = try { ctx.contentResolver.openFileDescriptor(u, "r") } catch (e: Exception) { null }
            handler.post {
                if (released || gen != generation) { runCatching { fd?.close() }; return@post }
                if (fd == null) { onError(); return@post }
                startPlayer(fd, sw)
            }
        }
    }

    private fun startPlayer(fd: ParcelFileDescriptor, sw: Boolean) {
        val l = layout ?: run { runCatching { fd.close() }; return }
        pfd = fd
        val vlc = VlcHolder.acquire(ctx)
        libVlc = vlc
        val p = MediaPlayer(vlc)
        mp = p
        p.attachViews(l, null, false, false)            // SurfaceView (không dùng TextureView)
        val media = Media(vlc, fd.fileDescriptor)
        media.setHWDecoderEnabled(!sw, false)
        if (sw) media.addOption(":avcodec-hw=none")
        media.addOption(":file-caching=2000")          // bộ đệm cho nguồn ngoài chậm (chỉnh 1500-3000 ms)
        p.setMedia(media)
        media.release()
        voutCount = 0
        seeked = false
        applyAspect()
        p.setEventListener { ev ->
            when (ev.type) {
                MediaPlayer.Event.Playing -> {
                    if (!seeked && startMs > 0) { p.setTime(startMs); seeked = true }
                    handler.postDelayed({ checkVideo(p, sw) }, 3500)
                }
                MediaPlayer.Event.Vout -> voutCount = ev.voutCount
                MediaPlayer.Event.EndReached -> handler.post { onEnded() }
                MediaPlayer.Event.EncounteredError -> handler.post {
                    if (mp === p && !sw && !swTried) { swTried = true; startMs = maxOf(startMs, p.time); play(true) }
                    else onError()
                }
                else -> {}
            }
        }
        p.play()
    }

    /** Không có hình sau vài giây dù có track video => chuyển sang giải mã phần mềm. */
    private fun checkVideo(p: MediaPlayer, sw: Boolean) {
        if (released || mp !== p || sw || swTried) return
        if (p.videoTracksCount > 0 && voutCount == 0 && p.isPlaying) {
            swTried = true
            startMs = maxOf(startMs, p.time)
            play(true)
        }
    }

    fun toggle() { mp?.let { if (it.isPlaying) it.pause() else it.play() } }
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
        mp?.let {
            runCatching { it.setEventListener(null) }
            runCatching { it.stop() }
            runCatching { it.detachViews() }
            runCatching { it.release() }
        }
        mp = null
        if (libVlc != null) { VlcHolder.done(); libVlc = null }
        runCatching { pfd?.close() }
        pfd = null
    }

    fun release() {
        released = true
        generation++
        releasePlayer()
        layout = null
        io.shutdown()
    }
}
