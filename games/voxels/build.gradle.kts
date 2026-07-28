plugins {
    id("common-conventions-app")
    id("common-conventions-metadata")
}

launcherIcon {
    symbol = "view_in_ar"
}

android {
    defaultConfig {
        versionCode = 20260728
        versionName = "v2.6.3"
        applicationId = "com.vayunmathur.games.voxels"
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addStaticSourceDirectory(
            layout.buildDirectory.dir("rustJniLibs").get().asFile.absolutePath
        )
    }
}

// Full Rust + Vulkan engine (ash) with Matcha texture atlas (16 textures → 64x64)
rustNativeLib("voxels_engine", "voxels")

dependencies {
    implementation(project(":sdk:games"))
}
