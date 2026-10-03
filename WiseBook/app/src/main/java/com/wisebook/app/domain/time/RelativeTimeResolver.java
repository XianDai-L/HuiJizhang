package com.wisebook.app.domain.time;

import com.wisebook.app.domain.model.OccurredAtSource;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 口语时间 → 具体时刻（D1 §3.6）。
 *
 * <p>「昨天下午打车 30」今晚录入时，{@code occurred_at} 必须落到昨天，
 * 而 {@code created_at} 是今晚——两者的区别就靠这个解析器划出来。
 *
 * <p><b>输入契约：传入的是「模型抽出的时间短语」，不是整句原文。</b>
 * 这一点很关键：像「然后天气不错」里的「后天」如果拿整句去匹配就会被误判成两天后。
 * 让模型先把时间短语摘出来（tool schema 里的 {@code occurredAtText}），
 * 本解析器只在一个短语上做确定性判断，误判面就小得多。
 *
 * <p><b>为什么不让模型直接给时间戳：</b>日期算术（「上周三」到底是几号）
 * 是模型很容易算错、而且错了不易察觉的地方。把这件事交给 {@code java.time}，
 * 结果可复现、可单测。
 *
 * <p>覆盖范围（按 D1 的落点取舍，不追求穷举）：
 * <table border="1">
 *   <caption>支持的时间表达</caption>
 *   <tr><th>类型</th><th>例子</th></tr>
 *   <tr><td>相对日</td><td>今天 / 昨天 / 前天 / 大前天 / 明天 / 后天 / 大后天 / 今晚 / 昨晚 / 今早</td></tr>
 *   <tr><td>周内</td><td>周三 / 星期三 / 礼拜天 / 这周五 / 上周一 / 上上周日 / 下周二</td></tr>
 *   <tr><td>月日</td><td>9月5号 / 九月五号</td></tr>
 *   <tr><td>时刻</td><td>下午3点 / 晚上8点半 / 3点20分 / 15:30</td></tr>
 *   <tr><td>时段</td><td>早上 / 上午 / 中午 / 下午 / 傍晚 / 晚上 / 夜里 / 半夜 / 凌晨</td></tr>
 *   <tr><td>模糊</td><td>刚才 / 刚刚</td></tr>
 * </table>
 *
 * <p>已知取舍（不阻塞 P1）：
 * <ul>
 *   <li>只有时刻没有时段时（「3点」）不猜上午下午，按原样取 3:00</li>
 *   <li>「12月31号」这类跨年的表达：算出来若在未来，当作去年</li>
 *   <li>方言与「上旬 / 月底」这类粗粒度表达不解析，落到 fallback</li>
 * </ul>
 */
public final class RelativeTimeResolver {

    /** 结果：时刻 + 来源标记 + 命中的片段 */
    public static final class Result {

        public final long occurredAtMillis;
        public final OccurredAtSource source;
        /** 命中的时间片段，用于排查与界面展示；没命中时为空串 */
        public final String matchedText;

        Result(long occurredAtMillis, OccurredAtSource source, String matchedText) {
            this.occurredAtMillis = occurredAtMillis;
            this.source = source;
            this.matchedText = matchedText;
        }

        /** 是否用了「当前时间」兜底 */
        public boolean isFallback() {
            return source == OccurredAtSource.FALLBACK;
        }

        @Override
        public String toString() {
            return source + "@" + occurredAtMillis + (matchedText.isEmpty() ? "" : " ← " + matchedText);
        }
    }

    // ------------------------------------------------------------ 时段定义

    private enum PartOfDay {
        MORNING(0),   // 上午侧：12 点算 0 点
        NOON(1),      // 中午侧：小于 11 点加 12
        AFTERNOON(2); // 下午侧：小于 12 点加 12

        final int adjust;

        PartOfDay(int adjust) {
            this.adjust = adjust;
        }
    }

    private static final class Period {

        final String word;
        final int defaultHour;
        final PartOfDay part;

        Period(String word, int defaultHour, PartOfDay part) {
            this.word = word;
            this.defaultHour = defaultHour;
            this.part = part;
        }
    }

