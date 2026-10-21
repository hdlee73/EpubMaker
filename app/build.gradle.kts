plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 버전은 GitHub Actions 릴리스 시 -PversionName=1.0.1 -PversionCode=12 로 넘겨 줍니다.
val appVersionName = (project.findProperty("versionName") as String?) ?: "1.0.0"
val appVersionCode = ((project.findProperty("versionCode") as String?) ?: "1").toInt()

// 서명 키는 저장소에 두지 않고 환경변수(GitHub Secrets)로만 받습니다.
val keystorePath: String? = System.getenv("KEYSTORE_FILE")

android {
    namespace = "com.hdlee.epubmaker"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.hdlee.epubmaker"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
    }

    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // PDFBox가 리플렉션/리소스를 쓰므로 축소(minify)는 끕니다.
            isMinifyEnabled = false
            signingConfig =
                if (keystorePath != null) signingConfigs.getByName("release")
                else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES", "META-INF/NOTICE*", "META-INF/LICENSE*")
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")

    // PDF 텍스트 위치 추출 (표 캡처/그림 캡처는 시스템 PdfRenderer 사용)
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    testImplementation("junit:junit:4.13.2")
}
