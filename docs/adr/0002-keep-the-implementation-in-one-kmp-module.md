# Keep the implementation in one KMP module

Rentile will keep its API, compiler, I/O, provider adapter, and Skiko renderer as deep package boundaries inside one `:kmp` Gradle module rather than as separately published subprojects. Standard KMP publication does not fold unpublished project dependencies into the aggregate artifact, so this structure preserves `com.rohittp.rentile:kmp` as the only consumer coordinate while allowing normal dependency resolution when a consumer already uses libraries such as Skiko.