    /** 词长降序：保证「凌晨」先于「晨」、「晚上」先于「晚」被命中 */
    private static final Period[] PERIODS = {
            new Period("凌晨", 5, PartOfDay.MORNING),
            new Period("清晨", 6, PartOfDay.MORNING),
            new Period("一早", 8, PartOfDay.MORNING),
            new Period("早上", 8, PartOfDay.MORNING),
            new Period("早晨", 8, PartOfDay.MORNING),
            new Period("上午", 10, PartOfDay.MORNING),
            new Period("晌午", 12, PartOfDay.NOON),
            new Period("中午", 12, PartOfDay.NOON),
            new Period("下午", 15, PartOfDay.AFTERNOON),
            new Period("傍晚", 18, PartOfDay.AFTERNOON),
            new Period("晚上", 20, PartOfDay.AFTERNOON),
            new Period("夜里", 23, PartOfDay.AFTERNOON),
            new Period("半夜", 23, PartOfDay.AFTERNOON)
    };

    private static final String[] FUZZY_WORDS = {"刚才", "刚刚", "刚刚才", "刚"};

    // ------------------------------------------------------------ 正则

    /** (大)?(前|昨|今|明|后)(天|日|儿|早|晨|晚|夜) —— 一个正则覆盖六种前缀与所有组合 */
    private static final Pattern RELATIVE_DAY =
            Pattern.compile("(大)?(前|昨|今|明|后)(天|日|儿|早|晨|晚|夜)");

    /** (这|本|上上|上|下)?(周|星期|礼拜)([一二三四五六日天]) */
    private static final Pattern WEEKDAY =
            Pattern.compile("(这|本|上上|上|下)?(?:周|星期|礼拜)([一二三四五六日天])");

    /** 9月5号 / 九月五号 */
    private static final Pattern MONTH_DAY = Pattern.compile(
            "([0-9]{1,2}|[零〇一二三四五六七八九十]{1,3})月"
                    + "([0-9]{1,2}|[零〇一二三四五六七八九十]{1,3})[号日]");

    /** 15:30 */
    private static final Pattern CLOCK_COLON =
            Pattern.compile("([0-9]{1,2})[:：]([0-9]{1,2})");

    /** 下午3点半 / 3点20分 / 8点 */
    private static final Pattern CLOCK_HOUR = Pattern.compile(
            "([0-9]{1,2}|[零〇一二三四五六七八九十两]{1,3})[点时時]"
                    + "([0-9]{1,2}|[零〇一二三四五六七八九十]{1,3}|半)?分?");

    private RelativeTimeResolver() {
    }

    /** 用当前时间作为参照 */
    public static Result resolve(String timePhrase, ZoneId zone) {
        return resolve(timePhrase, LocalDateTime.now(zone), zone);
    }

