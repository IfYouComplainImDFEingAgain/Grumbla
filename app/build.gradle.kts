import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "app.notmumla"
    compileSdk = 35

    // CI passes the tag's version (-PreleaseVersion=0.5.0); local builds fall back to the values
    // below. versionCode is derived so it rises with every tag: 0.5.0 -> 500, 1.2.3 -> 10203.
    val releaseVersion = (findProperty("releaseVersion") as String?)?.also {
        require(Regex("""\d+\.\d{1,2}\.\d{1,2}""").matches(it)) {
            "releaseVersion must be MAJOR.MINOR.PATCH with MINOR/PATCH < 100, got '$it'"
        }
    }

    defaultConfig {
        applicationId = "app.notmumla"
        minSdk = 31
        targetSdk = 35
        versionCode = releaseVersion
            ?.split('.')?.map(String::toInt)?.let { (ma, mi, pa) -> ma * 10000 + mi * 100 + pa }
            ?: 5
        versionName = releaseVersion ?: "0.4.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Release signing reads a gitignored keystore.properties at the repo root; without it the
    // release build is left unsigned so debug builds and fresh checkouts still work.
    val keystoreProps = rootProject.file("keystore.properties").takeIf { it.exists() }
        ?.let { f -> Properties().apply { f.inputStream().use(::load) } }
    signingConfigs {
        if (keystoreProps != null) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        // Store native libs uncompressed + page-aligned (incl. debug) so they load on 16 KB-page
        // devices via mmap. Otherwise debug builds compress them and 16 KB loading fails.
        jniLibs {
            useLegacyPackaging = false
        }
        resources {
            // BouncyCastle ships duplicate multi-release metadata across its jars.
            excludes += "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(project(":core-protocol"))
    implementation(project(":core-audio"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)

    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
}
