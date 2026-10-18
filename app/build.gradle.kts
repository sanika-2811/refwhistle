import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// Firebase is optional: the plugin is only applied once you add app/google-services.json.
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

android {
    namespace = "com.example.refwhistle"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.refwhistle"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Google Maps key: put  MAPS_API_KEY=...  in local.properties (git-ignored). Empty = map shows a notice.
        val lp = Properties()
        val lpFile = rootProject.file("local.properties")
        if (lpFile.exists()) lpFile.inputStream().use { lp.load(it) }
        manifestPlaceholders["MAPS_API_KEY"] = lp.getProperty("MAPS_API_KEY", "")
    }

    // Signed release: copy keystore.properties.example to keystore.properties (git-ignored) and fill it in.
    val ksFile = rootProject.file("keystore.properties")
    if (ksFile.exists()) {
        val ks = Properties()
        ksFile.inputStream().use { ks.load(it) }
        signingConfigs {
            create("release") {
                storeFile = rootProject.file(ks.getProperty("storeFile"))
                storePassword = ks.getProperty("storePassword")
                keyAlias = ks.getProperty("keyAlias")
                keyPassword = ks.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            if (ksFile.exists()) signingConfig = signingConfigs.getByName("release")
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.activity.ktx)
    implementation(libs.appcompat)
    implementation(libs.constraintlayout)
    implementation(libs.material)
    implementation(platform("com.google.firebase:firebase-bom:34.0.0"))
    implementation("com.google.android.gms:play-services-maps:19.2.0")
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    testImplementation(libs.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.ext.junit)
}