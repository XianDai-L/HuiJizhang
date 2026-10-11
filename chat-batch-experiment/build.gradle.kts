// 实验工程：把 WiseBook/llm-client 的源码直接挂进来编译。
//
// 为什么挂源码而不是抄一份：
//   要比较的三种策略（现状单笔 / 批量工具 / 两步）都跑在**同一个产品
//   StructuredExtractor**（含修正型重试与降级）上，只换工具定义与提示词。
//   抄一份近似实现的话，测出来的是"抄得像不像"，不是产品链路的行为。
//
// 为什么 app 模块挂不进来：
//   DraftToolSchema / DraftPrompt / DraftAssembler 都在 app 模块，带 Android 依赖，
//   挂进来会牵出 Room/Android SDK。所以 schema 与提示词在本工程里是**逐字副本**
//   （见 ExpSchema / ExpPrompt 的类注释）。
plugins {
    java
    application
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.11.0")
}

sourceSets {
    main {
        java {
            // 产品模块源码：只读挂载，不改动
            srcDir("D:/HuiJi/WiseBook/llm-client/src/main/java")
        }
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
    options.encoding = "UTF-8"
}

application {
    mainClass.set("exp.ChatBatchMain")
}

tasks.named<JavaExec>("run") {
    // 结果含中文，固定 JVM 侧编码，避免写文件时按平台默认编码落盘
    jvmArgs("-Dfile.encoding=UTF-8")
}
