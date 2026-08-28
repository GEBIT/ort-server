/*
 * Copyright (C) 2023 The ORT Server Authors (See <https://github.com/eclipse-apoapsis/ort-server/blob/main/NOTICE>)
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

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.beEmpty
import io.kotest.matchers.collections.containExactlyInAnyOrder
import io.kotest.matchers.should

import org.ossreviewtoolkit.model.Identifier
import org.ossreviewtoolkit.model.Package
import org.ossreviewtoolkit.model.PackageCuration
import org.ossreviewtoolkit.model.PackageCurationData
import org.ossreviewtoolkit.model.VcsInfoCurationData
import org.ossreviewtoolkit.model.VcsType
import org.ossreviewtoolkit.model.config.ProviderPluginConfiguration
import org.ossreviewtoolkit.plugins.packagecurationproviders.api.PackageCurationProviderFactory
import org.ossreviewtoolkit.utils.spdxexpression.SpdxExpression

class GebitPackageCurationProviderTest : StringSpec({
    val provider = PackageCurationProviderFactory.create(
        listOf(ProviderPluginConfiguration(type = "Gebit"))
    ).single().second

    "A Maven package with namespace de.gebit.rp should match" {
        val pkg = Package.EMPTY.copy(id = Identifier("Maven", "de.gebit.rp", "some-artifact", "1.0.0"))

        val curations = provider.getCurationsFor(listOf(pkg))

        curations should containExactlyInAnyOrder(
            PackageCuration(
                id = pkg.id,
                data = PackageCurationData(concludedLicense = SpdxExpression.parse("LicenseRef-GEBIT"))
            )
        )
    }

    "A Maven package with descendant namespace de.gebit.rp.example should match" {
        val pkg = Package.EMPTY.copy(id = Identifier("Maven", "de.gebit.rp.example", "some-artifact", "1.0.0"))

        val curations = provider.getCurationsFor(listOf(pkg))

        curations should containExactlyInAnyOrder(
            PackageCuration(
                id = pkg.id,
                data = PackageCurationData(concludedLicense = SpdxExpression.parse("LicenseRef-GEBIT"))
            )
        )
    }

    "Maven packages with de.gebit.pos and de.gebit.trend namespaces and descendants should match" {
        val packages = listOf(
            Package.EMPTY.copy(id = Identifier("Maven", "de.gebit.pos", "pos-artifact", "1.0.0")),
            Package.EMPTY.copy(id = Identifier("Maven", "de.gebit.pos.example", "pos-sub-artifact", "1.0.0")),
            Package.EMPTY.copy(id = Identifier("Maven", "de.gebit.trend", "trend-artifact", "1.0.0")),
            Package.EMPTY.copy(id = Identifier("Maven", "de.gebit.trend.example", "trend-sub-artifact", "1.0.0"))
        )

        val curations = provider.getCurationsFor(packages)

        curations.map { it.id } should containExactlyInAnyOrder(packages.map { it.id })
    }

    "Maven packages with de.gebit.compas namespace and descendants should match" {
        val packages = listOf(
            Package.EMPTY.copy(id = Identifier("Maven", "de.gebit.compas", "compas-artifact", "1.0.0")),
            Package.EMPTY.copy(id = Identifier("Maven", "de.gebit.compas.example", "compas-sub-artifact", "1.0.0"))
        )

        val curations = provider.getCurationsFor(packages)

        curations.map { it.id } should containExactlyInAnyOrder(packages.map { it.id })
    }

    "Maven packages with de.gebit.ep and de.gebit.integrity namespaces and descendants should match" {
        val packages = listOf(
            Package.EMPTY.copy(id = Identifier("Maven", "de.gebit.ep", "ep-artifact", "1.0.0")),
            Package.EMPTY.copy(id = Identifier("Maven", "de.gebit.ep.example", "ep-sub-artifact", "1.0.0")),
            Package.EMPTY.copy(id = Identifier("Maven", "de.gebit.integrity", "integrity-artifact", "1.0.0")),
            Package.EMPTY.copy(
                id = Identifier("Maven", "de.gebit.integrity.example", "integrity-sub-artifact", "1.0.0")
            )
        )

        val curations = provider.getCurationsFor(packages)

        curations.map { it.id } should containExactlyInAnyOrder(packages.map { it.id })
    }

    "Maven packages with de.gebit.lib namespace and descendants should match" {
        val packages = listOf(
            Package.EMPTY.copy(id = Identifier("Maven", "de.gebit.lib", "lib-artifact", "1.0.0")),
            Package.EMPTY.copy(id = Identifier("Maven", "de.gebit.lib.example", "lib-sub-artifact", "1.0.0"))
        )

        val curations = provider.getCurationsFor(packages)

        curations.map { it.id } should containExactlyInAnyOrder(packages.map { it.id })
    }

    "Maven packages with de.gebit.wildfly namespace and descendants should match" {
        val packages = listOf(
            Package.EMPTY.copy(id = Identifier("Maven", "de.gebit.wildfly", "wildfly-artifact", "1.0.0")),
            Package.EMPTY.copy(
                id = Identifier("Maven", "de.gebit.wildfly.example", "wildfly-sub-artifact", "1.0.0")
            )
        )

        val curations = provider.getCurationsFor(packages)

        curations.map { it.id } should containExactlyInAnyOrder(packages.map { it.id })
    }

    "A Maven package with near-match namespace de.gebit.rpx should not match" {
        val pkg = Package.EMPTY.copy(id = Identifier("Maven", "de.gebit.rpx", "some-artifact", "1.0.0"))

        val curations = provider.getCurationsFor(listOf(pkg))

        curations should beEmpty()
    }

    "A non-Maven package with namespace de.gebit.rp should not match" {
        val pkg = Package.EMPTY.copy(id = Identifier("NPM", "de.gebit.rp", "some-artifact", "1.0.0"))

        val curations = provider.getCurationsFor(listOf(pkg))

        curations should beEmpty()
    }

    "A custom namespace configured via options should match and the default namespaces should not" {
        val customProvider = PackageCurationProviderFactory.create(
            listOf(ProviderPluginConfiguration(type = "Gebit", options = mapOf("namespaces" to "de.acme")))
        ).single().second

        val matchingPkg = Package.EMPTY.copy(id = Identifier("Maven", "de.acme", "some-artifact", "1.0.0"))
        val defaultNamespacePkg = Package.EMPTY.copy(id = Identifier("Maven", "de.gebit.rp", "some-artifact", "1.0.0"))

        val curations = customProvider.getCurationsFor(listOf(matchingPkg, defaultNamespacePkg))

        curations should containExactlyInAnyOrder(
            PackageCuration(
                id = matchingPkg.id,
                data = PackageCurationData(concludedLicense = SpdxExpression.parse("LicenseRef-GEBIT"))
            )
        )
    }

    "Explicit curations configured via options should apply to matching packages regardless of namespace" {
        val curationProvider = PackageCurationProviderFactory.create(
            listOf(
                ProviderPluginConfiguration(
                    type = "Gebit",
                    options = mapOf(
                        "curations" to "Maven:net.sf:jargs:1.0=BSD-3-Clause,Maven:org.apache.bcel:bcel:5.2=Apache-2.0"
                    )
                )
            )
        ).single().second

        val jargsPkg = Package.EMPTY.copy(id = Identifier("Maven", "net.sf", "jargs", "1.0"))
        val bcelPkg = Package.EMPTY.copy(id = Identifier("Maven", "org.apache.bcel", "bcel", "5.2"))
        val uncuratedPkg = Package.EMPTY.copy(id = Identifier("Maven", "net.sf", "jargs", "2.0"))

        val curations = curationProvider.getCurationsFor(listOf(jargsPkg, bcelPkg, uncuratedPkg))

        curations should containExactlyInAnyOrder(
            PackageCuration(
                id = jargsPkg.id,
                data = PackageCurationData(concludedLicense = SpdxExpression.parse("BSD-3-Clause"))
            ),
            PackageCuration(
                id = bcelPkg.id,
                data = PackageCurationData(concludedLicense = SpdxExpression.parse("Apache-2.0"))
            )
        )
    }

    "Explicit VCS curations configured via options should apply to matching packages regardless of namespace" {
        val curationProvider = PackageCurationProviderFactory.create(
            listOf(
                ProviderPluginConfiguration(
                    type = "Gebit",
                    options = mapOf(
                        "vcsCurations" to
                            "Maven:org.springframework.cloud:spring-cloud-starter-stream-kafka:5.0.2=" +
                            "https://github.com/spring-cloud/spring-cloud-stream.git@v5.0.2"
                    )
                )
            )
        ).single().second

        val kafkaStarterPkg = Package.EMPTY.copy(
            id = Identifier(
                "Maven",
                "org.springframework.cloud",
                "spring-cloud-starter-stream-kafka",
                "5.0.2"
            )
        )
        val uncuratedPkg = Package.EMPTY.copy(
            id = Identifier(
                "Maven",
                "org.springframework.cloud",
                "spring-cloud-starter-stream-kafka",
                "5.0.1"
            )
        )

        val curations = curationProvider.getCurationsFor(listOf(kafkaStarterPkg, uncuratedPkg))

        curations should containExactlyInAnyOrder(
            PackageCuration(
                id = kafkaStarterPkg.id,
                data = PackageCurationData(
                    vcs = VcsInfoCurationData(
                        type = VcsType.GIT,
                        url = "https://github.com/spring-cloud/spring-cloud-stream.git",
                        revision = "v5.0.2"
                    )
                )
            )
        )
    }

    "Packages configured via noSourceCurations should be curated with empty sourceCodeOrigins" {
        val curationProvider = PackageCurationProviderFactory.create(
            listOf(
                ProviderPluginConfiguration(
                    type = "Gebit",
                    options = mapOf(
                        "noSourceCurations" to
                            "Maven:com.google.guava:listenablefuture:9999.0-empty-to-avoid-conflict-with-guava"
                    )
                )
            )
        ).single().second

        val emptyPkg = Package.EMPTY.copy(
            id = Identifier(
                "Maven",
                "com.google.guava",
                "listenablefuture",
                "9999.0-empty-to-avoid-conflict-with-guava"
            )
        )
        val uncuratedPkg = Package.EMPTY.copy(id = Identifier("Maven", "com.google.guava", "guava", "32.0.0-jre"))

        val curations = curationProvider.getCurationsFor(listOf(emptyPkg, uncuratedPkg))

        curations should containExactlyInAnyOrder(
            PackageCuration(id = emptyPkg.id, data = PackageCurationData(sourceCodeOrigins = emptyList()))
        )
    }
})
