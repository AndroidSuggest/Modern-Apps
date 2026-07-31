// Host-only JVM tool (NOT shipped in any app). Downloads Thunderbird's public
// holiday .ics files and converts them to the calendar app's bundled JSON
// assets. Run: ./gradlew :tools:holidaygen:run
plugins {
    id("common-conventions-jvm")
    application
}

application {
    mainClass.set("HolidayGenKt")
}

// Emit assets relative to the repo root.
tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}
