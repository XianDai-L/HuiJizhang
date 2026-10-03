package com.wisebook.money;

/**
 * 金额的精度语义。
 *
 * <p>口语金额不是精确值，必须保留"精度"信息，用于展示符号与统计口径。
 * 见 D1-A §5 约数词典。
 */
public enum AmountPrecision {

    /** 精确：三十块 */
    EXACT,

    /** 约等：三十左右 / 三十来块 */
    APPROX,

    /** 窄区间：十几块 / 五六十块 */
    RANGE,

    /** 只有下界：三百多 */
    LOWER_BOUND,

    /** 只有上界：不到三十 / 三十以内 */
    UPPER_BOUND
}
