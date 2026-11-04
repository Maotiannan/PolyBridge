plugins { alias(libs.plugins.kotlin.jvm) }

kotlin { jvmToolchain(17) }
dependencies {
    implementation(libs.gson)
    implementation(libs.okhttp)
    testImplementation(libs.junit)
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
