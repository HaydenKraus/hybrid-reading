plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.hybridreading"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.hybridreading"
        minSdk = 26
        targetSdk = 35
        versionCode = 14
        versionName = "2.0.0"
    }

    buildTypes {
        release {
            // R8: без минификации релизный APK раздувался до ~16 МБ (неиспользуемый код Compose/icons/OkHttp).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Release подписывается debug-ключом намеренно: приложение личное, в Google Play не публикуется,
            // а собственного keystore нет. Ключ тот же, что у установленной 1.0, поэтому 1.1 ставится поверх
            // обычным `adb install -r` без удаления данных. Для реальной публикации это нужно заменить.
            signingConfig = signingConfigs.getByName("debug")
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
        buildConfig = true
        compose = true
    }

    testOptions {
        // Часть тестов — на Robolectric (хранилища, ресурсы, View), остальные на чистом Kotlin.
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
        unitTests.isIncludeAndroidResources = false
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.02.00"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
}
