package com.wisebook.app.domain.classify;

import com.wisebook.app.data.local.entity.DraftEntity;
import com.wisebook.app.domain.model.CategoryCandidate;
import com.wisebook.app.domain.model.Direction;
import com.wisebook.app.domain.model.DraftSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 分类判定（D1 §6.4 / D1-A §4）。
 *
 * <p><b>判定顺序</b>（2026-10-07 删掉商户映射表后是两段，2026-10-08 为账单导入加回第二段，
 * 但只对账单入口生效，见 {@link BillCategoryRules}）：
 *
 * <pre>
 * 第一段  已明确的分类候选   直接采用；转账给「其他」视为"没给"
 * 第二段  账单关键词规则     <b>只对 source == BILL_IMPORT</b>：商户名 → 商品明细
 * 兜底    该方向的默认桶     支出/收入 →「其他」/「其他收入」；转账 →「人情」
 * </pre>
 *
 * <p>第二段排在候选之后，是因为<b>候选是"已经知道答案"</b>（收入转账的「红包」是适配器
 * 按账单语义定下的），而关键词表只是启发式——越确定的越靠前。
 *
 * <p>原先的「商户映射表命中即定」与「平台型商户只挂一级」两层<b>已整体删除</b>。
 * 理由来自实机反馈：<b>截图里出现的商户名不一定与这笔账有关</b>——账单列表里还有别的记录、
 * 页面上还有别处的文字，一旦 OCR 把无关的名字填进 {@code merchant}，
 * 命中映射表就会把分类定死，而"定死"意味着<b>连让用户改一次的机会都省掉了</b>，直接错到底。
 *
 * <p>商户映射表在文字入口其实很值钱（"星巴克 28"零确认就记对了），
 * 但它建立在一个前提上：<b>商户名是用户自己说的、可信</b>。截图场景恰恰不满足这个前提，
 * 而两个入口共用同一个分类器，所以只能整体删掉，不能只给截图入口"降级"。
 *
 * <p>删掉之后分类只剩"模型给"和"落默认桶"，两条都不反问用户：
 * 报表按一级分类聚合（D1 §6.2），二级选错不影响任何统计数字；
 * 真要改，账本里点任意一条账目就能改（决策 31）。
 *
 * <p>本类与 {@link com.wisebook.app.domain.confirm.ConfirmPolicy} 一样直接使用
 * {@link DraftEntity}，理由见那边的类注释：避免两套模型的互转代码引入漏映射 bug。
 */
public final class CategoryClassifier {

    /**
     * 转账的默认分类路径。
     *
     * <p>「人情」是转账最常落的一级，但转账<b>不是只能</b>选人情——
     * 这只是"模型说不出所以然"时的落点，不是硬规则。
     */
    private static final String TRANSFER_DEFAULT_PATH = "人情";

    /** 判定结果 */
    public static final class Result {

        /** 末级分类 id；{@code null} 只在分类表没播种好这种异常情况下出现 */
        public final Long categoryId;

        /** 所属一级分类 id */
        public final Long rootCategoryId;

        /** 判定出的分类（列表形态是为了兼容已存在的 {@code category_candidates} 列） */
        public final List<CategoryCandidate> candidates;

        /** 判定依据：采用模型的，还是兜底到了默认桶。既是排查线索也是界面解释 */
        public final String reason;

        /**
         * 这一笔是不是<b>没判出来、落进了默认桶</b>（其他 / 人情 / 其他收入）。
         *
         * <p>为什么把它写成一个字段而不是让调用方去认 {@link #reason} 的文案：账单导入
         * 要报「有几笔没能从账单里认出分类」，那个数只能照这个事实数
         * ——靠匹配提示语来统计，改一次文案就会静默数错。
         */
        public final boolean defaulted;

        Result(Long categoryId, Long rootCategoryId, List<CategoryCandidate> candidates,
               String reason, boolean defaulted) {
            this.categoryId = categoryId;
            this.rootCategoryId = rootCategoryId;
            // 不用 List.copyOf：Android 上它是 API 31 才有的（minSdk 26），详见 HANDOFF §8
            this.candidates = Collections.unmodifiableList(new ArrayList<>(candidates));
            this.reason = reason;
            this.defaulted = defaulted;
        }

        /** 是否已定下分类 */
        public boolean resolved() {
            return categoryId != null;
        }

        @Override
        public String toString() {
            return reason;
        }
    }

    private final CategoryTree tree;

    public CategoryClassifier(CategoryTree tree) {
        this.tree = tree;
    }

