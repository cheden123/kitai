plugins {
    id("com.android.application") version "8.5.2"
    id("org.jetbrains.kotlin.android") version "1.9.24"
    id("org.jetbrains.kotlin.kapt") version "1.9.24"
    id("com.google.dagger.hilt.android") version "2.51.1"
}

android {
    namespace = "ru.kitai.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "ru.kitai.app"
        minSdk = 28
        targetSdk = 34
        versionCode = 2
        versionName = "0.2.0"
    }

    val releaseSigning = listOf(
        "KITAI_KEYSTORE_PATH", "KITAI_KEYSTORE_PASSWORD", "KITAI_KEY_ALIAS", "KITAI_KEY_PASSWORD",
    ).map { providers.environmentVariable(it).orNull }
    if (releaseSigning.all { !it.isNullOrBlank() }) {
        val production = signingConfigs.create("production") {
            storeFile = file(requireNotNull(releaseSigning[0]))
            storePassword = releaseSigning[1]
            keyAlias = releaseSigning[2]
            keyPassword = releaseSigning[3]
        }
        buildTypes.named("release") { signingConfig = production }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

kapt { correctErrorTypes = true }

dependencies {
    implementation("cz.adaptech.tesseract4android:tesseract4android:4.9.0")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.2")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")
    implementation("com.google.dagger:hilt-android:2.51.1")
    kapt("com.google.dagger:hilt-compiler:2.51.1")
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("io.coil-kt:coil-compose:2.6.0")
    testImplementation("junit:junit:4.13.2")
}
