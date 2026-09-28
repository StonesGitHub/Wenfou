plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.wenfou.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.wenfou.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "0.3.0"
    }

    testOptions { unitTests.isIncludeAndroidResources = true }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.04.01"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.75.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.75.0")
}

tasks.withType<Test>().configureEach {
    systemProperty("roborazzi.test.record", "true")
    maxHeapSize = "3g"
    testLogging { events("started", "passed", "failed", "skipped"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
