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
package org.eclipse.apoapsis.ortserver.shared.packagemanagers

import org.ossreviewtoolkit.model.Hash
import org.ossreviewtoolkit.model.Identifier
import org.ossreviewtoolkit.model.Issue
import org.ossreviewtoolkit.model.Package
import org.ossreviewtoolkit.model.PackageLinkage
import org.ossreviewtoolkit.model.RemoteArtifact
import org.ossreviewtoolkit.model.SourceCodeOrigin
import org.ossreviewtoolkit.model.VcsInfo
import org.ossreviewtoolkit.model.utils.DependencyHandler

/** The package type used for Ivy modules; Ivy modules live in Maven-style repositories. */
private const val PACKAGE_TYPE = "Maven"

/** A [DependencyHandler] that maps [IvyDependency] trees to ORT identifiers and packages. */
internal class IvyDependencyHandler : DependencyHandler<IvyDependency> {
    override fun identifierFor(dependency: IvyDependency) =
        Identifier(PACKAGE_TYPE, dependency.organisation, dependency.name, dependency.revision)

    override fun dependenciesFor(dependency: IvyDependency): List<IvyDependency> = dependency.dependencies

    override fun linkageFor(dependency: IvyDependency) = PackageLinkage.DYNAMIC

    override fun createPackage(dependency: IvyDependency, issues: MutableCollection<Issue>): Package? =
        Package(
            id = identifierFor(dependency),
            authors = emptySet(),
            declaredLicenses = dependency.licenses,
            description = dependency.description,
            homepageUrl = dependency.homepageUrl,
            binaryArtifact = dependency.binaryArtifactUrl.toRemoteArtifact(),
            sourceArtifact = dependency.sourceArtifactUrl.toRemoteArtifact(),
            vcs = VcsInfo.EMPTY,
            sourceCodeOrigins = listOf(SourceCodeOrigin.ARTIFACT)
        )

    private fun String.toRemoteArtifact() =
        if (isEmpty()) RemoteArtifact.EMPTY else RemoteArtifact(this, Hash.NONE)
}
