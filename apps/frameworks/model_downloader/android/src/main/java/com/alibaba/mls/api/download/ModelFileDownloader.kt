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
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(false)
        .followSslRedirects(false) //                .addInterceptor(OkHttpUtils.createLoggingInterceptor())
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
        val requestBuilder = Request.Builder()
            .url(theUrlToDownload)
            .get()
        val request: Request = requestBuilder.build()
        try {
            client.newCall(request).execute().use { response ->
                Log.d(TAG, "response code: " + response.code)
                for (header in response.headers.names()) {
                    Log.d(
                        TAG,
                        "downloadToTmpAndMove response header: $header: " + response.header(
                            header
                        )
                    )
                }
                // Handle all redirect status codes (301, 302, 303, 307, 308)
                if (response.code in 301..308) {
                    theUrlToDownload = response.header("Location")!!
                }
            }
        } catch (e: IOException) {
            throw FileDownloadException("get header error" + e.message)
        }
        Log.d(
            TAG,
            "downloadToTmpAndMove urlToDownload: $theUrlToDownload to file: $incompletePath to destination: $destinationPath"
        )
        val maxRetry = 10
        var verifiedWhileStreaming = false
        if (fileDownloadTask.downloadedSize < expectedSize) {
            for (i in 0 until maxRetry) {
                try {
                    Log.d(
                        TAG,
                        "downloadChunk try the $i turn"
                    )
                    verifiedWhileStreaming = downloadChunk(
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
                    break
                } catch (e: DownloadPausedException) {
                    throw e
                } catch (e: HashMismatchException) {
                    // Retrying would download the whole shard again; fail fast instead.
                    throw FileDownloadException(e.message)
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
        // When the digest was computed during the transfer there is nothing left to read.
        if (verifiedWhileStreaming || validate(fileDownloadTask, incompletePath)) {
            moveWithPermissions(incompletePath, destinationPath)
        } else {
            incompletePath.delete()
            fileDownloadTask.downloadedSize = 0
        }
    }

    /**
     * @return true when the file was verified while streaming, so the caller does not need
     * to read and hash the whole file again after the transfer.
     */
    @Throws(FileDownloadException::class, DownloadPausedException::class)
    private fun downloadChunk(
        fileDownloadTask: FileDownloadTask,
        url: String,
        tempFile: File,
        expectedSize: Long,
        displayedFilename: String?,
        fileDownloadListener: FileDownloadListener?
    ): Boolean {
        val resumeOffset = fileDownloadTask.downloadedSize
        if (resumeOffset >= expectedSize) {
            return false
        }
        val requestBuilder = Request.Builder()
            .url(url)
            .get()
            .header("Accept-Encoding", "identity")
            .header("User-Agent", "MNN-LLM-Android")
        if (resumeOffset > 0) {
            requestBuilder.header("Range", "bytes=$resumeOffset-")
        }
        Log.d(TAG, "resume size: $resumeOffset expectedSize: $expectedSize")
        val request: Request = requestBuilder.build()
        try {
            client.newCall(request).execute().use { response ->
                Log.d(
                    TAG,
                    "downloadChunk response: success: ${response.isSuccessful} code: ${response.code}"
                )
                if (response.code == 416) {
                    // Already complete (or the server disagrees about the range). Let the
                    // caller validate the file instead of writing an error page into it.
                    if (expectedSize > 0 && resumeOffset >= expectedSize) {
                        return false
                    }
                    throw FileDownloadException("HTTP 416 while resuming at $resumeOffset")
                }
                if (!response.isSuccessful) {
                    Log.e(TAG, "downloadChunk error HfApiException " + response.code)
                    throw FileDownloadException("HTTP error: ${response.code}")
                }

                // A server that ignores the Range header answers with 200 and the whole file.
                // Writing that stream at resumeOffset would corrupt the model, so restart.
                var attemptedFrom = resumeOffset
                if (resumeOffset > 0 && response.code != 206) {
                    Log.w(TAG, "Server ignored Range for $displayedFilename, restarting file")
                    fileDownloadListener?.onDownloadDelta(
                        displayedFilename,
                        0,
                        expectedSize,
                        -resumeOffset
                    )
                    attemptedFrom = 0
                }

                // Hash the bytes while writing them. The old code hashed completed files from
                // disk (twice), which paused the transfer for several seconds per multi-GB
                // shard and looked like a sudden download speed collapse.
                val streamingDigest = if (attemptedFrom == 0L) {
                    HfShaVerifier.createStreamingDigest(fileDownloadTask.etag, expectedSize)
                } else {
                    null
                }

                response.body?.byteStream()?.use { input ->
                    RandomAccessFile(tempFile, "rw").use { raf ->
                        var writeOffset = attemptedFrom
                        if (writeOffset == 0L) {
                            raf.setLength(0)
                        }
                        raf.seek(writeOffset)
                        val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
                        var bytesRead: Int
                        while ((input.read(buffer).also { bytesRead = it }) != -1) {
                            raf.write(buffer, 0, bytesRead)
                            streamingDigest?.update(buffer, 0, bytesRead)
                            writeOffset += bytesRead
                            fileDownloadTask.downloadedSize = writeOffset
                            if (fileDownloadListener != null) {
                                val delta = bytesRead.toLong()
                                val paused = fileDownloadListener.onDownloadDelta(
                                    displayedFilename,
                                    writeOffset,
                                    expectedSize,
                                    delta
                                )
                                if (paused) {
                                    throw DownloadPausedException("Download paused")
                                }
                            }
                        }
                        attemptedFrom = writeOffset
                    }
                }

                if (streamingDigest != null) {
                    if (!HfShaVerifier.verifyStreamingDigest(
                            fileDownloadTask.etag!!,
                            streamingDigest
                        )
                    ) {
                        Log.e(TAG, "Hash mismatch for $displayedFilename, restarting file")
                        tempFile.delete()
                        fileDownloadTask.downloadedSize = 0
                        fileDownloadListener?.onDownloadDelta(
                            displayedFilename,
                            0,
                            expectedSize,
                            -attemptedFrom
                        )
                        throw HashMismatchException("Hash mismatch for $displayedFilename")
                    }
                    return true
                }
                return false
            }
        } catch (e: IOException) {
            Log.e(TAG, "downloadChunk error IOException", e)
            throw FileDownloadException("Connection error: " + e.message)
        }
    }


    private fun validate(fileDownloadTask: FileDownloadTask, src: File):Boolean {
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

    private class HashMismatchException(message: String?) : Exception(message)

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
        private const val DOWNLOAD_BUFFER_SIZE = 256 * 1024
    }
}
