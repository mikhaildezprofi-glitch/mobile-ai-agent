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
    }
}

rootProject.name = "MOBILE_AI_AGENT"
include(":app", ":llama")
project(":llama").projectDir = file("third_party/llama.cpp/examples/llama.android/lib")
