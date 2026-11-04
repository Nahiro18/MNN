// Created by ruoyi.sjd on 2024/12/18.
// Copyright (c) 2024 Alibaba Group Holding Limited All rights reserved.
package com.alibaba.mls.api.download

import android.util.Log
import com.alibaba.mls.api.FileDownloadException
import com.alibaba.mls.api.download.DownloadFileUtils.createSymlink
import com.alibaba.mls.api.download.hf.HfShaVerifier
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit

class ModelFileDownloader {
    // Optimized OkHttpClient configuration:
    // - Only connect timeout (no read/write timeouts to avoid premature failures on slow connections)
    // - Disabled automatic redirects (handled manually for proper Range header support)
    // - Disabled retryOnConnectionFailure (retry logic handles failures explicitly)
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    @Throws(FileDownloadException::class, DownloadPausedException::class, IOException::class)
    fun downloadFile(
        fileDownloadTask: FileDownloadTask,
        fileDownloadListener: FileDownloadListener
    ) {
        Log.d(TAG, "downloadFile inner")
        fileDownloadTask.pointerPath!!.parentFile?.mkdirs()
        fileDownloadTask.blobPath!!.parentFile?.mkdirs()

        if (fileDownloadTask.pointerPath!!.exists()) {
            Log.d(TAG, "DownloadFile " + fileDownloadTask.relativePath + " already exists")
            return
        }

        if (fileDownloadTask.blobPath!!.exists()) {
            createSymlink(
                fileDownloadTask.blobPath.toString(),
                fileDownloadTask.pointerPath.toString()
            )
            Log.d(
                TAG,
                "DownloadFile " + fileDownloadTask.relativePath + " already exists just create symlink"
            )
            return
        }
        synchronized(this) {
            val hfFileMetadata = fileDownloadTask.fileMetadata
            downloadToTmpAndMove(
                fileDownloadTask,
                fileDownloadTask.blobPathIncomplete!!,
                fileDownloadTask.blobPath!!,
                hfFileMetadata!!.location!!,
                hfFileMetadata.size,
                fileDownloadTask.relativePath, fileDownloadListener
            )
            createSymlink(
                fileDownloadTask.blobPath!!.toPath(),
                fileDownloadTask.pointerPath!!.toPath()
            )
        }
    }

    @Throws(FileDownloadException::class, DownloadPausedException::class)
    private fun downloadToTmpAndMove(
        fileDownloadTask: FileDownloadTask,
        incompletePath: File,
        destinationPath: File,
        urlToDownload: String,
        expectedSize: Long,
        fileName: String?,
        fileDownloadListener: FileDownloadListener
    ) {
        var theUrlToDownload = urlToDownload
        if (destinationPath.exists()) {
            if (validate(fileDownloadTask, destinationPath)) {
                return
            } else {
                destinationPath.delete()
                fileDownloadTask.downloadedSize = 0
            }
        }
        if (fileDownloadTask.downloadedSize >= expectedSize) {
            if (validate(fileDownloadTask, incompletePath)) {
                moveWithPermissions(incompletePath, destinationPath)
                return
            } else {
                incompletePath.delete()
                fileDownloadTask.downloadedSize = 0
            }
        }
        // The urlToDownload is already the final CDN URL from metadata fetch (HfFileMetadataUtils.getFileMetadata
        // follows redirects). No need for an extra HEAD request which causes an extra TLS handshake.
        // Only follow redirects if the server returns one during the actual GET request.
        Log.d(
            TAG,
            "downloadToTmpAndMove urlToDownload: $theUrlToDownload to file: $incompletePath to destination: $destinationPath"
        )
        val maxRetry = 10
        if (fileDownloadTask.downloadedSize < expectedSize) {
            for (i in 0 until maxRetry) {
                try {
                    Log.d(
                        TAG,
                        "downloadChunk try the $i turn"
                    )
                    downloadChunk(
                        fileDownloadTask,
                        theUrlToDownload,
                        incompletePath,
                        expectedSize,
                        fileName,
                        fileDownloadListener
                    )
                    Log.d(
                        TAG,
                        "downloadChunk try the $i turn finish"
                    )
                    if (!validate(fileDownloadTask, incompletePath)) {
                        incompletePath.delete()
                        fileDownloadTask.downloadedSize = 0
                    }
                    break
                } catch (e: DownloadPausedException) {
                    throw e
                } catch (e: Exception) {
                    if (i == maxRetry - 1) {
                        throw e
                    } else {
                        Log.e(TAG, "downloadChunk failed sleep and retrying: " + e.message)
                        try {
                            Thread.sleep(1000)
                        } catch (ex: InterruptedException) {
                            throw RuntimeException(ex)
                        }
                    }
                }
            }
        }
        if (validate(fileDownloadTask, incompletePath)) {
            moveWithPermissions(incompletePath, destinationPath)
        } else {
            incompletePath.delete()
            fileDownloadTask.downloadedSize = 0
        }
    }

