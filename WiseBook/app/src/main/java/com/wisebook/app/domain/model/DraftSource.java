package com.wisebook.app.domain.model;

/**
 * 草稿来源入口（D1 §3.2）。
 *
 * <p>P1 只会产生 {@link #TEXT_CHAT}；其余值现在写入枚举，是为了让
 * 「新增一个入口 = 新增一个 input 下的解析器，domain 与 data 零改动」
 * 这条架构约束在类型层面就成立。</p>
 */
public enum DraftSource implements CodedEnum {

    /** 语音输入（P3） */
    VOICE("voice", "语音"),

    /** 截图输入（P2） */
    IMAGE("image", "图片"),

    /** 对话 / 文本输入（P1） */
    TEXT_CHAT("text_chat", "文字"),

    /** 付款通知监听（P4） */
    NOTIFICATION("notification", "通知"),

    /** 账单文件导入（P4） */
    BILL_IMPORT("bill_import", "账单"),

    /** 用户手动填写表单（降级路径，任何阶段都可能走到） */
    MANUAL("manual", "手动");

    private final String code;
    private final String label;

    DraftSource(String code, String label) {
        this.code = code;
        this.label = label;
    }

    @Override
    public String code() {
        return code;
    }

    /**
     * 界面展示用中文名，用作账本列表里的「解析方式」标记。
     *
     * <p>标出来是有意义的：同一笔账可能来自文字、截图或语音，
     * 而用户对这几种入口的信任程度不一样——看到「图片」他会去核对截图，
     * 看到「文字」他会回想自己说过什么。
     */
    public String label() {
        return label;
    }

    public static DraftSource fromCode(String code) {
        return CodedEnums.fromCode(DraftSource.class, code);
    }
}
