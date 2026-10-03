package com.wisebook.app;

import com.wisebook.app.data.local.entity.CategoryEntity;
import com.wisebook.app.data.local.entity.DraftEntity;
import com.wisebook.app.domain.classify.CategoryTree;
import com.wisebook.app.domain.model.AmountRuleCheck;
import com.wisebook.app.domain.model.ConfidenceFlag;
import com.wisebook.app.domain.model.Direction;
import com.wisebook.app.domain.model.DraftSource;
import com.wisebook.app.domain.model.DraftStatus;
import com.wisebook.app.domain.model.EvidenceType;
import com.wisebook.app.domain.model.OccurredAtSource;
import com.wisebook.app.domain.model.PaymentMethod;

import java.util.ArrayList;
import java.util.List;

/**
 * 单测用的夹具。
 *
 * <p>造一棵<b>刻意包含歧义</b>的小分类树，而不是把 D1-A 的 52 条全搬进来：
 * 「红包」既是一级收入分类、又是「人情」下的二级分类，
 * 「其他」是没有二级的一级分类，工资是收入——这三种形态正好覆盖
 * 分类路径解析里最容易出错的边界。
 */
public final class TestFixtures {

    public static final long CAT_FOOD = 1;              // 餐饮（支出一级）
    public static final long CAT_COFFEE = 2;            // 餐饮>咖啡
    public static final long CAT_TAKEOUT = 3;           // 餐饮>外卖
    public static final long CAT_MISC = 4;              // 其他（支出一级，无二级）
    public static final long CAT_SALARY = 5;            // 工资（收入一级，无二级）
    public static final long CAT_SHOPPING = 6;          // 购物（支出一级）
    public static final long CAT_DAILY = 7;             // 购物>日用
    public static final long CAT_SOCIAL = 8;            // 人情（支出一级）
    public static final long CAT_RED_PACKET_OUT = 9;    // 人情>红包
    public static final long CAT_RED_PACKET_IN = 10;    // 红包（收入一级）
    public static final long CAT_MISC_INCOME = 11;      // 其他收入（收入一级，无二级）

    /** 固定的参照时间：2026-09-24 20:30（周四），供时间解析测试使用 */
    public static final long FIXED_MILLIS = 1_790_000_000_000L;

    private TestFixtures() {
    }

    public static List<CategoryEntity> categoryTree() {
        List<CategoryEntity> categories = new ArrayList<>();
        categories.add(category(CAT_FOOD, null, 1, "餐饮", Direction.EXPENSE));
        categories.add(category(CAT_COFFEE, CAT_FOOD, 2, "咖啡", null));
        categories.add(category(CAT_TAKEOUT, CAT_FOOD, 2, "外卖", null));
        categories.add(category(CAT_MISC, null, 1, "其他", Direction.EXPENSE));
        categories.add(category(CAT_SALARY, null, 1, "工资", Direction.INCOME));
        categories.add(category(CAT_SHOPPING, null, 1, "购物", Direction.EXPENSE));
        categories.add(category(CAT_DAILY, CAT_SHOPPING, 2, "日用", null));
        categories.add(category(CAT_SOCIAL, null, 1, "人情", Direction.EXPENSE));
        categories.add(category(CAT_RED_PACKET_OUT, CAT_SOCIAL, 2, "红包", null));
        categories.add(category(CAT_RED_PACKET_IN, null, 1, "红包", Direction.INCOME));
        categories.add(category(CAT_MISC_INCOME, null, 1, "其他收入", Direction.INCOME));
        return categories;
    }

    public static CategoryTree tree() {
        return new CategoryTree(categoryTree());
    }

    public static CategoryEntity category(long id, Long parentId, int level,
                                          String name, Direction direction) {
        CategoryEntity category = new CategoryEntity();
        category.categoryId = id;
        category.userId = 1L;
        category.parentId = parentId;
        category.level = level;
        category.name = name;
        category.isSystem = true;
        category.isArchived = false;
        category.sortOrder = (int) id;
        category.direction = direction;
        return category;
    }

    /**
     * 一笔「干净」的草稿：D1 §5.2 八条件全满足，
     * 在档位二下且金额较小时应当免确认直落。
     */
    public static DraftEntity cleanDraft() {
        DraftEntity draft = new DraftEntity();
        draft.userId = 1L;
        draft.status = DraftStatus.DRAFT;
        draft.source = DraftSource.TEXT_CHAT;
        draft.evidenceType = EvidenceType.TEXT;
        draft.rawInput = "今天打车28";
        draft.direction = Direction.EXPENSE;
        draft.amountCents = 2800L;
        draft.amountLowerCents = 2800L;
        draft.amountUpperCents = 2800L;
        draft.amountRaw = "二十八";
        draft.amountIsEstimated = false;
        draft.amountRuleCheck = AmountRuleCheck.PASS;
        draft.occurredAt = FIXED_MILLIS;
        draft.occurredAtSource = OccurredAtSource.EXPLICIT;
        draft.categoryId = CAT_TAKEOUT;
        draft.rootCategoryId = CAT_FOOD;
        draft.paymentMethod = PaymentMethod.WECHAT;
        draft.confidenceFlags = new ArrayList<>();
        draft.splitIndex = 0;
        return draft;
    }

    public static List<ConfidenceFlag> flagsOf(ConfidenceFlag... flags) {
        return new ArrayList<>(List.of(flags));
    }
}
