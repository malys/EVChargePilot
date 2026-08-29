plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.evsuite.chargepilot"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.evsuite.chargepilot"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    val keystorePath = System.getenv("EV_KEYSTORE")
        ?: (project.findProperty("evsuite.keystore") as String?)
    signingConfigs {
        if (keystorePath != null && file(keystorePath).exists()) {
            create("platform") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("EV_KEYSTORE_PASSWORD")
                    ?: (project.findProperty("evsuite.keystore.password") as String?)
                keyAlias = System.getenv("EV_KEY_ALIAS")
                    ?: (project.findProperty("evsuite.key.alias") as String?)
                    ?: "platform"
                keyPassword = System.getenv("EV_KEY_PASSWORD")
                    ?: (project.findProperty("evsuite.key.password") as String?)
            }
        }
    }

    flavorDimensions += "channel"
    productFlavors {
        create("stable") {
            dimension = "channel"
        }
        create("unstable") {
            dimension = "channel"
            applicationIdSuffix = ".unstable"
            versionNameSuffix = "-unstable"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfigs.findByName("platform")?.let { signingConfig = it }
        }
        debug {
            signingConfigs.findByName("platform")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// Prints the unstable versionName so the unstable workflow can name the APK asset
// numerically comparable ("EVChargePilot-unstable-0.1.0.42.apk"). The pre-release itself
// is always tagged "unstable" and overwritten, so the asset name carries the version.
tasks.register("printUnstableVersion") {
    doLast {
        println("${android.defaultConfig.versionName}.${project.findProperty("unstableBuild") ?: "0"}")
    }
}

dependencies {
    implementation(project(":evhardware"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.material)
    implementation(libs.gson)
    testImplementation(libs.junit)
}
