package com.vayunmathur.appstore.data.installer

import android.content.Context
import android.util.Log
import com.aurora.gplayapi.data.models.PlayFile
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads Play Store split APKs with resume support.
 *
 * There is deliberately no publisher-key check here and there cannot be one: Play App
 * Signing means Google holds the key, so nothing Play returns can be pinned to a
 * publisher. What integrity checking is possible happens in
 * [com.vayunmathur.appstore.data.security.InstallVerifier] just before install — package
 * identity, a single consistent signer across all splits, and continuity with the copy
 * already on the device.
 */
class PlayDownloader(
    private val context: Context
) {
    companion object {
        private const val TAG = "PlayDownloader"
    }

    class ExpiredUrlException(message: String) : Exception(message)

    /**
     * Download list of PlayFiles to cache dir, verify, and return local Files.
     */
    suspend fun downloadFiles(
        packageName: String,
        versionCode: Long,
        gplayFiles: List<PlayFile>,
        progressCallback: (Float) -> Unit = {}
    ): Result<List<File>> {
        return try {
            val baseDir = File(context.cacheDir, "PlayDownloads/$packageName/$versionCode").apply { mkdirs() }
            val totalSize = gplayFiles.sumOf { it.size }.takeIf { it > 0 } ?: -1L
            var totalDownloaded = 0L
            val localFiles = mutableListOf<File>()

            for ((index, gFile) in gplayFiles.withIndex()) {
                val fileName = gFile.name.takeIf { it.isNotBlank() } ?: "file_${index}.apk"
                val destFile = File(baseDir, fileName)
                val tmpFile = File(baseDir, "$fileName.tmp")

                if (destFile.exists() && destFile.length() > 0) {
                    if (gFile.size <= 0 || destFile.length() == gFile.size) {
                        localFiles.add(destFile)
                        totalDownloaded += destFile.length()
                        continue
                    }
                }

                val result = downloadSingleFile(gFile, destFile, tmpFile) { bytesDownloaded ->
                    val overall = if (totalSize > 0) {
                        (totalDownloaded + bytesDownloaded).toFloat() / totalSize
                    } else {
                        (index.toFloat() + (if (gFile.size > 0) bytesDownloaded.toFloat() / gFile.size else 0f)) / gplayFiles.size
                    }
                    progressCallback(overall.coerceIn(0f, 1f))
                }

                if (result.isFailure) {
                    return Result.failure(result.exceptionOrNull() ?: Exception("Download failed for $fileName"))
                }

                val file = result.getOrNull()!!
                localFiles.add(file)
                totalDownloaded += file.length()
            }

            Result.success(localFiles)
        } catch (e: Exception) {
            Log.e(TAG, "downloadFiles failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    private fun downloadSingleFile(
        gFile: PlayFile,
        destFile: File,
        tmpFile: File,
        progressCallback: (Long) -> Unit
    ): Result<File> {
        return try {
            val urlString = gFile.url.takeIf { it.isNotBlank() } ?: return Result.failure(Exception("Empty URL for ${gFile.name}"))
            val existing = if (tmpFile.exists()) tmpFile.length() else 0L

            val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                useCaches = false
                if (existing > 0) {
                    setRequestProperty("Range", "bytes=$existing-")
                }
            }

            val code = try { conn.responseCode } catch (e: Exception) {
                conn.disconnect()
                throw e
            }

            if (code !in 200..299 && code != 206) {
                conn.disconnect()
                if (code == 403 || code == 410) {
                    return Result.failure(ExpiredUrlException("URL expired $code"))
                }
                return Result.failure(Exception("HTTP $code"))
            }

            val input = try {
                conn.inputStream
            } catch (_: Exception) {
                conn.disconnect()
                return Result.failure(Exception("Empty body"))
            }

            val output = if (existing > 0) {
                java.io.FileOutputStream(tmpFile, true)
            } else {
                java.io.FileOutputStream(tmpFile, false)
            }

            var downloaded = existing
            try {
                output.use { out ->
                    input.use { inp ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        while (inp.read(buffer).also { read = it } != -1) {
                            out.write(buffer, 0, read)
                            downloaded += read
                            progressCallback(downloaded)
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }

            if (tmpFile.exists()) {
                if (destFile.exists()) destFile.delete()
                tmpFile.renameTo(destFile)
            }

            Result.success(destFile)
        } catch (e: Exception) {
            if (e is ExpiredUrlException) Result.failure(e)
            else Result.failure(e)
        }
    }

}
