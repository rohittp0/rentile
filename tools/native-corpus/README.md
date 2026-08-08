# Native corpus smoke test

This opt-in test loads Rentile's public style catalog and renders the same
coverage on real Kotlin/Native iOS and Linux ARM64 runtimes:

- 34 styles.
- 55 unique 512 px PNG tiles per style, including z0 and z1 through z22.
- Four 3x3 seam mosaics per style at z6, z12, z16, and z22.
- 1,870 tiles and 136 in-memory mosaics in total.

Normal test runs do not access the network. The corpus runs only when
`RENTILE_NATIVE_CORPUS=1` is present.

Set `RENTILE_NATIVE_CORPUS_Z0_ONLY=1` to render only the full-world tile for
each style. Set `RENTILE_NATIVE_CORPUS_OUTPUT_DIR` to export those z0 PNGs, a
contact sheet, and the sheet's style order.

## Linux ARM64 in Docker

The Kotlin/Native compiler cannot run on a Linux ARM64 host. Cross-link the
ARM64 ELF test binary on a supported macOS ARM64 host, then execute that binary
inside the ARM64 Ubuntu image:

```shell
./gradlew --no-configuration-cache :kmp:linkDebugTestLinuxArm64
docker build --file tools/native-corpus/Dockerfile --tag rentile-native-corpus:local .
docker run --rm \
  --volume "$PWD/kmp/build/bin/linuxArm64/debugTest/test.kexe:/work/test.kexe:ro" \
  rentile-native-corpus:local
```

## iOS Simulator

Set `RENTILE_IOS_SIMULATOR_ID` to a dedicated, booted arm64 simulator and run:

```shell
RENTILE_NATIVE_CORPUS=1 \
RENTILE_IOS_SIMULATOR_ID=<simulator-udid> \
./gradlew --no-configuration-cache :kmp:iosSimulatorArm64Test \
  --tests com.rohittp.rentile.NativeMapCatalogCorpusSmokeTest.rendersCompletePublicCatalogOnNativeTarget
```

The installed iOS simulator runtimes may reject otherwise live provider TLS
chains with `NSURLErrorDomain` `-1200` or `-1202`. Do not disable certificate
validation. For this test only, start the loopback bridge and opt into it:

```shell
/usr/bin/python3 tools/native-corpus/https_bridge.py --port 18765

RENTILE_NATIVE_CORPUS=1 \
RENTILE_NATIVE_HTTPS_BRIDGE_ORIGIN=http://127.0.0.1:18765 \
RENTILE_IOS_SIMULATOR_ID=<simulator-udid> \
./gradlew --no-configuration-cache :kmp:iosSimulatorArm64Test \
  --tests com.rohittp.rentile.NativeMapCatalogCorpusSmokeTest.rendersCompletePublicCatalogOnNativeTarget
```

The bridge binds IPv4 loopback, permits only the exact provider origins needed
by the corpus, validates upstream TLS with macOS system `curl`, suppresses HTTP
request logging, and supplies target URLs to `curl` over stdin. It is not part
of Rentile's published sources or runtime behavior.
