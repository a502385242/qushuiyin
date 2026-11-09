import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// 配置签名公钥等参数优先从 local.properties 读取，避免污染仓库
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun localProp(key: String, fallback: String): String = localProps.getProperty(key) ?: fallback

/**
 * 规则文件的镜像目录（客户端并发竞速，取最先返回且验签通过的一份）。
 *
 * 顺序按国内实测可达性排，不按「谁的官方」排：
 * 1. fastly.jsdelivr.net —— 国内基本稳定，实测 0.7~1.4s
 * 2. cdn.jsdelivr.net    —— 同源备用
 * 3. raw.githubusercontent.com —— 内容最权威、更新最即时，但国内常卡在 DNS / TLS 阶段
 *
 * jsDelivr 会缓存 @main 分支约 12 小时，所以客户端每次请求都会带上按分钟分桶的
 * cache-buster（见 MirrorClient），否则你推了新规则，用户半天后还在拿旧的。
 */
val defaultMirrors = listOf(
    "https://fastly.jsdelivr.net/gh/a502385242/qushuiyin@main/config/",
    "https://cdn.jsdelivr.net/gh/a502385242/qushuiyin@main/config/",
    "https://raw.githubusercontent.com/a502385242/qushuiyin/main/config/"
).joinToString(",")

/** 验签公钥，对应 keys/public.pem。私钥不进仓库，丢了老版本 App 就再也信不过新规则。 */
val defaultPubKey =
    "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEbcyOkw4K6ijdgWLvbIiUXb2mMuZyOUbAQEhUacMO74Br" +
        "PR88JTlFCrpyawChEdzZ+Sf1o04oBoxlX16/qQxRSQ=="

android {
    namespace = "com.qushuiyin.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.qushuiyin.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 8
        versionName = "0.4.0"

        // 修复配置的镜像地址（目录前缀，末尾带 /），可用 local.properties 的 repair.mirrors 覆盖
        buildConfigField(
            "String", "REPAIR_MIRRORS",
            "\"${localProp("repair.mirrors", defaultMirrors)}\""
        )
        // ECDSA P-256 公钥（Base64）。公钥本来就是公开的，直接内置在仓库里，
        // 这样别人 clone 下来不改任何本地文件也能编出「能验签」的包。
        // 换密钥时同时改这里和 tools/manifest_tool.py 生成的 keys/。
        buildConfigField(
            "String", "REPAIR_PUBKEY",
            "\"${localProp("repair.pubkey", defaultPubKey)}\""
        )
    }

    /**
     * Release 签名。密钥与口令只在 local.properties（不进仓库），
     * 新克隆的工程没有它时自动退回 debug 签名，仍然能编出可装的包。
     */
    signingConfigs {
        create("release") {
            val ksPath = localProps.getProperty("release.storeFile")
            if (ksPath != null && rootProject.file(ksPath).exists()) {
                storeFile = rootProject.file(ksPath)
                storePassword = localProps.getProperty("release.storePassword")
                keyAlias = localProps.getProperty("release.keyAlias")
                keyPassword = localProps.getProperty("release.keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isMinifyEnabled = false
        }
        release {
            // 暂不开混淆：R8 对 kotlinx.serialization 的 @Serializable 类有裁剪风险，
            // 而「装上去一打开就崩」比包体大几 MB 更糟。等真机验过再开。
            isMinifyEnabled = false
            signingConfig = if (signingConfigs.getByName("release").storeFile != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
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
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/DEPENDENCIES")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.datastore.preferences)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
