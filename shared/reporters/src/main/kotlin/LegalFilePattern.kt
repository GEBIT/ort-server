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

private const val META_INF = "meta-inf/"
private val EXTENSIONS = listOf(".txt", ".md", ".html", ".htm")

/**
 * Matches archive entry names against a list of legal file base names (e.g. `LICENSE`). An entry matches if it is at
 * the archive root or directly below `META-INF/`, and its name equals a base name, optionally followed by one of the
 * extensions `.txt`, `.md`, `.html` or `.htm`. Matching is case-insensitive.
 */
class LegalFilePattern(baseNames: Collection<String>) {
    private val names = baseNames.map { it.trim().lowercase() }.filter { it.isNotEmpty() }

    fun matches(entryName: String): Boolean {
        val lowerName = entryName.lowercase()
        val metaInfRelative = lowerName.removePrefix(META_INF)

        val fileName = when {
            '/' !in lowerName -> lowerName
            lowerName.startsWith(META_INF) && '/' !in metaInfRelative -> metaInfRelative
            else -> return false
        }

        return names.any { name -> fileName == name || EXTENSIONS.any { fileName == name + it } }
    }
}
