# p2-experiment · 截图解析路线 A/B 对比

> AI 产物区（`d:\HuiJi\WiseBook\` 之外）。**不参与产品构建，`WiseBook/` 未改动一行。**

## 这个工程在干什么

回答 D2 §6.3 留下的那个待定问题：**截图走多模态直读，还是走"OCR + 文本管线"**。

```
路线 A ：图 ──(多模态模型 + tool call)────────────────────→ 结构化
路线 B1：图 ──(通用视觉模型转写)──→ 文本 ──(文本模型 + tool call)──→ 结构化
路线 B2：图 ──(DeepSeek-OCR 转写)──→ 文本 ──(文本模型 + tool call)──→ 结构化
```

三条路线共用同一份工具定义（`ExpSchema`）、同一个产品 `StructuredExtractor`
（含修正型重试与降级）、同一套评估判据，只换"图怎么变成结构化输入"这一段。

**结论见 `d:\HuiJi\docs\P2-截图实验.md`：用户已定走路线 B（B2 为主，B1 为备胎）。**

## 目录

```
p2-experiment/
├── settings.gradle.kts / build.gradle.kts   独立 Gradle 工程
├── samples/                                 三张真实截图（⚠️ 含真实消费记录）
├── results/                                 每次运行产出的 md + json
├── tools/
│   ├── tool-choice-probe.ps1                各模型接受的 tool_choice 形状
│   └── ocr-probe.ps1                        DeepSeek-OCR 的调用方式与输出质量
└── src/main/java/exp/
    ├── ExpMain.java          编排三条路线、评估、生成报告
    ├── ExpSchema.java        工具定义（字段与产品 DraftToolSchema 一致）
    ├── ExpPrompt.java        提示词（文本版是产品 DraftPrompt 的逐字副本）
    ├── VisionLlmClient.java  路线 A：多模态内容块 + 复用 LlmClient 接口
    ├── Transcriber.java      路线 B 第一步：图 → 文本（支持 OCR 专用模型的 prompt 模板）
    ├── Samples.java          样本与人工基准答案
    └── Env.java              从 WiseBook/local.properties 读 Key（不落盘、不进日志）
```

## 怎么跑

```powershell
[Console]::OutputEncoding=[System.Text.Encoding]::UTF8
D:\HuiJi\WiseBook\gradlew.bat -p D:\HuiJi\p2-experiment run
```

- 用 WiseBook 的 gradle wrapper（Gradle 9.3.1 / JDK 21），不必另装
- `llm-client` 的源码是**挂载**进来的（`build.gradle.kts` 的 `srcDir`），不是复制
- 依赖走本机已有的 Gradle 缓存（okhttp 4.12.0 / gson 2.11.0）
- 缺硅基流动 Key 时 B2 自动跳过（不会让整轮失败）
- 结果落在 `results/run-<时间戳>.md`，含逐样本对比、尝试明细与模型原始 JSON

## 几点已记录的偏离（都写在代码注释里）

1. 路线 A 必须带 `thinking:{"type":"disabled"}`：V4 系列默认思考模式，
   而思考模式不接受"指定具体函数"的 tool_choice（实测 400），详见 `docs/P2-截图实验.md` §3.5。
2. OCR 专用模型（`DeepSeek-OCR`）不认 system 消息，指令必须随图放进 user 内容块，
   且只认最简模板 `<image>\nFree OCR.`。
3. `paymentMethod` 在产品里是枚举，实验里放宽成字符串——三张图上的措辞与产品枚举对不齐，
   卡在这里会触发 schema 重试，把"金额读没读准"这个正题淹掉。

## 隐私

`samples/` 与 `results/` **含真实支付记录（商户、金额、订单号）**。
不要提交到任何仓库、不要外发；实验结束后按用户指示处理。
