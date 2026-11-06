import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val secrets = Properties().apply {
    val f = file(System.getenv("VIDGRAB_SECRETS") ?: "/home/box/secrets/vidgrab.env")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun secret(k: String) = secrets.getProperty(k) ?: System.getenv(k) ?: ""

android {
    testOptions { unitTests.all { it.testLogging { showStandardStreams = true; events("passed","failed") } } }
    namespace = "com.mostafa.vidgrab"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.mostafa.vidgrab"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "2.1"
        ndk { abiFilters += listOf("arm64-v8a") }
    }
    signingConfigs {
        create("release") {
            storeFile = file("/home/box/secrets/vidgrab-release.jks")
            storePassword = secret("VIDGRAB_KEYSTORE_PASSWORD")
            keyAlias = "vidgrab"
            keyPassword = secret("VIDGRAB_KEYSTORE_PASSWORD")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }
    // youtubedl-android extracts python/ffmpeg from native libs at runtime
    packaging { jniLibs { useLegacyPackaging = true } }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    val ytdl = "0.18.1"
    implementation("io.github.junkfood02.youtubedl-android:library:$ytdl")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:$ytdl")
    implementation("io.github.junkfood02.youtubedl-android:aria2c:$ytdl")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
