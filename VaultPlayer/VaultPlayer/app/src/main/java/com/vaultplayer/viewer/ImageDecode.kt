package com.vaultplayer.viewer

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri

/** Giải mã ảnh thu nhỏ bằng ImageDecoder (tự áp dụng EXIF, hỗ trợ HEIF/AVIF/WebP/GIF khung đầu nếu máy hỗ trợ). */
object ImageDecode {
    /** Giải mã sao cho cạnh dài không vượt maxSide. */
    fun decode(cr: ContentResolver, uri: Uri, maxSide: Int, software: Boolean = false): Bitmap? = try {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(cr, uri)) { dec, info, _ ->
            val w = info.size.width
            val h = info.size.height
            val m = maxOf(w, h)
            if (m > maxSide) {
                val s = maxSide.toFloat() / m
                dec.setTargetSize(maxOf(1, (w * s).toInt()), maxOf(1, (h * s).toInt()))
            }
            if (software) dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    } catch (e: Throwable) { null }

    /** Giải mã sao cho chiều rộng ~ width (dùng cho thumbnail, bitmap phần mềm để chuyển RGB_565). */
    fun decodeToWidth(cr: ContentResolver, uri: Uri, width: Int): Bitmap? = try {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(cr, uri)) { dec, info, _ ->
            val w = info.size.width
            val h = info.size.height
            if (w > width) {
                val s = width.toFloat() / w
                dec.setTargetSize(width, maxOf(1, (h * s).toInt()))
            }
            dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    } catch (e: Throwable) { null }
}
