plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 버전: CI에서는 태그(v1.2.3)에서, 로컬에서는 기본값
val versionNameStr: String = (System.getenv("VERSION_NAME") ?: "1.0.0").removePrefix("v")
val versionCodeInt: Int = versionNameStr.split(".").let { p ->
    val major = p.getOrNull(0)?.toIntOrNull() ?: 1
    val minor = p.getOrNull(1)?.toIntOrNull() ?: 0
    val patch = p.getOrNull(2)?.toIntOrNull() ?: 0
    require(minor in 0..999 && patch in 0..999) { "minor/patch must be 0..999: $versionNameStr" }
    major * 1_000_000 + minor * 1_000 + patch
}

android {
    namespace = "kr.phishguard.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "kr.phishguard.app"
        minSdk = 26
        targetSdk = 35
        versionCode = versionCodeInt
        versionName = versionNameStr
        buildConfigField("String", "GITHUB_REPO", "\"${System.getenv("GITHUB_REPOSITORY") ?: "MrDH1004/phishguard-android"}\"")
    }

    // 개인용: 저장소에 포함된 고정 키로 debug/release 모두 서명 → 어디서 빌드해도 같은 서명이라 자동 업데이트가 됨
    signingConfigs {
        create("personal") {
            storeFile = rootProject.file("signing/phishguard-public.jks")
            storePassword = "phishguard"
            keyAlias = "phishguard"
            keyPassword = "phishguard"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("personal")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("personal")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("org.json:json:20240303")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
