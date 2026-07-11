allprojects {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://xdcobra.github.io/maven") }
    }
}

// Align Android build outputs with Flutter CLI expectations:
// Flutter looks for APKs under <project>/build/app/outputs/flutter-apk/
// (see flutter_tools getApkDirectory). Without this redirect, Gradle writes to
// android/app/build/ and `flutter build apk` reports "failed to produce an .apk".
val newBuildDir: Directory =
    rootProject.layout.buildDirectory
        .dir("../../build")
        .get()
rootProject.layout.buildDirectory.value(newBuildDir)

subprojects {
    val newSubprojectBuildDir: Directory = newBuildDir.dir(project.name)
    project.layout.buildDirectory.value(newSubprojectBuildDir)
}

subprojects {
    project.evaluationDependsOn(":app")
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
