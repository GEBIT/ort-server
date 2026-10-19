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

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

/** An exception for artifacts that cannot be obtained or inspected. A subclass of [IOException] for easy handling. */
class ArtifactException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Extracts [ArtifactLegalFacts] from a ZIP-based artifact (JAR, WAR, AAR, EAR). Only entry *names* are looked at, the
 * content is skipped while streaming, so memory use is independent of the archive size.
 */
class ArtifactInspector(
    private val licensePattern: LegalFilePattern,
    private val noticePattern: LegalFilePattern,
    private val maxEntries: Int
) {
    /**
     * Inspect the archive provided by [input]. The stream is **not** closed, so that the caller can drain the rest of
     * it, e.g. to compute a checksum. Throws an [ArtifactException] if the data is not a readable archive or exceeds
     * the configured number of entries.
     */
    fun inspect(input: InputStream): ArtifactLegalFacts {
        val licenseFiles = mutableListOf<String>()
        val noticeFiles = mutableListOf<String>()
        var entryCount = 0

        try {
            val zip = ZipInputStream(input)

            while (true) {
                val entry = zip.nextEntry ?: break

                entryCount++
                if (entryCount > maxEntries) throw ArtifactException("The archive has more than $maxEntries entries.")

                if (entry.isDirectory) continue

                if (licensePattern.matches(entry.name)) licenseFiles += entry.name
                if (noticePattern.matches(entry.name)) noticeFiles += entry.name
            }
        } catch (e: ZipException) {
            throw ArtifactException("The archive is corrupt: ${e.message}", e)
        } catch (e: EOFException) {
            throw ArtifactException("The archive is truncated: ${e.message}", e)
        }

        if (entryCount == 0) throw ArtifactException("The archive contains no entries.")

        return ArtifactLegalFacts(
            entryCount = entryCount,
            licenseFiles = licenseFiles,
            noticeFiles = noticeFiles
        )
    }
}
