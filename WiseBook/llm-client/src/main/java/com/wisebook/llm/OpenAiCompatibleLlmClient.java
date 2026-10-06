package com.wisebook.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * OpenAI 兼容协议的客户端实现。
 *
 * <p>硅基流动（{@code https://api.siliconflow.cn/v1}）与 DeepSeek 官方
 * （{@code https://api.deepseek.com/v1}）都使用这一套协议，
 * 因此一个实现 + 一份 {@link LlmModelConfig} 即可对接多家。
 */
public final class OpenAiCompatibleLlmClient implements LlmClient {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final LlmModelConfig config;
    private final OkHttpClient http;

    public OpenAiCompatibleLlmClient(LlmModelConfig config) {
        this(config, new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                // 多模态 / 长文本场景给足读超时
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build());
    }

    public OpenAiCompatibleLlmClient(LlmModelConfig config, OkHttpClient http) {
        this.config = config;
        this.http = http;
    }

    @Override
    public LlmRawResponse chatWithTool(String systemPrompt, String userContent, ToolSchema tool)
            throws LlmException {

        JsonObject payload = buildPayload(systemPrompt, userContent, tool);
        Request request = new Request.Builder()
                .url(config.chatCompletionsUrl())
                .addHeader("Authorization", "Bearer " + config.apiKey())
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(payload.toString(), JSON))
                .build();

        int code;
        String body;
        try (Response response = http.newCall(request).execute()) {
            code = response.code();
            body = response.body() == null ? "" : response.body().string();
        } catch (IOException e) {
            throw new LlmException(LlmException.ErrorKind.TRANSPORT, 0,
                    "网络请求失败：" + e.getMessage(), e);
        }

        if (code < 200 || code >= 300) {
            throw new LlmException(LlmHttp.classify(code), code,
                    "HTTP " + code + "：" + LlmHttp.snippet(body));
        }
        return parse(body);
    }

    // ------------------------------------------------------------------ 请求

    private JsonObject buildPayload(String systemPrompt, String userContent, ToolSchema tool) {
        JsonArray messages = new JsonArray();
        messages.add(message("system", systemPrompt));
        messages.add(message("user", userContent));

        JsonObject toolWrapper = new JsonObject();
        toolWrapper.addProperty("type", "function");
        toolWrapper.add("function", tool.toFunctionJson());
        JsonArray tools = new JsonArray();
        tools.add(toolWrapper);

        JsonObject choiceFunction = new JsonObject();
        choiceFunction.addProperty("name", tool.name());
        JsonObject toolChoice = new JsonObject();
        toolChoice.addProperty("type", "function");
        toolChoice.add("function", choiceFunction);

        JsonObject payload = new JsonObject();
        payload.addProperty("model", config.model());
        // 记账要的是可复现，不是创意
        payload.addProperty("temperature", 0);
        payload.add("messages", messages);
        payload.add("tools", tools);
        payload.add("tool_choice", toolChoice);
        return payload;
    }

    private static JsonObject message(String role, String content) {
        JsonObject message = new JsonObject();
        message.addProperty("role", role);
        message.addProperty("content", content == null ? "" : content);
        return message;
    }

    // ------------------------------------------------------------------ 响应

    private LlmRawResponse parse(String body) throws LlmException {
        JsonElement root;
        try {
            root = JsonParser.parseString(body);
        } catch (RuntimeException e) {
            throw new LlmException(LlmException.ErrorKind.BAD_RESPONSE, 200,
                    "响应不是合法 JSON：" + LlmHttp.snippet(body), e);
        }
        if (!root.isJsonObject()) {
            throw new LlmException(LlmException.ErrorKind.BAD_RESPONSE, 200,
                    "响应顶层不是 JSON 对象：" + LlmHttp.snippet(body));
        }

        JsonObject rootObject = root.getAsJsonObject();
        JsonArray choices = rootObject.getAsJsonArray("choices");
        if (choices == null || choices.isEmpty()) {
            throw new LlmException(LlmException.ErrorKind.BAD_RESPONSE, 200,
                    "响应缺少 choices：" + LlmHttp.snippet(body));
        }

        JsonObject choice = choices.get(0).getAsJsonObject();
        JsonObject message = choice.getAsJsonObject("message");
        if (message == null) {
            throw new LlmException(LlmException.ErrorKind.BAD_RESPONSE, 200,
                    "响应缺少 message：" + LlmHttp.snippet(body));
        }

        String model = optString(rootObject, "model");
        String content = optString(message, "content");

        String toolArguments = null;
        JsonArray toolCalls = message.getAsJsonArray("tool_calls");
        if (toolCalls != null && !toolCalls.isEmpty()) {
            JsonObject call = toolCalls.get(0).getAsJsonObject();
            JsonObject function = call.getAsJsonObject("function");
            if (function != null) {
                toolArguments = optString(function, "arguments");
            }
        }
        return new LlmRawResponse(model, toolArguments, content, body);
    }

    private static String optString(JsonObject object, String key) {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) {
            return null;
        }
        return element.isJsonPrimitive() ? element.getAsString() : element.toString();
    }

    // HTTP 状态码归类与错误体截断搬去了 LlmHttp：
    // 截图入口的 ImageReader 要用同一套判断，两个类各留一份迟早在提示文案上分叉
}
