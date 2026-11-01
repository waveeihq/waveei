plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val appName = providers.gradleProperty("appName").get()
val appId = providers.gradleProperty("appId").get()
val serverUrl = providers.gradleProperty("serverUrl").get()
val sourceUrl = providers.gradleProperty("sourceUrl").get()
val gcpProject = providers.gradleProperty("gcpProject").get()
val firebaseAppId = providers.gradleProperty("firebaseAppId").get()
val firebaseApiKey = providers.gradleProperty("firebaseApiKey").get()
// Updates come from this repository's releases.
val updateRepo = sourceUrl.removePrefix("https://github.com/")
// The upload key, from CI secrets. Without it the release build comes out unsigned.
val signingKeystore = providers.environmentVariable("SIGNING_KEYSTORE_PATH").orNull
val signingPassword = providers.environmentVariable("SIGNING_PASSWORD").orNull

android {
    namespace = "$appId.android"
    compileSdk = 37

    // English only, libraries' own strings too, so no screen mixes in the phone's language.
    androidResources { localeFilters += "en" }

    defaultConfig {
        applicationId = appId
        minSdk = 33
        targetSdk = 36
        // Minutes since 2026-01-01 UTC, set by CI, so that every build is newer than the last.
        versionCode = providers.environmentVariable("VERSION_CODE").orNull?.toInt() ?: 1
        // 1.1.<CI's run number less one>, set by CI and shown in About; the release on GitHub carries it as its title.
        versionName = providers.environmentVariable("VERSION_NAME").orNull ?: (providers.gradleProperty("appVersion").get() + ".0")
        manifestPlaceholders["appLabel"] = appName
        buildConfigField("String", "SERVER_URL", "\"$serverUrl\"")
        buildConfigField("String", "SOURCE_URL", "\"$sourceUrl\"")
        buildConfigField("String", "GCP_PROJECT", "\"$gcpProject\"")
        buildConfigField("String", "FIREBASE_APP_ID", "\"$firebaseAppId\"")
        buildConfigField("String", "FIREBASE_API_KEY", "\"$firebaseApiKey\"")
        buildConfigField("String", "UPDATE_REPO", "\"$updateRepo\"")
    }

    // The shipped build: not debuggable, shrunk by R8, so the app runs at full speed.
    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    if (signingKeystore != null && signingPassword != null) {
        val stable = signingConfigs.create("stable") {
            storeFile = file(signingKeystore)
            storePassword = signingPassword
            keyAlias = "upload"
            keyPassword = signingPassword
        }
        buildTypes.getByName("release").signingConfig = stable
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":core"))
    implementation(libs.okhttp)
    implementation(libs.customview)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    // App Check: whether a request comes from this app on a genuine phone; free minutes need it.
    implementation(libs.firebase.appcheck.playintegrity)
    // Firebase Auth brings an old Fragment; the activity result APIs need 1.3 or later.
    implementation(libs.fragment)
    // The emoji panel: Android's own picker, with recents and skin tones.
    implementation(libs.emoji.picker)
    // Custom Tabs: the payment pages open over the app and close back into it.
    implementation(libs.browser)
    // Sonic, as ExoPlayer plays at another speed: a short piece goes a little slower, its pitch kept.
    implementation(libs.media3.common)

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}

tasks.withType<Test>().configureEach {
    // Robolectric reaches into FileDescriptor internals on recent JDKs.
    jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED", "--add-opens=java.base/java.io=ALL-UNNAMED")
}
