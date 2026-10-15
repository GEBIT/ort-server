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

import kotlinx.serialization.Serializable

/**
 * The version of the fact extraction logic. Bump it whenever [ArtifactInspector] or the default file patterns change,
 * which invalidates all cache entries (the version is part of the cache key).
 */
const val EXTRACTOR_VERSION = 1

/**
 * The facts about an artifact that are relevant for obligation checks. This is what the cache stores; verdicts are
 * derived from it by [ObligationRules], so changing rules never requires cache invalidation.
 */
@Serializable
data class ArtifactLegalFacts(
    val extractorVersion: Int = EXTRACTOR_VERSION,

    /** The number of entries in the archive. */
    val entryCount: Int,

    /** The names of the archive entries that are license files. */
    val licenseFiles: List<String>,

    /** The names of the archive entries that are notice files. */
    val noticeFiles: List<String>
)
