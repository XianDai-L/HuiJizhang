package com.wisebook.app.domain.draft;

import com.wisebook.app.domain.model.Direction;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

/**
 * 去重键（D1 §7）。
 *
 * <pre>
 * dedupe_key = hash(direction + amount_cents + occurred_at(分钟精度) + 归一化 merchant)
 * </pre>
 *
 * <p><b>命中之后绝不自动合并。</b>连续买两杯一模一样的咖啡是真实存在的，
 * 所以去重键只负责「捞出来给用户看」，判断留给用户（D1 §7）。
 *
 * <p>三个细节都不是随手写的：
 * <ul>
 *   <li><b>分钟精度</b>：秒级时间几乎不可能重合，用秒会让去重完全失效；
 *       精确到分钟才既能把「同一笔被记了两次」捞出来，又不误伤「一分钟内买了两杯」</li>
 *   <li><b>金额用分</b>：与全项目一致，整数比浮点稳定</li>
 *   <li><b>商户归一化</b>：去掉空白、英文转小写，让「滴滴」与「滴 滴」「DIDI」「didi」
 *       落到同一个键上</li>
 * </ul>
 *
 * <p>用 SHA-256 而不是把原文拼起来存：键会被建索引，定长摘要比变长文本省空间，
 * 也避免把商户名原文重复存一份。
 */
public final class DedupeKey {

    /** 分钟精度对应的毫秒数 */
    public static final long MINUTE_MILLIS = 60_000L;

    private DedupeKey() {
    }

    /**
     * @param direction        收支方向，不可为空（落账前必填字段必然齐全）
     * @param amountCents      金额（分）
     * @param occurredAtMillis 交易发生时间（毫秒）
     * @param merchant         商户或对方，可为空
     */
    public static String of(Direction direction, long amountCents,
                            long occurredAtMillis, String merchant) {
        Objects.requireNonNull(direction, "direction 不能为空：落账前必填字段必然齐全");
        String canonical = direction.code()
                + '|' + amountCents
                + '|' + (occurredAtMillis / MINUTE_MILLIS)
                + '|' + normalizeMerchant(merchant);
        return sha256Hex(canonical);
    }

    /**
     * 归一化商户名：去掉所有空白字符，英文字母转小写。
     *
     * <p>不做「去掉『有限公司』后缀」这类智能处理——那种规则很难穷举，
     * 而且一旦过度归一化，会把本来不同的商户合并掉，那比漏去重更糟。
     */
    public static String normalizeMerchant(String merchant) {
        if (merchant == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder(merchant.length());
        for (int i = 0; i < merchant.length(); i++) {
            char c = merchant.charAt(i);
            if (Character.isWhitespace(c)) {
                continue;
            }
            builder.append(Character.toLowerCase(c));
        }
        return builder.toString();
    }

    private static String sha256Hex(String text) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 规定必须提供的算法，正常运行时走不到这里
            throw new IllegalStateException("当前运行时不支持 SHA-256", e);
        }
        byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16));
            hex.append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }
}
