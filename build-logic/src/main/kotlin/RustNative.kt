import org.gradle.api.Project
import org.gradle.api.tasks.Exec
import org.gradle.internal.os.OperatingSystem
import org.gradle.kotlin.dsl.register
import java.util.Properties

/**
 * Registers the per-ABI cargo cross-compile of a Rust cdylib (arm64-v8a only)
 * into `build/rustJniLibs`, wired ahead of `preBuild`. This consolidates the
 * ~80 lines of NDK-toolchain + cargo + reproducible-build boilerplate that used
 * to be copy-pasted across every native module (pdf, camera, weather, passwords,
 * e2ee-p2p). The caller still registers `build/rustJniLibs` as a jniLibs source
 * dir in its own `android { }` block.
 *
 * Prereq: `rustup target add aarch64-linux-android`.
 *
 * @param crate      cargo package lib name → produces `lib<crate>.so`
 * @param remapLabel `--remap-path-prefix` label baked in for reproducible builds
 *                   (defaults to [crate]).
 */
fun Project.rustNativeLib(crate: String, remapLabel: String = crate) {
    val ndkVersionForRust = "29.0.14206865"
    val androidApiLevel = 31

    fun resolveSdkDir(): String =
        System.getenv("ANDROID_HOME")
            ?: System.getenv("ANDROID_SDK_ROOT")
            ?: rootProject.file("local.properties").takeIf { it.exists() }?.let { f ->
                Properties().apply { f.inputStream().use { load(it) } }.getProperty("sdk.dir")
            }
            ?: error("Android SDK not found (set ANDROID_HOME or sdk.dir in local.properties)")

    val cargoBin = "${System.getProperty("user.home")}/.cargo/bin"
    val ndkRoot = "${resolveSdkDir()}/ndk/$ndkVersionForRust"
    val hostTag = when {
        OperatingSystem.current().isMacOsX -> "darwin-x86_64"
        OperatingSystem.current().isLinux -> "linux-x86_64"
        OperatingSystem.current().isWindows -> "windows-x86_64"
        else -> error("Unsupported host OS for NDK toolchain")
    }
    val ndkBin = "$ndkRoot/toolchains/llvm/prebuilt/$hostTag/bin"
    val ndkSysroot = "$ndkRoot/toolchains/llvm/prebuilt/$hostTag/sysroot"

    // arm64-v8a only — all physical devices + the Apple-Silicon emulator are arm64.
    val rustAbis = listOf("arm64-v8a" to "aarch64-linux-android")

    val perAbi = rustAbis.map { (abiDir, triple) ->
        tasks.register<Exec>("cargoBuild_${abiDir.replace('-', '_')}") {
            description = "Cross-compiles lib$crate for $abiDir."
            workingDir = file("src/main/rust")

            val clang = "$ndkBin/$triple$androidApiLevel-clang"
            val linkerVar = "CARGO_TARGET_${triple.uppercase().replace('-', '_')}_LINKER"
            val soOut = file("src/main/rust/target/$triple/release/lib$crate.so")
            val destSo = layout.buildDirectory.file("rustJniLibs/$abiDir/lib$crate.so").get().asFile

            inputs.dir("src/main/rust/src")
            inputs.file("src/main/rust/Cargo.toml")
            inputs.file("src/main/rust/Cargo.lock")
            inputs.file("src/main/rust/rust-toolchain.toml")
            outputs.file(destSo)

            val cargoHome = System.getenv("CARGO_HOME") ?: "${System.getProperty("user.home")}/.cargo"
            val rustSrc = file("src/main/rust").absolutePath

            environment("PATH", "$cargoBin:${System.getenv("PATH")}")
            // The per-API NDK clang wrapper bakes in --target and the sysroot.
            environment("CC", clang)
            environment("AR", "$ndkBin/llvm-ar")
            environment("SYSROOT", ndkSysroot)
            environment(linkerVar, clang)
            environment("HOST_CC", "/usr/bin/clang")
            // bindgen (used by some crates, e.g. weather's om-file-format-sys) parses
            // C headers with libclang — point it at the NDK target + sysroot. Harmless
            // and ignored by crates that don't use bindgen.
            environment(
                "BINDGEN_EXTRA_CLANG_ARGS",
                "--target=$triple$androidApiLevel --sysroot=$ndkSysroot",
            )
            // Reproducible builds: remap $HOME-specific paths (cargo registry + crate
            // dir) to fixed constants so different machines produce identical .so bytes.
            environment(
                "RUSTFLAGS",
                "--remap-path-prefix=$cargoHome=/cargo --remap-path-prefix=$rustSrc=/$remapLabel",
            )
            environment("CFLAGS", "-ffile-prefix-map=$cargoHome=/cargo -ffile-prefix-map=$rustSrc=/$remapLabel -Wdate-time -Werror=date-time")
            environment("CXXFLAGS", "-ffile-prefix-map=$cargoHome=/cargo -ffile-prefix-map=$rustSrc=/$remapLabel -Wdate-time -Werror=date-time")
            environment("CPPFLAGS", "-ffile-prefix-map=$cargoHome=/cargo -ffile-prefix-map=$rustSrc=/$remapLabel -Wdate-time -Werror=date-time")
            environment("ZERO_AR_DATE", "1")
            // Reproducible builds: respect SOURCE_DATE_EPOCH if set (exported by release.sh / CI)
            // https://reproducible-builds.org/docs/source-date-epoch/
            System.getenv("SOURCE_DATE_EPOCH")?.takeIf { it.isNotBlank() }?.let {
                environment("SOURCE_DATE_EPOCH", it)
            }
            environment("CARGO_INCREMENTAL", "0")

            commandLine("$cargoBin/cargo", "build", "--locked", "--release", "--target", triple)

            doLast {
                destSo.parentFile.mkdirs()
                soOut.copyTo(destSo, overwrite = true)
            }
        }
    }

    val cargoNdkBuild = tasks.register("cargoNdkBuild") {
        description = "Builds lib$crate.so for all Android ABIs."
        dependsOn(perAbi)
    }
    tasks.matching { it.name == "preBuild" }.configureEach {
        dependsOn(cargoNdkBuild)
    }
}
