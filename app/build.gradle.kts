import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

val vistioVersionName = providers.gradleProperty("appVersionName").orElse("1.7.2").get()
val versionParts = Regex("(0|[1-9][0-9]{0,5})\\.(0|[1-9][0-9]?)\\.(0|[1-9][0-9]?)")
    .matchEntire(vistioVersionName)?.groupValues?.drop(1)?.map(String::toLong)
    ?: error("appVersionName debe usar el formato MAYOR.MENOR.PARCHE.")
val vistioVersionCode = (versionParts[0] * 10_000 + versionParts[1] * 100 + versionParts[2])
    .also { require(it in 1..2_100_000_000) { "versionCode fuera del rango de Android." } }.toInt()

val updateProperties = Properties().apply {
    val local = rootProject.file("local.properties")
    if (local.isFile) local.inputStream().use(::load)
}

fun updateValue(key: String): String = updateProperties.getProperty(key, "").trim()
    .replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "\\r").replace("\n", "\\n")
    .let { "\"" + it + "\"" }

android {
    namespace = "com.quirogaj.vistio"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.quirogaj.vistio"
        minSdk = 26
        targetSdk = 37
        versionCode = vistioVersionCode
        versionName = vistioVersionName

        buildConfigField("String", "UPDATE_GITHUB_OWNER", updateValue("VISTIO_UPDATE_GITHUB_OWNER"))
        buildConfigField("String", "UPDATE_GITHUB_REPOSITORY", updateValue("VISTIO_UPDATE_GITHUB_REPOSITORY"))
        buildConfigField("String", "UPDATE_GITHUB_TOKEN", updateValue("VISTIO_UPDATE_GITHUB_TOKEN"))

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation("com.google.zxing:core:3.5.3")
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.tv.material)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.rtsp)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