    /**
     * @param timePhrase 模型抽出的时间短语，可为空
     * @param reference  参照时间（「今天」相对它计算）。显式传入是为了让单测不受时钟影响
     * @param zone       时区
     */
    public static Result resolve(String timePhrase, LocalDateTime reference, ZoneId zone) {
        String text = timePhrase == null ? "" : timePhrase.replaceAll("\\s+", "");
        if (text.isEmpty()) {
            return new Result(toMillis(reference, zone), OccurredAtSource.FALLBACK, "");
        }

        LocalDate date = null;
        LocalTime explicitTime = null;
        Period period = null;
        // 命中的片段全部记下来，排查时能一眼看出「结果是从哪几个词拼出来的」
        List<String> hits = new ArrayList<>();

        // ---------------- 1) 相对日
        Matcher dayMatcher = RELATIVE_DAY.matcher(text);
        if (dayMatcher.find()) {
            date = reference.toLocalDate().plusDays(dayOffset(dayMatcher.group(1), dayMatcher.group(2)));
            hits.add(dayMatcher.group());
            // 「今晚」「昨晚」的基字本身就带时段信息
            String base = dayMatcher.group(3);
            if ("早".equals(base) || "晨".equals(base)) {
                period = periodOf("早上");
            } else if ("晚".equals(base) || "夜".equals(base)) {
                period = periodOf("晚上");
            }
        }

        // ---------------- 2) 周内
        if (date == null) {
            Matcher weekMatcher = WEEKDAY.matcher(text);
            if (weekMatcher.find()) {
                date = weekdayDate(reference.toLocalDate(),
                        weekMatcher.group(1), weekMatcher.group(2));
                hits.add(weekMatcher.group());
            }
        }

        // ---------------- 3) 月日
        if (date == null) {
            Matcher monthDayMatcher = MONTH_DAY.matcher(text);
            if (monthDayMatcher.find()) {
                LocalDate candidate = monthDay(reference.toLocalDate(),
                        number(monthDayMatcher.group(1)), number(monthDayMatcher.group(2)));
                if (candidate != null) {
                    date = candidate;
                    hits.add(monthDayMatcher.group());
                }
            }
        }

        // ---------------- 4) 时刻（「15:30」优先于「15点」）
        Matcher colonMatcher = CLOCK_COLON.matcher(text);
        if (colonMatcher.find()) {
            int hour = safeInt(colonMatcher.group(1), -1);
            int minute = safeInt(colonMatcher.group(2), -1);
            if (hour >= 0 && hour <= 23 && minute >= 0 && minute <= 59) {
                explicitTime = LocalTime.of(hour, minute);
                hits.add(colonMatcher.group());
            }
        } else {
            Matcher clockMatcher = CLOCK_HOUR.matcher(text);
            if (clockMatcher.find()) {
                Integer hour = number(clockMatcher.group(1));
                if (hour != null && hour >= 0 && hour <= 24) {
                    explicitTime = LocalTime.of(Math.min(hour, 23), minuteOf(clockMatcher.group(2)));
                    hits.add(clockMatcher.group());
                }
            }
        }

        // ---------------- 5) 时段
        Period found = findPeriod(text);
        if (found != null) {
            if (period == null) {
                period = found;
            }
            hits.add(found.word);
        }

        // ---------------- 6) 模糊词（刚才 / 刚刚）
        if (hits.isEmpty()) {
            for (String fuzzy : FUZZY_WORDS) {
                if (text.contains(fuzzy)) {
                    hits.add(fuzzy);
                    break;
                }
            }
        }

        if (hits.isEmpty()) {
            return new Result(toMillis(reference, zone), OccurredAtSource.FALLBACK, "");
        }
        String matchedText = String.join("+", hits);

        // ---------------- 组合
        LocalDate resolvedDate = date != null ? date : reference.toLocalDate();
        LocalTime resolvedTime = resolveTime(explicitTime, period, reference);
        LocalDateTime resolved = LocalDateTime.of(resolvedDate, resolvedTime);

        // 来源标记按「文本里到底给了什么」来定：
        // 有明确日期 → explicit；信息模糊但至少命中了点什么 → inferred。
        // （什么都没命中时上面已经提前返回 fallback 了）
        OccurredAtSource source = date != null
                ? OccurredAtSource.EXPLICIT
                : OccurredAtSource.INFERRED;

        return new Result(toMillis(resolved, zone), source, matchedText);
    }

    /** 显式时刻优先；否则按时段的默认钟点；再否则沿用参照时间的钟点 */
    private static LocalTime resolveTime(LocalTime explicitTime, Period period,
                                        LocalDateTime reference) {
        if (explicitTime != null) {
            if (period == null) {
                return explicitTime;
            }
            return adjustToPartOfDay(explicitTime, period.part);
        }
        if (period != null) {
            return LocalTime.of(period.defaultHour, 0);
        }
        return reference.toLocalTime().truncatedTo(ChronoUnit.MINUTES);
    }

    private static LocalTime adjustToPartOfDay(LocalTime time, PartOfDay part) {
        int hour = time.getHour();
        switch (part) {
            case AFTERNOON:
                // 下午三点 → 15:00；下午 15 点 → 保持
                if (hour < 12) {
                    hour += 12;
                }
                break;
            case NOON:
                // 中午一点 → 13:00；中午 12 点 → 保持
                if (hour < 11) {
                    hour += 12;
                }
                break;
            case MORNING:
            default:
                // 凌晨 12 点 → 0:00
                if (hour == 12) {
                    hour = 0;
                }
                break;
        }
        return LocalTime.of(hour, time.getMinute());
    }

    // ------------------------------------------------------------------ 词法

    private static long dayOffset(String prefix, String recent) {
        boolean big = "大".equals(prefix);
        switch (recent) {
            case "前":
                return big ? -3 : -2;
            case "昨":
                return -1;
            case "今":
                return 0;
            case "明":
                return 1;
            case "后":
                return big ? 3 : 2;
            default:
                return 0;
        }
    }

