plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Release signing is configured only when a keystore is supplied (via -P flags or
// ~/.gradle/gradle.properties). Without it the release build still succeeds, just unsigned --
// so contributors don't need a keystore to compile. The keystore and its passwords must
// never be committed.
val keystorePath = (project.findProperty("watchwireKeystore") as String?)?.takeIf { it.isNotBlank() }

android {
    namespace = "com.watchwire.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.watchwire.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        // Overridable at build time: -PbackendWsUrl=wss://your-host
        // This is only the *default* -- the pairing screen lets the user change it at
        // runtime, which is how a prebuilt APK is pointed at an arbitrary backend.
        buildConfigField("String", "DEFAULT_WS_URL", "\"${project.findProperty("backendWsUrl") ?: "ws://10.0.2.2:8000"}\"")
    }

    // OpenCV's native library dominates the APK size, so build one slim APK per ABI plus a
    // universal fallback. The per-ABI APKs are what you'd hand to a known device; the
    // universal one installs anywhere (real phones and emulators) at roughly double the size.
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86_64")
            isUniversalApk = true
        }
    }

    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = project.findProperty("watchwireKeystorePassword") as String?
                keyAlias = project.findProperty("watchwireKeyAlias") as String?
                keyPassword = project.findProperty("watchwireKeyPassword") as String?
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystorePath != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")

    implementation(platform("androidx.compose:compose-bom:2024.09.02"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    val cameraxVersion = "1.4.1"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")

    implementation("org.opencv:opencv:4.10.0")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")

    testImplementation("junit:junit:4.13.2")
}
