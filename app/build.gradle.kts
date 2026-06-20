plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
    alias(libs.plugins.detekt)
}

android {
    namespace = "com.qimeng.media"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.qimeng.media"
        minSdk = 31
        targetSdk = 36
        versionCode = 9
        versionName = "1.7"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // libspng NDK 配置
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86_64")
        }
    }

    // libspng CMake 构建
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            // 开启资源压缩：配合 R8 移除未引用资源，减小 release APK 体积
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    buildFeatures {
        viewBinding = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        // 不阻断构建：Lint 报告供参考，详细检查用 Android Studio Inspect Code
        // （命令行 lintDebug 实测 1m54s 可完成，历史卡死问题已随 daemon 规范化消失）
        abortOnError = false
        warningsAsErrors = false
        checkReleaseBuilds = true
        // 排除不需要检查的 Issue
        disable += setOf(
            "MissingTranslation",
            "ImpliedQuantity",
            "UnusedQuantity",
            "Typos",
            "DuplicateStrings",
            "IconMissingDensityFolder",
            "IconDensities",
            "Overdraw",
            "UseCompoundDrawables",
            "TooManyViews",
            // Media3 ExoPlayer @UnstableApi opt-in 会向引用 MediaDetailFragment 的
            // MainActivity 传播报 Error；项目已决定使用 Media3，无需逐层 opt-in 标注
            "UnsafeOptInUsageError",
            // App 仅中文本地应用，硬编码中文文本是有意设计（不提取 strings.xml）
            "ConstantLocale",
            // App 用 SAF 目录授权而非标准媒体选择器，SelectedPhotoAccess（部分照片访问）不适用
            "SelectedPhotoAccess",
            // targetSdk=36 已是当前最新稳定版（Android 16），Lint 提示升级到预览版无意义
            "OldTargetApi"
        )
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.material)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.coil)
    implementation(libs.coil.gif)
    implementation(libs.coil.video)
    implementation(libs.androidx.swiperefreshlayout)
    ksp(libs.androidx.room.compiler)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.truth)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom("$rootDir/detekt.yml")
    autoCorrect = true
    // 不阻断构建，仅生成报告
    ignoreFailures = true
}

tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    reports {
        html.required.set(true)
        xml.required.set(false)
        txt.required.set(false)
    }
}
