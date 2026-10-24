plugins {
    id("com.android.application")
}

import java.util.Properties

// The release key lives beside this project in keystore.properties +
// release.keystore, both gitignored. Every published APK must be signed with
// it: Android refuses an update signed with any other key. A checkout without
// it builds release with the debug key instead, which is fine for testing and
// never for the website.
val releaseKey = rootProject.file("keystore.properties").takeIf { it.exists() }?.let { file ->
    Properties().apply { file.inputStream().use(::load) }
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
        versionCode = 2
        versionName = "0.1.1"
    }

    signingConfigs {
        if (releaseKey != null) {
            create("release") {
                storeFile = rootProject.file(releaseKey.getProperty("storeFile"))
                storePassword = releaseKey.getProperty("storePassword")
                keyAlias = releaseKey.getProperty("keyAlias")
                keyPassword = releaseKey.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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
