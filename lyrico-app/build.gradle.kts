import com.android.build.api.variant.FilterConfiguration
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
    id("kotlin-parcelize")
    alias(libs.plugins.aboutLibraries)
}

aboutLibraries {
    collect {
        configPath = file("config")
    }
}
// 记得在发布版本时更新此处的值
// LyricoX 版本线：V0.01（内置标签源 + LRCLIB 歌词）→
//                V0.02（内置网易/QQ 歌词含翻译轨 + 封面搜索）→
//                V0.03（品牌去上游化：改名 LyricoX + 更新指向 kakadex）→
//                V0.04（批量匹配：两阶段预览确认 + 全部匹配 + 单首入口 + 搜索结果多选）→
//                V0.05（扫描整个媒体库：MediaStore 全库查询，绕过 SAF 逐目录授权）
val baseVersionName = "0.07.1"
val baseVersionCode = 29

// 签名信息取两处：先环境变量，再仓库根目录的 signing.properties（已在 .gitignore，不入库）。
// signing.properties 的键名与「音乐整理工具」保持一致（storeFile/storePassword/keyAlias/keyPassword），
// 两份工程的签名材料可以共用同一套写法，只改路径。
val signingProps = Properties().apply {
    val f = rootProject.file("signing.properties")
    if (f.isFile) f.reader(Charsets.UTF_8).use { load(it) }
}

fun signingValue(envKey: String, propKey: String): String? =
    providers.environmentVariable(envKey).orNull?.takeIf(String::isNotEmpty)
        ?: signingProps.getProperty(propKey)?.takeIf(String::isNotEmpty)

val releaseSigningEnv = mapOf(
    "LYRICO_KEYSTORE_PATH" to signingValue("LYRICO_KEYSTORE_PATH", "storeFile"),
    "LYRICO_KEYSTORE_PASSWORD" to signingValue("LYRICO_KEYSTORE_PASSWORD", "storePassword"),
    "LYRICO_KEY_ALIAS" to signingValue("LYRICO_KEY_ALIAS", "keyAlias"),
    "LYRICO_KEY_PASSWORD" to signingValue("LYRICO_KEY_PASSWORD", "keyPassword")
)
val hasReleaseSigning = releaseSigningEnv.values.any { it != null }
if (hasReleaseSigning) {
    require(releaseSigningEnv.values.all { it != null }) {
        "Incomplete Release signing configuration. Missing: " +
            releaseSigningEnv.filterValues { it == null }.keys.joinToString()
    }
}

fun gitCommitHash(): Provider<String> = providers.exec {
    commandLine("git", "rev-parse", "--short", "HEAD")
    isIgnoreExitValue = true
}.standardOutput.asText.map { it.trim().ifEmpty { "unknown" } }
    .orElse("unknown")



android {
    namespace = "com.lonx.lyrico"
    ndkVersion = "29.0.14206865"
    compileSdk {
        version = release(37)
    }

    splits {
        abi {
            isEnable = true
            reset()
            // 只出 64 位包（2026-10-22 按用户要求砍掉 armeabi-v7a）。
            // 依据：minSdk 28 的机器基本都是 64 位；v7a 包多 80MB 产物、多一轮 FFmpeg 全量编译，
            // 且历史上反复在 v7a 的 taglib/ffmpeg 编译阶段挂死。要恢复只需把 v7a 加回这一行。
            include("arm64-v8a")
            isUniversalApk = false
        }
    }
    defaultConfig {
        applicationId = "com.lonx.lyrico"
        minSdk = 28
        targetSdk = 36
        versionCode = baseVersionCode
        versionName = "$baseVersionName-${gitCommitHash().get()}"

        externalNativeBuild {
            cmake {
                cppFlags += ""
            }
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseSigningEnv.getValue("LYRICO_KEYSTORE_PATH")!!)
                storePassword = releaseSigningEnv.getValue("LYRICO_KEYSTORE_PASSWORD")
                keyAlias = releaseSigningEnv.getValue("LYRICO_KEY_ALIAS")
                keyPassword = releaseSigningEnv.getValue("LYRICO_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            isShrinkResources = true
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "4.1.2"
        }
    }
}

androidComponents {
    onVariants(selector().all()) { variant ->
        variant.outputs.forEach { output ->
            val abi = output.filters
                .singleOrNull { it.filterType == FilterConfiguration.FilterType.ABI }
                ?.identifier
            val abiSuffix = abi?.let { "-$it" }.orEmpty()

            output.outputFileName.set(
                output.versionName.map { versionName ->
                    "Lyrico-$versionName$abiSuffix.apk"
                }
            )
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}
dependencies {
    implementation(libs.salt.ui)
    // Project Modules
    implementation(project(":lyrico-audiotag"))

    // network
    implementation(libs.okhttp)
    // JSON 解析
    implementation(libs.kotlinx.serialization.json)
    // Compose & UI
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.compose.destinations.core)
    implementation(libs.miuix.preference.android)
    implementation(libs.miuix.ui.android)
    implementation(libs.lottie.compose)
    implementation(libs.miuix.blur.android)
    implementation(libs.coil.compose)
    implementation(libs.zoomable)
    implementation(libs.coil.network.okhttp)
    implementation(libs.reorderable)
    implementation(libs.compose.markdown)
    implementation(libs.lazycolumnscrollbar)
    // AndroidX
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.work.runtime.ktx)

    // Dependency Injection
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)

    // Utilities
    implementation(libs.devicecompat)
    implementation(libs.tinypinyin)
    implementation(libs.opencc4j)
    implementation(libs.aboutlibraries.compose.core)
    // KSP
    ksp(libs.compose.destinations.ksp)
    ksp(libs.androidx.room.compiler)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
