package exp;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 样本与人工基准答案（ground truth）。
 *
 * <p>基准是<b>人工逐句写定的</b>，不是任何模型的输出——它是整场实验唯一可信的参照物。
 *
 * <p>样本设计的三条考虑：
 * <ol>
 *   <li><b>单笔样本占一半（7/15）</b>：多笔方案最容易伤害的其实是单笔场景
 *       （多一次数组解析、多一次拆错的机会）。不给够单笔样本，A 方案的代价就测不出来。</li>
 *   <li><b>多笔样本覆盖不同"连接方式"</b>：逗号并列、连词「和」、不同时间并列、
 *       混合方向（收入 + 转账）、带时间的多笔。</li>
 *   <li><b>放了两个"陷阱"样本</b>：<br>
 *       · {@code s06}「三个人吃了 120」——句中数字比金额多，考"会不会把人/份数拆成笔"<br>
 *       · {@code m08}「这个月买菜加打车一共花了 1000」——<b>合并总额型</b>：
 *         语义上有两笔、但只有一个金额。正确行为是<b>留一笔</b>让用户自己补，
 *         硬拆成两笔会凭空造出一个金额。它是"防过拆"的对照样本。</li>
 * </ol>
 */
public final class Samples {

    /** 一笔人工核定过的期望账目 */
    public static final class Truth {

        /** 期望金额（分） */
        public final long amountCents;
        public final String direction;
        /** 期望时间短语；null 表示该样本的时间只记录、不判分（多笔句中各笔的时间归属易有歧义） */
        public final String occurredAtText;

        Truth(long amountCents, String direction, String occurredAtText) {
            this.amountCents = amountCents;
            this.direction = direction;
            this.occurredAtText = occurredAtText;
        }

        Truth(long amountCents, String direction) {
            this(amountCents, direction, null);
        }
    }

    public static final class Sample {

        public final String id;
        /** 用户原话 */
        public final String text;
        /** 样本类型，结果按它分组 */
        public final String kind;
        /** 期望金额的容差（分）：约数折算这类表达不可能逐分对齐，给一个区间 */
        public final long amountToleranceCents;
        public final List<Truth> truths;
        /** 人工备注，写清这条样本在考什么 */
        public final String note;

        Sample(String id, String text, String kind, long amountToleranceCents, String note,
               Truth... truths) {
            this.id = id;
            this.text = text;
            this.kind = kind;
            this.amountToleranceCents = amountToleranceCents;
            this.note = note;
            this.truths = Collections.unmodifiableList(Arrays.asList(truths));
        }

        public int expectedCount() {
            return truths.size();
        }

        public boolean isMulti() {
            return truths.size() > 1;
        }

        /** 金额是否落在期望区间内 */
        public boolean amountMatches(int index, Long actualCents) {
            if (actualCents == null || index >= truths.size()) {
                return false;
            }
            long expected = truths.get(index).amountCents;
            long diff = Math.abs(actualCents - expected);
            return diff <= amountToleranceCents;
        }
    }

    private Samples() {
    }

    public static List<Sample> all() {
        List<Sample> samples = new ArrayList<>();

        // ---------------------------------------------------------------- 单笔（7）
        samples.add(new Sample("s01", "楼下小馆吃面 28", "单笔·最简单", 0,
                "基线：一句话一笔、金额紧贴动作",
                new Truth(2800L, "expense")));

        samples.add(new Sample("s02", "昨天下午打车 35", "单笔·带时间", 0,
                "D1 §3.6 的例子：时间短语要与金额/方向一起被抄对",
                new Truth(3500L, "expense", "昨天下午")));

        samples.add(new Sample("s03", "买游戏花了三百五左右", "单笔·约数", 5000L,
                "约数折算（产品会折成 35000 分并打 estimated 标记），允许 ±50 元",
                new Truth(35000L, "expense", null)));

        samples.add(new Sample("s04", "发工资了 7500", "单笔·收入", 0,
                "方向必须是 income，且不该被拆成多笔",
                new Truth(750000L, "income")));

        samples.add(new Sample("s05", "给妈转了 1000", "单笔·转账", 0,
                "方向必须是 transfer（D1 决策 32：转账默认落「人情」）",
                new Truth(100000L, "transfer")));

        samples.add(new Sample("s06", "三个人吃了 120", "单笔·干扰数字", 0,
                "陷阱一：句中「三」不是金额、也不是笔数——不许拆成 3 笔",
                new Truth(12000L, "expense")));

        samples.add(new Sample("s07", "今天星巴克 32", "单笔·带商户", 0,
                "商户名与金额都要留住",
                new Truth(3200L, "expense", "今天")));

        // ---------------------------------------------------------------- 多笔（8）
        samples.add(new Sample("m01", "早饭8块，打车17", "多笔2·逗号并列", 0,
                "产品 prompt 里的原例，全项目用它当「多笔」的代名词",
                new Truth(800L, "expense"),
                new Truth(1700L, "expense")));

        samples.add(new Sample("m02", "昨天买菜 56，今天买水果 30", "多笔2·各自带时间", 0,
                "两笔要各自带上自己的时间短语，不能张冠李戴",
                new Truth(5600L, "expense", "昨天"),
                new Truth(3000L, "expense", "今天")));

        samples.add(new Sample("m03", "房租 2500，水电 180，网费 99", "多笔3·并列", 0,
                "三笔全对才算对；从中漏一笔最容易被忽略，所以逐笔比对",
                new Truth(250000L, "expense"),
                new Truth(18000L, "expense"),
                new Truth(9900L, "expense")));

        samples.add(new Sample("m04", "发工资 8000，还花呗 1200", "多笔2·混合方向", 0,
                "一笔收入 + 一笔转账：方向必须各归各的",
                new Truth(800000L, "income"),
                new Truth(120000L, "transfer")));

        samples.add(new Sample("m05", "买了个键盘 399 和鼠标 129", "多笔2·连词", 0,
                "用「和」连接而不是逗号，考拆分是否只认标点",
                new Truth(39900L, "expense"),
                new Truth(12900L, "expense")));

        samples.add(new Sample("m06", "早上一杯咖啡 25，中午请客 260，晚上电影票 45",
                "多笔3·时段并列", 0,
                "三个时段各一笔，时间短语要各归各笔",
                new Truth(2500L, "expense", "早上"),
                new Truth(26000L, "expense", "中午"),
                new Truth(4500L, "expense", "晚上")));

        samples.add(new Sample("m07", "打车 17 块，另外给小李转了 200", "多笔2·混合方向", 0,
                "支出 + 转账；「另外」这类连接词不该被当成新的一笔的开始而丢掉前半句",
                new Truth(1700L, "expense"),
                new Truth(20000L, "transfer")));

        samples.add(new Sample("m08", "这个月买菜加打车一共花了 1000", "陷阱·合并总额", 0,
                "陷阱二：语义两笔、金额只有总额。正确行为是留一笔（金额 1000）等用户自己补，"
                        + "硬拆成两笔等于凭空造金额——这条专门用来抓「过拆」",
                new Truth(100000L, "expense")));

        return Collections.unmodifiableList(samples);
    }
}
