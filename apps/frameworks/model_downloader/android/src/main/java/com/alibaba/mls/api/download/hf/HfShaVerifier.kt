// Created by ruoyi.sjd on 2025/5/8.
// Copyright (c) 2024 Alibaba Group Holding Limited All rights reserved.
package com.alibaba.mls.api.download.hf

import android.util.Log
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.DigestInputStream
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.Locale
import kotlin.io.path.exists

object HfShaVerifier {
    private const val TAG = "HfShaVerifier"

    /**
     * Creates a digest that can be updated while the file is downloaded, so the SHA is
     * computed without a second full read of the file after the transfer finishes.
     *
     * Returns null when the ETag is missing or does not use a known hash format.
     */
    fun createStreamingDigest(etag: String?, fileSize: Long): MessageDigest? {
        if (etag.isNullOrEmpty()) return null
        val expected = etag.trim('"').lowercase(Locale.ROOT)
        return try {
            when (expected.length) {
                40 -> {
                    if (fileSize <= 0) return null
                    MessageDigest.getInstance("SHA-1").apply {
                        update("blob $fileSize\u0000".toByteArray(StandardCharsets.UTF_8))
                    }
                }
                64 -> MessageDigest.getInstance("SHA-256")
                else -> null
            }
        } catch (e: NoSuchAlgorithmException) {
            null
        }
    }

    /** Compares a digest built with [createStreamingDigest] against the ETag. */
    fun verifyStreamingDigest(etag: String, digest: MessageDigest): Boolean {
        val expected = etag.trim('"').lowercase(Locale.ROOT)
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        return expected == actual
    }

    fun verify(etag: String, file: Path): Boolean {
        Log.d(TAG, "Verifying $file exists: ${file.exists()}")
        val expected = etag.trim('"').lowercase(Locale.ROOT)
        val actual = when (expected.length) {
            40 -> gitSha1Hex(file)
            64 -> digestHex(file, "SHA-256")
            else -> throw IllegalArgumentException("Unexpected ETag length: ${expected.length}")
        }
        Log.d(TAG, "Verifying $file: expected=$expected actual=$actual")
        return expected == actual
    }

    @Throws(IOException::class, NoSuchAlgorithmException::class)
    private fun gitSha1Hex(file: Path): String {
        val md = MessageDigest.getInstance("SHA-1")
        val size = Files.size(file)
        md.update("blob $size\u0000".toByteArray(StandardCharsets.UTF_8))
        Files.newInputStream(file).use { input ->
            val buf = ByteArray(64 * 1024)
            var n: Int
            while (input.read(buf).also { n = it } > 0) {
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    @Throws(IOException::class, NoSuchAlgorithmException::class)
    private fun digestHex(file: Path, algo: String): String {
        val md = MessageDigest.getInstance(algo)
        Files.newInputStream(file).use { `in` ->
            DigestInputStream(`in`, md).use { dis ->
                val buf = ByteArray(64 * 1024)
                while (dis.read(buf) != -1) { }
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}