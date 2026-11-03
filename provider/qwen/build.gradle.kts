plugins { alias(libs.plugins.kotlin.jvm) }

kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":core"))
    implementation(libs.gson)
    testImplementation(libs.junit)
}
