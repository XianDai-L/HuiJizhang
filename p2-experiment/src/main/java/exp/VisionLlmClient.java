package exp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wisebook.llm.LlmClient;
import com.wisebook.llm.LlmException;
import com.wisebook.llm.LlmModelConfig;
import com.wisebook.llm.LlmRawResponse;
import com.wisebook.llm.ToolSchema;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

/**
 * 路线 A 的客户端：多模态直读。
 *
 * <p><b>它实现的是产品现成的 {@link LlmClient} 接口，没有加任何方法。</b>
 * 图片通过 {@code userContent} 的首行 {@code IMG:<绝对路径>} 传给实现，
 * 其余文字照旧当提示词。这样 {@code StructuredExtractor}（含修正型重试与降级）
 * 可以原样复用——这本身就是路线 A 结论的一部分：
 *
 * <p><b>这条路线在产品里的真实改动面</b>，就是把
 * {@code OpenAiCompatibleLlmClient.buildPayload} 里那句
 * {@code message("user", userContent)} 换成"支持内容块数组的 user 消息"，
 * 其余（schema 校验、重试、降级、落库）都不用动。本类即那段改动的原型。
 */
public final class VisionLlmClient implements LlmClient {

    /** userContent 首行前缀约定：IMG:<绝对路径> */
    public static final String IMAGE_PREFIX = "IMG:";

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final int SNIPPET_LIMIT = 300;

    private final LlmModelConfig config;
    private final OkHttpClient http;

    public VisionLlmClient(LlmModelConfig config) {
        this.config = config;
        this.http = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                // 多模态 + 长图，读超时给足
                .readTimeout(180, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public LlmRawResponse chatWithTool(String systemPrompt, String userContent, ToolSchema tool)
            throws LlmException {

        // 首行取图，其余当文字提示；重试时 StructuredExtractor 会把错误反馈追加在末尾，
        // 那些反馈会自然落进"其余文字"里，首行约定不受影响
        int breakAt = userContent.indexOf('\n');
        String firstLine = breakAt < 0 ? userContent : userContent.substring(0, breakAt);
        String text = breakAt < 0 ? "" : userContent.substring(breakAt + 1);

        if (!firstLine.startsWith(IMAGE_PREFIX)) {
            throw new LlmException(LlmException.ErrorKind.TRANSPORT, 0,
                    "userContent 首行必须形如 " + IMAGE_PREFIX + "<图片绝对路径>", null);
        }
        Path image = Paths.get(firstLine.substring(IMAGE_PREFIX.length()).trim());

        String dataUrl;
        try {
            byte[] bytes = Files.readAllBytes(image);
            dataUrl = "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(bytes);
        } catch (IOException e) {
            throw new LlmException(LlmException.ErrorKind.TRANSPORT, 0,
                    "读取图片失败：" + image, e);
        }

        JsonObject payload = buildPayload(systemPrompt, text, dataUrl, tool);
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
            throw new LlmException(classify(code), code, "HTTP " + code + "：" + snippet(body));
        }
        return parse(body);
    }

    private JsonObject buildPayload(String systemPrompt, String userText, String dataUrl,
                                    ToolSchema tool) {
        JsonArray messages = new JsonArray();
        messages.add(textMessage("system", systemPrompt));

        // 关键差异：user 消息的 content 是内容块数组，而不是一个字符串
        JsonArray blocks = new JsonArray();
        JsonObject imageBlock = new JsonObject();
        imageBlock.addProperty("type", "image_url");
        JsonObject imageUrl = new JsonObject();
        imageUrl.addProperty("url", dataUrl);
        imageBlock.add("image_url", imageUrl);
        blocks.add(imageBlock);

        JsonObject textBlock = new JsonObject();
        textBlock.addProperty("type", "text");
        textBlock.addProperty("text", userText == null ? "" : userText);
        blocks.add(textBlock);

        JsonObject userMessage = new JsonObject();
        userMessage.addProperty("role", "user");
        userMessage.add("content", blocks);
        messages.add(userMessage);

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
        payload.addProperty("temperature", 0);
        payload.add("messages", messages);
        payload.add("tools", tools);
        payload.add("tool_choice", toolChoice);

        // DeepSeek V4 系列默认开思考模式，而思考模式**不接受**"指定具体函数"的 tool_choice：
        //   400 Thinking mode does not support this tool_choice
        // 实测 deepseek-chat（产品的默认模型）没有这个限制，它仍接受指定函数。
        // 两条路可选：换模型，或显式关掉思考。这里选后者——V4 的视觉能力是路线 A 的前提。
        // 这条限制要写进 P2 结论：产品若把模型换成 V4 系列，客户端必须补这个字段。
        JsonObject thinking = new JsonObject();
        thinking.addProperty("type", "disabled");
        payload.add("thinking", thinking);
        return payload;
    }

    private static JsonObject textMessage(String role, String content) {
        JsonObject message = new JsonObject();
        message.addProperty("role", role);
        message.addProperty("content", content == null ? "" : content);
        return message;
    }

    /** 解析逻辑与产品客户端保持一致：优先取 tool_calls 的 arguments */
    private LlmRawResponse parse(String body) throws LlmException {
        JsonElement root;
        try {
            root = JsonParser.parseString(body);
        } catch (RuntimeException e) {
            throw new LlmException(LlmException.ErrorKind.BAD_RESPONSE, 200,
                    "响应不是合法 JSON：" + snippet(body), e);
        }
        if (!root.isJsonObject()) {
            throw new LlmException(LlmException.ErrorKind.BAD_RESPONSE, 200,
                    "响应顶层不是 JSON 对象：" + snippet(body));
        }
        JsonObject rootObject = root.getAsJsonObject();
        JsonArray choices = rootObject.getAsJsonArray("choices");
        if (choices == null || choices.isEmpty()) {
            throw new LlmException(LlmException.ErrorKind.BAD_RESPONSE, 200,
                    "响应缺少 choices：" + snippet(body));
        }
        JsonObject choice = choices.get(0).getAsJsonObject();
        JsonObject message = choice.getAsJsonObject("message");
        if (message == null) {
            throw new LlmException(LlmException.ErrorKind.BAD_RESPONSE, 200,
                    "响应缺少 message：" + snippet(body));
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

    private static LlmException.ErrorKind classify(int code) {
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

    private static String snippet(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= SNIPPET_LIMIT ? text : text.substring(0, SNIPPET_LIMIT) + "…";
    }
}
