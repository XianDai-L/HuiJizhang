package com.wisebook.app.input.image;

/**
 * 一张待解析的图片。
 *
 * <p>只带字节、mimeType 与一个展示名，<b>刻意不带 Uri 也不带路径</b>：
 * 用户明确决定「截图原图不留」（HANDOFF 决策 37），解析完这段字节就该被丢弃。
 * 如果这里留着路径，`t_draft.evidence_ref` 迟早会被人顺手填上——
 * 那时候"不留原图"就变成一句空话，而隐私代价是不可逆的。
 *
 * <p>展示名（如「相册选择」）只用于结果行与日志，不落库。
 */
public final class ImageInput {

    public final byte[] bytes;
    /** 如 {@code image/jpeg}；拼 data URL 用 */
    public final String mimeType;
    public final String displayName;

    public ImageInput(byte[] bytes, String mimeType, String displayName) {
        this.bytes = bytes;
        this.mimeType = mimeType;
        this.displayName = displayName;
    }
}
