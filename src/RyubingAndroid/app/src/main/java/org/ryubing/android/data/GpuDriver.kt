package org.ryubing.android.data

/**
 * A Vulkan driver package the user can select. [SYSTEM_ID] is the built-in Adreno loader;
 * imported Turnip zips are extracted under app storage with a [libraryName] .so for adrenotools.
 */
data class GpuDriver(
    val id: String,
    val displayName: String,
    /** Directory containing the extracted driver files (meta.json + .so). */
    val extractDir: String?,
    /** Soname passed to adrenotools, e.g. libvulkan_freedreno.so */
    val libraryName: String?,
) {
    val isSystem: Boolean get() = id == SYSTEM_ID

    companion object {
        const val SYSTEM_ID = "system"
    }
}
