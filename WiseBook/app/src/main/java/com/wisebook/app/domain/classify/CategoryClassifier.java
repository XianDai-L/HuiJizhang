package com.wisebook.app.domain.classify;

import com.wisebook.app.data.local.entity.DraftEntity;
import com.wisebook.app.domain.model.CategoryCandidate;
import com.wisebook.app.domain.model.Direction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * 分类判定（D1 §6.4 / D1-A §3）。
 *
 * <p>设计原则：<b>能确定的地方不交给模型。</b>所以判定分三层，越靠前越确定：
 *
 * <pre>
 * 第一层  商户映射表   星巴克 → 餐饮&gt;咖啡         命中即定
 * 第二层  平台型规则   平台型 + 无商品名 → 只挂一级
 * 第三层  模型给的分类  直接采用；没有可用的就归入「其他」
 * </pre>
 *
 * <p><b>分类判定不再产生任何"不确定"信号</b>（2026-10-02，见 HANDOFF 决策 30）。
 *
 * <p>原设计有一整套摇摆机制：候选 ≥2 个、商户未命中且金额达大额阈值、原文含模糊词，
 * 命中任一条就把这笔账推进确认流程。加上"平台型无商品名"也标不确定，
 * 结果是分类几乎每笔都要用户点一下——而<b>报表按一级分类聚合</b>，
 * 二级选错本来就不影响任何统计数字。
 *
 * <p>换句话说：那套判据换来的"安全"用户感受不到，付出的"每次都要点一下"感受得很清楚。
 * 现在分类要么由确定性规则直接定（前两层），要么采用模型给的（第三层），
 * 要么落到该方向的默认桶（支出/收入是「其他」，转账是「人情」）——<b>三条路都不再反问用户</b>。
 *
 * <p>需要分类确认时用户仍然可以改：账本里点任意一条账目就能改正分类。
 *
 * <p>本类与 {@link com.wisebook.app.domain.confirm.ConfirmPolicy} 一样直接使用
 * {@link DraftEntity}，理由见那边的类注释：避免两套模型的互转代码引入漏映射 bug。
 * 前置条件是草稿的 {@code direction} / {@code merchant} / {@code items} /
 * {@code categoryCandidates} 已经填好，其中 {@code categoryCandidates} 是
 * 「模型给的分类路径已解析成 id」之后的产物。
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

        /** 判定依据：命中哪一层、还是兜底到了「其他」。既是排查线索也是界面解释 */
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
        String merchant = draft.merchant;

        // ---------------- 第一层：商户映射表，命中即定，永不摇摆
        Optional<String> vertical = MerchantMap.verticalPath(merchant);
        if (vertical.isPresent()) {
            Long mappedId = tree.idOfPath(vertical.get(), direction);
            if (mappedId != null) {
                return decided(mappedId, "商户映射表命中：" + merchant + " → " + vertical.get());
            }
            // 映射命中了，但本地分类树里找不到这个路径（用户改过分类体系）。
            // 不硬报错，退到下一层用模型给的分类。
        }

        // ---------------- 第二层：平台型商户且无商品名，不猜二级，只挂一级
        if (MerchantMap.isPlatformMerchant(merchant) && noItems(draft)) {
            Optional<String> topLevel = MerchantMap.platformTopLevel(merchant);
            if (topLevel.isPresent()) {
                Long topId = tree.idOfPath(topLevel.get(), direction);
                if (topId != null) {
                    // 只挂一级，不猜二级，也<b>不标不确定</b>（2026-10-02）。
                    // 挂一级在报表层面完全正确（按一级聚合），
                    // 而"我们不知道具体买了什么"这件事，用户看分类名就知道，不必再拦一次
                    return decided(topId,
                            "平台型商户「" + merchant + "」没有商品名，只挂一级「"
                                    + topLevel.get() + "」");
                }
            }
        }

        // ---------------- 第三层：模型给的分类，直接采用
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
     * <p><b>转账落「人情」</b>（2026-10-02，见 HANDOFF 决策 31）。转账复用支出分类树
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

    private static boolean noItems(DraftEntity draft) {
        return draft.items == null || draft.items.isEmpty();
    }

    /**
     * 候选名用<b>完整路径</b>（{@code 餐饮&gt;咖啡}）而不是末级名。
     *
     * <p>「AI 处理详情」面板要靠它一眼看出选到了哪一支；只写「咖啡」的话，
     * 用户没法判断这是「餐饮&gt;咖啡」还是「娱乐&gt;咖啡」。
     */
}
