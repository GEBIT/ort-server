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

import org.ossreviewtoolkit.model.Package
import org.ossreviewtoolkit.model.PackageCuration
import org.ossreviewtoolkit.model.PackageCurationData
import org.ossreviewtoolkit.plugins.api.OrtPlugin
import org.ossreviewtoolkit.plugins.api.PluginDescriptor
import org.ossreviewtoolkit.plugins.packagecurationproviders.api.PackageCurationProvider
import org.ossreviewtoolkit.plugins.packagecurationproviders.api.PackageCurationProviderFactory
import org.ossreviewtoolkit.utils.spdxexpression.SpdxExpression

@OrtPlugin(
    id = "Gebit",
    displayName = "Gebit Package Curation Provider",
    summary = "A package curation provider that applies GEBIT license curations to GEBIT Maven packages.",
    factory = PackageCurationProviderFactory::class
)
class GebitPackageCurationProvider(
    override val descriptor: PluginDescriptor
) : PackageCurationProvider {
    override fun getCurationsFor(packages: Collection<Package>): Set<PackageCuration> =
        packages.filter { pkg ->
            pkg.id.type == "Maven" &&
                listOf(
                    "de.gebit.rp",
                    "de.gebit.pos",
                    "de.gebit.trend",
                    "de.gebit.compas",
                    "de.gebit.ep",
                    "de.gebit.integrity",
                    "de.gebit.wildfly",
                    // maybe switch later to license mapping
                    "de.gebit.lib"
                ).any { namespace ->
                    pkg.id.namespace == namespace || pkg.id.namespace.startsWith("$namespace.")
                }
        }.mapTo(mutableSetOf()) { pkg ->
            PackageCuration(
                id = pkg.id,
                data = PackageCurationData(concludedLicense = SpdxExpression.parse("LicenseRef-GEBIT"))
            )
        }
}
