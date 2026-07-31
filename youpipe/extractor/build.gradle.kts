plugins {
    id("common-conventions-jvm")
    alias(libs.plugins.protobuf)
}

dependencies {
    // nanojson removed — replaced by kotlinx.serialization-json via common-conventions-jvm
    implementation(libs.jsoup)
    implementation(libs.google.jsr305)
    implementation(libs.protobuf.javalite)
    implementation(libs.brotli.dec)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit)
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:${libs.versions.protobufJavalite.get()}"
    }

    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                named("java") {
                    option("lite")
                }
            }
        }
    }
}