    /**
     * @param draft 已填好方向、商户、商品明细、模型候选的草稿。
     *              大额阈值不再是入参——判据里已经没有任何一条看金额了
     */
    public Result classify(DraftEntity draft) {
        Direction direction = draft.direction;

        // ---------------- 第一段（只对账单导入生效）：商户名与商品明细的关键词规则
        //
        // 只对 source == BILL_IMPORT 生效，这是**刻意的入口隔离**，不是漏了条件：
        // 决策 46 删掉商户映射表，是因为截图里的商户名可能根本不是这笔账的商户
        // （OCR 会把页面上别处的文字读进来），而账单里的「交易对方」是支付平台
        // 给的结构化字段，前提成立。**两个入口的商户可信度不同，所以判据也不同**。
        //
        // ⚠️ 顺序上它排在"候选"之前，这一点是被实测逼出来的：
        // 导入时会把判定结果写回 `category_candidates`（详情页靠它显示分类名），
        // 若候选排在前面，**重跑时就会把上一次自己的结论当成"已明确的候选"**，
        // 依据随之变成一句"采用…给出的分类"——同一笔账，导入时与回看时给出两种说法。
        // 放在前面则每次重跑都从账单字段重新推，结论与依据都稳定。
        //
        // 判据放在这里（而不是藏在导入代码里）的理由与决策 29/31 一致：
        // 账目详情页的「分类依据」是重新跑一遍本方法得到的，判据只有一个出处，
        // 重跑才不会得出另一个结论、把导入的账误标成"你手动改正过分类"。
        if (draft.source == DraftSource.BILL_IMPORT) {
            BillCategoryRules.Hit hit = BillCategoryRules.match(draft.merchant, draft.items);
            if (hit != null) {
                Long id = tree.idOfPath(hit.path, direction);
                if (id != null) {
                    return decided(id, hit.reason);
                }
                // 规则写的分类在本机不存在（用户停用过分类？），或方向对不上
                // （退款的商户名指向一个支出分类）——不当作错误，继续往下走
            }
        }

        // ---------------- 第二段：已明确的分类候选，直接采用
        //
        // 文字/截图入口的候选来自模型；账单入口的候选来自适配器按账单语义定下的路径
        // （如收入转账的「红包」）。
        List<CategoryCandidate> candidates = draft.categoryCandidates == null
                ? Collections.emptyList()
                : draft.categoryCandidates;
        if (!candidates.isEmpty()) {
            CategoryCandidate adopted = candidates.get(0);
            if (tree.rootIdOf(adopted.categoryId) != null
                    && !isMeaninglessForTransfer(adopted.categoryId, direction)) {
                return decided(adopted.categoryId, candidateReason(draft, adopted));
            }
        }

        // ---------------- 兜底：归入该方向的默认桶，而不是反问用户
        Long fallbackId = defaultCategoryId(direction);
        if (fallbackId != null) {
            return new Result(fallbackId, tree.rootIdOf(fallbackId),
                    Collections.singletonList(new CategoryCandidate(fallbackId, tree.pathOf(fallbackId))),
                    "没有可用的分类，归入「" + tree.pathOf(fallbackId) + "」", true);
        }

        // 连「其他」都没有（分类表没播种好）——只能交给用户，但这不是正常路径
        return new Result(null, null, Collections.emptyList(),
                "分类字段缺失，且没有可兜底的分类", true);
    }

    /**
     * 采用候选时的判据文案。
     *
     * <p>账单入口的候选是导入时<b>按账单语义定下的</b>（收入转账 → 红包），
     * 全程没有模型参与；这时候还写"采用模型给出的分类"就是在骗看这一行的人
     * ——而这一行的用处恰恰是让人分清"这是 AI 判的、还是别的什么判的"（决策 31）。
     */
    private static String candidateReason(DraftEntity draft, CategoryCandidate adopted) {
        String prefix = draft.source == DraftSource.BILL_IMPORT
                ? "采用账单里直接给出的分类："
                : "采用模型给出的分类：";
        return prefix + adopted.name;
    }

    /** 判定完成：同一个分类 id 要同时给出末级、一级与展示名，避免各调用点各算一遍 */
    private Result decided(long categoryId, String reason) {
        CategoryCandidate only = new CategoryCandidate(categoryId, tree.pathOf(categoryId));
        return new Result(categoryId, tree.rootIdOf(categoryId),
                Collections.singletonList(only), reason, false);
    }

    /**
     * 某个方向下，「其他」这个兜底项的 id。
     *
     * <p>名字按方向取，是因为 D1-A §1 的支出与收入本来就各有一个兜底项，
     * 而它们在 {@code t_category} 里是<b>不同的两行</b>（同名不同方向各存一条）。
     */
    private Long miscCategoryId(Direction direction) {
        if (direction == Direction.INCOME) {
            return tree.idOfPath("其他收入", Direction.INCOME);
        }
        return tree.idOfPath("其他", direction == null ? Direction.EXPENSE : direction);
    }

    /**
     * 各方向的默认落点。
     *
     * <p><b>转账落「人情」</b>（2026-10-02，见 HANDOFF 决策 32）。转账复用支出分类树
     * （决策 27），而「人情」是其中最贴近转账语义的一级——红包、礼物、请客、
     * 给家里人转钱，本来都是钱的往来。所以转账没有可用的分类时，
     * 「人情」比「其他」有信息量得多。
     *
     * <p>万一「人情」被用户停用了，才退到「其他」——兜底路径不该假设某个分类一定存在。
     */
    private Long defaultCategoryId(Direction direction) {
        if (direction == Direction.INCOME) {
            return tree.idOfPath("其他收入", Direction.INCOME);
        }
        if (direction == Direction.TRANSFER) {
            Long social = tree.idOfPath(TRANSFER_DEFAULT_PATH, Direction.TRANSFER);
            if (social != null) {
                return social;
            }
        }
        return tree.idOfPath("其他", direction == null ? Direction.EXPENSE : direction);
    }

    /**
     * 模型给的这个分类，对一笔转账来说是否等于"没给"。
     *
     * <p>只有「其他」算。实机反馈：模型常常把一笔转账判成「其他」，
     * 而对转账而言「其他」的含义就是"我不知道"——它不是一个有信息量的答案，
     * 却会把这笔钱永久留在一个什么都说明不了的桶里。
     * 与其这样，不如让它落到默认的「人情」；真想改的话账本里点一下就能改。
     */
    private boolean isMeaninglessForTransfer(long categoryId, Direction direction) {
        if (direction != Direction.TRANSFER) {
            return false;
        }
        Long miscId = miscCategoryId(Direction.TRANSFER);
        return miscId != null && miscId.longValue() == categoryId;
    }
}
