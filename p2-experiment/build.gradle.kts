// 实验工程：把 WiseBook/llm-client 的源码直接挂进来编译。
//
// 为什么用 srcDir 而不是把 llm-client 复制一份：
//   路线 B 要证明的是「截图退化为文本来源后仍复用同一条管线」，
//   所以必须跑真实的 StructuredExtractor / ToolSchema / JsonPayloadValidator，
//   不能拿一份手抄的近似实现代替——那样测的是抄得像不像，不是产品链路。
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
    mainClass.set("exp.ExpMain")
}

tasks.named<JavaExec>("run") {
    // 结果含中文，固定 JVM 侧编码，避免写文件时按平台默认编码落盘
    jvmArgs("-Dfile.encoding=UTF-8")
}
