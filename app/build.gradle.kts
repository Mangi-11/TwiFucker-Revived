plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "mangi.twifuckerx"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "mangi.twifuckerx"
        minSdk = 27
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    packaging {
        resources {
            merges += "META-INF/xposed/*"
            excludes += "**"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    compileOnly(libs.libxposed.api)
}
