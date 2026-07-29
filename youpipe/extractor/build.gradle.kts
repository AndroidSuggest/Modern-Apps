import org.gradle.api.JavaVersion
import org.gradle.api.tasks.bundling.AbstractArchiveTask

plugins {
    `java-library`
    alias(libs.plugins.protobuf)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

// Protobuf files would uselessly end up in the JAR otherwise, see
// https://github.com/google/protobuf-gradle-plugin/issues/390
tasks.jar {
    exclude("**/*.proto")
    includeEmptyDirs = false
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

dependencies {
    implementation(project(":third_party:nanojson"))
    implementation(libs.jsoup)
    implementation(libs.google.jsr305)
    implementation(libs.protobuf.javalite)
    implementation(libs.rhino)
    implementation(libs.rhino.engine)
    implementation(libs.brotli.dec)

    testImplementation(libs.junit)
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
