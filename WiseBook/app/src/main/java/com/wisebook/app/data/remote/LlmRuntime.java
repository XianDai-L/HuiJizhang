package com.wisebook.app.data.remote;

import com.wisebook.llm.LlmClient;

/**
 * 「当前配置下的模型访问能力」——客户端本体 + 溯源标签。
 *
 * <p>抽这一个口子出来，是为了让 {@code DraftRepository} 不必知道
 * 「Key 从哪来、客户端怎么造、配置改了怎么刷新」这些事，
 * 也让仓储层可以在测试里被喂一个假客户端。
 *
 * <p>为什么不直接在仓储里调 {@link LlmClientFactory#create}：
 * 那会在每次提交时都新建一个 {@code OkHttpClient}，而它自带连接池与调度线程池，
 * 建多了就是白白泄漏线程。客户端的缓存与失效放在组装点（{@code WiseBookApp}）更合适。
 */
public interface LlmRuntime {

    /** 当前配置下的模型客户端 */
    LlmClient client();

    /**
     * 是否具备发起调用的条件（主要是 Key 有没有）。
     *
     * <p>界面据此决定：先提示「去设置页填 Key」，还是直接发起解析。
     * 把这件事放在这里而不是让界面自己去翻配置，是为了让「能不能用」
     * 只有一个判断口径。
     */
    boolean isReady();

    /**
     * 写入 {@code t_draft.model} 的溯源标签，形如 {@code siliconflow:Qwen/...}。
     * D2 §6.2 的多模型对比实验就靠它区分数据来源。
     */
    String modelLabel();
}
