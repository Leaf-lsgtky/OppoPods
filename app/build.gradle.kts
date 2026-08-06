plugins {
    alias(libs.plugins.agp.app)
    alias(libs.plugins.lsplugin.apksign)
    alias(libs.plugins.lsplugin.resopt)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.parcelize)
    alias(libs.plugins.compose.compiler)
}

apksign {
    storeFileProperty = "KEYSTORE_FILE"
    storePasswordProperty = "KEYSTORE_PASSWORD"
    keyAliasProperty = "KEY_ALIAS"
    keyPasswordProperty = "KEY_PASSWORD"
}

android {
    namespace = "moe.chenxy.oppopods"
    compileSdk = 37

    defaultConfig {
        applicationId = "moe.chenxy.oppopods"
        minSdk = 35
        targetSdk = 36
        versionCode = 12
        versionName = "1.2.1"
    }

    buildTypes {
        debug {
            isDebuggable = true
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    dependenciesInfo.includeInApk = false

    buildFeatures {
        buildConfig = true
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/**.version"
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
            excludes += "okhttp3/**"
            excludes += "kotlin/**"
            excludes += "org/**"
            excludes += "**.properties"
            excludes += "**.bin"
            excludes += "kotlin-tooling-metadata.json"
        }
    }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(JavaVersion.VERSION_22.majorVersion)
    }
}

kotlin {
    jvmToolchain(JavaVersion.VERSION_22.majorVersion.toInt())
}

configurations.configureEach {
    exclude(group = "androidx.lifecycle", module = "lifecycle-viewmodel-ktx")
}

dependencies {
    implementation(libs.coreKtx)
    compileOnly(libs.libxposedApi)
    implementation(libs.kotlinx.serialization.json)

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.androidx.activity.compose)
    implementation("androidx.compose.material:material-icons-extended")

    // MIUIX
    implementation(libs.miuix)
    implementation(libs.miuix.preference)
    implementation(libs.miuix.icons)
    implementation(libs.miuix.blur)
    implementation(libs.miuix.navigation3.ui)

    // Navigation3
    implementation(libs.navigation3.runtime)

    // HyperOS Focus Island API
    implementation(libs.focus.api)

    // Unit tests. android.jar 里的 org.json 是空桩，用真实实现替换，
    // 使 DeviceModelRegistry 的解析逻辑能在 JVM 上直接跑。
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
