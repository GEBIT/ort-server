/*
 * Copyright (C) 2025 The ORT Server Authors (See <https://github.com/eclipse-apoapsis/ort-server/blob/main/NOTICE>)
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

package org.eclipse.apoapsis.ortserver.shared.packagecurationproviders

import org.ossreviewtoolkit.model.Identifier
import org.ossreviewtoolkit.model.Package
import org.ossreviewtoolkit.model.PackageCuration
import org.ossreviewtoolkit.model.PackageCurationData
import org.ossreviewtoolkit.model.VcsInfoCurationData
import org.ossreviewtoolkit.model.VcsType
import org.ossreviewtoolkit.plugins.api.OrtPlugin
import org.ossreviewtoolkit.plugins.api.OrtPluginOption
import org.ossreviewtoolkit.plugins.api.PluginDescriptor
import org.ossreviewtoolkit.plugins.packagecurationproviders.api.PackageCurationProvider
import org.ossreviewtoolkit.plugins.packagecurationproviders.api.PackageCurationProviderFactory
import org.ossreviewtoolkit.utils.spdxexpression.SpdxExpression

/** The default namespaces used to identify GEBIT-owned Maven packages if none are configured. */
private val DEFAULT_GEBIT_NAMESPACES = listOf(
    "de.gebit.rp",
    "de.gebit.pos",
    "de.gebit.trend",
    "de.gebit.compas",
    "de.gebit.ep",
    "de.gebit.integrity",
    "de.gebit.wildfly",
    "de.gebit.lib"
)

/**
 * The configuration options supported by [GebitPackageCurationProvider].
 */
data class GebitPackageCurationProviderConfig(
    /**
     * The list of Maven namespaces (and their sub-namespaces) that are considered GEBIT-owned. Packages whose
     * namespace equals one of these entries, or starts with one of these entries followed by a dot, are curated with
     * the `LicenseRef-GEBIT` concluded license.
     */
    @OrtPluginOption(defaultValue = DEFAULT_GEBIT_NAMESPACES_STRING)
    val namespaces: List<String>,

    /**
     * Additional explicit package curations, independent of [namespaces]. Each entry must have the format
     * `<package-coordinates>=<SPDX-license-expression>`, where `<package-coordinates>` is an identifier in the form
     * `type:namespace:name:version` (e.g. `Maven:net.sf:jargs:1.0`), and `<SPDX-license-expression>` is the concluded
     * license to apply, for example `BSD-3-Clause`. This can be used to curate packages that do not have a
     * detectable license, such as those identified with an empty "License IDs" field in a compliance report.
     */
    val curations: List<String>?,

    /**
     * Package coordinates (in the same `type:namespace:name:version` form as used in [curations]) of packages that
     * have no actual source code to download or scan, for example placeholder/empty artifacts. Curates these
     * packages with an empty `sourceCodeOrigins` list so the downloader/scanner don't attempt to resolve VCS or
     * source artifact provenance for them, which would otherwise fail with an error.
     */
    val noSourceCurations: List<String>?,

    /**
     * Explicit VCS location overrides, independent of [namespaces]. Each entry must have the format
     * `<package-coordinates>=<git-url>@<revision>`, where `<package-coordinates>` is an identifier in the form
     * `type:namespace:name:version` (e.g. `Maven:org.example:example:1.0`), `<git-url>` is the Git clone URL to use
     * instead of the one declared in the package's own metadata, and `<revision>` is the tag/branch/commit to check
     * out. This can be used for packages whose declared VCS metadata is stale (e.g. points at a now-archived
     * repository with no matching tag), preventing the downloader/scanner from failing to resolve a revision.
     */
    val vcsCurations: List<String>?
)

private const val DEFAULT_GEBIT_NAMESPACES_STRING =
    "de.gebit.rp,de.gebit.pos,de.gebit.trend,de.gebit.compas,de.gebit.ep,de.gebit.integrity,de.gebit.wildfly," +
        "de.gebit.lib"

