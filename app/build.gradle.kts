import java.util.Properties

val localProperties = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

val llmBaseUrl = localProperties.getProperty("LLM_BASE_URL", "https://api.openai.com")
val llmEndpoint = localProperties.getProperty("LLM_ENDPOINT", "")
val llmModel = localProperties.getProperty("LLM_MODEL", "gpt-4o-mini")
val llmModelWeak = localProperties.getProperty("LLM_MODEL_WEAK", "meta-llama/llama-3.2-3b-instruct")
val llmModelMedium = localProperties.getProperty("LLM_MODEL_MEDIUM", "deepseek/deepseek-chat")
val llmModelStrong = localProperties.getProperty("LLM_MODEL_STRONG", "openai/gpt-4o-mini")

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.aiadventchallenge"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.aiadventchallenge"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        buildConfigField("String", "LLM_BASE_URL", "\"$llmBaseUrl\"")
        buildConfigField("String", "LLM_ENDPOINT", "\"$llmEndpoint\"")
        buildConfigField("String", "LLM_MODEL", "\"$llmModel\"")
        buildConfigField("String", "LLM_MODEL_WEAK", "\"$llmModelWeak\"")
        buildConfigField("String", "LLM_MODEL_MEDIUM", "\"$llmModelMedium\"")
        buildConfigField("String", "LLM_MODEL_STRONG", "\"$llmModelStrong\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    debugImplementation(libs.androidx.ui.tooling)
}