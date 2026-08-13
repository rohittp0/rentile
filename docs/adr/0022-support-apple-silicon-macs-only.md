# Support Apple Silicon Macs only

Rentile publishes `macosArm64` but not `macosX64`, so a macOS consumer resolves the aggregate coordinate only on Apple Silicon. Rentile already drops Intel elsewhere on Apple (`iosArm64` and `iosSimulatorArm64` ship without `iosX64`) and every dependency in the closure publishes both macOS architectures, so the exclusion is a deliberate release-surface choice rather than a dependency limitation: Intel Macs are outside the supported development and rendering hardware, and each additional target is a permanent publication commitment because removing one later breaks resolution for anyone who adopted it.

An Intel Mac consumer therefore gets a hard resolution failure for `com.rohittp.rentile:kmp-macosx64`, not a degraded render. Adding `macosX64` later is a compatible change and needs only the same wiring as any other target; it is intentionally deferred until an Intel Mac consumer exists.
