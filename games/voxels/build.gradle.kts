plugins {
    id("common-conventions-app")
    id("common-conventions-metadata")
}

launcherIcon {
    symbol = "view_in_ar"
}

android {
    defaultConfig {
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
