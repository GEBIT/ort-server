# Gebit Package Curation Provider Design

## Goal

Add an ORT package curation provider that assigns the concluded license
`LicenseRef-GEBIT` to every matching Maven package during analysis.

## Matching behavior

The provider matches packages where:

- `id.type` is `Maven`, and
- `id.namespace` is exactly `de.gebit.rp` or starts with `de.gebit.rp.`.

This includes the bare groupId and all of its descendants, while excluding
near-matches such as `de.gebit.rpx`. Non-Maven packages are ignored.

## Architecture and data flow

Implement `GebitPackageCurationProvider` in
`shared/package-curation-providers`, following the existing
`DirPackageCurationProvider` pattern. Annotate it with `@OrtPlugin` so KSP
generates its factory and service registration. The provider has no options or
secrets and is enabled through an analyzer provider configuration with type
`Gebit`.

For every matching package passed to `getCurationsFor()`, return one
`PackageCuration` whose identifier is the package's exact identifier and whose
`PackageCurationData` sets `concludedLicense` to `LicenseRef-GEBIT`. The
analyzer worker already creates configured providers through
`PackageCurationProviderFactory`, so no analyzer wiring changes are required.

## Testing

Add provider tests using `PackageCurationProviderFactory` covering:

- the exact `de.gebit.rp` namespace;
- descendant namespaces such as `de.gebit.rp.example`;
- non-Maven packages;
- near-match namespaces such as `de.gebit.rpx`; and
- the generated curation's exact identifier and concluded license.

