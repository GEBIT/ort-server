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

import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.HexFormat

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

import org.ossreviewtoolkit.model.RemoteArtifact

import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger(LicenseObligationCache::class.java)

private val json = Json { ignoreUnknownKeys = true }

/** A store for [ArtifactLegalFacts], keyed by [cacheKey]. Implementations must be safe for concurrent use. */
interface LicenseObligationCache {
    /** Return the facts stored for [key], or `null` on a miss. Never throws. */
    fun get(key: String): ArtifactLegalFacts?

    /** Store [facts] for [key]. Failures are logged, not thrown, since the cache is only an optimization. */
    fun put(key: String, facts: ArtifactLegalFacts)
}

/** A cache that stores nothing. Used if caching is disabled or the cache directory is unusable. */
object NoOpLicenseObligationCache : LicenseObligationCache {
    override fun get(key: String): ArtifactLegalFacts? = null

    override fun put(key: String, facts: ArtifactLegalFacts) = Unit
}

/** A cache that stores one JSON file per key in [directory]. Writes are atomic (temporary file and rename). */
class FileLicenseObligationCache(private val directory: File) : LicenseObligationCache {
    override fun get(key: String): ArtifactLegalFacts? {
        val file = File(directory, "$key.json")

        return try {
            json.decodeFromString<ArtifactLegalFacts>(file.readText())
                .takeIf { it.extractorVersion == EXTRACTOR_VERSION }
        } catch (_: IOException) {
            null
        } catch (e: SerializationException) {
            logger.warn("Ignoring corrupt cache file '{}': {}", file, e.message)
            null
        } catch (e: IllegalArgumentException) {
            logger.warn("Ignoring invalid cache file '{}': {}", file, e.message)
            null
        }
    }

    override fun put(key: String, facts: ArtifactLegalFacts) {
        var tempFile: Path? = null

        try {
            tempFile = Files.createTempFile(directory.toPath(), "tmp-", ".part")
            tempFile.toFile().writeText(json.encodeToString(facts))
            Files.move(tempFile, File(directory, "$key.json").toPath(), StandardCopyOption.ATOMIC_MOVE)
            tempFile = null
        } catch (e: IOException) {
            logger.warn("Could not write cache entry '{}': {}", key, e.message)
        } finally {
            tempFile?.let {
                try {
                    Files.deleteIfExists(it)
                } catch (e: IOException) {
                    logger.warn("Could not remove temporary cache file '{}': {}", it, e.message)
                }
            }
        }
    }
}

/**
 * Create the cache for [cacheDir]. A blank value disables caching. If the directory cannot be created or written, a
 * warning is logged and caching is disabled, so that the advisor still works outside of its container.
 */
fun createCache(cacheDir: String): LicenseObligationCache {
    if (cacheDir.isBlank()) return NoOpLicenseObligationCache

    val directory = File(cacheDir)

    return try {
        Files.createDirectories(directory.toPath())
        if (!Files.isWritable(directory.toPath())) throw IOException("The directory is not writable.")
        FileLicenseObligationCache(directory)
    } catch (e: IOException) {
        logger.warn("The cache directory '{}' is unusable ({}). Caching is disabled.", cacheDir, e.message)
        NoOpLicenseObligationCache
    }
}

/**
 * Return the cache key for [artifact]: the SHA-1 of its URL, its hash value and the [extractorVersion]. The hash makes
 * an entry immutable per artifact content; the version invalidates entries when the extraction logic changes.
 */
fun cacheKey(artifact: RemoteArtifact, extractorVersion: Int = EXTRACTOR_VERSION): String {
    val input = "${artifact.url}|${artifact.hash.value}|$extractorVersion"
    val digest = MessageDigest.getInstance("SHA-1").digest(input.toByteArray(StandardCharsets.UTF_8))
    return HexFormat.of().formatHex(digest)
}
