# Pinned Corpus Credential Audit

The credential-safe scan of the 34 style snapshots available on 2026-08-07 inspected URL fields without printing or retaining URL values, query strings, or credentials.

## Findings

- All 34 styles contain embedded credential-bearing references.
- Each style contains between two and eight credential-bearing references.
- The corpus contains 133 credential-bearing URL references.
- Credentials are non-empty and consistent within every style, exact-origin, and query-parameter group.
- One style contains two distinct credentials because it references two different provider origins; there is no same-origin inconsistency.
- MapTiler source, GeoJSON, and glyph references carry `key`; 31 MapTiler sprite base URLs omit it.
- Stadia references use `api_key`; the keyless ArcGIS imagery URL is a separate origin and must never receive another provider's credential.
- Glyph credentials do not affect Rentile because the approved profile never requests glyphs.

## API consequence

Rentile does not require a separate API-key argument for the pinned corpus. Preparation extracts credentials already present in `StyleInput` into redacted ephemeral state. An optional caller credential provider is needed only for a future style whose required origin lacks an embedded credential.

## Safety consequence

Credential extraction and reuse are scoped by exact HTTPS origin and parameter name, allowing keyless same-origin sprite references to use the style's extracted credential without propagating it to another provider. Credential values never enter compatibility manifests, persistent raw resources, cache keys, content keys, diagnostics, metrics, or exception messages.
