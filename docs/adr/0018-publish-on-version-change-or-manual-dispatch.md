# Publish manually to the immutable R2 Maven repository

Rentile publishes `com.rohittp.rentile:kmp` and all target publications to the public Maven repository at `https://maven.rohittp.com`. Publishing starts only through an explicit `workflow_dispatch` whose comma-separated module input currently accepts `kmp`. A push, tag, or GitHub Release does not publish by itself.

`VERSION_NAME` in the root `gradle.properties` file remains the sole canonical version. Gradle publication coordinates, local-repository verification, the R2 immutability key, and public consumer verification all read that value; no workflow input, tag, module build file, or GitHub Release may supply a competing publication version.

Versions ending in `-SNAPSHOT` are local-repository-only. The workflow rejects them before it reads or writes the R2 bucket.

Historical releases `0.1.0` through `0.1.4` were published to Maven Central. The shared R2 repository is canonical after this migration, and consumers must add `https://maven.rohittp.com` to `dependencyResolutionManagement`. No consumer credentials are required.

The release gate builds and tests the supported target set, publishes the exact version to an isolated local Maven repository, checks POM metadata and signatures, and resolves it from clean Android, JVM, iOS, and Linux consumers. The complete rolling map-catalog corpus gate must also pass and uploads a credential-free Corpus Report for inspection.

Before uploading, the workflow checks the exact primary POM key under `com/rohittp/rentile/kmp/<version>/` in R2. If that key exists, the release fails instead of overwriting any object. After upload, every locally published version artifact—including Gradle module metadata, JAR/AAR/KLIB files, sources, checksums, and signatures—must return HTTP 200 from the public repository. A fresh consumer with a fresh Gradle user home then resolves every supported target from that public URL without credentials.

The workflow expects `R2_ACCESS_KEY_ID`, `R2_SECRET_ACCESS_KEY`, `SIGNING_KEY`, `SIGNING_KEY_ID`, and `SIGNING_KEY_PASSWORD` as GitHub secrets. It expects `R2_ENDPOINT`, `R2_BUCKET`, and `R2_PUBLIC_URL` as GitHub variables. Credentials are never stored in repository files or passed to the public consumer check.

The published POM project URL is `https://rohittp.com/rentile/`, backed by the static documentation committed under `docs/`. Developer and SCM metadata use `rohittp0`, `Rohit T P`, `https://rohittp.com`, and `https://github.com/rohittp0/rentile` respectively.
