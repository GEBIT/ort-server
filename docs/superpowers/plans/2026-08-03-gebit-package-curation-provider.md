# Gebit Package Curation Provider Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a registered ORT package curation provider that assigns `LicenseRef-GEBIT` as the concluded license for matching Maven packages.

**Architecture:** Implement a dedicated, option-free `GebitPackageCurationProvider` in the existing shared package-curation-providers module. The provider will be discovered through the existing ORT KSP factory/service mechanism and will return one exact-ID curation for Maven packages whose namespace is `de.gebit.rp` or begins with `de.gebit.rp.`.

**Tech Stack:** Kotlin/JVM, ORT plugin API and KSP registration, Kotest, Gradle.

---

### Task 1: Add failing provider behavior tests

**Files:**
- Create: `shared/package-curation-providers/src/test/kotlin/GebitPackageCurationProviderTest.kt`

- [ ] **Step 1: Write the failing test**

Create a Kotest `StringSpec` that constructs the provider through
`PackageCurationProviderFactory`, then verifies matching and non-matching
packages:

```kotlin
package org.eclipse.apoapsis.ortserver.shared.packagecurationproviders

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.containExactlyInAnyOrder
import io.kotest.matchers.should

import org.ossreviewtoolkit.model.Identifier
import org.ossreviewtoolkit.model.Package
import org.ossreviewtoolkit.model.PackageCuration
import org.ossreviewtoolkit.model.PackageCurationData
import org.ossreviewtoolkit.model.config.ProviderPluginConfiguration
import org.ossreviewtoolkit.plugins.packagecurationproviders.api.PackageCurationProvider
import org.ossreviewtoolkit.plugins.packagecurationproviders.api.PackageCurationProviderFactory

class GebitPackageCurationProviderTest : StringSpec({
    "curate matching Maven packages only" {
        val packageBareGroup = packageWith("Maven", "de.gebit.rp", "library", "1.0")
        val packageChildGroup = packageWith("Maven", "de.gebit.rp.example", "library", "1.1")
        val packageNearMatch = packageWith("Maven", "de.gebit.rpx", "library", "1.2")
        val packageOtherType = packageWith("Gradle", "de.gebit.rp", "library", "1.3")

        val curations = createProvider().getCurationsFor(
            listOf(packageBareGroup, packageChildGroup, packageNearMatch, packageOtherType)
        )

        curations should containExactlyInAnyOrder(
            createCuration(packageBareGroup.id),
            createCuration(packageChildGroup.id)
        )
    }
})

private fun createProvider(): PackageCurationProvider =
    PackageCurationProviderFactory.create(
        listOf(ProviderPluginConfiguration(type = "Gebit"))
    ).single().second

private fun packageWith(type: String, namespace: String, name: String, version: String): Package =
    Package.EMPTY.copy(id = Identifier(type, namespace, name, version))

private fun createCuration(id: Identifier): PackageCuration =
    PackageCuration(
        id = id,
        data = PackageCurationData(concludedLicense = "LicenseRef-GEBIT")
    )
```

- [ ] **Step 2: Run the focused test to verify it fails**

Run from `ort-server`:

```bash
./gradlew :shared:package-curation-providers:test \
  --tests org.eclipse.apoapsis.ortserver.shared.packagecurationproviders.GebitPackageCurationProviderTest
```

Expected: FAIL because the `Gebit` plugin is not yet registered.

- [ ] **Step 3: Commit the failing test**

```bash
git add shared/package-curation-providers/src/test/kotlin/GebitPackageCurationProviderTest.kt
git commit -m "test: specify Gebit package curation behavior"
```

### Task 2: Implement and register the Gebit provider

**Files:**
- Create: `shared/package-curation-providers/src/main/kotlin/GebitPackageCurationProvider.kt`

- [ ] **Step 1: Add the option-free ORT plugin**

Create the provider using the existing `@OrtPlugin` and generated factory
pattern:

```kotlin
package org.eclipse.apoapsis.ortserver.shared.packagecurationproviders

import org.ossreviewtoolkit.model.Package
import org.ossreviewtoolkit.model.PackageCuration
import org.ossreviewtoolkit.model.PackageCurationData
import org.ossreviewtoolkit.plugins.api.OrtPlugin
import org.ossreviewtoolkit.plugins.api.PluginDescriptor
import org.ossreviewtoolkit.plugins.packagecurationproviders.api.PackageCurationProvider
import org.ossreviewtoolkit.plugins.packagecurationproviders.api.PackageCurationProviderFactory

@OrtPlugin(
    id = "Gebit",
    displayName = "Gebit Package Curation Provider",
    summary = "A package curation provider that assigns LicenseRef-GEBIT to Gebit Maven packages.",
    factory = PackageCurationProviderFactory::class
)
class GebitPackageCurationProvider(
    override val descriptor: PluginDescriptor = GebitPackageCurationProviderFactory.descriptor
) : PackageCurationProvider {
    override fun getCurationsFor(packages: Collection<Package>): Set<PackageCuration> =
        packages
            .filter { pkg ->
                pkg.id.type == MAVEN_TYPE &&
                    (pkg.id.namespace == GEBIT_GROUP_ID ||
                        pkg.id.namespace.startsWith("$GEBIT_GROUP_ID."))
            }
            .map { pkg ->
                PackageCuration(
                    id = pkg.id,
                    data = PackageCurationData(concludedLicense = GEBIT_LICENSE)
                )
            }
            .toSet()

    private companion object {
        const val MAVEN_TYPE = "Maven"
        const val GEBIT_GROUP_ID = "de.gebit.rp"
        const val GEBIT_LICENSE = "LicenseRef-GEBIT"
    }
}
```

- [ ] **Step 2: Run the focused test to verify it passes**

Run:

```bash
./gradlew :shared:package-curation-providers:test \
  --tests org.eclipse.apoapsis.ortserver.shared.packagecurationproviders.GebitPackageCurationProviderTest
```

Expected: PASS, including KSP-generated `GebitPackageCurationProviderFactory`
and service discovery.

- [ ] **Step 3: Commit the provider**

```bash
git add shared/package-curation-providers/src/main/kotlin/GebitPackageCurationProvider.kt
git commit -m "feat: add Gebit package curation provider"
```

### Task 3: Run the module validation

**Files:**
- No source changes.

- [ ] **Step 1: Run the complete module test suite**

Run from `ort-server`:

```bash
./gradlew :shared:package-curation-providers:test
```

Expected: PASS for the existing directory provider tests and the new Gebit
provider tests.

- [ ] **Step 2: Inspect the final diff**

Run:

```bash
git diff HEAD~2..HEAD -- \
  shared/package-curation-providers/src/main/kotlin/GebitPackageCurationProvider.kt \
  shared/package-curation-providers/src/test/kotlin/GebitPackageCurationProviderTest.kt
```

Expected: only the dedicated provider and its focused tests are included; the
pre-existing worktree changes remain untouched.
