// 纯 JVM 模块：大模型调用与结构化输出容错。不依赖 Android。
// 之所以独立成模块而不塞进 money-parser：金额解析和网络调用是两件不同的事，
// 混在一起以后换 HTTP 库或换模型协议会互相牵动。
//
// 注意：仓库声明统一由根工程的 dependencyResolutionManagement 提供，
// 这里不得再写 repositories {}，否则会触发 FAIL_ON_PROJECT_REPOS。
plugins {
    java
}

group = "com.wisebook"
version = "0.1.0"

dependencies {
    implementation(libs.okhttp)
    implementation(libs.gson)

    testImplementation(libs.junit)
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
    options.encoding = "UTF-8"
}

tasks.test {
    useJUnit()
    systemProperty("file.encoding", "UTF-8")
    // 真实调用冒烟测试从环境变量读 Key（见 RealCallSmokeTest）。
    // 这里刻意不转发任何变量：Test 任务默认继承环境，手动转发反而可能在
    // 配置期取到空值把真实 Key 覆盖掉。
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
    }
}
