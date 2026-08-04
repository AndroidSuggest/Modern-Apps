import java.util.Locale
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations

plugins {
    id("common-conventions-app")
    // Listing screenshots come from Compose previews (src/screenshotTest), not from an
    // instrumented test on a device. Same `:messages:metadata` task name either way.
    id("common-conventions-preview-metadata")
    id("com.google.devtools.ksp")
}

launcherIcon {
    symbol = "sms"
}

android {
    defaultConfig {
        versionCode = 20260804
        versionName = "v2.6.5"
        applicationId = "com.vayunmathur.messages"
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
    }
    packaging {
        resources {
            // libsignal-client bundles its desktop JNI natives (macOS .dylib,
            // Windows .dll) as Java resources. They can never load on Android and
            // add ~40 MB to the APK — strip them. The Android lib/arm64-v8a/
            // libsignal_jni.so is unaffected.
            excludes += setOf("**/*.dylib", "*.dylib", "**/*.dll", "*.dll")
        }
        jniLibs {
            // Test-only libsignal native (NativeTesting bridge); unused in prod.
            excludes += "**/libsignal_jni_testing.so"
        }
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
}

// ----------------------------------------------------------------
// Protobuf code generation (manual)
// ----------------------------------------------------------------
//
// Both `com.squareup.wire` and `com.google.protobuf` Gradle plugins fail
// to apply to this module because they try to cast AGP 9's
// `ApplicationExtensionImpl` to the legacy `BaseExtension`, which AGP 9
// removed. Until those plugins catch up, we resolve `protoc` from Maven
// Central and invoke it directly. The resulting Java classes (lite
// runtime) are added to the variant's Java sources via the AGP
// `androidComponents` extension below.

val osClassifier: String = run {
    val osName = System.getProperty("os.name").lowercase(Locale.US)
    val arch = System.getProperty("os.arch").lowercase(Locale.US)
    val os = when {
        osName.contains("mac") || osName.contains("darwin") -> "osx"
        osName.contains("win") -> "windows"
        else -> "linux"
    }
    val cpu = when {
        arch.contains("aarch64") || arch.contains("arm64") -> "aarch_64"
        arch.contains("64") -> "x86_64"
        else -> "x86_32"
    }
    "$os-$cpu"
}

val protocConfig: Configuration = configurations.create("protocBinary") {
    isCanBeResolved = true
    isCanBeConsumed = false
}

dependencies {
    "protocBinary"("com.google.protobuf:protoc:${libs.versions.protoc.get()}:$osClassifier@exe")
}

val protoSrcDir = layout.projectDirectory.dir("src/main/proto")
val protoGenDir = layout.buildDirectory.dir("generated/source/proto/java")

// Custom task class so we can inject ExecOperations cleanly (the
// configuration cache rejects capturing the Project at execution time).
abstract class GenerateProtoTask @Inject constructor(
    private val exec: ExecOperations,
) : DefaultTask() {
    @get:InputFile
    abstract val protocBinary: RegularFileProperty

    @get:InputDirectory
    abstract val protoSourceDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun run() {
        val binary = protocBinary.get().asFile
        binary.setExecutable(true)
        val out = outputDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        val srcDir = protoSourceDir.get().asFile
        val protoFiles = srcDir.walkTopDown()
            .filter { it.isFile && it.extension == "proto" }
            .toList()
        if (protoFiles.isEmpty()) return
        exec.exec {
            commandLine = buildList {
                add(binary.absolutePath)
                add("--java_out=${out.absolutePath}")
                add("-I=${srcDir.absolutePath}")
                addAll(protoFiles.map { it.absolutePath })
            }
        }
    }
}

val generateProto = tasks.register<GenerateProtoTask>("generateProto") {
    protocBinary.set(layout.file(protocConfig.elements.map { it.single().asFile }))
    protoSourceDir.set(protoSrcDir)
    outputDir.set(protoGenDir)
}

// Make Kotlin/Java/KSP compilation wait on protoc. KSP runs ahead of
// compileKotlin so it has to be in this list too.
tasks.matching {
    it.name.startsWith("compile") &&
        (it.name.endsWith("Kotlin") || it.name.endsWith("JavaWithJavac")) ||
    it.name.startsWith("ksp")
}.configureEach {
    dependsOn(generateProto)
}

androidComponents {
    onVariants { variant ->
        // Wire proto gen sources + Rust JNI libs into each variant.
        variant.sources.java?.addStaticSourceDirectory(protoGenDir.get().asFile.absolutePath)
        val rustDir = layout.buildDirectory.dir("rustJniLibs").get().asFile.absolutePath
        variant.sources.jniLibs?.addStaticSourceDirectory(rustDir)
    }
}

// Classic Signal protocol v3 (X3DH + Double Ratchet + Sender Keys) for WhatsApp — Rust impl.
rustNativeLib("whatsapp_signal", "whatsapp_signal")

dependencies {
    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(project(":library:room"))

    // Protobuf runtime — full Java variant. The lite runtime drops the
    // reflection API which we need for the PB-Lite encoder.
    implementation(libs.protobuf.java)

    // ZXing core — QR code encoding only (no scanner UI). We render the
    // pairing QR ourselves in a native Compose composable.
    implementation(libs.zxing.core)

    // HTTP + streaming for the gmessages / gvoice bridges (HttpURLConnection based).
    implementation(project(":library:network"))

    // Async avatar loading via library:image (replaces coil).
    implementation(project(":library:image"))

    // CameraX — built-in capture fallback when no system camera app
    // handles ACTION_IMAGE_CAPTURE (see ui/CameraCaptureScreen.kt).
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    // Signal protocol crypto (Double Ratchet, sealed sender, pre-keys, etc.).
    // Also provides certificate verification (ECPublicKey.verifySignature) for WhatsApp
    // Noise handshake cert chain.
    implementation(libs.libsignal.android)
    // Classic Signal X3DH (org.whispersystems) previously provided by :whatsapp-signal
    // shaded jar — now replaced by Rust libwhatsapp_signal.so (see rustNativeLib above +
    // messages/src/main/rust/). X25519 via Rust x25519-dalek (constant-time).

    // kotlinx.serialization — session data persistence
    implementation(libs.kotlinx.serialization.json)
}
