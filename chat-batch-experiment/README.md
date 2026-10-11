# chat-batch-experiment · 对话（文字）入口「多笔录入」策略对比

> AI 产物区（`d:\HuiJi\WiseBook\` 之外）。**不参与产品构建，`WiseBook/` 未改动一行。**

## 这个工程在干什么

回答 P1 决策 14 留下的那个问题：**一句话里含好几笔账时，文字入口该怎么拆**。

```
S0 现状：单笔工具 + 单笔 prompt（"只解析第一笔，报 transactionCount"）
         → 系统只落 1 笔，其余让用户在确认页手填
A  批量：批量工具（drafts 数组）+ 覆写【多笔】段的 prompt
         → 一次拆出 N 笔（截图入口 P2 已经在用这条路）
B  两步：先用 S0；它报出 >1 笔时，再补一次 A 的调用，采用 A 的结果
```

三条策略吃同一个产品 `StructuredExtractor`（含修正型重试与降级）、同一个模型
（产品默认的 `deepseek-chat`）、同一批样本与同一份人工基准，只换"工具定义 + 提示词"。

## 目录

```
chat-batch-experiment/
├── settings.gradle.kts / build.gradle.kts   独立 Gradle 工程（挂载 llm-client 源码）
├── results/                                 每次运行产出的 md + json
└── src/main/java/exp/
    ├── ChatBatchMain.java  编排三条策略、评估、生成报告
    ├── ExpSchema.java      工具定义：单笔 / 批量（逐字对齐产品 DraftToolSchema）
    ├── ExpPrompt.java      提示词：单笔版是产品 DraftPrompt 的逐字副本；批量版是覆写【多笔】段
    ├── Samples.java        15 句样本与人工基准答案
    └── Env.java            从 WiseBook/local.properties 读 Key（不落盘、不进日志）
```

## 怎么跑

```powershell
[Console]::OutputEncoding=[System.Text.Encoding]::UTF8
D:\HuiJi\WiseBook\gradlew.bat -p D:\HuiJi\chat-batch-experiment run
```

- 用 WiseBook 的 gradle wrapper（Gradle 9.3.1 / JDK 21），不必另装
- `llm-client` 的源码是**挂载**进来的（`build.gradle.kts` 的 `srcDir`），不是复制
- 缺 DeepSeek Key 时直接退出（不会跑出一堆鉴权失败）
- 结果落在 `results/run-<时间戳>.md`，含逐样本对比、汇总、多笔拆分对照、模型原始 JSON
- **会真实调用模型**：15 样本 × 3 策略 + 重试 + B 的第二步 ≈ 55 次调用

## 判据（为什么是这几项）

| 层 | 指标 | 为什么 |
| --- | --- | --- |
| 拆分 | 笔数全对 / 过拆 / 漏拆 | 多笔方案的核心能力；过拆与漏拆是两种相反的错，必须分开数 |
| 逐笔 | 逐笔金额全对 / 逐笔方向全对 | 金额与方向是"错一个字就变成另一笔账"的字段 |
| 单笔侧 | 首笔金额对 | S0 只能落一笔，这一项才对它有区分度；也是"多笔方案有没有伤害单笔场景"的对照 |
| 代价 | 首次通过率 / 模型调用次数 / 耗时 | A 让单笔句也走数组解析，代价要能被看见 |
| 端到端 | 多笔句"可落库笔数"合计 | S0 每条多笔句只落 1 笔，这一列就是它的全部代价 |

时间短语与商户只并列展示、不自动判分：多笔句里各笔的时间归属人工也难判到逐字，
自动判分会自造误差（与 `p2-experiment` 对时间列的处理一致）。

## 两个刻意的陷阱样本

- `s06`「三个人吃了 120」：句中数字比金额多，考"会不会把人/份数拆成笔"
- `m08`「这个月买菜加打车一共花了 1000」：**合并总额型**——语义两笔、金额只有总额，
  正确行为是留一笔让用户补；硬拆成两笔等于凭空造金额。它是"防过拆"的对照样本

## 已知边界（跑之前先知道）

1. **A 与现状的差异不只是 schema**：prompt 也一起换了（覆写【多笔】段）。
   这是刻意的——方案 A 落地时这两者本来就会一起改，只换 schema 反而不是真实的 A。
2. `paymentMethod` 在产品里是枚举，实验里放宽成字符串：它只是观察项，
   卡在枚举上会触发 schema 重试，把"拆没拆对"这个正题淹掉。
3. 样本是**合成句**（不是用户真实输入），作用是横向比较三条策略，不是估产品准确率的绝对值。
4. 只跑 `deepseek-chat` 一家。换模型只需改 `ChatBatchMain.TEXT_MODEL` 一个字符串——
   但如果换到 V4 系列，要注意思考模式与"指定函数 tool_choice"互斥（见 `docs/HANDOFF.md` §8-30）。
