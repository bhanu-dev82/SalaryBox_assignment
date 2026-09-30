package com.bhanu.attendance.data.storage

import android.graphics.Bitmap
import com.bhanu.attendance.core.common.dispatchers.AppDispatchers
import com.bhanu.attendance.core.common.logging.AppLogger
import com.bhanu.attendance.domain.repository.SelfieStorage
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stores attendance selfies as JPEGs under app-internal storage.
 *
 * `filesDir` is app-private and excluded from the media scanner, so a selfie can never be
 * picked up by a gallery app or a file browser.
 *
 * Layout is `filesDir/selfies/<yyyy-MM-dd>/<recordId>.jpg` — sharded by date so that clearing
 * a single day is possible without walking every file, and so no single directory accumulates
 * tens of thousands of entries.
 */
@Singleton
class FileSelfieStorage @Inject constructor(
    private val dispatchers: AppDispatchers,
    private val logger: AppLogger,
    baseDirectory: File,
) : SelfieStorage {

    private val root: File = File(baseDirectory, DIRECTORY)

    override suspend fun save(jpegBytes: ByteArray, recordId: String, localDate: LocalDate): String =
        withContext(dispatchers.io) {
            val directory = File(root, localDate.toString())
            if (!directory.exists() && !directory.mkdirs() && !directory.exists()) {
                throw java.io.IOException("Could not create ${directory.absolutePath}")
            }
            val file = File(directory, "$recordId.jpg")
            // Written to a temp file and renamed, so a process death mid-write cannot leave a
            // truncated JPEG that later fails to decode.
            val temp = File(directory, "$recordId.jpg.tmp")
            temp.writeBytes(jpegBytes)
            if (!temp.renameTo(file)) {
                // Rename can fail on some filesystems; fall back to a direct copy.
                file.writeBytes(jpegBytes)
                temp.delete()
            }
            logger.d(TAG, "Saved selfie ${file.name} (${jpegBytes.size} bytes)")
            file.absolutePath
        }

    override suspend fun load(path: String): ByteArray? = withContext(dispatchers.io) {
        runCatching {
            val file = File(path)
            // Guard against a path that escaped the app directory. Nothing should ever produce
            // one, but this is a read of arbitrary input and the check is nearly free.
            if (!file.canonicalPath.startsWith(root.canonicalPath)) {
                logger.w(TAG, "Refusing to read outside the selfie directory: $path")
                null
            } else if (file.exists()) {
                file.readBytes()
            } else {
                logger.w(TAG, "Selfie missing on disk: $path")
                null
            }
        }.getOrNull()
    }

    /** Never throws: a missing file is a successful no-op, by contract. */
    override suspend fun delete(path: String) {
        withContext(dispatchers.io) {
            runCatching { File(path).takeIf { it.exists() }?.delete() }
                .onFailure { logger.w(TAG, "Failed to delete selfie", it) }
            Unit
        }
    }

    override suspend fun deleteAllUnder(directoryName: String) {
        withContext(dispatchers.io) {
            runCatching {
                File(root, directoryName).takeIf { it.isDirectory }?.deleteRecursively()
            }.onFailure { logger.w(TAG, "Failed to delete $directoryName", it) }
            Unit
        }
    }

    companion object {
        const val DIRECTORY = "selfies"
        private const val TAG = "SelfieStorage"
    }
}

/** Encodes a bitmap to the JPEG format the app stores, at a size appropriate for a selfie. */
class SelfieEncoder @Inject constructor() {

    /**
     * Downscales to at most [maxDimension] on the longest edge and encodes at [quality].
     *
     * Full camera resolution is unnecessary for a stored attendance photo and would make the
     * data directory grow quickly; 1280px is ample to identify a person and keeps a typical
     * capture in the low tens of kilobytes.
     */
    fun encode(bitmap: Bitmap, maxDimension: Int = 1280, quality: Int = 85): ByteArray {
        val scale = minOf(1f, maxDimension.toFloat() / maxOf(bitmap.width, bitmap.height))
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            bitmap
        }
        return java.io.ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
            out.toByteArray()
        }
    }
}
