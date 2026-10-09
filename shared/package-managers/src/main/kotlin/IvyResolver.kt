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

import java.io.File

import org.apache.ivy.core.cache.ArtifactOrigin
import org.apache.ivy.core.module.descriptor.DefaultArtifact
import org.apache.ivy.core.module.descriptor.ModuleDescriptor
import org.apache.ivy.core.module.id.ModuleRevisionId
import org.apache.ivy.core.report.ResolveReport
import org.apache.ivy.core.resolve.IvyNode
import org.apache.ivy.core.resolve.ResolveOptions
import org.apache.ivy.plugins.parser.ModuleDescriptorParserRegistry

import org.ossreviewtoolkit.model.Issue
import org.ossreviewtoolkit.model.Severity

import org.slf4j.LoggerFactory

/** A resolved Ivy module together with the modules it depends on within one configuration. */
internal class IvyDependency(
    val organisation: String,
    val name: String,
    val revision: String,
    val description: String,
    val homepageUrl: String,
    val licenses: Set<String>,
    val binaryArtifactUrl: String,
    val sourceArtifactUrl: String
) {
    val dependencies = mutableListOf<IvyDependency>()
}

/** The outcome of resolving one `ivy.xml`: project metadata, the direct dependencies per scope and issues. */
internal class IvyResolution(
    val organisation: String,
    val module: String,
    val revision: String,
    val description: String,
    val homepageUrl: String,
    val licenses: Set<String>,
    /** The direct dependencies (with their transitive tree) per Ivy configuration. */
    val scopes: Map<String, List<IvyDependency>>,
    val issues: List<Issue>
)

/** Resolves `ivy.xml` files with Apache Ivy and converts the result into an [IvyResolution]. */
internal class IvyResolver(private val config: IvyConfig) {
    private val logger = LoggerFactory.getLogger(IvyResolver::class.java)
    private val factory = IvyFactory(config)

    fun resolve(analysisRoot: File, definitionFile: File): IvyResolution {
        val ivy = factory.createIvy(analysisRoot, definitionFile)
        val url = definitionFile.toURI().toURL()

        val descriptor = ModuleDescriptorParserRegistry.getInstance().parseDescriptor(ivy.settings, url, false)
        val confs = selectConfigurations(descriptor)
        val issues = mutableListOf<Issue>()
        val scopes = linkedMapOf<String, List<IvyDependency>>()

        if (confs.isEmpty()) {
            val available = descriptor.publicConfigurationsNames.joinToString(", ")
            val configured = config.confs.cleaned().joinToString(", ")
            val excluded = config.excludeConfs.cleaned().joinToString(", ")

            issues += Issue(
                source = IVY_PROJECT_TYPE,
                message = "No Ivy configurations were selected for '${definitionFile.name}'. " +
                        "Available public configurations in the project descriptor: [$available]. " +
                        "Configured confs: [$configured]. Configured excludeConfs: [$excluded]. " +
                        "Adjust the confs and/or excludeConfs options to select at least one available configuration.",
                severity = Severity.WARNING
            )
        } else {
            val options = ResolveOptions()
                .setConfs(confs.toTypedArray())
                .setValidate(false)
                .setDownload(false)
                .setUseCacheOnly(config.offline)

            val report = ivy.resolve(url, options)
            val metadataCache = mutableMapOf<ModuleRevisionId, IvyDependency>()

            confs.forEach { conf -> scopes[conf] = buildScope(report, conf, metadataCache) }

            report.unresolvedDependencies.forEach { node ->
                issues += Issue(
                    source = IVY_PROJECT_TYPE,
                    message = "Unresolved dependency ${node.id}: ${node.problemMessage}",
                    severity = Severity.WARNING
                )
            }
        }

        val id = descriptor.moduleRevisionId

        return IvyResolution(
            organisation = id.organisation,
            module = id.name,
            revision = id.revision,
            description = descriptor.description.orEmpty(),
            homepageUrl = descriptor.homePage.orEmpty(),
            licenses = descriptor.licenses.mapNotNull { it.name }.toSet(),
            scopes = scopes,
            issues = issues
        )
    }

    /** Return the configurations to resolve: the wanted ones that the descriptor declares, minus the excluded. */
    private fun selectConfigurations(descriptor: ModuleDescriptor): List<String> {
        val available = descriptor.publicConfigurationsNames.toList()
        val configured = config.confs.cleaned()
        val wanted = if (configured == listOf("*")) available else configured
        val excluded = config.excludeConfs.cleaned().toSet()

        return wanted.filter { it in available && it !in excluded }
    }

