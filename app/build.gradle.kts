plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val ariaVersionName = "0.2.31.2-alpha"

android {
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { buildConfig = true }

    namespace = "com.kura.aria"
    compileSdk = 35

    // llama.cpp discovers CPU backends by scanning applicationInfo.nativeLibraryDir.
    packaging { jniLibs { useLegacyPackaging = true } }

    val ariaKeystore = System.getenv("ARIA_SIGNING_KEYSTORE")
    check(System.getenv("GITHUB_ACTIONS") != "true" || !ariaKeystore.isNullOrBlank()) {
        "CI requires the verified ARIA signing key; refusing an automatically generated debug key."
    }
    if (!ariaKeystore.isNullOrBlank()) {
        signingConfigs.getByName("debug") {
            storeFile = file(ariaKeystore)
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    defaultConfig {
        applicationId = "com.kura.aria"
        minSdk = 33
        targetSdk = 35
        versionCode = 35
        versionName = ariaVersionName
        resValue("string", "app_name", "ARIA Mobile $ariaVersionName")
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation(project(":llama"))
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    kotlinOptions {
        jvmTarget = "17"
    }
}
