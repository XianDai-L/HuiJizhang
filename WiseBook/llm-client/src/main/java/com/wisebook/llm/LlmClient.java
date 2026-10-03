package com.wisebook.llm;

/**
 * 大模型客户端抽象。
 *
 * <p>这一层接口是刻意留出的缝：</p>
 * <ul>
 *   <li>直连、代理服务、以及测试用的假客户端都实现它，上层无感</li>
 *   <li>{@link StructuredExtractor} 的「校验 + 重试 + 降级」逻辑因此可以
 *       完全不依赖网络地被测试——这是容错代码能被真正验证的前提</li>
 * </ul>
 */
public interface LlmClient {

    /**
     * 发起一次带工具约束的对话。
     *
     * @param systemPrompt 系统提示词
     * @param userContent  用户内容（重试时会附带上一次的错误反馈）
     * @param tool         工具定义，约束模型必须以该结构输出
     * @return 原始返回，未做校验
     */
    LlmRawResponse chatWithTool(String systemPrompt, String userContent, ToolSchema tool)
            throws LlmException;
}
