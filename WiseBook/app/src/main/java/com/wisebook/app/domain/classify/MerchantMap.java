package com.wisebook.app.domain.classify;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 商户映射表（D1-A §3）。
 *
 * <p>它是「能确定的地方不交给模型」这条原则里最先落下的一层：
 * 星巴克就是餐饮 &gt; 咖啡，没必要问模型，也<b>永不摇摆</b>（D1 §6.4 层 2）。
 *
 * <p><b>匹配规则：关键词按长度降序，命中即止。</b>
 * 这不是可有可无的细节，D1-A 的表里本来就有包含关系：
 * <ul>
 *   <li>「京东健康」必须优先于「京东」——前者是医疗 &gt; 药品，后者是平台型购物</li>
 *   <li>「美团买药」「美团单车」必须优先于「美团」——前者是垂直商户，后者是平台型餐饮</li>
 * </ul>
 * 若不按长度排序，先命中的会是短关键词，这三条映射会全部落到错误分支。
 * 同样的手法在 {@code money-parser} 的 {@code ApproxDictionary} 里也用过。
 *
 * <p>表里只有关键词与分类路径，没有代码分支——新增词汇只改这一份常量（D1-A §3）。
 */
public final class MerchantMap {

    /** 与 {@code t_setting.merchant_map_version} 对应 */
    public static final int VERSION = 1;

    /** 一条映射：关键词 → 分类路径（垂直商户）或一级分类名（平台型商户） */
    private static final class Entry {

        final String keyword;
        final String value;

        Entry(String keyword, String value) {
            // 关键词也归一化，好让 Manner / manner / MANNER 都能命中
            this.keyword = normalize(keyword);
            this.value = value;
        }
    }

    // -------------------------------------------------- 垂直商户（命中即定）

    // 以下两张表刻意用 Collections.unmodifiableList(Arrays.asList(...)) 而不是 List.of：
    // 后者在 Android 上需要 API 30，而本项目 minSdk 26（详见 HANDOFF §8）
    private static final List<Entry> VERTICAL = Collections.unmodifiableList(Arrays.asList(
            // 咖啡店（现制咖啡）
            new Entry("星巴克", "餐饮>咖啡"),
            new Entry("瑞幸", "餐饮>咖啡"),
            new Entry("库迪", "餐饮>咖啡"),
            new Entry("Manner", "餐饮>咖啡"),
            // 奶茶店
            new Entry("蜜雪冰城", "餐饮>饮品"),
            new Entry("茶百道", "餐饮>饮品"),
            new Entry("喜茶", "餐饮>饮品"),
            new Entry("古茗", "餐饮>饮品"),
            new Entry("奈雪的茶", "餐饮>饮品"),
            // 便利店（D1-A §2.2：便利店买的瓶装饮料归饮品）
            new Entry("美宜佳", "餐饮>饮品"),
            new Entry("全家", "餐饮>饮品"),
            new Entry("7-11", "餐饮>饮品"),
            new Entry("罗森", "餐饮>饮品"),
            new Entry("便利蜂", "餐饮>饮品"),
            // 加油
            new Entry("中国石化", "交通>加油"),
            new Entry("中国石油", "交通>加油"),
            // 打车
            new Entry("滴滴", "交通>打车"),
            new Entry("高德打车", "交通>打车"),
            new Entry("花小猪", "交通>打车"),
            // 共享单车
            new Entry("哈啰", "交通>共享单车"),
            new Entry("美团单车", "交通>共享单车"),
            new Entry("青桔", "交通>共享单车"),
            // 影音订阅
            new Entry("腾讯视频", "娱乐>影音"),
            new Entry("爱奇艺", "娱乐>影音"),
            new Entry("优酷", "娱乐>影音"),
            new Entry("芒果TV", "娱乐>影音"),
            new Entry("网易云", "娱乐>影音"),
            new Entry("QQ音乐", "娱乐>影音"),
            new Entry("B站大会员", "娱乐>影音"),
            // 游戏
            new Entry("Steam", "娱乐>游戏"),
            new Entry("Epic", "娱乐>游戏"),
            new Entry("米哈游", "娱乐>游戏"),
            new Entry("网易游戏", "娱乐>游戏"),
            // 买药
            new Entry("美团买药", "医疗>药品"),
            new Entry("京东健康", "医疗>药品"),
            new Entry("老百姓大药房", "医疗>药品"),
            // 水电燃气
            new Entry("国家电网", "居住>水电燃气"),
            new Entry("南方电网", "居住>水电燃气"),
            new Entry("燃气公司", "居住>水电燃气"),
            // 话费
            new Entry("中国移动", "通讯>话费"),
            new Entry("中国联通", "通讯>话费"),
            new Entry("中国电信", "通讯>话费")
    ));

