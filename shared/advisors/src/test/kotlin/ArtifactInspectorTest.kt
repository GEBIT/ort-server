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
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class ArtifactInspectorTest : StringSpec({
    fun inspector(maxEntries: Int = 100) = ArtifactInspector(
        licensePattern = LegalFilePattern(listOf("LICENSE", "LICENCE", "COPYING")),
        noticePattern = LegalFilePattern(listOf("NOTICE", "NOTICES")),
        maxEntries = maxEntries
    )

    "license and notice files at the root and in META-INF are found" {
        val jar = buildJar(
            "com/example/Foo.class" to "x",
            "META-INF/LICENSE" to "license",
            "NOTICE.txt" to "notice",
            "META-INF/MANIFEST.MF" to "m"
        )

        val facts = inspector().inspect(jar.inputStream())

        facts.licenseFiles shouldContainExactlyInAnyOrder listOf("META-INF/LICENSE")
        facts.noticeFiles shouldContainExactlyInAnyOrder listOf("NOTICE.txt")
        facts.entryCount shouldBe 4
        facts.extractorVersion shouldBe EXTRACTOR_VERSION
    }

    "an archive without legal files yields empty lists" {
        val facts = inspector().inspect(buildJar("com/example/Foo.class" to "x").inputStream())

        facts.licenseFiles.shouldBeEmpty()
        facts.noticeFiles.shouldBeEmpty()
    }

    "nested legal files and directories are ignored" {
        val jar = buildJar(
            "docs/LICENSE" to "x",
            "LICENSE/" to "",
            "META-INF/" to ""
        )

        val facts = inspector().inspect(jar.inputStream())

        facts.licenseFiles.shouldBeEmpty()
    }

    "an archive with too many entries is rejected" {
        val jar = buildJar("a" to "1", "b" to "2", "c" to "3")

        shouldThrow<ArtifactException> { inspector(maxEntries = 2).inspect(jar.inputStream()) }
            .message shouldContain "more than 2 entries"
    }

    "data that is not an archive is rejected" {
        shouldThrow<ArtifactException> {
            inspector().inspect("<html>404</html>".toByteArray().inputStream())
        }.message shouldContain "no entries"
    }

    "a truncated archive is rejected" {
        val jar = buildJar("LICENSE" to "x".repeat(2000))
        val truncated = jar.copyOf(40)

        shouldThrow<ArtifactException> { inspector().inspect(truncated.inputStream()) }
    }

    "the input stream is not closed so that the caller can drain it" {
        var closed = false
        val stream = object : java.io.ByteArrayInputStream(buildJar("LICENSE" to "x")) {
            override fun close() {
                closed = true
            }
        }

        inspector().inspect(stream)

        closed shouldBe false
    }
})