    @Throws(FileDownloadException::class, DownloadPausedException::class)
    private fun downloadChunk(
        fileDownloadTask: FileDownloadTask,
        url: String,
        tempFile: File,
        expectedSize: Long,
        displayedFilename: String?,
        fileDownloadListener: FileDownloadListener?
    ) {
        if (fileDownloadTask.downloadedSize >= expectedSize) {
            return
        }
        val requestBuilder = Request.Builder()
            .url(url)
            .get()
            .header("Accept-Encoding", "identity")
        if (fileDownloadTask.downloadedSize > 0) {
            requestBuilder.header("Range", "bytes=" + fileDownloadTask.downloadedSize + "-")
        }
        Log.d(
            TAG,
            "resume size: " + fileDownloadTask.downloadedSize + " expectedSize: " + expectedSize
        )
        val request: Request = requestBuilder.build()
        try {
            client.newCall(request).execute().use { response ->
                Log.d(
                    TAG,
                    "downloadChunk response: success: " + response.isSuccessful + " code: " + response.code
                )
                if (response.isSuccessful || response.code == 416) {
                    response.body!!
                        .byteStream().use { `is` ->
                            RandomAccessFile(tempFile, "rw").use { raf ->
                                raf.seek(fileDownloadTask.downloadedSize)
                                val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
                                var bytesRead: Int
                                while ((`is`.read(buffer).also { bytesRead = it }) != -1) {
                                    raf.write(buffer, 0, bytesRead)
                                    fileDownloadTask.downloadedSize += bytesRead.toLong()
                                    if (fileDownloadListener != null) {
                                        val paused = fileDownloadListener.onDownloadDelta(
                                            displayedFilename,
                                            fileDownloadTask.downloadedSize,
                                            expectedSize,
                                            bytesRead.toLong()
                                        )
                                        if (paused) {
                                            throw DownloadPausedException("Download paused")
                                        }
                                    }
                                }
                            }
                        }
                } else {
                    Log.e(TAG, "downloadChunk error HfApiException " + response.code)
                    throw FileDownloadException("HTTP error: ${response.code}")
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "downloadChunk error IOException", e)
            throw FileDownloadException("Connection error: " + e.message)
        }
    }


    private fun validate(fileDownloadTask: FileDownloadTask, src: File): Boolean {
        var verifyResult = true
        if (!fileDownloadTask.etag.isNullOrEmpty()) {
            verifyResult = HfShaVerifier.verify(fileDownloadTask.etag!!, src.toPath())
            Log.d(TAG, "verifyResult: $verifyResult")
        }
        return verifyResult
    }

    private fun moveWithPermissions(src: File, dest: File) {
        Log.d(DownloadFileUtils.TAG, "moveWithPermissions ${src.absolutePath} to ${dest.absolutePath}")
        Files.move(src.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
        dest.setReadable(true, true)
        dest.setWritable(true, true)
        dest.setExecutable(false, false)
    }

    interface FileDownloadListener {
        fun onDownloadDelta(
            fileName: String?,
            downloadedBytes: Long,
            totalBytes: Long,
            delta: Long
        ): Boolean
    }

    companion object {
        const val TAG: String = "ModelFileDownloader"
        // 8KB buffer - optimal for Android to balance memory usage and syscall overhead
        // Larger buffers (256KB) cause GC pressure and memory allocation delays
        private const val DOWNLOAD_BUFFER_SIZE = 8192
    }
}
