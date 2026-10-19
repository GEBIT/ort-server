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
import io.kotest.matchers.shouldBe

class LegalFilePatternTest : StringSpec({
    val pattern = LegalFilePattern(listOf("LICENSE", "LICENCE", "COPYING"))

    "files at the archive root match" {
        pattern.matches("LICENSE") shouldBe true
        pattern.matches("COPYING") shouldBe true
    }

    "files directly below META-INF match" {
        pattern.matches("META-INF/LICENSE") shouldBe true
        pattern.matches("META-INF/LICENCE.txt") shouldBe true
    }

    "matching is case-insensitive" {
        pattern.matches("license") shouldBe true
        pattern.matches("meta-inf/License.MD") shouldBe true
    }

    "the supported extensions match" {
        listOf(".txt", ".md", ".html", ".htm").forEach { ext ->
            pattern.matches("LICENSE$ext") shouldBe true
        }
    }

    "other extensions and name variants do not match" {
        pattern.matches("LICENSE.class") shouldBe false
        pattern.matches("LICENSE-2.0.txt") shouldBe false
        pattern.matches("LICENSEE") shouldBe false
        pattern.matches("MYLICENSE") shouldBe false
    }

    "files in other directories do not match" {
        pattern.matches("com/example/LICENSE") shouldBe false
        pattern.matches("META-INF/versions/9/LICENSE") shouldBe false
        pattern.matches("META-INF/maven/org.example/lib/LICENSE") shouldBe false
    }

    "directories do not match" {
        pattern.matches("LICENSE/") shouldBe false
        pattern.matches("META-INF/") shouldBe false
    }

    "blank base names are ignored" {
        LegalFilePattern(listOf("", " ")).matches("") shouldBe false
    }
})
