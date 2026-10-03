package com.wisebook.app.domain.model;

/**
 * 「在库里、在文档里、在模型协议里，都用一个固定小写字符串表示」的枚举。
 *
 * <p>为什么不让 Room 直接存 {@code name()}：
 * <ol>
 *   <li>D1 定稿里写的取值是小写（{@code expense} / {@code text_chat} / {@code strict}），
 *       存小写可以对着设计文档逐字核对，不用做心算翻译</li>
 *   <li>这些取值同时是发给模型的 JSON Schema {@code enum} 列表，也是模型实际返回的值
 *       （2026-09-24 实测两家都返回 {@code "expense"}）。三处共用同一份 code，
 *       就不会出现「文档写 expense、库里存 EXPENSE、schema 写 0」这种漂移</li>
 *   <li>存名字而不是 {@code ordinal()}：枚举顺序一旦调整，ordinal 会静默错位</li>
 * </ol>
 */
public interface CodedEnum {

    /** 稳定的持久化 / 协议取值，全小写 */
    String code();
}
