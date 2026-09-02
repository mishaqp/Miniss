plugins {
    id("com.android.application")
}

android {
    namespace = "com.openminis.assisthook"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.openminis.assisthook"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    compileOnly("de.robv.android.xposed:api:82")
}
