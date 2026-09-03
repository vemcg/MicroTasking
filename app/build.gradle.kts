import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val buildInstant = Instant.now()
val defaultVersionCode = buildInstant.epochSecond.toInt()
val defaultVersionName = "0.1.7-0-" + DateTimeFormatter.ofPattern("yyyyMMdd.HHmmss")
    .withZone(ZoneOffset.UTC)
    .format(buildInstant)

android {
    namespace = "com.microtasking.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.microtasking.app"
        minSdk = 26
        targetSdk = 34
        versionCode = providers.gradleProperty("buildVersionCode").map(String::toInt).orElse(defaultVersionCode).get()
        versionName = providers.gradleProperty("buildVersionName").orElse(defaultVersionName).get()
    }

    signingConfigs {
        getByName("debug") {
            // Checked-in debug key (not a secret by design) keeps CI/local builds
            // consistently signed so sideloaded updates install over prior versions.
            storeFile = rootProject.file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
}
