// The :libryubing module is not an Android module; it exists solely to publish the
// NativeAOT emulator core (libryubing.so) via `dotnet publish` and drop it into the
// app's jniLibs so it gets packaged into the APK.

import org.gradle.internal.os.OperatingSystem

val repoRoot: File = rootProject.projectDir.parentFile.parentFile // -> /home/jochem/Ryubing-Android
val libRyubingProject = File(repoRoot, "src/LibRyubing/LibRyubing.csproj")
val jniLibsArm64 = File(rootProject.projectDir, "app/src/main/jniLibs/arm64-v8a")
val upstreamDir = File(repoRoot, "upstream/ryubing")
val patchesDir = File(repoRoot, "patches")

val dotnetBin: String = (project.findProperty("ryubing.dotnet.bin") as String?)?.takeIf { it.isNotBlank() } ?: "dotnet"
val dotnetConfig: String = (project.findProperty("ryubing.dotnet.config") as String?)?.takeIf { it.isNotBlank() } ?: "Release"
val ndkToolchainProp: String = (project.findProperty("ryubing.ndk.toolchain") as String?) ?: ""

// Auto-detect the NDK LLVM 'bin' for the host OS from the Android SDK. This keeps the same
// checkout building on both Windows (Android Studio) and WSL/Linux, where the SDK/NDK live at
// different paths — the committed gradle.properties can only hard-code one of them.
fun detectNdkToolchain(): String {
    val hostTag = when {
        OperatingSystem.current().isWindows -> "windows-x86_64"
        OperatingSystem.current().isMacOsX -> "darwin-x86_64"
        else -> "linux-x86_64"
    }
    val sdkRoots = buildList {
        // Parse via Properties so Java escaping (sdk.dir=C\:\\Users\\...) is unescaped correctly;
        // on Windows/Android Studio this is usually the only SDK hint (no ANDROID_HOME env).
        val localProps = File(rootProject.projectDir, "local.properties")
        if (localProps.exists()) {
            val props = java.util.Properties()
            localProps.inputStream().use { props.load(it) }
            props.getProperty("sdk.dir")?.trim()?.takeIf { it.isNotEmpty() }?.let { add(File(it)) }
        }
        System.getenv("ANDROID_HOME")?.let { add(File(it)) }
        System.getenv("ANDROID_SDK_ROOT")?.let { add(File(it)) }
        // Matches .vscode/tasks.json and common WSL installs when env vars are unset.
        add(File("/opt/android-sdk"))
    }
    for (sdk in sdkRoots) {
        val ndkRoot = File(sdk, "ndk")
        val version = ndkRoot.listFiles()?.filter { it.isDirectory }?.maxByOrNull { it.name } ?: continue
        val bin = File(version, "toolchains/llvm/prebuilt/$hostTag/bin")
        if (bin.isDirectory) return bin.absolutePath
    }
    return ""
}

// A configured path wins only if it actually exists on this host; otherwise fall back to
// auto-detection so a stale (e.g. Windows) path in gradle.properties doesn't break WSL builds.
val ndkToolchain: String = ndkToolchainProp.takeIf { it.isNotBlank() && File(it).isDirectory }
    ?: detectNdkToolchain()

// NativeAOT cannot cross-compile a linux-bionic-arm64 image from a Windows host, so on Windows
// (e.g. Android Studio) we consume the libryubing.so produced by a Linux/WSL build rather than
// running `dotnet publish`. Build the .so with `./gradlew :libryubing:publishLibRyubing` in WSL.
val isWindowsHost = OperatingSystem.current().isWindows
val stagedSo = File(jniLibsArm64, "libryubing.so")

// Apply the Android patch queue to the upstream submodule at build time. Upstream is kept
// pristine in git (see docs/patch-candidates.md + patches/README.md); the only sanctioned
// modifications live as patches/NNNN-*.patch and are re-applied here on every build so the
// NativeAOT compile sees the Android-specific source changes without committing to upstream.
val applyUpstreamPatches = tasks.register("applyUpstreamPatches") {
    group = "ryubing"
    description = "Resets upstream/ryubing to its pinned commit and applies the patches/ queue."

    inputs.dir(patchesDir)
    // No declarable file output (it mutates the whole submodule tree), so this always runs;
    // the reset + apply is cheap and keeps the checkout deterministic for the publish below.

    // Only meaningful before a real compile; the Windows host doesn't compile the core.
    onlyIf {
        if (isWindowsHost) {
            logger.lifecycle("Windows host: skipping upstream patch apply (core is built under WSL/Linux).")
        }
        !isWindowsHost
    }

    doLast {
        // Start from the pristine pinned commit, discarding any patches a previous build (or
        // scripts/apply-patches.sh) applied, so re-running is idempotent regardless of state.
        project.exec {
            workingDir = repoRoot
            commandLine("git", "submodule", "update", "--init", "--force", "--checkout", "upstream/ryubing")
        }

        val patches = patchesDir
            .listFiles { f -> f.isFile && f.name.matches(Regex("""^\d.*\.patch$""")) }
            ?.sortedBy { it.name }
            ?: emptyList()

        for (patch in patches) {
            // `git apply` reads git-format-patch files (it ignores the mbox headers) and runs
            // cross-platform without needing bash or git-am, unlike scripts/apply-patches.sh.
            project.exec {
                workingDir = upstreamDir
                commandLine("git", "apply", "--whitespace=nowarn", patch.absolutePath)
            }
        }
        logger.lifecycle("Applied ${patches.size} upstream patch(es) onto pinned upstream/ryubing.")
    }
}

