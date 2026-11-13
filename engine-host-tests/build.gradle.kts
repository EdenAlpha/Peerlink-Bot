plugins { id("com.android.library") }

android {
    namespace = "com.peerlink.enginehosttests"
    compileSdk = 36
    defaultConfig {
        minSdk = 26
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // Compile the actual production host without the ARM64 engine or UI app.
    sourceSets.getByName("main").java.apply {
        srcDir("../app/src/main/java")
        include("com/peerlink/app/bot/EngineAndroidHost.java")
    }
}

dependencies {
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
