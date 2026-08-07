# Rentile owns Android Skiko natives

Rentile's Android publication will be the sole packaging owner of the Skiko native libraries for `arm64-v8a` and `x86_64`, allowing a clean consumer to use only `com.rohittp.rentile:kmp`. Travel Animator currently embeds the same Skiko 0.148.2 binaries manually; that packaging remains untouched during standalone development and is removed only in the separately authorized integration change, where duplicate-runtime absence is verified from the final APK/AAB rather than inferred from dependency resolution.
