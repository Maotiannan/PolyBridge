plugins { alias(libs.plugins.kotlin.jvm) }

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
dependencies {
    implementation(project(":core"))
    // Android supplies its own native runtime; never package desktop binaries in the APK.
    compileOnly(libs.onnxruntime.jvm)
    testImplementation(libs.onnxruntime.jvm)
    testImplementation(libs.junit)
    testImplementation(libs.gson)
}
kotlin.sourceSets.test { kotlin.srcDir(rootProject.file("evaluation/noise/kotlin")) }
tasks.test { systemProperty("polybridge.root", rootProject.projectDir.absolutePath) }
