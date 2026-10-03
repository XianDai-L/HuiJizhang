package exp;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 样本与人工基准答案（ground truth）。
 *
 * <p>基准由人工逐字读图确定，<b>不是模型输出</b>——这是整个实验唯一可信的参照物。
 * 字段为 {@code null} 表示"这张图里无法判定，不计入对错"，不是"期望留空"。
 *
 * <p>三张图刻意覆盖了三种版面，因为"截图"在真实使用里不是只有一种形态：
 * 单笔详情页信息最全（最干净的基准），账单列表流与第三方记账 App 明细页
 * 都是多笔混排。产品当前的工具定义只能产出一笔草稿＋一个笔数，
 * 所以多笔样本只评估"最上面那一笔"与"笔数识别"。
 */
public final class Samples {

    /** 样本图目录；含真实消费记录，禁止提交、禁止外发 */
    public static final Path DIR = Paths.get("D:/HuiJi/p2-experiment/samples");

    /** 一笔人工核对过的期望账目 */
    public static final class Truth {

        public final String merchant;
        public final long amountCents;
        public final String direction;
        public final String occurredAtText;

        Truth(String merchant, long amountCents, String direction, String occurredAtText) {
            this.merchant = merchant;
            this.amountCents = amountCents;
            this.direction = direction;
            this.occurredAtText = occurredAtText;
        }
    }

    public static final class Sample {

        public final String id;
        public final String file;
        /** 版面类型，用于结果分组 */
        public final String kind;
        public final List<Truth> truths;
        /** 该图的"时间"字段能否严格判定；false 时只记录模型输出，不计分 */
        public final boolean timeScorable;
        public final String timeNote;

        Sample(String id, String file, String kind, boolean timeScorable, String timeNote,
               Truth... truths) {
            this.id = id;
            this.file = file;
            this.kind = kind;
            this.timeScorable = timeScorable;
            this.timeNote = timeNote;
            this.truths = Collections.unmodifiableList(Arrays.asList(truths));
        }

        public Path path() {
            return DIR.resolve(file);
        }

        public Truth first() {
            return truths.get(0);
        }
    }

    private Samples() {
    }

    public static List<Sample> all() {
        return Collections.unmodifiableList(Arrays.asList(
                // 微信支付「我的账单」列表流；时间「昨天 21:09」是分组标签，
                // 归属在版面上有歧义（图 s2 显示同一笔的时间是 10月02日 21:09:26），
                // 所以这一条的时间不计分——把有歧义的东西算进准确率等于给自己造误差
                new Sample("s1", "s1.jpg", "账单列表（多笔）", false,
                        "时间标签「昨天 21:09」在两张卡片之间，归属有歧义",
                        new Truth("邓三姐", 700L, "expense", null),
                        new Truth("发丝缘", 1500L, "expense", null)),

                // 微信支付单笔交易详情页：字段最全，是主基准
                new Sample("s2", "s2.jpg", "单笔详情", true, null,
                        new Truth("发丝缘", 1500L, "expense", "2026年10月02日 21:09:26")),

                // 第三方记账 App「记账本」明细页：收支转账混排、商户在备注里
                new Sample("s3", "s3.jpg", "记账明细（多笔）", true, null,
                        new Truth(null, 10000L, "transfer", "18:34"),
                        new Truth("滴滴出行", 1630L, "expense", "15:10"),
                        new Truth(null, 800L, "income", "14:42"),
                        new Truth(null, 3400L, "transfer", "14:37"),
                        new Truth(null, 1000L, "transfer", "13:28"),
                        new Truth("钦州市泰禾公共交通有限公司", 1000L, "expense", "16:34"))));
    }
}
