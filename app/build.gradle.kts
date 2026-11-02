import java.util.Properties

plugins {
    id("com.github.triplet.play")
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Local uploads use the existing private properties file; CI supplies environment variables.
val uploadProperties = Properties().apply {
    file("${System.getProperty("user.home")}/.android/keystores/londontrainer-play.properties")
        .takeIf { it.isFile }?.reader()?.use { load(it) }
}
fun uploadValue(environment: String, property: String): String? =
    providers.environmentVariable(environment).orNull?.takeIf { it.isNotBlank() }
        ?: uploadProperties.getProperty(property)?.takeIf { it.isNotBlank() }

val uploadStore = uploadValue("ANDROID_KEYSTORE_PATH", "storeFile")?.let { file(it) }
val uploadStorePassword = uploadValue("ANDROID_KEYSTORE_PASSWORD", "storePassword")
val uploadAlias = uploadValue("ANDROID_KEY_ALIAS", "keyAlias")
val uploadKeyPassword = uploadValue("ANDROID_KEY_PASSWORD", "keyPassword")
val hasUploadSigning = uploadStore?.isFile == true &&
    listOf(uploadStorePassword, uploadAlias, uploadKeyPassword).all { !it.isNullOrBlank() }
val playCredentials = file(providers.environmentVariable("PLAY_SERVICE_ACCOUNT_FILE")
    .getOrElse("${System.getProperty("user.home")}/.android/play-credentials/londontrainer-play-service-account.json"))

if (providers.gradleProperty("requirePlaySigning").orNull == "true") {
    check(hasUploadSigning) { "Play publishing requires the upload keystore and all signing credentials." }
    check(playCredentials.isFile) { "Play publishing requires the service-account JSON file." }
}

android {
    namespace = "app.londontrainer"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.tbse.london"
        minSdk = 31
        targetSdk = 36
        versionCode = 2
        versionName = "1.1"
    }
    buildFeatures { compose = true }
    signingConfigs {
        if (hasUploadSigning) create("upload") {
            storeFile = uploadStore
            storePassword = uploadStorePassword
            keyAlias = uploadAlias
            keyPassword = uploadKeyPassword
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName(if (hasUploadSigning) "upload" else "debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
}

play {
    enabled.set(playCredentials.isFile)
    serviceAccountCredentials.set(playCredentials)
    defaultToAppBundles.set(true)
    track.set(providers.gradleProperty("playTrack").orElse("internal"))
    releaseStatus.set(com.github.triplet.gradle.androidpublisher.ReleaseStatus.COMPLETED)
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
