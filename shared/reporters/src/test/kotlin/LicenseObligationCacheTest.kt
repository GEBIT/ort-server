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

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf

import java.io.File
import java.util.concurrent.CountDownLatch

import org.ossreviewtoolkit.model.Hash
import org.ossreviewtoolkit.model.HashAlgorithm
import org.ossreviewtoolkit.model.RemoteArtifact

class LicenseObligationCacheTest : StringSpec({
    val tempDirs = mutableListOf<File>()
    val cacheRoot = File("build/license-obligation-cache-test").apply {
        deleteRecursively()
        mkdirs()
    }

    fun tempDir() = cacheRoot.resolve("cache-${tempDirs.size}").apply { mkdirs() }.also { tempDirs += it }

    afterSpec { cacheRoot.deleteRecursively() }

    val facts = ArtifactLegalFacts(
        entryCount = 7,
        licenseFiles = listOf("META-INF/LICENSE"),
        noticeFiles = listOf("META-INF/NOTICE.txt")
    )

    "facts survive a round trip through the file cache" {
        val cache = createCache(tempDir().absolutePath)

        cache.put("key", facts)

        cache.get("key") shouldBe facts
    }

    "a missing key is a cache miss" {
        createCache(tempDir().absolutePath).get("unknown").shouldBeNull()
    }

    "a corrupt cache file is a cache miss" {
        val dir = tempDir()
        File(dir, "key.json").writeText("{ this is not json")

        createCache(dir.absolutePath).get("key").shouldBeNull()
    }

    "an entry written by another extractor version is a cache miss" {
        val cache = createCache(tempDir().absolutePath)

        cache.put("key", facts.copy(extractorVersion = EXTRACTOR_VERSION + 1))

        cache.get("key").shouldBeNull()
    }

    "the cache directory is created on demand, including parents" {
        val dir = File(tempDir(), "a/b/advisor")

        createCache(dir.absolutePath).put("key", facts)

        dir.isDirectory shouldBe true
    }

    "a blank cache directory disables caching" {
        val cache = createCache("")

        cache.put("key", facts)

        cache.get("key").shouldBeNull()
        cache.shouldBeInstanceOf<NoOpLicenseObligationCache>()
    }

    "an unusable cache directory disables caching instead of failing" {
        val file = File(tempDir(), "regular-file").apply { writeText("x") }

        createCache(File(file, "advisor").absolutePath).shouldBeInstanceOf<NoOpLicenseObligationCache>()
    }

    "the key depends on URL, hash and extractor version" {
        val artifact = RemoteArtifact("https://example.org/a.jar", Hash("a".repeat(40), HashAlgorithm.SHA1))

        val key = cacheKey(artifact)

        key shouldBe cacheKey(artifact)
        key shouldNotBe cacheKey(artifact.copy(url = "https://example.org/b.jar"))
        key shouldNotBe cacheKey(artifact.copy(hash = Hash("b".repeat(40), HashAlgorithm.SHA1)))
        key shouldNotBe cacheKey(artifact, extractorVersion = EXTRACTOR_VERSION + 1)
        key.matches(Regex("[0-9a-f]{40}")) shouldBe true
    }

    "temporary files are not treated as cache entries" {
        val dir = tempDir()
        File(dir, "tmp-key.part").writeText("""{"entryCount":7}""")

        createCache(dir.absolutePath).get("key").shouldBeNull()
    }

    "failed writes remove their temporary files" {
        val dir = tempDir()
        File(dir, "key.json").mkdir()

        createCache(dir.absolutePath).put("key", facts)

        dir.listFiles()?.map { it.name } shouldBe listOf("key.json")
    }

    "concurrent writers never expose partial facts" {
        val cache = createCache(tempDir().absolutePath)
        val ready = CountDownLatch(8)
        val threads = (1..8).map { i ->
            Thread {
                ready.countDown()
                repeat(50) { cache.put("key", facts.copy(entryCount = i)) }
            }.also { it.start() }
        }
        ready.await()

        while (threads.any { it.isAlive }) {
            cache.get("key")?.entryCount?.let { count -> (count in 1..8) shouldBe true }
        }
        threads.forEach { it.join() }

        cache.get("key") shouldNotBe null
    }
})
