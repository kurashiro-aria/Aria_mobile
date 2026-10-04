pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Official sherpa-onnx Android artifacts are published through JitPack.
        maven { url = uri("https://jitpack.io") }
    }
}
rootProject.name = "ARIA Mobile"
include(":app")
include(":llama")
project(":llama").projectDir = file("llama.cpp/examples/llama.android/lib")
