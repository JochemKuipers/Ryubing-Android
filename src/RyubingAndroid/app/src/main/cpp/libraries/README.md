# Native C++ submodules

Optional native dependencies for the JNI shim live here as git submodules:

- `libadrenotools/` — custom Vulkan (Turnip) driver loading on Adreno GPUs.
  Enable in the build with:

  ```
  -DUSE_ADRENOTOOLS=ON -DADRENOTOOLS_DIR=$PWD/libraries/libadrenotools
  ```

  (wire these into `externalNativeBuild.cmake.arguments` in `app/build.gradle.kts`).

Add with, for example:

```bash
git submodule add https://github.com/bylaws/libadrenotools \
    src/RyubingAndroid/app/src/main/cpp/libraries/libadrenotools
```

The shim builds and runs without these (system Vulkan loader only); they are needed
for custom driver injection on devices where the stock driver underperforms.