@OrtPlugin(
    id = "Gebit",
    displayName = "Gebit Package Curation Provider",
    summary = "A package curation provider that applies GEBIT license curations to GEBIT Maven packages, and " +
        "optionally applies additional explicit curations configured via the 'curations' option, marks " +
        "packages with no source code via the 'noSourceCurations' option, and overrides stale VCS metadata via " +
        "the 'vcsCurations' option.",
    factory = PackageCurationProviderFactory::class
)
class GebitPackageCurationProvider(
    override val descriptor: PluginDescriptor,
    config: GebitPackageCurationProviderConfig
) : PackageCurationProvider {
    /** The namespaces used to identify GEBIT-owned Maven packages, falling back to the built-in defaults. */
    private val namespaces = config.namespaces.takeUnless { it.isEmpty() } ?: DEFAULT_GEBIT_NAMESPACES

    /** Explicit curations parsed from the `curations` option, keyed by package identifier. */
    private val explicitCurations: Map<Identifier, SpdxExpression> = config.curations.orEmpty().associate { entry ->
        val (coordinates, license) = entry.split("=", limit = 2).also {
            require(it.size == 2) {
                "Invalid Gebit curation entry '$entry'. Expected format: '<coordinates>=<license-expression>'."
            }
        }

        Identifier(coordinates.trim()) to SpdxExpression.parse(license.trim())
    }

    /** Package identifiers parsed from the `noSourceCurations` option. */
    private val noSourceCurations: Set<Identifier> = config.noSourceCurations.orEmpty()
        .map { Identifier(it.trim()) }
        .toSet()

    /** Explicit VCS curations parsed from the `vcsCurations` option, keyed by package identifier. */
    private val explicitVcsCurations: Map<Identifier, VcsInfoCurationData> = config.vcsCurations.orEmpty()
        .associate { entry ->
            val (coordinates, vcsSpec) = entry.split("=", limit = 2).also {
                require(it.size == 2) {
                    "Invalid Gebit VCS curation entry '$entry'. Expected format: '<coordinates>=<git-url>@<revision>'."
                }
            }

            val url = vcsSpec.substringBeforeLast("@")
            val revision = vcsSpec.substringAfterLast("@")
            require(url.isNotBlank() && revision.isNotBlank() && url != vcsSpec) {
                "Invalid Gebit VCS curation entry '$entry'. Expected format: '<coordinates>=<git-url>@<revision>'."
            }

            Identifier(coordinates.trim()) to
                VcsInfoCurationData(type = VcsType.GIT, url = url.trim(), revision = revision.trim())
        }

    override fun getCurationsFor(packages: Collection<Package>): Set<PackageCuration> {
        val namespaceCurations = packages.filter { pkg ->
            pkg.id.type == "Maven" &&
                namespaces.any { namespace ->
                    pkg.id.namespace == namespace || pkg.id.namespace.startsWith("$namespace.")
                }
        }.map { pkg ->
            PackageCuration(
                id = pkg.id,
                data = PackageCurationData(concludedLicense = SpdxExpression.parse("LicenseRef-GEBIT"))
            )
        }

        val additionalCurations = packages.mapNotNull { pkg ->
            explicitCurations[pkg.id]?.let { license ->
                PackageCuration(id = pkg.id, data = PackageCurationData(concludedLicense = license))
            }
        }

        val noSourceCurationsForPackages = packages.filter { it.id in noSourceCurations }.map { pkg ->
            PackageCuration(id = pkg.id, data = PackageCurationData(sourceCodeOrigins = emptyList()))
        }

        val vcsCurationsForPackages = packages.mapNotNull { pkg ->
            explicitVcsCurations[pkg.id]?.let { vcs ->
                PackageCuration(id = pkg.id, data = PackageCurationData(vcs = vcs))
            }
        }

        return (namespaceCurations + additionalCurations + noSourceCurationsForPackages + vcsCurationsForPackages)
            .toSet()
    }
}
