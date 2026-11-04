plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}
android {
    namespace = "dev.polybridge.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.polybridge.app"
        minSdk = 31
        targetSdk = 36
        val release = libs.versions.app.get()
        val (major, minor, patch) = release.split('.').map(String::toInt)
        versionName = release
        versionCode = major * 1_000_000 + minor * 1_000 + patch
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true }
    // The sideloaded APK supports all ABIs. Compress native libraries and let Android extract its own ABI.
    packaging { jniLibs.useLegacyPackaging = true }
    testOptions.unitTests {
        isIncludeAndroidResources = true
        all { it.systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2") }
    }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    implementation(project(":core"))
    implementation(project(":provider:qwen"))
    implementation(libs.onnxruntime)
    implementation(platform(libs.compose.bom))
    implementation(libs.activity.compose)
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    androidTestImplementation(libs.android.test.runner)
    androidTestImplementation(libs.android.test.junit)
    androidTestImplementation(project(":evaluation:qwen"))
    androidTestImplementation(libs.gson)
}
