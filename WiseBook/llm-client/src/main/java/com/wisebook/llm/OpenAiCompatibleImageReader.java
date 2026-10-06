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
import java.util.Base64;
import java.util.concurrent.TimeUnit;

/**
 * OpenAI 兼容协议的「图 → 文本」实现。
 *
 * <p>与 {@link OpenAiCompatibleLlmClient} 分成两个类，而不是给那个类加一个方法，
 * 原因是两者的请求形状本就不同：<b>这里不带 tools</b>（只读字、不结构化），
 * 少了一个字段就少一处能出错的地方；而且业务侧也看得更清楚——
 * 这一步的职责就是"把它看到的东西照抄下来"。
 *
 * <p><b>两种指令放置方式</b>，因为实测里两类模型对 prompt 的要求不一样：
 *
 * <ul>
 *   <li>{@link InstructionPlacement#SYSTEM}：通用视觉模型。system 放业务指令
 *       （"逐条平铺、金额照抄"），user 只带图。</li>
 *   <li>{@link InstructionPlacement#USER_ONLY}：OCR 专用模型
 *       （如 {@code deepseek-ai/DeepSeek-OCR}）。它不认 system 消息，且只认
 *       {@link #OCR_TEMPLATE} 这种最简模板——塞长指令反而降低识别质量（实测）。
 *       这也是"换 OCR 模型要连输出风格一起收住"的落点（见 `docs/P2-截图实验.md`）。</li>
 * </ul>
 */
public final class OpenAiCompatibleImageReader implements ImageReader {

    /** 指令放哪 */
    public enum InstructionPlacement {
        /** 通用视觉模型：system 放业务指令，user 只带图 */
        SYSTEM,
        /** OCR 专用模型：不认 system，只认最简模板 */
        USER_ONLY
    }

    /** OCR 专用模型的固定模板；{@code <image>} 是这类模型约定的占位符 */
    public static final String OCR_TEMPLATE = "<image>\nFree OCR.";

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final LlmModelConfig config;
    private final InstructionPlacement placement;
    private final OkHttpClient http;

    public OpenAiCompatibleImageReader(LlmModelConfig config, InstructionPlacement placement) {
        this(config, placement, new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                // 图片比文本大得多，读超时给足
                .readTimeout(180, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build());
    }

    public OpenAiCompatibleImageReader(LlmModelConfig config, InstructionPlacement placement,
                                       OkHttpClient http) {
        this.config = config;
        this.placement = placement;
        this.http = http;
    }

    @Override
    public String readImage(byte[] imageBytes, String mimeType, String instruction)
            throws LlmException {
        if (imageBytes == null || imageBytes.length == 0) {
            throw new LlmException(LlmException.ErrorKind.BAD_RESPONSE, 0, "图片内容为空", null);
        }
        String resolvedMime = (mimeType == null || mimeType.trim().isEmpty())
                ? "image/jpeg"
                : mimeType.trim();
        String dataUrl = "data:" + resolvedMime + ";base64,"
                + Base64.getEncoder().encodeToString(imageBytes);

        Request request = new Request.Builder()
                .url(config.chatCompletionsUrl())
                .addHeader("Authorization", "Bearer " + config.apiKey())
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(buildPayload(dataUrl, instruction).toString(), JSON))
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
        return readContent(body);
    }

    // ------------------------------------------------------------------ 请求

    private JsonObject buildPayload(String dataUrl, String instruction) {
        JsonArray blocks = new JsonArray();

        JsonObject imageBlock = new JsonObject();
        imageBlock.addProperty("type", "image_url");
        JsonObject imageUrl = new JsonObject();
        imageUrl.addProperty("url", dataUrl);
        imageBlock.add("image_url", imageUrl);
        blocks.add(imageBlock);

        JsonObject textBlock = new JsonObject();
        textBlock.addProperty("type", "text");
        textBlock.addProperty("text", placement == InstructionPlacement.USER_ONLY
                ? OCR_TEMPLATE
                : "请转写这张图里的文字。");
        blocks.add(textBlock);

        JsonObject userMessage = new JsonObject();
        userMessage.addProperty("role", "user");
        userMessage.add("content", blocks);

        JsonArray messages = new JsonArray();
        if (placement == InstructionPlacement.SYSTEM) {
            JsonObject systemMessage = new JsonObject();
            systemMessage.addProperty("role", "system");
            systemMessage.addProperty("content", instruction == null ? "" : instruction);
            messages.add(systemMessage);
        }
        messages.add(userMessage);

        JsonObject payload = new JsonObject();
        payload.addProperty("model", config.model());
        // 转写要的是"照抄"，不是"发挥"
        payload.addProperty("temperature", 0);
        payload.add("messages", messages);
        return payload;
    }

    // ------------------------------------------------------------------ 响应

    private static String readContent(String body) throws LlmException {
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
        JsonArray choices = root.getAsJsonObject().getAsJsonArray("choices");
        if (choices == null || choices.isEmpty()) {
            throw new LlmException(LlmException.ErrorKind.BAD_RESPONSE, 200,
                    "响应缺少 choices：" + LlmHttp.snippet(body));
        }
        JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
        if (message == null) {
            throw new LlmException(LlmException.ErrorKind.BAD_RESPONSE, 200,
                    "响应缺少 message：" + LlmHttp.snippet(body));
        }

        JsonElement content = message.get("content");
        if (content == null || content.isJsonNull()) {
            throw new LlmException(LlmException.ErrorKind.BAD_RESPONSE, 200,
                    "转写结果为空。图里没有可识别的文字，或模型拒绝了这次请求");
        }
        if (content.isJsonArray()) {
            // 少数实现按内容块数组返回；只取其中的文本块，忽略别的块类型
            StringBuilder builder = new StringBuilder();
            for (JsonElement element : content.getAsJsonArray()) {
                if (element.isJsonObject()) {
                    JsonElement text = element.getAsJsonObject().get("text");
                    if (text != null && !text.isJsonNull()) {
                        builder.append(text.getAsString());
                    }
                }
            }
            return builder.toString();
        }
        return content.getAsString();
    }
}
