package com.morneven.kron.security

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ImageCompressor @Inject constructor() {

    fun compress(input: File, output: File, maxDimension: Int = MAX_DIMENSION, quality: Int = QUALITY): CompressResult {
        val rotation = exifRotation(input)
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(input.absolutePath, options)
        val (width, height) = if (rotation == 90 || rotation == 270) {
            options.outHeight to options.outWidth
        } else {
            options.outWidth to options.outHeight
        }
        val sampleSize = computeSampleSize(width, height, maxDimension)
        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        var bitmap = BitmapFactory.decodeFile(input.absolutePath, decodeOptions)
            ?: return CompressResult(error = "Gagal membaca gambar")
        if (rotation != 0) {
            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
            bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }
        val (finalW, finalH) = fitDimensions(bitmap.width, bitmap.height, maxDimension)
        if (finalW != bitmap.width || finalH != bitmap.height) {
            bitmap = Bitmap.createScaledBitmap(bitmap, finalW, finalH, true)
        }
        output.parentFile?.mkdirs()
        FileOutputStream(output).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        }
        bitmap.recycle()
        return CompressResult(
            width = finalW,
            height = finalH,
            byteSize = output.length(),
        )
    }

    fun extractMetadata(input: File): Metadata {
        val exif = try { ExifInterface(input.absolutePath) } catch (_: Exception) { return Metadata() }
        val dt = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
        val lat = exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE)
        val latRef = exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE_REF)
        val lon = exif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE)
        val lonRef = exif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF)
        val gpsLat = if (lat != null && latRef != null) {
            try { exifLatLonToDouble(lat, latRef) } catch (_: Exception) { null }
        } else null
        val gpsLon = if (lon != null && lonRef != null) {
            try { exifLatLonToDouble(lon, lonRef) } catch (_: Exception) { null }
        } else null
        val capturedAt = try {
            dateTimeOriginalToEpochMillis(dt)
        } catch (_: Exception) { null }
        return Metadata(capturedAt = capturedAt, latitude = gpsLat, longitude = gpsLon)
    }

    data class CompressResult(
        val width: Int = 0,
        val height: Int = 0,
        val byteSize: Long = 0,
        val error: String? = null,
    )

    data class Metadata(
        val capturedAt: Long? = null,
        val latitude: Double? = null,
        val longitude: Double? = null,
    )

    companion object {
        const val MAX_DIMENSION = 1920
        private const val QUALITY = 80

        private fun exifRotation(file: File): Int {
            val exif = try { ExifInterface(file.absolutePath) } catch (_: Exception) { return 0 }
            return when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        }

        private fun computeSampleSize(width: Int, height: Int, maxDimension: Int): Int {
            val longest = maxOf(width, height)
            if (longest <= maxDimension) return 1
            var sample = 1
            while (longest / (sample * 2) >= maxDimension) sample *= 2
            return sample
        }

        private fun fitDimensions(width: Int, height: Int, maxDimension: Int): Pair<Int, Int> {
            if (width <= maxDimension && height <= maxDimension) return width to height
            val ratio = minOf(maxDimension.toFloat() / width, maxDimension.toFloat() / height)
            return (width * ratio).toInt() to (height * ratio).toInt()
        }

        private fun exifLatLonToDouble(dms: String, ref: String): Double {
            val parts = dms.split(",").map {
                it.trim().split("/").let { p ->
                    p[0].toDouble() / p.getOrElse(1) { "1" }.toDouble()
                }
            }
            val result = parts[0] + parts.getOrElse(1) { 0.0 } / 60.0 + parts.getOrElse(2) { 0.0 } / 3600.0
            return if (ref in listOf("S", "W")) -result else result
        }

        private fun dateTimeOriginalToEpochMillis(dt: String?): Long? {
            if (dt == null) return null
            val parts = dt.split(" ", ":")
            if (parts.size < 6) return null
            val cal = java.util.Calendar.getInstance()
            cal.set(parts[0].toInt(), parts[1].toInt() - 1, parts[2].toInt(),
                parts[3].toInt(), parts[4].toInt(), parts[5].toInt())
            return cal.timeInMillis
        }
    }
}
