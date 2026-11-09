plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("kotlin-kapt")
}

android {
    namespace = "com.example.photofeed"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.photofeed"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    // 签名：优先使用随本地工具链附带的 debug keystore（离线构建环境）；
    // 克隆仓库后没有该文件时自动退回——debug 用默认签名，release 产出未签名包
    val keystore = rootProject.file("../toolchain/debug.keystore")
    signingConfigs {
        if (keystore.exists()) {
            create("local") {
                storeFile = keystore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.findByName("local")
        }
        getByName("release") {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("local")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.fragment:fragment-ktx:1.6.2")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.coordinatorlayout:coordinatorlayout:1.2.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.viewpager2:viewpager2:1.0.6")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("com.google.android.material:material:1.11.0")
    implementation("com.github.bumptech.glide:glide:4.16.0")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-ui:1.3.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}

tasks.register("checkPermissions") {
    dependsOn("processReleaseMainManifest")
    doLast {
        val candidates = listOf(
            layout.buildDirectory.file("intermediates/merged_manifests/release/processReleaseManifest/AndroidManifest.xml").get().asFile,
            layout.buildDirectory.file("intermediates/merged_manifests/release/AndroidManifest.xml").get().asFile
        )
        val manifest = candidates.firstOrNull { it.exists() }
        if (manifest == null) {
            logger.lifecycle("checkPermissions: merged manifest not found, skipping")
            return@doLast
        }
        val allowed = setOf(
            "READ_MEDIA_IMAGES", "READ_MEDIA_VIDEO",
            "READ_MEDIA_VISUAL_USER_SELECTED", "READ_EXTERNAL_STORAGE"
        )
        val found = Regex("""android:name="android\.permission\.([A-Z_]+)"""")
            .findAll(manifest.readText()).map { it.groupValues[1] }.toSet()
        val extra = found - allowed
        logger.lifecycle("checkPermissions: found=$found")
        check(extra.isEmpty()) { "发现白名单外权限: $extra" }
    }
}
