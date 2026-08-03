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
import org.ossreviewtoolkit.utils.spdxexpression.SpdxExpression
import org.ossreviewtoolkit.model.Package
import org.ossreviewtoolkit.model.PackageCuration
import org.ossreviewtoolkit.model.PackageCurationData
import org.ossreviewtoolkit.model.config.ProviderPluginConfiguration
import org.ossreviewtoolkit.plugins.packagecurationproviders.api.PackageCurationProviderFactory

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
})
