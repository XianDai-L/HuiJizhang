package com.wisebook.llm;

/**
 * 把一张图片读成文本（OCR / 视觉转写）。
 *
 * <p>它是截图入口的<b>第一步</b>：图片进来、文本出去，之后的金额双通道校验、
 * 分类判定、去重、档位判定全部复用文字入口那条管线。换句话说，
 * <b>截图不是"另一条解析路径"，只是"文本的另一个来源"</b>
 * ——这正是 D2 §6.3 里路线 B 的架构主张，也是 P2 实验选它的原因。
 *
 * <p>接口只吃字节与 mimeType，不碰 Android 的 {@code Uri} / {@code Bitmap}：
 * 纯 JVM 模块因此仍不依赖 Android，测试可以直接喂一张本地图片的字节。
 *
 * <p>实现可能失败（网络、鉴权、限流），失败一律抛 {@link LlmException}，
 * 由调用方决定"降级为手动填写"还是"提示重试"。
 */
public interface ImageReader {

    /**
     * @param imageBytes  图片字节
     * @param mimeType    如 {@code image/jpeg}，用于拼 data URL
     * @param instruction 业务指令（如「逐条平铺、金额照抄、不要 Markdown」）。
     *                    把指令放 system 的实现会用到它；只认固定模板的 OCR 模型会忽略它
     * @return 识别出的文本
     */
    String readImage(byte[] imageBytes, String mimeType, String instruction) throws LlmException;
}
