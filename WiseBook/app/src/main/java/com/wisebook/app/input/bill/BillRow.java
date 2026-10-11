package com.wisebook.app.input.bill;

import com.wisebook.app.domain.model.Direction;
import com.wisebook.app.domain.model.PaymentMethod;

import java.util.List;

/**
 * 账单里的一行，<b>已经翻译成项目自己的语言</b>。
 *
 * <p>各厂商的适配器（现在只有微信）负责把源文件的一行填成这个对象，
 * 之后的事情——去重、分类、落账——全部只认它。
 * 这就是「多入口 + 统一管线」在账单入口上的落点：<b>供应商差异止步于适配器</b>。
 *
 * <p>字段用 public 可变字段而不是构造器：与本项目 {@code EntryCsv.OutRow} 同一种写法
 * （那是"一份扁平数据"的既有约定），适配器逐格填的时候最省事。
 *
 * <p>只保留项目真正用得上的字段（2026-10-08 用户明确要求）：微信的
 * 「当前状态」「商户单号」「商户单号」这类不落库——
 * 「退款原样导入」这个口径下，方向由「收/支」列就能定，状态列没有判据作用。
 */
public final class BillRow {

    /** 源文件里的行号（1 起）。坏了哪一行要能说得出来，用户才好回 Excel 里核对 */
    public int sourceRow;

    public Direction direction;

    public long amountCents;

    /** 发生时间（毫秒） */
    public long occurredAt;

    /** 商户 / 对方 */
    public String merchant;

    /** 商品明细；没有则为 {@code null} */
    public List<String> items;

    public String note;

    public PaymentMethod paymentMethod;

    /**
     * 由确定性规则直接得出的分类路径（如 {@code 交通>打车} / {@code 红包}），可空。
     *
     * <p>它是"候选"而不是"结论"：最终分类照样要过 {@code CategoryClassifier}，
     * 只是把这条路径当模型给的候选喂进去。这样<b>判据只有一个出处</b>，
     * 账目详情页重跑判定时能得出同一个结果，不会把导入的账误标成"你手动改正过分类"。
     */
    public String categoryPath;

    /** 落进 {@code t_draft.raw_input} 的来源摘要，用于详情页溯源 */
    public String rawInput;
}
