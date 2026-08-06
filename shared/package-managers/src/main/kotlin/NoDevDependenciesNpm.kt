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

package org.eclipse.apoapsis.ortserver.shared.packagemanagers

import java.io.File

import org.ossreviewtoolkit.analyzer.PackageManager
import org.ossreviewtoolkit.analyzer.PackageManagerFactory
import org.ossreviewtoolkit.analyzer.PackageManagerResult
import org.ossreviewtoolkit.model.ProjectAnalyzerResult
import org.ossreviewtoolkit.model.config.AnalyzerConfiguration
import org.ossreviewtoolkit.model.config.Excludes
import org.ossreviewtoolkit.model.config.Includes
import org.ossreviewtoolkit.model.config.ScopeExclude
import org.ossreviewtoolkit.model.config.ScopeExcludeReason
import org.ossreviewtoolkit.plugins.api.OrtPlugin
import org.ossreviewtoolkit.plugins.api.OrtPluginOption
import org.ossreviewtoolkit.plugins.api.PluginDescriptor
import org.ossreviewtoolkit.plugins.packagemanagers.node.npm.NpmFactory

/** The name of the NPM scope that contains development-only dependencies. */
private const val DEV_DEPENDENCIES_SCOPE_NAME = "devDependencies"

/**
 * The configuration options for [NoDevDependenciesNpm], mirroring the options supported by ORT's built-in `NPM`
 * package manager since they are forwarded to the delegate unchanged.
 */
data class NoDevDependenciesNpmConfig(
    /**
     * If true, ignore any project-specific `.npmrc` files. Forwarded to the delegate NPM package manager.
     */
    @OrtPluginOption(defaultValue = "false")
    val ignoreProjectNpmrcFiles: Boolean,

    /**
     * If true, pass "--legacy-peer-deps" to NPM. Forwarded to the delegate NPM package manager.
     */
    @OrtPluginOption(defaultValue = "false")
    val legacyPeerDeps: Boolean
)

/**
 * A [PackageManager] for NPM projects that always excludes the [DEV_DEPENDENCIES_SCOPE_NAME] scope, regardless of
 * whether a scope exclude for it is declared in a repository's `.ort.yml` and regardless of the global
 * `analyzer.skipExcluded` setting.
 *
 * This does not reimplement any NPM parsing logic. Instead, it delegates almost entirely to ORT's built-in `NPM`
 * package manager (obtained via [NpmFactory]) and only enriches the [Excludes] that are passed down to it with an
 * additional, hard-coded [ScopeExclude] for `devDependencies`. This mirrors the composition-based delegation pattern
 * used by `GebitPackageCurationProvider`, but for the [PackageManager] SPI: ORT's built-in `Npm` class cannot be
 * subclassed directly (it is not declared `open`), so delegation via composition is used instead of inheritance.
 */
@OrtPlugin(
    id = "NpmNoDevDependencies",
    displayName = "NPM (excluding devDependencies)",
    summary = "Delegates to ORT's built-in NPM package manager, but always excludes the 'devDependencies' scope.",
    factory = PackageManagerFactory::class
)
class NoDevDependenciesNpm(
    override val descriptor: PluginDescriptor,
    config: NoDevDependenciesNpmConfig
) : PackageManager("NPM") {
    /** The real NPM package manager implementation that this plugin delegates to. */
    private val delegate = NpmFactory.create(
        ignoreProjectNpmrcFiles = config.ignoreProjectNpmrcFiles,
        legacyPeerDeps = config.legacyPeerDeps
    )

    override val globsForDefinitionFiles = delegate.globsForDefinitionFiles

    // Note: mapDefinitionFiles() is intentionally not delegated. NodePackageManager's implementation (inherited by
    // the delegate) resolves conflicts between multiple Node package managers (e.g. NPM vs. Yarn) by looking up this
    // plugin's id in NodePackageManagerType, which only knows the built-in "NPM"/"PNPM"/"YARN"/"YARN2" ids. Since
    // this plugin is meant to fully replace the built-in NPM manager (not run alongside it), the inherited identity
    // default (returning definitionFiles unchanged) is used instead.

    override fun beforeResolution(
        analysisRoot: File,
        definitionFiles: List<File>,
        analyzerConfig: AnalyzerConfiguration
    ) = delegate.beforeResolution(analysisRoot, definitionFiles, analyzerConfig)

    override fun afterResolution(analysisRoot: File, definitionFiles: List<File>) =
        delegate.afterResolution(analysisRoot, definitionFiles)

    /**
     * Override the plural entry point (called directly by ORT's `Analyzer`) instead of relying on the base class's
     * default implementation. The base class's default `resolveDependencies(files)` would loop over
     * [resolveDependencies] (singular, below) and then call *this* instance's `createPackageManagerResult()`, which
     * -- since it is not overridden here -- produces a [PackageManagerResult] with an empty dependency graph. The
     * actual dependency graph is only built and exposed by [delegate]'s own `createPackageManagerResult()`
     * (accessible only via its own `resolveDependencies(files)`), so the whole plural call must be delegated as-is.
     */
    override fun resolveDependencies(
        analysisRoot: File,
        definitionFiles: List<File>,
        excludes: Excludes,
        includes: Includes,
        analyzerConfig: AnalyzerConfiguration,
        labels: Map<String, String>
    ): PackageManagerResult {
        val excludesWithDevDependencies = excludes.copy(
            scopes = excludes.scopes + ScopeExclude(
                pattern = DEV_DEPENDENCIES_SCOPE_NAME,
                reason = ScopeExcludeReason.DEV_DEPENDENCY_OF,
                comment = "Always excluded by the '${descriptor.id}' package manager, independent of .ort.yml."
            )
        )

        return delegate.resolveDependencies(
            analysisRoot,
            definitionFiles,
            excludesWithDevDependencies,
            includes,
            analyzerConfig,
            labels
        )
    }

    /**
     * Not used: the plural [resolveDependencies] override above delegates the whole resolution (and the resulting
     * dependency graph) to [delegate] directly, so this per-file method is never invoked from within this class.
     * It is still implemented to satisfy the abstract contract of [PackageManager].
     */
    override fun resolveDependencies(
        analysisRoot: File,
        definitionFile: File,
        excludes: Excludes,
        includes: Includes,
        analyzerConfig: AnalyzerConfiguration,
        labels: Map<String, String>
    ): List<ProjectAnalyzerResult> =
        delegate.resolveDependencies(analysisRoot, definitionFile, excludes, includes, analyzerConfig, labels)
}
