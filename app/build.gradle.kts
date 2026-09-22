plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    namespace = "com.kura.aria"
    compileSdk = 35

    // llama.cpp discovers CPU backends by scanning applicationInfo.nativeLibraryDir.
    packaging { jniLibs { useLegacyPackaging = true } }

    defaultConfig {
        applicationId = "com.kura.aria"
        minSdk = 33
        targetSdk = 35
        versionCode = 6
        versionName = "0.2.4-alpha"
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
