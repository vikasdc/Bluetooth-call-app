plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.btcall.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.btcall.app"
        minSdk = 29  // Android 10 - required for modern BT permissions
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isDebuggable = true
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-Xopt-in=kotlin.RequiresOptIn")
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// Force sqlite-jdbc to a version that ships aarch64 macOS natives.
// room-compiler's DatabaseVerifier loads this at KSP time to validate schema SQL.
// The version bundled with room-compiler 2.6.1 (3.43.0.0) sometimes fails to extract
// its native lib on Apple Silicon. 3.45.1.0 is the first release with a stable
// Mac/aarch64 dylib that reliably extracts to the OS temp directory.
configurations.all {
    resolutionStrategy.force("org.xerial:sqlite-jdbc:3.45.1.0")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)

    // Lifecycle
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.androidx.lifecycle.service)

    // Activity & Fragment
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.fragment.ktx)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)

    // Hilt DI
    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)

    // Room (call history)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    // Explicitly add sqlite-jdbc with aarch64 support to the KSP processor classpath.
    // Without this, KSP may resolve an older version that lacks the Mac/aarch64 native lib.
    ksp("org.xerial:sqlite-jdbc:3.45.1.0")

    // Navigation
    implementation(libs.navigation.fragment.ktx)
    implementation(libs.navigation.ui.ktx)

    // Logging
    implementation(libs.timber)
}

kapt {
    correctErrorTypes = true
}

ksp {
    // Required when using KSP with Room — tells Room where to write exported schemas.
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
}
