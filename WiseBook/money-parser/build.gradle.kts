// 纯 JVM 模块：金额解析 + 双通道校验。不依赖 Android，可脱离设备快速迭代。
// 注意：仓库声明统一由根工程的 dependencyResolutionManagement 提供，
// 这里不得再写 repositories {}，否则会触发 FAIL_ON_PROJECT_REPOS。
plugins {
    java
}

group = "com.wisebook"
version = "0.1.0"

dependencies {
    testImplementation("junit:junit:4.13.2")
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
    options.encoding = "UTF-8"
}

tasks.test {
    useJUnit()
    systemProperty("file.encoding", "UTF-8")
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
    }
}
