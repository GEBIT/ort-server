/*
 * Copyright (C) 2026 The ORT Server Authors (See <https://github.com/eclipse-apoapsis/ort-server/blob/main/NOTICE>)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 * License-Filename: LICENSE
 */
package org.eclipse.apoapsis.ortserver.shared.reporters

import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.DigestInputStream
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.time.Duration
import java.util.Base64
import java.util.HexFormat

import org.ossreviewtoolkit.model.RemoteArtifact

/**
 * Downloads artifacts via HTTP(S) as a stream, enforcing a size limit and verifying the checksum from the
 * [RemoteArtifact] (if it has a verifiable algorithm). The optional [username] and [password] are sent as basic
 * authentication. All failures are reported as [ArtifactException].
 */
class ArtifactDownloader(
    private val maxBytes: Long,
    private val username: String? = null,
    private val password: String? = null,
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(30))
        .build()
) {
    /**
     * Download [artifact] and pass its content to [process]. After [process] returns, the rest of the stream is
     * drained, so that the size limit and the checksum cover the complete artifact, not only what [process] read.
     */
    fun <T> download(artifact: RemoteArtifact, process: (InputStream) -> T): T {
        val response = send(artifact)

        response.body().use { body ->
            if (response.statusCode() != 200) {
                throw ArtifactException("HTTP status ${response.statusCode()} for ${artifact.url}")
            }

            val digest = createDigest(artifact)
            val input = if (digest != null) {
                LimitedInputStream(DigestInputStream(body, digest), maxBytes)
            } else {
                LimitedInputStream(body, maxBytes)
            }

            val result = try {
                val processed = process(input)
                input.copyTo(OutputStream.nullOutputStream())
                processed
            } catch (e: ArtifactException) {
                throw e
            } catch (e: IOException) {
                throw ArtifactException("Download of ${artifact.url} failed: ${e.message ?: e::class.simpleName}", e)
            }

            if (digest != null) {
                val actual = HexFormat.of().formatHex(digest.digest())
                val expected = artifact.hash.value.lowercase()

                if (actual != expected) {
                    val message = "Artifact checksum mismatch for ${artifact.url}: expected $expected, got $actual."
                    throw ArtifactException(message)
                }
            }

            return result
        }
    }

    private fun send(artifact: RemoteArtifact): HttpResponse<InputStream> {
        try {
            val request = HttpRequest.newBuilder(URI.create(artifact.url))
                .timeout(Duration.ofMinutes(5))
                .GET()
                .also { builder ->
                    if (username != null && password != null) {
                        val credentials = Base64.getEncoder().encodeToString("$username:$password".toByteArray())
                        builder.header("Authorization", "Basic $credentials")
                    }
                }
                .build()

            return httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
        } catch (e: IllegalArgumentException) {
            throw ArtifactException("Invalid artifact URL '${artifact.url}'.", e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw ArtifactException("Interrupted while downloading ${artifact.url}.", e)
        } catch (e: ArtifactException) {
            throw e
        } catch (e: IOException) {
            throw ArtifactException("Download of ${artifact.url} failed: ${e.message ?: e::class.simpleName}", e)
        }
    }

    private fun createDigest(artifact: RemoteArtifact): MessageDigest? {
        val algorithm = artifact.hash.algorithm.takeIf { it.isVerifiable } ?: return null

        return try {
            MessageDigest.getInstance(algorithm.aliases.first())
        } catch (_: NoSuchAlgorithmException) {
            null
        }
    }
}

/** Fails with an [ArtifactException] once more than [maxBytes] have been read. */
private class LimitedInputStream(input: InputStream, private val maxBytes: Long) : FilterInputStream(input) {
    private var count = 0L

    override fun markSupported(): Boolean = false

    override fun read(): Int = super.read().also { if (it >= 0) add(1) }

    override fun read(b: ByteArray, off: Int, len: Int): Int =
        super.read(b, off, len).also { if (it > 0) add(it.toLong()) }

    // Skipping must count as reading, otherwise the limit could be bypassed.
    override fun skip(n: Long): Long {
        if (n <= 0) return 0

        val read = read(ByteArray(minOf(n, 8192L).toInt()))
        return maxOf(read, 0).toLong()
    }

    private fun add(bytes: Long) {
        count += bytes
        if (count > maxBytes) throw ArtifactException("The artifact exceeds the maximum size of $maxBytes bytes.")
    }
}
