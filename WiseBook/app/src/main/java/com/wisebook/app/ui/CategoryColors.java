package com.wisebook.app.ui;

import com.wisebook.app.R;

/**
 * 分类配色。
 *
 * <p>抽成一个类而不是在适配器、报表页各写一份，是因为<b>两处必须用同一套颜色</b>：
 * 用户先在账本里把「餐饮」认成橙色，翻到报表时那块橙色还得是餐饮，
 * 否则颜色就成了装饰，不再是信息。
 *
 * <p>取色的键是<b>一级分类 id</b>而不是列表下标：下标会随排序和数据量变化，
 * 同一个分类今天是橙色、明天变蓝，用户就会以为分类被人改过。
 *
 * <p>色相只有五组加一个兜底：一级分类本来就不该有十几个，
 * 颜色再多反而失去「一眼归类」的意义。
 */
public final class CategoryColors {

    private static final int[] BACKGROUND = {
            R.color.cat_food_bg,
            R.color.cat_move_bg,
            R.color.cat_shop_bg,
            R.color.cat_life_bg,
            R.color.cat_fun_bg,
            R.color.cat_other_bg,
    };

    private static final int[] FOREGROUND = {
            R.color.cat_food,
            R.color.cat_move,
            R.color.cat_shop,
            R.color.cat_life,
            R.color.cat_fun,
            R.color.cat_other,
    };

    private CategoryColors() {
    }

    /** 色块底色的颜色资源 id */
    public static int backgroundOf(long rootCategoryId) {
        return BACKGROUND[index(rootCategoryId)];
    }

    /** 色块文字与占比条的颜色资源 id */
    public static int foregroundOf(long rootCategoryId) {
        return FOREGROUND[index(rootCategoryId)];
    }

    /**
     * 取模前先 floorMod：分类 id 由数据库自增，理论上不会是负数，
     * 但 {@code %} 对负数返回负值，会让数组下标越界。用 floorMod 一次挡掉。
     */
    private static int index(long rootCategoryId) {
        return (int) Math.floorMod(rootCategoryId, BACKGROUND.length);
    }
}
