package com.wisebook.money;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 约数词典。
 *
 * <p>实现形态为「外置配置 + 纯函数转换」：新增词汇只需在这里加一行，不改解析逻辑。
 * 标记可出现在数字前（不到三十）、数字后（三十左右），或数字与单位之间（三十来块）。
 *
 * <p>折算方向原则（见 D1-A §5.2）：<b>一律向"高估"方向取整，禁止低估</b>。
 * 高估只是轻微误差，且更容易撞上大额阈值触发确认；
 * 低估会漏掉确认流程，因此无上界的表达必须反问。
 */
public final class ApproxDictionary {

    /** 约数标记的语义类型 */
    public enum Kind {
        /** 约等：三十左右、差不多三十 → 区间中值 */
        APPROX,

        /** 约等偏上：三十来块 → 折算偏上 */
        APPROX_UP,

        /** 开下界：三百多 → 上界 = N + 一个数量级 */
        LOWER_BOUND,

        /** 开上界：不到三十 → 折算上界 */
        UPPER_BOUND_EXCLUSIVE,

        /** 闭上界：三十以内、最多三十 → 折算上界 */
        UPPER_BOUND_INCLUSIVE,

        /** 按数量级展开的窄区间：十几、三十几 */
        RANGE_BY_STEP,

        /** 无上界：至少三十、三十以上 → 无法安全折算，必须反问 */
        LOWER_NO_UPPER
    }

    /** 一个约数标记词 */
    public static final class Marker {

        private final String word;
        private final Kind kind;

        public Marker(String word, Kind kind) {
            this.word = word;
            this.kind = kind;
        }

        public String word() {
            return word;
        }

        public Kind kind() {
            return kind;
        }
    }

    /** 命中结果：标记本身 + 剥离该标记后的剩余文本 */
    public static final class Hit {

        private final Marker marker;
        private final String remaining;

        Hit(Marker marker, String remaining) {
            this.marker = marker;
            this.remaining = remaining;
        }

        public Marker marker() {
            return marker;
        }

        public String remaining() {
            return remaining;
        }
    }

    private static final List<Marker> BY_WORD_LENGTH_DESC;

    static {
        List<Marker> markers = new ArrayList<>();

        // 前缀式
        markers.add(new Marker("差不多", Kind.APPROX));
        markers.add(new Marker("大概", Kind.APPROX));
        markers.add(new Marker("大约", Kind.APPROX));
        markers.add(new Marker("约", Kind.APPROX));
        markers.add(new Marker("不到", Kind.UPPER_BOUND_EXCLUSIVE));
        markers.add(new Marker("不足", Kind.UPPER_BOUND_EXCLUSIVE));
        markers.add(new Marker("最多", Kind.UPPER_BOUND_INCLUSIVE));
        markers.add(new Marker("顶多", Kind.UPPER_BOUND_INCLUSIVE));
        markers.add(new Marker("至少", Kind.LOWER_NO_UPPER));
        markers.add(new Marker("最少", Kind.LOWER_NO_UPPER));
        markers.add(new Marker("起码", Kind.LOWER_NO_UPPER));

        // 后缀式
        markers.add(new Marker("左右", Kind.APPROX));
        markers.add(new Marker("上下", Kind.APPROX));
        markers.add(new Marker("来", Kind.APPROX_UP));
        markers.add(new Marker("出头", Kind.APPROX_UP));
        markers.add(new Marker("多", Kind.LOWER_BOUND));
        markers.add(new Marker("几", Kind.RANGE_BY_STEP));
        markers.add(new Marker("以内", Kind.UPPER_BOUND_INCLUSIVE));
        markers.add(new Marker("以下", Kind.UPPER_BOUND_INCLUSIVE));
        markers.add(new Marker("以上", Kind.LOWER_NO_UPPER));
        markers.add(new Marker("往上", Kind.LOWER_NO_UPPER));

        // 按词长降序匹配：保证「差不多」先于「多」、「最多」先于「多」、
        // 「大约」先于「约」被命中。List.sort 是稳定排序，同长度保持登记顺序。
        List<Marker> sorted = new ArrayList<>(markers);
        sorted.sort(Comparator.comparingInt((Marker m) -> m.word().length()).reversed());
        BY_WORD_LENGTH_DESC = Collections.unmodifiableList(sorted);
    }

    private ApproxDictionary() {
    }

    /** 在文本中查找第一个命中的约数标记 */
    public static Hit find(String text) {
        for (Marker marker : BY_WORD_LENGTH_DESC) {
            int index = text.indexOf(marker.word());
            if (index >= 0) {
                String remaining = text.substring(0, index)
                        + text.substring(index + marker.word().length());
                return new Hit(marker, remaining);
            }
        }
        return null;
    }
}