val publishLibRyubing = tasks.register<Exec>("publishLibRyubing") {
    group = "ryubing"
    description = "Publishes libryubing.so (NativeAOT, linux-bionic-arm64) into app jniLibs."

    val artifacts = File(repoRoot, "artifacts/libryubing")

    // Patches are applied to upstream before this task compiles it, so a changed patch queue
    // must force a republish or Gradle would ship a stale libryubing.so.
    dependsOn(applyUpstreamPatches)

    inputs.dir(File(repoRoot, "src/LibRyubing"))
    inputs.dir(patchesDir)
    // The NativeAOT image is compiled from the (patched) upstream sources too, so upstream
    // edits or a submodule pin bump must invalidate this task as well.
    inputs.dir(File(repoRoot, "upstream/ryubing/src"))
    outputs.file(File(jniLibsArm64, "libryubing.so"))

    // On Windows there is no supported NativeAOT cross-compile to linux-bionic; reuse the .so a
    // Linux/WSL build already staged into jniLibs, and fail loudly only if it's actually absent.
    onlyIf {
        if (isWindowsHost) {
            if (!stagedSo.isFile) {
                throw GradleException(
                    "libryubing.so is missing at $stagedSo and cannot be built on a Windows host " +
                        "(NativeAOT has no Windows -> linux-bionic cross-compile). Build it under WSL/Linux: " +
                        "run './gradlew :libryubing:publishLibRyubing' there, then rebuild the app."
                )
            }
            logger.lifecycle("Windows host: reusing prebuilt $stagedSo (built under WSL/Linux); skipping dotnet publish.")
        }
        !isWindowsHost
    }

    doFirst {
        jniLibsArm64.mkdirs()
        if (ndkToolchain.isBlank()) {
            throw GradleException(
                "No usable NDK LLVM toolchain found (checked ryubing.ndk.toolchain, local.properties " +
                    "sdk.dir, ANDROID_HOME, ANDROID_SDK_ROOT, /opt/android-sdk). " +
                    "`dotnet publish -r linux-bionic-arm64` needs the NDK's clang/ld.lld on PATH; " +
                    "set ryubing.ndk.toolchain to the toolchain 'bin' for this OS, or install the NDK " +
                    "via the Android SDK."
            )
        } else {
            logger.lifecycle("Using NDK toolchain: $ndkToolchain")
            val clang = File(ndkToolchain, if (OperatingSystem.current().isWindows) "clang.exe" else "clang")
            if (!clang.isFile) {
                throw GradleException("NDK clang not found at ${clang.path}")
            }
        }
    }

    // Prepend the NDK toolchain to PATH so `clang`/`ld` resolve to the NDK's.
    if (ndkToolchain.isNotBlank()) {
        val sep = File.pathSeparator
        environment("PATH", ndkToolchain + sep + (System.getenv("PATH") ?: ""))
    }

    commandLine(
        dotnetBin, "publish", libRyubingProject.absolutePath,
        "-r", "linux-bionic-arm64",
        "-c", dotnetConfig,
        "-p:DisableUnsupportedError=true",
        "-p:PublishAotUsingRuntimePack=true",
        "-p:StripSymbols=true",
        "-p:DefineConstants=ANDROID",
        "--artifacts-path", artifacts.absolutePath,
    )

    doLast {
        // NativeAOT names the shared library after the assembly (LibRyubing.so),
        // so match case-insensitively before copying to the lowercase libryubing.so
        // that Android's System.loadLibrary/JNA expects.
        val produced = artifacts.walkTopDown()
            .firstOrNull { it.isFile && it.name.equals("libryubing.so", ignoreCase = true) }
            ?: throw GradleException("libryubing.so not found after publish under $artifacts")
        produced.copyTo(File(jniLibsArm64, "libryubing.so"), overwrite = true)
        logger.lifecycle("Copied ${produced.path} -> ${jniLibsArm64}/libryubing.so")
    }
}

// Expose a stable 'assemble' entry point so the app can depend on it.
tasks.register("assemble") {
    dependsOn(publishLibRyubing)
}
