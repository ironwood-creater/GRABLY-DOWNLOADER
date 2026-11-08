plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// Download server address. The live Grably site on Vercel unless -Pgrably.apiBaseUrl=... says otherwise
// (CI passes the stand-in server for the device test).
val defaultApiBaseUrl = "https://grably-downloader-server.vercel.app"
val apiBaseUrl: String = (findProperty("grably.apiBaseUrl") as String?).orEmpty().trim().trimEnd('/')
    .ifEmpty { defaultApiBaseUrl }

// Google Play needs a higher versionCode for every upload: CI passes the run number.
val appVersionCode = (findProperty("grably.versionCode") as String?)?.toIntOrNull() ?: 1
val appVersionName = (findProperty("grably.versionName") as String?)?.ifBlank { null } ?: "1.1.0"

// Upload key for Google Play, from environment variables (GitHub secrets in CI) or gradle properties.
// Without it, release builds are unsigned and have to be signed before uploading.
fun secret(name: String): String? =
    (System.getenv(name) ?: findProperty(name.lowercase().replace('_', '.')) as String?)?.ifBlank { null }
val keystorePath = secret("GRABLY_KEYSTORE_FILE")
val keystorePassword = secret("GRABLY_KEYSTORE_PASSWORD")
val keyAliasName = secret("GRABLY_KEY_ALIAS")
val keyPasswordValue = secret("GRABLY_KEY_PASSWORD") ?: keystorePassword
val canSignRelease = keystorePath != null && keystorePassword != null && keyAliasName != null && file(keystorePath).isFile

android {
    namespace = "com.grably.downloader"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.grably.downloader"
        minSdk = 29
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
        buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrl\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (canSignRelease) {
            create("upload") {
                storeFile = file(keystorePath!!)
                storePassword = keystorePassword
                keyAlias = keyAliasName
                keyPassword = keyPasswordValue
            }
        }
    }

    buildTypes {
        release {
            if (canSignRelease) signingConfig = signingConfigs.getByName("upload")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    debugImplementation(libs.compose.ui.test.manifest)
}
