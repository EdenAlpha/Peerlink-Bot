import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.JavaExec

// app/build.gradle.kts — PeerLink
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    // Compose Compiler (Kotlin 2.0+). Replaces the old composeOptions { kotlinCompilerExtensionVersion }.
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.peerlink.app"
    compileSdk = 36
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "com.peerlink.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 16
        versionName = "5.0.12-bot-test"
        // User-facing name, kept separate from applicationId so existing installs keep their data.
        resValue("string", "app_displayed_name", "PeerLink")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // arm64 only: the shipped APK carries one .so per ABI (the OCR
        // pipeline alone is ~40 MB across four ABIs). Phones from the last
        // years are arm64-v8a; dropping the other three cuts the download
        // users keep re-paying for on every fix (user complaint 2026-10-16).
        externalNativeBuild {
            cmake { arguments += listOf("-DANDROID_STL=c++_shared") }
        }
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // The shipped APK is this one: R8 and the resource shrinker take
            // it from the debug build's 50 MB to the lean size.  It is signed
            // with the same key the debug builds use, so the app installs
            // and updates exactly as it always has.
            signingConfig = signingConfigs.getByName("debug")
        }
        debug {
            isDebuggable = true
            if (providers.gradleProperty("engineHostTestBuild").orNull == "true") {
                applicationIdSuffix = ".enginetest"
                versionNameSuffix = "-engine-test"
                resValue("string", "app_displayed_name", "PeerLink Test")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("engineBridge/assets"))

    externalNativeBuild {
        cmake {
            path = file("src/main/jni/CMakeLists.txt")
        }
    }

    // Single, de-duplicated packaging block (the old file declared this twice).
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// The bridge extends the INSTALLED game's Activity. Compile-only API signatures
// must never enter the APK's main dex (or replace any original game class).
val bridgeClasses = layout.buildDirectory.dir("engineBridge/classes")
val bridgeJar = tasks.register<Jar>("gameHostBridgeJar") {
    dependsOn("compileGameHostBridge")
    from(bridgeClasses) { include("com/peerlink/engine/**") }
    archiveFileName.set("game-host-bridge.jar")
    destinationDirectory.set(layout.buildDirectory.dir("engineBridge"))
}
tasks.register<JavaCompile>("compileGameHostBridge") {
    source(fileTree("src/main/engine-bridge"), fileTree("src/main/engine-bridge-api"))
    classpath = files(android.bootClasspath)
    destinationDirectory.set(bridgeClasses)
    sourceCompatibility = "17"
    targetCompatibility = "17"
}
val dexGameHostBridge = tasks.register<JavaExec>("dexGameHostBridge") {
    dependsOn(bridgeJar)
    val destination = layout.buildDirectory.file("engineBridge/assets/game-host/bridge.zip")
    inputs.file(bridgeJar.flatMap { it.archiveFile })
    outputs.file(destination)
    classpath = files("${android.sdkDirectory}/build-tools/35.0.0/lib/d8.jar")
    mainClass.set("com.android.tools.r8.D8")
    doFirst {
        destination.get().asFile.parentFile.mkdirs()
        args = listOf("--min-api", "26", "--lib", android.bootClasspath.first().absolutePath,
            "--output", destination.get().asFile.absolutePath,
            bridgeJar.get().archiveFile.get().asFile.absolutePath)
    }
}
tasks.named("preBuild").configure { dependsOn(dexGameHostBridge) }

// Modern compiler config. `kotlinOptions {}` is an ERROR in Kotlin 2.3 — this is the replacement.
// The global opt-ins save annotating every file; M3 Expressive lives behind
// ExperimentalMaterial3ExpressiveApi until Material 1.5.0 goes stable.
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.addAll(
            listOf(
                "-opt-in=kotlin.ExperimentalTypeInference",
                "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
                "-opt-in=androidx.compose.animation.ExperimentalAnimationApi",
                "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
                "-opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
                "-opt-in=androidx.compose.ui.ExperimentalComposeUiApi"
            )
        )
    }
}

dependencies {
    // ── AndroidX core ──
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.10.1")

    // ── Material (Views) — still needed by the activity XML themes ──
    implementation("com.google.android.material:material:1.12.0")

    // ── Coroutines ──
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // ───────────────────────────────────────────────────────────────────
    // JETPACK COMPOSE — BOM-managed.  Compose 1.11 + Material 3 Expressive.
    // The BOM pins every compose-* artifact to one tested set, so they stay versionless below.
    // ───────────────────────────────────────────────────────────────────
    val composeBom = platform("androidx.compose:compose-bom:2026.05.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")

    // ── Lifecycle (incl. collectAsStateWithLifecycle) ──
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    // ── Prime Mode (ADB engine). libadb-android pulls bcprov-jdk15to18:1.81 transitively. ──
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1")
    implementation("org.conscrypt:conscrypt-android:2.5.3")
    implementation("org.bouncycastle:bcpkix-jdk15to18:1.81")

    // ── Match result OCR — on-device only; used on short Prime screenshots after full time. ──
    implementation("com.google.mlkit:text-recognition:16.0.1")

    // ── Testing ──
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
