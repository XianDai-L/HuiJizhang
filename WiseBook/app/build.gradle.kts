import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// ---------------------------------------------------------------- API Key 注入
// 从 local.properties 读 Key。该文件已在 .gitignore 中，Key 不进版本库。
// 刻意不用 System.getenv：Gradle 守护进程读到的环境变量不可靠（HANDOFF §8-4），
// 取不到时会静默变成空串，反而把测试进程里真实的 Key 覆盖掉。
// 这里注入的只是「编译期默认值」，运行时若在设置页填了 Key，以设置页的为准。
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}
val siliconFlowApiKey: String = localProperties.getProperty("wisebook.siliconflow.key").orEmpty()
val deepSeekApiKey: String = localProperties.getProperty("wisebook.deepseek.key").orEmpty()

/** 生成 BuildConfig 的字符串字面量：必须自带引号，内部的反斜杠与引号要转义 */
fun buildConfigString(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "com.wisebook.app"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.wisebook.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "SILICONFLOW_API_KEY", buildConfigString(siliconFlowApiKey))
        buildConfigField("String", "DEEPSEEK_API_KEY", buildConfigString(deepSeekApiKey))

        // Room 导出的 schema JSON：既是迁移的编写依据，也是 MigrationTest 的数据源。
        // 不配这个参数 Room 会告警，且迁移测试无从校验「v1 到底长什么样」。
        javaCompileOptions {
            annotationProcessorOptions {
                arguments["room.schemaLocation"] = "$projectDir/schemas"
            }
        }
    }

    buildFeatures {
        // 需要 BuildConfig 承载上面注入的 Key
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // 必须为 17：money-parser 编译到 Java 17 字节码，版本不一致会导致构建失败
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        // 把导出的 schema 挂进 androidTest 的 assets，供 MigrationTestHelper 读取
        getByName("androidTest").assets.directories.add("$projectDir/schemas")
    }
}

dependencies {
    // 纯 JVM 逻辑模块（不依赖 Android，可脱离设备快速迭代）
    implementation(project(":money-parser"))
    implementation(project(":llm-client"))

    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
    implementation(libs.recyclerview)

    // 架构组件
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.lifecycle.livedata)

    // 本地存储：Room 四表（t_draft / t_entry / t_category / t_setting）
    // 设置项统一走 t_setting 表，不再使用 DataStore（D1 §3.5 已定稿该表结构）
    implementation(libs.room.runtime)
    annotationProcessor(libs.room.compiler)

    // 网络：OpenAI 兼容协议
    implementation(libs.okhttp)
    implementation(libs.gson)

    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
    // 迁移测试：读取导出的 schema JSON 建出真实的 v1 库，再验证迁移后数据不丢
    androidTestImplementation(libs.room.testing)
}