    private static LocalDate weekdayDate(LocalDate reference, String prefix, String weekdayText) {
        int target = weekdayIndex(weekdayText);          // 周一 = 1 … 周日 = 7
        int current = reference.getDayOfWeek().getValue();
        int delta = target - current;
        if (prefix == null || "这".equals(prefix) || "本".equals(prefix)) {
            return reference.plusDays(delta);
        }
        if ("上".equals(prefix)) {
            return reference.plusDays(delta - 7);
        }
        if ("上上".equals(prefix)) {
            return reference.plusDays(delta - 14);
        }
        return reference.plusDays(delta + 7);           // 下
    }

    private static int weekdayIndex(String text) {
        switch (text) {
            case "一":
                return 1;
            case "二":
                return 2;
            case "三":
                return 3;
            case "四":
                return 4;
            case "五":
                return 5;
            case "六":
                return 6;
            default:
                return 7;                                // 日 / 天
        }
    }

    /** 记账记的都是过去：算出来在未来，就当是去年 */
    private static LocalDate monthDay(LocalDate reference, Integer month, Integer dayOfMonth) {
        if (month == null || dayOfMonth == null
                || month < 1 || month > 12 || dayOfMonth < 1 || dayOfMonth > 31) {
            return null;
        }
        LocalDate candidate;
        try {
            candidate = LocalDate.of(reference.getYear(), month, dayOfMonth);
        } catch (DateTimeException e) {
            return null;                                 // 例如 2 月 30 号
        }
        return candidate.isAfter(reference) ? candidate.minusYears(1) : candidate;
    }

    private static Period findPeriod(String text) {
        for (Period period : PERIODS) {
            if (text.contains(period.word)) {
                return period;
            }
        }
        return null;
    }

    private static Period periodOf(String word) {
        for (Period period : PERIODS) {
            if (period.word.equals(word)) {
                return period;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ 数字

    /** 阿拉伯数字或中文数字 → 整数；无法解析返回 {@code null} */
    static Integer number(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        boolean allDigits = true;
        for (int i = 0; i < text.length(); i++) {
            if (!Character.isDigit(text.charAt(i))) {
                allDigits = false;
                break;
            }
        }
        if (allDigits) {
            return safeInt(text, -1) < 0 ? null : Integer.valueOf(text);
        }
        return chineseNumber(text);
    }

    /**
     * 中文数字 0–59。
     *
     * <p>只处理口语时间的取值范围，所以不必像金额那样考虑「万 / 省略单位」——
     * 点钟与分钟最大就是 59，形式只有「十五 / 二十 / 二十三」三种。
     */
    private static Integer chineseNumber(String text) {
        int ten = text.indexOf('十');
        if (ten < 0) {
            return digit(text);
        }
        String head = text.substring(0, ten);
        String tail = text.substring(ten + 1);
        Integer tens = head.isEmpty() ? 1 : digit(head);
        Integer units = tail.isEmpty() ? 0 : digit(tail);
        if (tens == null || units == null) {
            return null;
        }
        return tens * 10 + units;
    }

    private static Integer digit(String text) {
        if (text == null || text.length() != 1) {
            return null;
        }
        switch (text.charAt(0)) {
            case '零':
            case '〇':
                return 0;
            case '一':
            case '壹':
                return 1;
            case '二':
            case '两':
            case '贰':
                return 2;
            case '三':
            case '叁':
                return 3;
            case '四':
            case '肆':
                return 4;
            case '五':
            case '伍':
                return 5;
            case '六':
            case '陆':
                return 6;
            case '七':
            case '柒':
                return 7;
            case '八':
            case '捌':
                return 8;
            case '九':
            case '玖':
                return 9;
            default:
                return null;
        }
    }

    /** 「3点半」的「半」= 30 分 */
    private static int minuteOf(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        if ("半".equals(text)) {
            return 30;
        }
        Integer minute = number(text);
        return minute == null || minute < 0 || minute > 59 ? 0 : minute;
    }

    private static int safeInt(String text, int fallback) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static long toMillis(LocalDateTime dateTime, ZoneId zone) {
        return dateTime.atZone(zone).toInstant().toEpochMilli();
    }
}
