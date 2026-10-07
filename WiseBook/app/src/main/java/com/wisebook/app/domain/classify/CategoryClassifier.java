package com.wisebook.app.domain.classify;

import com.wisebook.app.data.local.entity.DraftEntity;
import com.wisebook.app.domain.model.CategoryCandidate;
import com.wisebook.app.domain.model.Direction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 分类判定（D1 §6.4 / D1-A §4）。
 *
 * <p><b>2026-10-07 起只剩两段</b>（HANDOFF 决策 46）：
 *
 * <pre>
 * 第一段  模型给的分类      直接采用；转账给「其他」视为"没给"
 * 兜底    该方向的默认桶     支出/收入 →「其他」/「其他收入」；转账 →「人情」
 * </pre>
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

        Result(Long categoryId, Long rootCategoryId, List<CategoryCandidate> candidates,
               String reason) {
            this.categoryId = categoryId;
            this.rootCategoryId = rootCategoryId;
            // 不用 List.copyOf：Android 上它是 API 31 才有的（minSdk 26），详见 HANDOFF §8
            this.candidates = Collections.unmodifiableList(new ArrayList<>(candidates));
            this.reason = reason;
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

        // ---------------- 第一段：模型给的分类，直接采用
        List<CategoryCandidate> candidates = draft.categoryCandidates == null
                ? Collections.emptyList()
                : draft.categoryCandidates;
        if (!candidates.isEmpty()) {
            CategoryCandidate adopted = candidates.get(0);
            if (tree.rootIdOf(adopted.categoryId) != null
                    && !isMeaninglessForTransfer(adopted.categoryId, direction)) {
                return decided(adopted.categoryId, "采用模型给出的分类：" + adopted.name);
            }
        }

        // ---------------- 兜底：归入该方向的默认桶，而不是反问用户
        Long fallbackId = defaultCategoryId(direction);
        if (fallbackId != null) {
            return decided(fallbackId, "没有可用的分类，归入「" + tree.pathOf(fallbackId) + "」");
        }

        // 连「其他」都没有（分类表没播种好）——只能交给用户，但这不是正常路径
        return new Result(null, null, Collections.emptyList(), "分类字段缺失，且没有可兜底的分类");
    }

    /** 判定完成：同一个分类 id 要同时给出末级、一级与展示名，避免各调用点各算一遍 */
    private Result decided(long categoryId, String reason) {
        CategoryCandidate only = new CategoryCandidate(categoryId, tree.pathOf(categoryId));
        return new Result(categoryId, tree.rootIdOf(categoryId),
                Collections.singletonList(only), reason);
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
