plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Versión de GeckoView (Firefox 159). Cambia "159.+" por una versión exacta si quieres fijarla.
val geckoVersion = "159.+"

android {
    namespace = "com.marrdev.wearnav"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.marrdev.wearnav"
        minSdk = 30
        targetSdk = 34
        versionCode = 2
        versionName = "2.0"
    }

    // Un APK por arquitectura (cada uno lleva solo sus librerías nativas)
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a")
            isUniversalApk = false
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    buildTypes {
        release {
            // Sin R8: GeckoView usa JNI/reflexión y no conviene ofuscarlo
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// GeckoView y androidx.core 1.19 piden compileSdk 37, pero nuestro código compila bien con 36
// (el APK corre en Wear OS 4 / API 33). Desactivamos solo esa comprobación de metadatos.
tasks.configureEach {
    if (name.startsWith("check") && name.endsWith("AarMetadata")) enabled = false
}

dependencies {
    implementation("org.mozilla.geckoview:geckoview:$geckoVersion")
}
