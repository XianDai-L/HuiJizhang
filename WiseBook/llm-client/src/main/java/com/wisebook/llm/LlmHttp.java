package com.wisebook.llm;

/**
 * OpenAI 兼容请求里两处共用的小事：HTTP 状态码归类、错误体截断。
 *
 * <p>抽出来是因为 P2 多了 {@link OpenAiCompatibleImageReader}——它和
 * {@link OpenAiCompatibleLlmClient} 一样要判断 401/429/5xx 并把错误体截断给人看。
 * 复制一份不是不行，但那正是「同一件事两处写法」的开端，而这两处的差异会表现为
 * 「文本入口报"限流"、截图入口报"响应异常"」这种让人白查半小时的现象。
 */
final class LlmHttp {

    private static final int SNIPPET_LIMIT = 300;

    private LlmHttp() {
    }

    static LlmException.ErrorKind classify(int code) {
        if (code == 401 || code == 403) {
            return LlmException.ErrorKind.AUTH;
        }
        if (code == 429) {
            return LlmException.ErrorKind.RATE_LIMIT;
        }
        if (code >= 500) {
            return LlmException.ErrorKind.SERVER;
        }
        return LlmException.ErrorKind.BAD_RESPONSE;
    }

    static String snippet(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= SNIPPET_LIMIT ? text : text.substring(0, SNIPPET_LIMIT) + "…";
    }
}
