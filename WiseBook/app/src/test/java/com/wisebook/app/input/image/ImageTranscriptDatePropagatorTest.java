package com.wisebook.app.input.image;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * 日期标题下推。
 *
 * <p>要守住的就一件事：账单版面里「日期在分组标题上、时刻在记录行里」这个结构，
 * 由代码确定性地补出来，而不是交给模型去猜（`docs/P2-截图实验.md` §3.2 实测它猜不准）。
 */
public class ImageTranscriptDatePropagatorTest {

    @Test
    public void singleTitleAtTopCoversEveryRowBelow() {
        // 用户 2026-10-08 反馈的那张图的形状：标题一次、下面每笔只写时刻
        String transcript = String.join("\n",
                "10月1日 星期四",
                "转账",
                "-100.00",
                "18:34 | 转帐-转给某某",
                "交通",
                "-10.00",
                "16:34 | 某某运输局");
        assertEquals(String.join("\n",
                "10月1日 星期四",
                "转账",
                "-100.00",
                "10月1日 18:34 | 转帐-转给某某",
                "交通",
                "-10.00",
                "10月1日 16:34 | 某某运输局"),
                ImageTranscriptDatePropagator.propagate(transcript));
    }

    @Test
    public void singleTitleAnywhereCoversTheWholeImage() {
        // 截图从中间裁起时，开头那几笔的标题已经不在图里。全图只有一个日期，
        // 说明这几笔也是同一天，用它补上——这是「标题之前也补」的唯一情形
        String transcript = String.join("\n",
                "12:05 | 转帐-转给某某",
                "10月1日 星期四",
                "18:34 | 某某运输局");
        assertEquals(String.join("\n",
                "10月1日 12:05 | 转帐-转给某某",
                "10月1日 星期四",
                "10月1日 18:34 | 某某运输局"),
                ImageTranscriptDatePropagator.propagate(transcript));
    }

    @Test
    public void multipleTitlesOnlyCoverTheirOwnGroup() {
        // 两个标题说明文本确实是分组的：第一个标题之前的记录没有日期可用，原样留着，
        // 由 ConfirmPolicy 的「没读到交易时间」那条把用户拦下来核对——这里不猜
        String transcript = String.join("\n",
                "09:10 | 某某早餐店",
                "10月1日 星期四",
                "18:34 | 转帐-转给某某",
                "9月30日 星期三",
                "08:20 | 某某超市");
        assertEquals(String.join("\n",
                "09:10 | 某某早餐店",
                "10月1日 星期四",
                "10月1日 18:34 | 转帐-转给某某",
                "9月30日 星期三",
                "9月30日 08:20 | 某某超市"),
                ImageTranscriptDatePropagator.propagate(transcript));
    }

    @Test
    public void withoutAnyTitleNothingChanges() {
        String transcript = String.join("\n",
                "18:34 | 转帐-转给某某",
                "-100.00");
        assertEquals(transcript, ImageTranscriptDatePropagator.propagate(transcript));
    }

    @Test
    public void rowsThatAlreadyCarryADateAreLeftAlone() {
        String transcript = String.join("\n",
                "10月1日 星期四",
                "10月1日 18:34 | 转帐-转给某某");
        assertEquals(transcript, ImageTranscriptDatePropagator.propagate(transcript));
    }

    @Test
    public void merchantLineContainingADateIsNotATitle() {
        // 「今天便利店」是商户名，不是标题。整行匹配这条规则防的就是它——
        // 若把它当标题，它就成了"最近的那个日期"，后面每条记录都会被补错
        String transcript = String.join("\n",
                "今天便利店 -20.00",
                "10月1日 星期四",
                "18:34 | 某某");
        assertEquals(String.join("\n",
                "今天便利店 -20.00",
                "10月1日 星期四",
                "10月1日 18:34 | 某某"),
                ImageTranscriptDatePropagator.propagate(transcript));
    }

    @Test
    public void relativeDayTitleIsPropagatedAsWritten() {
        // 「昨天」这种标题原样下推：换算成具体日期是 RelativeTimeResolver 的事，
        // 这里只搬原文，免得日期算术又出现一份实现
        String transcript = String.join("\n", "昨天", "18:34 | 某某");
        assertEquals(String.join("\n", "昨天", "昨天 18:34 | 某某"),
                ImageTranscriptDatePropagator.propagate(transcript));
    }

    @Test
    public void fullWidthColonCountsAsAClock() {
        String transcript = String.join("\n", "10月1日", "18：34 | 某某");
        assertEquals(String.join("\n", "10月1日", "10月1日 18：34 | 某某"),
                ImageTranscriptDatePropagator.propagate(transcript));
    }

    @Test
    public void headerLineCarryingDailyTotalsStillCounts() {
        // 用户 2026-10-08 截图里那张记账 App：标题行右侧还印着当天的「出 X 入 Y」。
        // 原先"标题行只许有日期和星期"，整行匹配失败 → 一整天的记录都推不下日期。
        // 顺带验证多标题场景那条规则仍成立：第一个标题之前的记录不补
        String transcript = String.join("\n",
                "13:23 | 中铁网络",
                "10月3日 星期六 出 0.00 入 200.00",
                "00:35 | 转账-来自lxx",
                "10月2日 星期五 出 22.00 入 0.00",
                "21:09 | 扫二维码付款-给发丝缘");
        assertEquals(String.join("\n",
                "13:23 | 中铁网络",
                "10月3日 星期六 出 0.00 入 200.00",
                "10月3日 00:35 | 转账-来自lxx",
                "10月2日 星期五 出 22.00 入 0.00",
                "10月2日 21:09 | 扫二维码付款-给发丝缘"),
                ImageTranscriptDatePropagator.propagate(transcript));
    }

    @Test
    public void recordLineWithADateIsStillNotATitle() {
        // 放宽行尾之后这条防线必须还在：带日期的记录行不能被当成标题，
        // 否则它后面的记录会被补上它（这里是 10月2日）而不是真正的分组日期
        String transcript = String.join("\n",
                "10月1日 星期四",
                "10月2日 转账 -100.00",
                "19:00 | 某某");
        assertEquals(String.join("\n",
                "10月1日 星期四",
                "10月2日 转账 -100.00",
                "10月1日 19:00 | 某某"),
                ImageTranscriptDatePropagator.propagate(transcript));
    }

    @Test
    public void nullAndBlankInput() {
        assertEquals("", ImageTranscriptDatePropagator.propagate(null));
        assertEquals("", ImageTranscriptDatePropagator.propagate(""));
    }
}
