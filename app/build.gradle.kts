plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val ariaVersionName = "0.2.41-alpha"
val cloudEndpoint = providers.gradleProperty("ARIA_CLOUD_ENDPOINT").orElse("").get()
val cloudClientToken = providers.gradleProperty("ARIA_CLOUD_CLIENT_TOKEN").orElse("").get()

android {
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { buildConfig = true }

    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt") } }

    defaultConfig {
        externalNativeBuild { cmake { targets += "qwen3_tts_jni" } }
    }

    namespace = "com.kura.aria"
    compileSdk = 35

    // llama.cpp discovers CPU backends by scanning applicationInfo.nativeLibraryDir.
    packaging { jniLibs { useLegacyPackaging = true } }

    val ariaKeystore = System.getenv("ARIA_SIGNING_KEYSTORE")
    val signingBuild = gradle.startParameter.taskNames.any { it.contains("assemble", ignoreCase = true) }
    check(System.getenv("GITHUB_ACTIONS") != "true" || !signingBuild || !ariaKeystore.isNullOrBlank()) {
        "CI requires the verified ARIA signing key for APK builds; refusing an automatically generated debug key."
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
        versionCode = 57
        versionName = ariaVersionName
        resValue("string", "app_name", "ARIA Mobile $ariaVersionName")
        buildConfigField("String", "ARIA_CLOUD_ENDPOINT",
            "\"${cloudEndpoint.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
        buildConfigField("String", "ARIA_CLOUD_CLIENT_TOKEN",
            "\"${cloudClientToken.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
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
