# Publish the KMP implementation as `kmp`

Rentile's Kotlin Multiplatform implementation will be published as the single consumer-facing coordinate `com.rohittp.rentile:kmp`, rather than `com.rohittp:rentile`. The product-scoped group leaves room for other Rentile implementations, while implementation boundaries remain unpublished behind the aggregate artifact so consumers declare only one dependency.
