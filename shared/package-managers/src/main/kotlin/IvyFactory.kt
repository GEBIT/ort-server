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
import java.util.Properties

import org.apache.ivy.Ivy
import org.apache.ivy.core.settings.IvySettings
import org.apache.ivy.util.Message

private const val SETTINGS_FILE_NAME = "ivysettings.xml"

/** Creates configured [Ivy] instances according to an [IvyConfig]. */
internal class IvyFactory(private val config: IvyConfig) {
    /**
     * Return the settings file to use for [definitionFile]: an `ivysettings.xml` next to it, otherwise the first
     * existing configured candidate relative to [analysisRoot], or `null` if there is none.
     */
    fun findSettingsFile(analysisRoot: File, definitionFile: File): File? {
        val nextToDefinition = definitionFile.parentFile.resolve(SETTINGS_FILE_NAME)
        val candidates = config.settingsFileCandidates.cleaned().map { analysisRoot.resolve(it) }

        return (listOf(nextToDefinition) + candidates).firstOrNull { it.isFile }
    }

    /**
     * Create an [Ivy] instance for [definitionFile]. Variables are set in this order, later ones overriding earlier
     * ones: `env.*`, `basedir`, the existing files of [IvyConfig.propertiesFiles], [IvyConfig.extraProperties].
     * Settings files may still define further variables, e.g. via `<properties override="false">`.
     */
    fun createIvy(analysisRoot: File, definitionFile: File): Ivy {
        Message.setDefaultLogger(IvyMessageLogger())

        val settings = IvySettings()

        settings.addAllVariables(System.getenv().mapKeys { "env.${it.key}" }, false)
        settings.setVariable("basedir", definitionFile.parentFile.absolutePath, true)

        config.propertiesFiles.cleaned().map { analysisRoot.resolve(it) }.filter { it.isFile }.forEach { file ->
            val properties = Properties().apply { file.inputStream().use { load(it) } }
            properties.forEach { (key, value) -> settings.setVariable(key.toString(), value.toString(), true) }
        }

        parseExtraProperties().forEach { (key, value) -> settings.setVariable(key, value, true) }

        val settingsFile = findSettingsFile(analysisRoot, definitionFile)
        if (settingsFile != null) settings.load(settingsFile) else settings.loadDefault()

        return Ivy.newInstance(settings)
    }

    private fun parseExtraProperties(): List<Pair<String, String>> =
        config.extraProperties.cleaned().map { entry ->
            val index = entry.indexOf('=')
            require(index > 0) { "Invalid extraProperties entry '$entry', expected 'key=value'." }

            entry.substring(0, index).trim() to entry.substring(index + 1).trim()
        }
}
