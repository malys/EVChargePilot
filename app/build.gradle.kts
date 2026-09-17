plugins {
    alias(libs.plugins.android.application)
}

// The rolling unstable build number, passed by the unstable workflow as its run number.
// It has to reach BOTH the APK's own versionName and the published asset name, because the
// update check compares one against the other: a build whose versionName stops at "0.2.0"
// reads as older than the very release it was cut from, so every launch offers the update
// that is already installed and installing it changes nothing.
val unstableBuild = (project.findProperty("unstableBuild") ?: "0").toString()

android {
    namespace = "com.evsuite.chargepilot"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.evsuite.chargepilot"
        minSdk = 28
        targetSdk = 34
        versionCode = 2
        versionName = "0.2.0"
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
            // Same shape as the asset name: "0.2.0.85-unstable" against a published
            // "EVChargePilot-unstable-0.2.0.85.apk". A local build has no run number and
            // becomes 0, which is older than any release — which is what a local build is.
            versionNameSuffix = ".$unstableBuild-unstable"
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

// An unsigned release APK is indistinguishable from a signed one in the build output and
// installs on nothing. The keystore stays optional so that debug builds, unit tests and lint
// all work on a machine that has none; only a release assembly insists on it, and
// `-Pevsuite.allowUnsignedRelease=true` is the deliberate way to say a bare APK is wanted.
if (android.signingConfigs.findByName("platform") == null) {
    val allowUnsigned = project.findProperty("evsuite.allowUnsignedRelease") == "true"
    tasks.matching { it.name.startsWith("assemble") && it.name.endsWith("Release") }
        .configureEach {
            doFirst {
                if (!allowUnsigned) throw GradleException(
                    "No signing keystore was found, so this release APK would be unsigned. " +
                        "Set EV_KEYSTORE / EV_KEYSTORE_PASSWORD / EV_KEY_ALIAS / EV_KEY_PASSWORD, " +
                        "or the matching evsuite.* Gradle properties. " +
                        "Pass -Pevsuite.allowUnsignedRelease=true to build one anyway."
                )
            }
        }
}

// Prints the unstable versionName so the unstable workflow can name the APK asset
// numerically comparable ("EVChargePilot-unstable-0.2.0.42.apk"). The pre-release itself
// is always tagged "unstable" and overwritten, so the asset name carries the version.
tasks.register("printUnstableVersion") {
    doLast {
        println("${android.defaultConfig.versionName}.$unstableBuild")
    }
}

// Some unit tests read files off disk: res/values*/strings.xml, because the app's own resources
// are not on a plain JVM test's classpath and nothing here runs Robolectric, and the seeded
// fixtures, which live beside the mise task that pushes them. Gradle cannot see those inputs by
// itself, so a changed string or fixture would leave the tests UP-TO-DATE and the check would
// pass without running them. Declared here rather than worked around in the tests.
tasks.withType<Test>().configureEach {
    inputs.dir(layout.projectDirectory.dir("src/main/res"))
        .withPropertyName("appResources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(rootProject.layout.projectDirectory.files(
        "evchargepilot-trips.json",
        "evchargepilot-battery-ledger.json",
    )).withPropertyName("seededFixtures").withPathSensitivity(PathSensitivity.RELATIVE)
}

dependencies {
    implementation(project(":evhardware"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.material)
    implementation(libs.gson)
    implementation(libs.androidx.security.crypto)
    implementation(libs.jsr305)
    // Encodes the About QR locally; the same version EVTasker ships.
    implementation(libs.zxing.core)
    testImplementation(libs.junit)
}
