# Fail resolution for untested compatibility-critical dependencies

Rentile will publish strict constraints for compatibility-critical dependencies: native or ABI-bearing modules, generated-code/compiler-runtime pairs, and tightly coupled module families. Each starts pinned to the version proven by Rentile's cross-platform consumer matrix and is widened only after additional versions pass compilation, linkage, packaging, and runtime tests; ordinary pure-Kotlin dependencies remain normal unshaded implementation dependencies so the consumer can resolve compatible versions naturally.

The initial host-aligned baseline is Kotlin 2.3.21, Gradle 9.5.1, AGP 9.3.1, Skiko 0.148.2, Wire 6.4.5, Okio 3.18.1, and coroutines plus serialization JSON 1.11.0. Skiko and the Wire generator/runtime pair begin exact-constrained; the other library versions are the first tested baseline rather than automatically strict public constraints.
