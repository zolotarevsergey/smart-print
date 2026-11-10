plugins {
    id("com.android.application")
}

fun prop(name: String, fallback: String = ""): String =
    (project.findProperty(name) as String?)?.trim().takeUnless { it.isNullOrEmpty() } ?: fallback

val keystorePath = prop("print.storeFile")

android {
    namespace = "uk.mwms1488.print"
    compileSdk = 37

    defaultConfig {
        applicationId = "uk.mwms1488.print"
        minSdk = 26
        targetSdk = 37
        versionCode = 4
        versionName = "1.0.3"
        buildConfigField("String", "DEFAULT_SERVER", "\"${prop("print.defaultServer", "192.168.0.88:8631")}\"")
    }

    signingConfigs {
        if (keystorePath.isNotEmpty()) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = prop("print.storePassword")
                keyAlias = prop("print.keyAlias")
                keyPassword = prop("print.keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (keystorePath.isNotEmpty()) signingConfigs.getByName("release")
            else signingConfigs.getByName("debug")
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
