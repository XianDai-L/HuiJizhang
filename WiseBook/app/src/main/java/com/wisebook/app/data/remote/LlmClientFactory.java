package com.wisebook.app.data.remote;

import com.wisebook.app.domain.model.LlmProvider;
import com.wisebook.llm.ImageReader;
import com.wisebook.llm.LlmClient;
import com.wisebook.llm.LlmModelConfig;
import com.wisebook.llm.OpenAiCompatibleImageReader;
import com.wisebook.llm.OpenAiCompatibleLlmClient;

/**
 * 按当前配置造一个 {@link LlmClient}。
 *
 * <p>它是「换服务商只改一个枚举值」这句话的落点：{@link LlmProvider} 决定走哪家的
 * baseUrl，{@link LlmModelConfig} 承载 baseUrl + Key + 模型名三件套，
 * 而客户端实现只有一套（两家都是 OpenAI 兼容协议）。
 *
 * <p>{@link #modelConfig} 刻意做成<b>不碰 SharedPreferences 与 BuildConfig 的纯函数</b>，
 * 因此可以被单测直接调——否则"换服务商"这件事就只能靠人肉点开设置页去验。
 */
public final class LlmClientFactory {

    private LlmClientFactory() {
    }

    public static LlmClient create(LlmConfigStore store) {
        return new OpenAiCompatibleLlmClient(
                modelConfig(store.provider(), store.model(), store.apiKey()));
    }

    /**
     * 造截图入口的转写器（图 → 文字）；<b>没有硅基流动 Key 时返回 {@code null}</b>。
     *
     * <p>用 {@link OpenAiCompatibleImageReader.InstructionPlacement#USER_ONLY}：
     * {@code DeepSeek-OCR} 是 OCR 专用模型，不认 system 消息、只认固定模板（P2 实测）。
     *
     * <p>刻意不复用 {@link #create}：那一步的产出是<b>文本</b>，
     * 与"用哪家模型做结构化"是两件独立的事——这正是路线 B 的架构主张
     * （见 `docs/P2-截图实验.md`）。
     */
    public static ImageReader createImageReader(LlmConfigStore store) {
        String key = store.apiKeyFor(LlmProvider.SILICONFLOW);
        if (key == null || key.trim().isEmpty()) {
            return null;
        }
        return new OpenAiCompatibleImageReader(
                LlmModelConfig.siliconFlow(key.trim(), LlmProvider.SILICONFLOW_OCR_MODEL),
                OpenAiCompatibleImageReader.InstructionPlacement.USER_ONLY);
    }

    /**
     * 纯函数版本：给定服务商、模型名与 Key，造出端点配置。
     *
     * @param model  可为空，为空时用该服务商的默认模型
     * @param apiKey 可为空，为空时请求会因鉴权失败而返回可读的错误，不会崩
     */
    public static LlmModelConfig modelConfig(LlmProvider provider, String model, String apiKey) {
        LlmProvider resolved = provider == null ? LlmProvider.SILICONFLOW : provider;
        String resolvedModel = (model == null || model.trim().isEmpty())
                ? resolved.defaultModel()
                : model.trim();
        String resolvedKey = apiKey == null ? "" : apiKey.trim();

        switch (resolved) {
            case DEEPSEEK:
                return LlmModelConfig.deepSeek(resolvedKey, resolvedModel);
            case SILICONFLOW:
            default:
                return LlmModelConfig.siliconFlow(resolvedKey, resolvedModel);
        }
    }
}