    // ------------------------------------------------ 平台型商户（规则映射）

    private static final List<Entry> PLATFORM = Collections.unmodifiableList(Arrays.asList(
            new Entry("淘宝", "购物"),
            new Entry("天猫", "购物"),
            new Entry("京东", "购物"),
            new Entry("拼多多", "购物"),
            new Entry("美团", "餐饮"),
            new Entry("饿了么", "餐饮"),
            new Entry("盒马", "购物"),
            new Entry("永辉", "购物"),
            new Entry("沃尔玛", "购物"),
            new Entry("华润万家", "购物"),
            new Entry("大润发", "购物")
    ));

    /** 按关键词长度降序，保证长关键词先被命中（见类注释里的三个反例） */
    private static final List<Entry> VERTICAL_BY_LENGTH = longestFirst(VERTICAL);

    private static final List<Entry> PLATFORM_BY_LENGTH = longestFirst(PLATFORM);

    private MerchantMap() {
    }

    /**
     * 垂直商户硬映射。
     *
     * @return 命中则返回分类路径，如 {@code 餐饮>咖啡}
     */
    public static Optional<String> verticalPath(String merchant) {
        return lookup(VERTICAL_BY_LENGTH, merchant);
    }

    /**
     * 平台型商户的兜底一级分类。
     *
     * <p>平台型<b>无法硬映射</b>——淘宝可能是任何东西。所以只在「没有商品名」时
     * 用它挂到一级，不猜二级，也<b>不再标不确定</b>（挂一级在报表层面本来就是对的）。
     *
     * @return 命中则返回一级分类名，如 {@code 购物}
     */
    public static Optional<String> platformTopLevel(String merchant) {
        return lookup(PLATFORM_BY_LENGTH, merchant);
    }

    /** 是否是平台型商户 */
    public static boolean isPlatformMerchant(String merchant) {
        return platformTopLevel(merchant).isPresent();
    }

    /** 是否命中任一映射表（垂直或平台），用于 D1-A §4 判据 2 */
    public static boolean isKnown(String merchant) {
        return verticalPath(merchant).isPresent() || isPlatformMerchant(merchant);
    }

    // ------------------------------------------------------------------ 内部

    private static Optional<String> lookup(List<Entry> entries, String merchant) {
        String normalized = normalize(merchant);
        if (normalized.isEmpty()) {
            return Optional.empty();
        }
        for (Entry entry : entries) {
            if (normalized.contains(entry.keyword)) {
                return Optional.of(entry.value);
            }
        }
        return Optional.empty();
    }

    /** 去掉所有空白、英文转小写 */
    static String normalize(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                continue;
            }
            builder.append(Character.toLowerCase(c));
        }
        return builder.toString();
    }

    private static List<Entry> longestFirst(List<Entry> entries) {
        List<Entry> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator.comparingInt((Entry entry) -> entry.keyword.length()).reversed());
        // sorted 是本地新建的，包装成不可变视图即可，不必再拷一份
        return Collections.unmodifiableList(sorted);
    }
}
