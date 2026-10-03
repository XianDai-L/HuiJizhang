package com.wisebook.app.domain.model;

/**
 * 支付方式（D1 §3.2）。
 *
 * <p><b>一处刻意的调整：</b>D1 §3.2 把这个枚举的取值写成中文（`微信 | 支付宝 | 现金 |
 * 银行卡 | 其他`），而同一张表里的 `direction` 写的是英文（`expense | income`）。
 * 这里统一成「英文 code + 中文 label」：
 * <ul>
 *   <li>code 要和「发给模型的 enum 取值」「数据库存储值」完全一致，
 *       中文取值在模型侧更容易被改写（实测模型对小写英文枚举的服从度更高）</li>
 *   <li>label 只管展示，将来要国际化时把 label 换成 strings.xml 即可，不动数据</li>
 * </ul>
 */
public enum PaymentMethod implements CodedEnum {

    WECHAT("wechat", "微信"),

    ALIPAY("alipay", "支付宝"),

    CASH("cash", "现金"),

    BANK_CARD("bank_card", "银行卡"),

    OTHER("other", "其他");

    private final String code;
    private final String label;

    PaymentMethod(String code, String label) {
        this.code = code;
        this.label = label;
    }

    @Override
    public String code() {
        return code;
    }

    /** 界面展示用中文名 */
    public String label() {
        return label;
    }

    public static PaymentMethod fromCode(String code) {
        return CodedEnums.fromCode(PaymentMethod.class, code);
    }
}
