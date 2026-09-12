plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("io.github.takahirom.roborazzi")
}

/**
 * Release builds are signed with a dedicated key so every build, local or CI, installs over the
 * last one. Locally the key is read from ~/.aoide/release.jks; in CI it is decoded from a secret
 * into a temp file and passed as -PaoideKeystore=... The debug build type keeps the SDK debug key.
 */
val keystoreFile: File = (project.findProperty("aoideKeystore") as String?)?.let(::File)
    ?: File(System.getProperty("user.home"), ".aoide/release.jks")
val keystorePassword = (project.findProperty("aoideStorePassword") as String?) ?: System.getenv("AOIDE_STORE_PASSWORD") ?: "aoide-release"
val signingAlias = (project.findProperty("aoideKeyAlias") as String?) ?: "aoide"
val signingKeyPassword = (project.findProperty("aoideKeyPassword") as String?) ?: System.getenv("AOIDE_KEY_PASSWORD") ?: keystorePassword

android {
    namespace = "app.aoide"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.aoide"
        minSdk = 26
        targetSdk = 35
        versionCode = 11
        versionName = "0.9.0"
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        if (keystoreFile.exists()) {
            create("release") {
                storeFile = keystoreFile
                storePassword = keystorePassword
                // Distinct names: inside this lambda `keyAlias` and `keyPassword` resolve to the receiver's own (null) properties.
                keyAlias = signingAlias
                keyPassword = signingKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
        debug {
            isDebuggable = true
            applicationIdSuffix = ".debug"
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
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
    lint {
        abortOnError = false
    }
    // Screenshot tests render real Compose on the JVM through Robolectric; no emulator needed.
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all { it.jvmArgs("-Xmx1300m", "-XX:MaxMetaspaceSize=768m", "-XX:ErrorFile=/tmp/aoide_hs_err_%p.log", "-Xss2m"); it.maxHeapSize = "1300m"; it.setForkEvery(1); it.testLogging.showStandardStreams = true }
        }
    }
}

roborazzi {
    outputDir.set(file("build/shots"))
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.navigation:navigation-compose:2.9.0")
    implementation("androidx.palette:palette-ktx:1.0.0")

    implementation("androidx.media3:media3-exoplayer:1.7.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.7.1")
    implementation("androidx.media3:media3-session:1.7.1")

    implementation("io.coil-kt.coil3:coil-compose:3.2.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.2.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("androidx.test:core-ktx:1.6.1")
    testImplementation("org.robolectric:robolectric:4.15.1")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.43.1")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.43.1")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-junit-rule:1.43.1")
    testImplementation(platform("androidx.compose:compose-bom:2025.06.00"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("androidx.media3:media3-test-utils:1.7.1")
    testImplementation("androidx.media3:media3-test-utils-robolectric:1.7.1")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
