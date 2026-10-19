plugins {
    id("com.android.application")
}

// Pinned on purpose: GeckoView's API moves between majors, and the
// extension/prompt delegates this app implements are written against this one.
val geckoviewVersion = "158.0.20261007201254"

android {
    namespace = "online.borkbrowser.mobile"
    compileSdk {
        version = release(37) {
            minorApiLevel = 2
        }
    }

    defaultConfig {
        applicationId = "online.borkbrowser.mobile"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Self-signed like the desktop builds: release uses the debug key
            // until a real signing config exists.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        buildConfig = true
    }
    packaging {
        jniLibs { useLegacyPackaging = true }
    }
    sourceSets {
        getByName("main") {
            // The add-ons are shared with the iOS app: shared/extensions/
            // lands in the APK as assets/extensions/.
            assets.srcDir("../../shared")
        }
    }
    androidResources {
        // The extensions ship as directories and .xpi files; aapt must not
        // compress or drop any of them.
        noCompress += listOf("xpi", "js", "css", "html", "json", "png")
        ignoreAssetsPattern = "!.svn:!.git:!.ds_store:!*.scc:!CVS:!thumbs.db:!picasa.ini:!*~"
    }
}

dependencies {
    implementation("org.mozilla.geckoview:geckoview-arm64-v8a:$geckoviewVersion")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("com.google.android.material:material:1.12.0")
}
