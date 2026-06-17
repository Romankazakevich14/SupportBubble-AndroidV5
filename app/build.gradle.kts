plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("com.google.gms.google-services")       // parses google-services.json at compile time
}

android {
    namespace = "com.supportbubble.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.supportbubble.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Dev domain — used for debug builds and local testing.
        // Both dev and deployed Replit apps are accessible via this same domain
        // (Replit routes all traffic through a shared reverse proxy).
        buildConfigField("String", "SERVER_URL", "\"https://9caedc81-3be9-453b-80d0-d900ddce98cc-00-1pb1ldmh9zrzp.janeway.replit.dev\"")
        buildConfigField("String", "SOCKET_PATH", "\"/api/socket.io/\"")
    }

    buildTypes {
        debug {
            isDebuggable = true
            // Explicitly use the Replit dev/production domain for debug builds.
            buildConfigField("String", "SERVER_URL", "\"https://9caedc81-3be9-453b-80d0-d900ddce98cc-00-1pb1ldmh9zrzp.janeway.replit.dev\"")
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // ⚠️  If you deploy the backend to a custom domain (e.g. your own VPS or
            // a dedicated Replit App URL), replace the URL below before building a
            // release APK.  The current value points to the Replit dev domain which
            // works for testing but may change if the repl is renamed/forked.
            buildConfigField("String", "SERVER_URL", "\"https://9caedc81-3be9-453b-80d0-d900ddce98cc-00-1pb1ldmh9zrzp.janeway.replit.dev\"")
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
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/INDEX.LIST",
                "/META-INF/io.netty.versions.properties",
            )
        }
    }
}

dependencies {
    // ── Compose BOM (manages all Compose versions) ────────────────────────────
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // ── Core ──────────────────────────────────────────────────────────────────
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")

    // ── Lifecycle + ViewModel ─────────────────────────────────────────────────
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    // ── Room ──────────────────────────────────────────────────────────────────
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // ── Socket.io client ─────────────────────────────────────────────────────
    // Exclude org.json — Android SDK provides it natively, avoids duplicate class
    implementation("io.socket:socket.io-client:2.1.0") {
        exclude(group = "org.json", module = "json")
    }

    // ── Networking ────────────────────────────────────────────────────────────
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // ── Coroutines ────────────────────────────────────────────────────────────
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // ── JSON parsing (HTTP responses) ─────────────────────────────────────────
    implementation("com.google.code.gson:gson:2.11.0")

    // ── Saved state (required by ServiceLifecycleOwner for ComposeView in Service)
    implementation("androidx.savedstate:savedstate:1.2.1")

    // ── DataStore (deviceId persistence) ─────────────────────────────────────
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // ── Firebase BOM + FCM ────────────────────────────────────────────────────
    // The BOM pins all Firebase library versions; no individual version needed.
    val firebaseBom = platform("com.google.firebase:firebase-bom:33.7.0")
    implementation(firebaseBom)
    implementation("com.google.firebase:firebase-messaging-ktx")

    // ── Tests ─────────────────────────────────────────────────────────────────
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
