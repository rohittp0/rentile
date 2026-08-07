# Publish on version change or manual dispatch

Rentile will publish `com.rohittp.rentile:kmp` and all target publications to Maven Central from GitHub Actions through either of two triggers: a push to `main` that changes the canonical project version, or an explicit `workflow_dispatch`. An ordinary push that does not change the version does not publish, and a GitHub Release is not required.

`VERSION_NAME` in the root `gradle.properties` file is the sole canonical version. Gradle publication coordinates, local-repository verification, workflow change detection, and Maven Central upload all read that value; no workflow input, tag, module build file, or GitHub Release may supply a different publication version.

Versions ending in `-SNAPSHOT` are local-repository-only. A snapshot version may be built and verified, but neither a change on `main` nor a manual dispatch may contact Maven Central for it. Central publishing requires a non-snapshot `VERSION_NAME`.

Development begins at `VERSION_NAME=0.1.0-SNAPSHOT`. After the required local-repository consumer gates pass, the first Maven Central release is `0.1.0`; there is no required alpha publication before it.

The `0.1.0` release is also blocked until the versioned Coverage Manifest proves profile-complete rendering of all current styles resolved from the public map catalog through output zoom 22 on Android, iOS, Linux x64, and Linux ARM64. Partial background, raster, vector, or icon milestones remain `0.1.0-SNAPSHOT` local-repository builds and are not published as a reduced public contract.

The release gate is automated: preparation, capability coverage, decoded-pixel determinism, seam and ownership checks, and versioned perceptual comparison against the transformed MapLibre oracle must pass. Each corpus run also uploads a credential-free Corpus Report for human inspection, including failures, but visual approval is supplemental and cannot waive an automated failure.

Both trigger paths run the same gates before contacting Maven Central: build and test the complete target set, publish the exact version to an isolated local Maven repository, and resolve and exercise it from clean Android, iOS, and Linux consumers. Only a fully passing gate may upload the coordinated KMP publication set from one host. Maven Central automatically releases the deployment only after Central validation succeeds. Republishing an immutable version is a workflow failure, never an overwrite.

The workflow expects `MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_PASSWORD`, `SIGNING_KEY_ID`, `SIGNING_KEY_PASSWORD`, and the ASCII-armored private key in `SIGNING_KEY`. Secrets are never stored in repository files or emitted by validation tasks.

The published POM project URL is `https://rohittp.com/rentile/`, backed by the static documentation committed under `docs/`. Developer and SCM metadata use `rohittp0`, `Rohit T P`, `https://rohittp.com`, and `https://github.com/rohittp0/rentile` respectively.
