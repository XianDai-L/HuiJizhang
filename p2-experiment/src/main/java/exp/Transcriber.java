package exp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wisebook.llm.LlmModelConfig;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

/**
 * 路线 B 的第一步：把截图转成纯文本（OCR 角色）。
 *
 * <p>这里用一个多模态模型充当"转写器"而不是 {@code DeepSeek-OCR}，原因是本机
 * {@code local.properties} 里只有 DeepSeek 官方 Key，而 {@code deepseek-ai/DeepSeek-OCR}
 * 挂在硅基流动下。等拿到硅基流动 Key 后，只需把这里的 model 换掉，
 * 第二步与评估逻辑一行都不用改——这正好是路线 B "OCR 可替换"的论证。
 *
 * <p>注意它<b>不带 tools</b>：这一步只做文字识别，不理解、不结构化，输出是纯文本，
 * 随后交给与文字入口完全相同的那条管线。
 */
public final class Transcriber {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final LlmModelConfig config;
    private final OkHttpClient http;

    /** OCR 专用模型（DeepSeek-OCR）只认这种最简模板，塞长指令反而会降低识别质量 */
    public static final String OCR_TEMPLATE = "<image>\nFree OCR.";

    /** true = 指令随图放进 user 内容块；OCR 专用模型不认 system 消息 */
    private final boolean instructionInUserMessage;

    public Transcriber(LlmModelConfig config) {
        this(config, false);
    }

    public Transcriber(LlmModelConfig config, boolean instructionInUserMessage) {
        this.config = config;
        this.instructionInUserMessage = instructionInUserMessage;
        this.http = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(180, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build();
    }

    /** @return 识别出的纯文本；失败时抛 IOException */
    public String transcribe(Path image, String instruction) throws IOException {
        String dataUrl = "data:image/jpeg;base64,"
                + Base64.getEncoder().encodeToString(Files.readAllBytes(image));

        JsonArray blocks = new JsonArray();
        JsonObject imageBlock = new JsonObject();
        imageBlock.addProperty("type", "image_url");
        JsonObject imageUrl = new JsonObject();
        imageUrl.addProperty("url", dataUrl);
        imageBlock.add("image_url", imageUrl);
        blocks.add(imageBlock);

        JsonObject textBlock = new JsonObject();
        textBlock.addProperty("type", "text");
        textBlock.addProperty("text", instructionInUserMessage
                ? OCR_TEMPLATE
                : "请转写这张图里的文字。");
        blocks.add(textBlock);

        JsonObject userMessage = new JsonObject();
        userMessage.addProperty("role", "user");
        userMessage.add("content", blocks);

        JsonArray messages = new JsonArray();
        if (!instructionInUserMessage) {
            JsonObject systemMessage = new JsonObject();
            systemMessage.addProperty("role", "system");
            systemMessage.addProperty("content", instruction);
            messages.add(systemMessage);
        }
        messages.add(userMessage);

        JsonObject payload = new JsonObject();
        payload.addProperty("model", config.model());
        payload.addProperty("temperature", 0);
        payload.add("messages", messages);

        Request request = new Request.Builder()
                .url(config.chatCompletionsUrl())
                .addHeader("Authorization", "Bearer " + config.apiKey())
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(payload.toString(), JSON))
                .build();

        try (Response response = http.newCall(request).execute()) {
            String body = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw new IOException("转写失败 HTTP " + response.code() + "：" + body);
            }
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            JsonArray choices = root.getAsJsonArray("choices");
            if (choices == null || choices.isEmpty()) {
                throw new IOException("转写响应缺少 choices：" + body);
            }
            JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
            if (message == null || !message.has("content") || message.get("content").isJsonNull()) {
                throw new IOException("转写响应缺少 content：" + body);
            }
            return message.get("content").getAsString();
        }
    }
}
