# Native C++ dependencies

adrenotools is fetched automatically at build time (see `CMakeLists.txt`) to inject
custom Turnip Vulkan drivers on Adreno GPUs.

The JNI shim calls `adrenotools_open_libvulkan` when a persisted driver is selected;
`libryubing.so` receives the resulting handle via `ryubing_set_vulkan_driver`.

**Requirement:** `useLegacyPackaging = true` for jniLibs (already set in
`app/build.gradle.kts`) — adrenotools hooks must live in `nativeLibraryDir`.

Optional submodule checkout (if you prefer a local tree over FetchContent):

```bash
git submodule add https://github.com/bylaws/libadrenotools \
    src/RyubingAndroid/app/src/main/cpp/libraries/libadrenotools
```
