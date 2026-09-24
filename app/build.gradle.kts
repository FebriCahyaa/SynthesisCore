plugins {
    alias(libs.plugins.android.application)
}

// Release builds pass the tag version (e.g. VERSION_NAME=2.1.0 for tag v2.1.0).
val appVersionName: String = providers.environmentVariable("VERSION_NAME").orNull
    ?.also { require(Regex("""\d+\.\d+\.\d+([-.][0-9A-Za-z.]+)?""").matches(it)) { "Invalid VERSION_NAME '$it'" } }
    ?: "2.1.0"

fun gitRevisionCount(): Int {
    return try {
        val process = ProcessBuilder("git", "rev-list", "--count", "HEAD")
            .redirectErrorStream(true)
            .start()
        process.inputStream.bufferedReader().readText().trim().toInt()
    } catch (_: Exception) {
        System.err.println("ERROR: Failed to get git revision count")
        1
    }
}

android {
    namespace = "com.febricahyaa.synthesiscore"
    // Android 17 (API 37)
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.febricahyaa.synthesiscore"
        minSdk = 28
        targetSdk = 37
        versionCode = gitRevisionCount()
        versionName = appVersionName
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isCrunchPngs = true

            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }

        debug {
            isMinifyEnabled = false
            isDebuggable = true
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.hiddenapibypass)
    testImplementation(libs.junit)
}
