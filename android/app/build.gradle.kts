plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The app shares its version with the desktop app (bloom/__init__.py).
val bloomVersion = Regex("""__version__\s*=\s*"([^"]+)"""")
    .find(rootDir.resolve("../bloom/__init__.py").readText())!!.groupValues[1]
val (major, minor, patch) = bloomVersion.split(".").map { it.toInt() }

// Release signing comes from the environment (GitHub Actions secrets); without it the APK is unsigned.
val keystore: String? = System.getenv("BLOOM_KEYSTORE")

android {
    namespace = "io.github.omjasoliya.bloom"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.omjasoliya.bloom"
        minSdk = 29
        targetSdk = 35
        versionCode = major * 10000 + minor * 100 + patch
        versionName = bloomVersion
    }

    signingConfigs {
        if (keystore != null) {
            create("release") {
                storeFile = file(keystore)
                storeType = "pkcs12"
                storePassword = System.getenv("BLOOM_KEYSTORE_PASSWORD")
                keyAlias = "bloom"
                keyPassword = System.getenv("BLOOM_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystore != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
    packaging {
        resources.excludes += listOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
    }
}

dependencies {
    implementation("androidx.core:core:1.13.1")
    implementation("androidx.customview:customview:1.1.0")
    implementation("androidx.media3:media3-common:1.4.1")
    implementation("androidx.media3:media3-effect:1.4.1")
    implementation("androidx.media3:media3-transformer:1.4.1")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
}
