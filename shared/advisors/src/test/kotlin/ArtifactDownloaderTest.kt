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
package org.eclipse.apoapsis.ortserver.shared.advisors

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

import java.io.IOException
import java.util.Base64

import org.ossreviewtoolkit.model.Hash
import org.ossreviewtoolkit.model.HashAlgorithm
import org.ossreviewtoolkit.model.RemoteArtifact

class ArtifactDownloaderTest : StringSpec({
    val server = TestArtifactServer()
    val jar = buildJar("META-INF/LICENSE" to "license", "com/example/Foo.class" to "x".repeat(10_000))
    val url = server.serve("/lib-1.0.jar", jar)
    val artifact = RemoteArtifact(url, Hash(sha1(jar), HashAlgorithm.SHA1))

    afterSpec { server.close() }

    afterTest { server.requiredAuthorization = null }

    fun downloader(maxBytes: Long = 1_000_000, username: String? = null, password: String? = null) =
        ArtifactDownloader(maxBytes, username, password)

    "the processing function receives the artifact content" {
        val size = downloader().download(artifact) { it.readAllBytes().size }

        size shouldBe jar.size
    }

    "the whole artifact is verified even if the processing function reads only part of it" {
        val wrongHash = artifact.copy(hash = Hash("0".repeat(40), HashAlgorithm.SHA1))

        val exception = shouldThrow<ArtifactException> {
            downloader().download(wrongHash) { it.read() }
        }

        exception.message shouldContain "checksum"
    }

    "skipping bytes still verifies the whole artifact" {
        downloader().download(artifact) {
            it.skip(100) shouldBe 100L
            it.readAllBytes().size
        } shouldBe jar.size - 100
    }

    "a hash with an unknown algorithm is not verified" {
        val unverified = artifact.copy(hash = Hash.NONE)

        downloader().download(unverified) { it.readAllBytes().size } shouldBe jar.size
    }

    "a failing HTTP status is reported" {
        val missing = RemoteArtifact(server.url("/missing.jar"), Hash.NONE)

        shouldThrow<ArtifactException> { downloader().download(missing) { } }.message shouldContain "404"
    }

    "an artifact larger than the limit is rejected" {
        shouldThrow<ArtifactException> {
            downloader(maxBytes = 100).download(artifact) { it.readAllBytes() }
        }.message shouldContain "maximum size"
    }

    "the size limit is enforced when the processing function reads only part of the artifact" {
        shouldThrow<ArtifactException> {
            downloader(maxBytes = 100).download(artifact) { it.read() }
        }.message shouldContain "maximum size"
    }

    "IOExceptions while processing are reported as ArtifactException" {
        val exception = shouldThrow<ArtifactException> {
            downloader().download(artifact) { throw IOException("processing failed") }
        }

        exception.message shouldContain "Download of ${artifact.url} failed: processing failed"
        exception.cause?.message shouldBe "processing failed"
    }

    "an invalid URL is reported as ArtifactException" {
        shouldThrow<ArtifactException> {
            downloader().download(RemoteArtifact("not a url", Hash.NONE)) { }
        }
    }

    "an unreachable server is reported as ArtifactException" {
        shouldThrow<ArtifactException> {
            downloader().download(RemoteArtifact("http://127.0.0.1:1/x.jar", Hash.NONE)) { }
        }
    }

    "credentials are sent as basic authentication" {
        server.requiredAuthorization = "Basic " + Base64.getEncoder().encodeToString("user:secret".toByteArray())

        downloader(username = "user", password = "secret").download(artifact) { it.readAllBytes().size } shouldBe
                jar.size

        shouldThrow<ArtifactException> { downloader().download(artifact) { } }.message shouldContain "401"
    }
})
