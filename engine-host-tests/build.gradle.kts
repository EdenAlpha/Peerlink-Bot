plugins { id("com.android.library") }

android {
    namespace = "com.peerlink.enginehosttests"
    compileSdk = 36
    ndkVersion = "27.0.12077973"
    defaultConfig {
        minSdk = 26
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // Compile the actual production host without the ARM64 engine or UI app.
    sourceSets.getByName("main").java.apply {
        srcDir("../app/src/main/java")
        include("com/peerlink/app/bot/EngineAndroidHost.java")
        include("com/peerlink/app/bot/SoftwareGamepadEvents.java")
        include("com/peerlink/app/bot/BotRenderSurface.java")
        include("com/peerlink/app/bot/GameResourceImportProvider.java")
        include("com/peerlink/app/bot/EngineProcessExitReports.java")
        include("com/peerlink/app/bot/EngineNativeCrashTrace.java")
    }
}

dependencies {
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
