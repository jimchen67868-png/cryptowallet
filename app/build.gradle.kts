plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.wallet"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.wallet"
        minSdk = 30
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false // enable only after testing web3j/BouncyCastle keep rules
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    testOptions { unitTests { isReturnDefaultValues = true } }

    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES", "META-INF/LICENSE", "META-INF/LICENSE.md",
                "META-INF/NOTICE", "META-INF/NOTICE.md", "META-INF/INDEX.LIST",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
            )
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.fragment:fragment-ktx:1.8.3")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    implementation("org.web3j:core:4.9.8")      // BIP-39/32, secp256k1, Ethereum RPC + signing
    implementation("com.google.zxing:core:3.5.3") // QR codes

    testImplementation("junit:junit:4.13.2")
    // The Android SDK jar only has stubs for org.json (they return null in unit tests), so use the real implementation.
    testImplementation("org.json:json:20240303")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.2")
}