    private fun buildScope(
        report: ResolveReport,
        conf: String,
        metadataCache: MutableMap<ModuleRevisionId, IvyDependency>
    ): List<IvyDependency> {
        val confReport = report.getConfigurationReport(conf)
        val rootId = confReport.moduleDescriptor.moduleRevisionId

        val nodes = confReport.moduleRevisionIds
            .mapNotNull { confReport.getDependency(it) }
            .filter { it.isLoaded && !it.isCompletelyEvicted && !it.isEvicted(conf) }
            .filter { it.resolvedId.moduleId != rootId.moduleId }

        val dependencies = nodes.associate { node ->
            node.resolvedId to createDependency(node, metadataCache)
        }

        val roots = mutableListOf<IvyDependency>()
        val childDependencies = mutableMapOf<IvyDependency, MutableList<IvyDependency>>()

        nodes.forEach { node ->
            val dependency = dependencies.getValue(node.resolvedId)

            node.getCallers(conf).forEach { caller ->
                val callerId = caller.moduleRevisionId

                if (callerId.moduleId == rootId.moduleId) {
                    if (dependency !in roots) roots += dependency
                } else {
                    val parent = dependencies[callerId]
                    if (parent != null) {
                        childDependencies.getOrPut(parent) { mutableListOf() }.apply {
                            if (dependency !in this) add(dependency)
                        }
                    }
                }
            }
        }

        val visited = mutableSetOf<IvyDependency>()

        fun addDependencies(parent: IvyDependency) {
            if (!visited.add(parent)) return

            childDependencies[parent].orEmpty().forEach { dependency ->
                if (!hasPath(dependency, parent)) {
                    parent.dependencies += dependency
                    addDependencies(dependency)
                }
            }
        }

        roots.forEach(::addDependencies)

        return roots
    }

    /** Return whether [target] is already reachable from [start] by following dependency edges. */
    private fun hasPath(start: IvyDependency, target: IvyDependency): Boolean {
        val visited = mutableSetOf<IvyDependency>()

        fun visit(dependency: IvyDependency): Boolean {
            if (dependency === target) return true
            if (!visited.add(dependency)) return false

            return dependency.dependencies.any(::visit)
        }

        return visit(start)
    }

    /** Create a fresh [IvyDependency] for [node] (the tree edges are per configuration), reusing cached metadata. */
    private fun createDependency(
        node: IvyNode,
        metadataCache: MutableMap<ModuleRevisionId, IvyDependency>
    ): IvyDependency {
        val metadata = metadataCache.getOrPut(node.resolvedId) { readMetadata(node) }

        return IvyDependency(
            organisation = metadata.organisation,
            name = metadata.name,
            revision = metadata.revision,
            description = metadata.description,
            homepageUrl = metadata.homepageUrl,
            licenses = metadata.licenses,
            binaryArtifactUrl = metadata.binaryArtifactUrl,
            sourceArtifactUrl = metadata.sourceArtifactUrl
        )
    }

    private fun readMetadata(node: IvyNode): IvyDependency {
        val id = node.resolvedId
        val descriptor = node.descriptor

        return IvyDependency(
            organisation = id.organisation,
            name = id.name,
            revision = id.revision,
            description = descriptor?.description.orEmpty(),
            homepageUrl = descriptor?.homePage.orEmpty(),
            licenses = descriptor?.licenses?.mapNotNull { it.name }?.toSet().orEmpty(),
            binaryArtifactUrl = locate(node, DefaultArtifact(id, null, id.name, "jar", "jar")),
            sourceArtifactUrl = locate(
                node,
                DefaultArtifact(id, null, id.name, "source", "jar", mapOf("classifier" to "sources"))
            )
        )
    }

    /** Return the location of [artifact] as known to the resolver of [node], or an empty string if unavailable. */
    private fun locate(node: IvyNode, artifact: DefaultArtifact): String {
        if (config.offline) return ""

        return runCatching {
            val origin = node.moduleRevision?.resolver?.locate(artifact)
            if (origin == null || ArtifactOrigin.isUnknown(origin)) "" else origin.location
        }.onFailure { logger.debug("Failed to locate Ivy artifact $artifact.", it) }
            .getOrDefault("")
    }
}
