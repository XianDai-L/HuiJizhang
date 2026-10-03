package com.wisebook.app.domain.model;

/**
 * {@link CodedEnum} 的查找工具。
 *
 * <p>每个枚举都自己写一遍 {@code fromCode} 循环是 8 份重复代码，这里收成一份。
 * 泛型签名 {@code <E extends Enum<E> & CodedEnum>} 是 Java 里表达
 * 「既是枚举又实现了某接口」的标准写法。
 */
public final class CodedEnums {

    private CodedEnums() {
    }

    /**
     * 按 code 反查枚举值。
     *
     * @throws IllegalArgumentException code 不属于该枚举。数据库里的值只可能来自本应用写入，
     *                                  查不到说明数据被外部改过或枚举被改过 code，属于必须暴露的问题，
     *                                  不做静默兜底。
     */
    public static <E extends Enum<E> & CodedEnum> E fromCode(Class<E> type, String code) {
        if (code == null) {
            return null;
        }
        for (E constant : type.getEnumConstants()) {
            if (constant.code().equals(code)) {
                return constant;
            }
        }
        throw new IllegalArgumentException("未知的 " + type.getSimpleName() + " 取值：" + code);
    }
}
