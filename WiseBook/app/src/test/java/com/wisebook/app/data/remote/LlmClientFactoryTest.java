package com.wisebook.app.data.remote;

import static org.junit.Assert.assertEquals;

import com.wisebook.app.domain.model.LlmProvider;
import com.wisebook.llm.LlmModelConfig;

import org.junit.Test;

/**
 * 端点配置的构造（D2 §6.2 的「换服务商只改一个字符串」）。
 *
 * <p>{@code modelConfig} 刻意是不碰 {@code BuildConfig} 与 SharedPreferences 的纯函数，
 * 否则「换服务商」这件事就只能靠人肉点开设置页去验。
 */
public class LlmClientFactoryTest {

    @Test
    public void siliconFlowUsesItsOwnEndpoint() {
        LlmModelConfig config = LlmClientFactory.modelConfig(
                LlmProvider.SILICONFLOW, "Qwen/Qwen3.6-35B-A3B", "sk-test");

        assertEquals("https://api.siliconflow.cn/v1/chat/completions", config.chatCompletionsUrl());
        assertEquals("Qwen/Qwen3.6-35B-A3B", config.model());
        assertEquals("sk-test", config.apiKey());
    }

    @Test
    public void deepSeekUsesItsOwnEndpoint() {
        LlmModelConfig config = LlmClientFactory.modelConfig(
                LlmProvider.DEEPSEEK, "deepseek-chat", "sk-test");

        assertEquals("https://api.deepseek.com/v1/chat/completions", config.chatCompletionsUrl());
    }

    @Test
    public void blankModelFallsBackToProviderDefault() {
        LlmModelConfig config = LlmClientFactory.modelConfig(LlmProvider.DEEPSEEK, "  ", "k");

        assertEquals(LlmProvider.DEEPSEEK.defaultModel(), config.model());
    }

    @Test
    public void nullModelAndProviderFallBackSafely() {
        LlmModelConfig config = LlmClientFactory.modelConfig(null, null, null);

        assertEquals(LlmProvider.SILICONFLOW.defaultModel(), config.model());
        assertEquals("https://api.siliconflow.cn/v1/chat/completions", config.chatCompletionsUrl());
        assertEquals("没有 Key 也不该崩，让鉴权失败给出可读的错误", "", config.apiKey());
    }

    @Test
    public void apiKeyAndModelAreTrimmed() {
        LlmModelConfig config = LlmClientFactory.modelConfig(
                LlmProvider.SILICONFLOW, "  Qwen/Qwen3.6-35B-A3B  ", "  sk-test  ");

        assertEquals("Qwen/Qwen3.6-35B-A3B", config.model());
        assertEquals("sk-test", config.apiKey());
    }

    @Test
    public void labelIsUsableAsDraftModelTrace() {
        LlmModelConfig config = LlmClientFactory.modelConfig(
                LlmProvider.SILICONFLOW, "Qwen/Qwen3.6-35B-A3B", "k");

        // t_draft.model 存的就是这个标签，用来做多模型对比实验的溯源
        assertEquals("siliconflow:Qwen/Qwen3.6-35B-A3B", config.label());
    }
}
