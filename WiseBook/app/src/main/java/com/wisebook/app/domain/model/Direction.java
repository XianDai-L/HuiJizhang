package com.wisebook.app.domain.model;

/**
 * 收支方向（D1 §3.2 / §3.3）。
 *
 * <p>{@code TRANSFER} 保留但 P1 不做转账 UI：D1 §8 决策 7 要求它保留并参与统计，
 * 报表可一键排除，因此枚举里必须有这一项，否则将来加转账要动数据。
 */
public enum Direction implements CodedEnum {

    EXPENSE("expense", "支出"),

    INCOME("income", "收入"),

    TRANSFER("transfer", "转账");

    private final String code;
    private final String label;

    Direction(String code, String label) {
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

    public static Direction fromCode(String code) {
        return CodedEnums.fromCode(Direction.class, code);
    }
}
