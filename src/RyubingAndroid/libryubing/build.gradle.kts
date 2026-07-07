// The :libryubing module is not an Android module; it exists solely to publish the
// NativeAOT emulator core (libryubing.so) via `dotnet publish` and drop it into the
// app's jniLibs so it gets packaged into the APK.

import org.gradle.internal.os.OperatingSystem

val repoRoot: File = rootProject.projectDir.parentFile.parentFile // -> /home/jochem/Ryubing-Android
val libRyubingProject = File(repoRoot, "src/LibRyubing/LibRyubing.csproj")
val jniLibsArm64 = File(rootProject.projectDir, "app/src/main/jniLibs/arm64-v8a")

val dotnetBin: String = (project.findProperty("ryubing.dotnet.bin") as String?)?.takeIf { it.isNotBlank() } ?: "dotnet"
val dotnetConfig: String = (project.findProperty("ryubing.dotnet.config") as String?)?.takeIf { it.isNotBlank() } ?: "Release"
val ndkToolchain: String = (project.findProperty("ryubing.ndk.toolchain") as String?) ?: ""

val publishLibRyubing = tasks.register<Exec>("publishLibRyubing") {
    group = "ryubing"
    description = "Publishes libryubing.so (NativeAOT, linux-bionic-arm64) into app jniLibs."

    val artifacts = File(repoRoot, "artifacts/libryubing")

    inputs.dir(File(repoRoot, "src/LibRyubing"))
    outputs.file(File(jniLibsArm64, "libryubing.so"))

    doFirst {
        jniLibsArm64.mkdirs()
        if (ndkToolchain.isBlank()) {
            logger.warn(
                "ryubing.ndk.toolchain is not set. `dotnet publish -r linux-bionic-arm64` " +
                    "needs the NDK LLVM toolchain 'bin' directory on PATH. Set it in gradle.properties."
            )
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
        val produced = artifacts.walkTopDown().firstOrNull { it.name == "libryubing.so" }
            ?: throw GradleException("libryubing.so not found after publish under $artifacts")
        produced.copyTo(File(jniLibsArm64, "libryubing.so"), overwrite = true)
        logger.lifecycle("Copied ${produced.path} -> ${jniLibsArm64}/libryubing.so")
    }
}

// Expose a stable 'assemble' entry point so the app can depend on it.
tasks.register("assemble") {
    dependsOn(publishLibRyubing)
}
