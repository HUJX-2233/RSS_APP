plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.example.rssreader"
    compileSdk {
        version = release(37) {
            minorApiLevel = 0
        }
    }

    defaultConfig {
        applicationId = "com.example.rssreader"
        // minSdk 26：java.time 无需 core library desugaring（开发计划第 5 节决策）
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        debug {
            optimization {
                enable = false
            }
            isMinifyEnabled = false
        }
        release {
            optimization {
                enable = false
            }
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        // M1 的 DEBUG 种子源用 BuildConfig.DEBUG 做门控
        buildConfig = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

ksp {
    // Room 把建表 SQL 导出成 JSON 存档：答辩时能直接展示表结构，也支持后续写迁移
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    // 导航：单 Activity 架构下用 NavHost 管页面栈，返回键、进程重建后的栈恢复交给官方组件处理，
    // 比手写一个 enum + when 的“假导航”更贴近真实项目，也省掉自己处理越界返回的问题。
    implementation(libs.androidx.navigation.compose)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // 网络层自持 OkHttp（只喂字符串给解析器，分层清晰）
    implementation(libs.okhttp)
    // 只用它的 parse(xml: String) 纯解析；网络我们自己拉
    implementation(libs.rssparser)

    // Coil：M2 的文章缩略图要用。D0 就接入，是为了让“依赖能解析、能编译”这件事
    // 在导航骨架这一轮就被验证掉，不把构建风险留到写业务逻辑的时候。
    implementation(libs.coil.compose)
    // Coil 的网络层交给 OkHttp：图片和 RSS 共用一套 HTTP 栈，
    // 复用连接池/超时配置，也少引一个 HTTP 客户端。
    implementation(libs.coil.network.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
