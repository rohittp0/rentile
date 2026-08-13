# Publish continuously from the R2 version line

Supersedes [ADR 0018](0018-publish-on-version-change-or-manual-dispatch.md).

Every push to `main` that touches anything outside `docs/`, Markdown, or `LICENSE` publishes a new release; documentation-only commits are ignored so they cannot consume a public version. `workflow_dispatch` is retained as a manual trigger. This reverses ADR 0018's rule that a push never publishes, trading deliberate release moments for a continuously shipped `main`.

The release version is resolved from the public repository rather than read from the repository alone. The workflow takes the highest semantically ordered version in `maven-metadata.xml` under `com/rohittp/rentile/kmp/` and increments its patch component. `VERSION_NAME` in the root `gradle.properties` still governs when it names a version strictly greater than everything already public, which is how a deliberate minor or major release is requested; otherwise it is ignored and the patch line advances on its own. A `-SNAPSHOT` value never governs and is never published. The resolved version reaches Gradle as `-PVERSION_NAME`, so `VERSION_NAME` is no longer the sole canonical version that ADR 0018 described — the published R2 version line is, with `gradle.properties` acting only as an upward override.

Two mechanisms keep the immutable repository safe. In automatic mode the workflow advances past any candidate patch whose POM already answers on the public URL, so stale or lagging metadata cannot produce a colliding coordinate; in explicit mode a declared version that already exists fails the release instead of silently advancing, because quietly shipping a different number than the one requested would be worse than stopping. The pre-upload check against the authoritative bucket key is unchanged and still fails closed rather than overwriting. Releases are serialised through a `publish-main` concurrency group with cancellation disabled, so concurrent pushes cannot race for one coordinate and no run is interrupted mid-upload.

Because the release workflow runs the complete rolling map-catalog corpus gate on every publishing push, the standalone corpus workflow no longer triggers on push and remains available through `workflow_dispatch`.

Version numbers are consequently cheap and non-contiguous: a failed release, a skipped documentation commit, or an advanced probe all leave gaps, and no gap implies a withdrawn version.
