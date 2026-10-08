import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// 高德 key 从 local.properties 读（不进仓库）
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val amapKey: String = localProps.getProperty("amap.key") ?: ""
val appAuthToken: String = localProps.getProperty("app.auth.token") ?: ""
// 后端地址从 local.properties 读（仓库内不写死服务器地址）
val baseUrl: String = localProps.getProperty("app.base.url") ?: "http://10.0.2.2:8080"

// 签名证书：密码读 keystore/PASSWORD.txt（keystore/ 未纳入仓库）。
// 没有证书时构建仍然通过，使用系统默认的 debug key 签名，此时高德 key 校验不通过（地图空白）。
// 密码文件保存为不带 BOM 的 UTF-8：带 BOM 时 JVM 读到的首个字符是 \uFEFF，
// 密码校验失败并报 KeytoolException "password was incorrect"。
val ksFile = rootProject.file("keystore/release.jks")
val ksPwFile = rootProject.file("keystore/PASSWORD.txt")
val ksPassword: String =
    if (ksPwFile.exists()) ksPwFile.readText(Charsets.UTF_8).trim().trimStart('\uFEFF').trim() else ""

android {
    namespace = "com.navi.shell"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.navi.shell"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        // 真机 arm64 为主；x86_64 留着，方便模拟器里跑
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        manifestPlaceholders["AMAP_KEY"] = amapKey

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "APP_AUTH_TOKEN", "\"$appAuthToken\"")
        buildConfigField("String", "BASE_URL", "\"$baseUrl\"")
    }

    signingConfigs {
        if (ksFile.exists() && ksPassword.isNotEmpty()) {
            create("release") {
                storeFile = ksFile
                storePassword = ksPassword
                keyAlias = "release"
                keyPassword = ksPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (ksFile.exists() && ksPassword.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        // debug 也用正式证书签。
        // 高德的 key 是绑「包名 + 签名 SHA1」的，debug 用系统那套 debug.keystore 就换了 SHA1，
        // 装出来的包 key 直接不认（地图空白）。用同一把证书，装哪个都不折腾。
        debug {
            if (ksFile.exists() && ksPassword.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.amap.navi)
    debugImplementation(libs.androidx.ui.tooling)
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
