// P2 截图路线 A/B 对比实验（AI 产物，不属于产品代码）
// 独立工程：只引用 WiseBook/llm-client 的源码，不碰产品模块的构建配置。
rootProject.name = "p2-experiment"

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}
