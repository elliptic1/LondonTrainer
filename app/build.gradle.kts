import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.github.triplet.play")
}

// Publishing secrets stay outside the repo. Without them, release builds use the debug key.
val home = System.getProperty("user.home")
val uploadKey = Properties().apply {
    file("$home/.android/keystores/londontrainer-play.properties").takeIf { it.exists() }?.reader()?.use { load(it) }
}

android {
    namespace = "app.londontrainer"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.tbse.london"
        minSdk = 31
        targetSdk = 36
        versionCode = 2
        versionName = "1.3"
    }
    buildFeatures { compose = true }
    signingConfigs {
        if (uploadKey.isNotEmpty()) create("upload") {
            storeFile = file(uploadKey.getProperty("storeFile"))
            storePassword = uploadKey.getProperty("storePassword")
            keyAlias = uploadKey.getProperty("keyAlias")
            keyPassword = uploadKey.getProperty("keyPassword")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName(if (uploadKey.isNotEmpty()) "upload" else "debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
}

val playAccount = file("$home/.android/play-credentials/londontrainer-play-service-account.json")

play {
    enabled.set(playAccount.exists())
    serviceAccountCredentials.set(playAccount)
    defaultToAppBundles.set(true)
    track.set(providers.gradleProperty("playTrack").orElse("internal"))
    resolutionStrategy.set(com.github.triplet.gradle.androidpublisher.ResolutionStrategy.AUTO)
}

tasks.withType<Test> { inputs.file("src/main/assets/repertoire.json") }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
