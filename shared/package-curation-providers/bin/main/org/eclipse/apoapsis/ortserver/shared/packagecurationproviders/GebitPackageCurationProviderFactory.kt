package org.eclipse.apoapsis.ortserver.shared.packagecurationproviders

import org.ossreviewtoolkit.plugins.api.PluginConfig
import org.ossreviewtoolkit.plugins.api.PluginDescriptor
import org.ossreviewtoolkit.plugins.packagecurationproviders.api.PackageCurationProviderFactory

public class GebitPackageCurationProviderFactory : PackageCurationProviderFactory {
  override val descriptor: PluginDescriptor by Companion::descriptor

  override fun create(config: PluginConfig): GebitPackageCurationProvider = GebitPackageCurationProvider(descriptor)

  public companion object {
    public val descriptor: PluginDescriptor = PluginDescriptor(
            id = "Gebit",
            displayName = "Gebit Package Curation Provider",
            summary = "A package curation provider that applies GEBIT license curations to GEBIT Maven packages.",
            description = null,
            options = listOf(
            )
        )

    public fun create(): GebitPackageCurationProvider = GebitPackageCurationProvider(descriptor)
  }
}
