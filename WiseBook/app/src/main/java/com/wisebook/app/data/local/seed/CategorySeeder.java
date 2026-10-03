package com.wisebook.app.data.local.seed;

import com.wisebook.app.data.local.WiseBookDatabase;
import com.wisebook.app.data.local.dao.CategoryDao;
import com.wisebook.app.data.local.entity.CategoryEntity;
import com.wisebook.app.domain.model.Direction;

import java.util.ArrayList;
import java.util.List;

/**
 * 系统预置分类的播种（D1-A §1 / §2 / §6）。
 *
 * <p>时机（D1-A §6）：<b>首次启动一次性把一级 + 二级全建好</b>，
 * 之后用户选「简单 / 标准」只改 {@code t_setting.category_scheme}，
 * <b>不动分类表</b>。所以本类不关心方案是哪一个——
 * 两套方案共享同一棵树，只是「允不允许选到二级」的开关不同。
 *
 * <p>分类名与层级关系在这里是<b>唯一来源</b>，连数据库迁移里的回填
 * 也是从这份常量生成的（见 {@code Migrations.MIGRATION_1_2}），
 * 避免同一份清单在两处各抄一遍然后慢慢漂移。
 */
public final class CategorySeeder {

    private CategorySeeder() {
    }

    /** 一棵子树：一级分类名 + 它的二级分类名 */
    private static final class Node {

        final String name;
        final String[] children;

        Node(String name, String... children) {
            this.name = name;
            this.children = children;
        }
    }

    /**
     * 支出一级 + 二级（D1-A §2）。
     *
     * <p>「其他」刻意不设二级：兜底项再分层没有意义，账目本来就可以挂在末级即一级。
     */
    private static final Node[] EXPENSE_TREE = {
            new Node("餐饮", "买菜", "外卖", "咖啡", "饮品", "零食", "聚餐"),
            new Node("交通", "公交地铁", "打车", "加油", "停车", "机票火车", "共享单车"),
            new Node("购物", "服饰", "数码", "日用", "美妆", "母婴"),
            new Node("居住", "房租", "水电燃气", "物业", "家居"),
            new Node("通讯", "话费", "宽带"),
            new Node("娱乐", "游戏", "影音", "旅行", "运动"),
            new Node("医疗", "门诊", "药品", "体检"),
            new Node("教育", "学费", "书籍", "培训"),
            new Node("人情", "红包", "礼物", "请客"),
            new Node("其他")
    };

    /** 收入一级（D1-A §1.2：收入不设二级，一级粒度已足够） */
    private static final Node[] INCOME_TREE = {
            new Node("工资"),
            new Node("奖金"),
            new Node("兼职"),
            new Node("投资"),
            new Node("红包"),
            new Node("其他收入")
    };

    /** 支出一级分类名。迁移回填与测试都要用，所以外置成方法而不是散落各处的字面量 */
    public static List<String> expenseLevelOneNames() {
        return namesOf(EXPENSE_TREE);
    }

    /** 收入一级分类名 */
    public static List<String> incomeLevelOneNames() {
        return namesOf(INCOME_TREE);
    }

    private static List<String> namesOf(Node[] tree) {
        List<String> names = new ArrayList<>(tree.length);
        for (Node node : tree) {
            names.add(node.name);
        }
        return names;
    }

    /**
     * 表里一条分类都没有时才播种。
     *
     * <p><b>必须在后台线程调用</b>，且整体包在一个事务里：
     * 五十多行的插入要么全成功要么全失败，否则会出现「播了一半」的分类树。
     *
     * <p>判据用「表是否为空」而不是额外的标记位：空表就是没播过种，
     * 这个判据天然幂等，且应用被杀在播种中途时下次启动会重新补上。
     */
    public static void seedIfEmpty(WiseBookDatabase db) {
        db.runInTransaction(() -> {
            CategoryDao dao = db.categoryDao();
            long userId = WiseBookDatabase.DEFAULT_USER_ID;
            if (dao.count(userId) > 0) {
                return;
            }
            int order = 0;
            order = insertTree(dao, EXPENSE_TREE, Direction.EXPENSE, userId, order);
            insertTree(dao, INCOME_TREE, Direction.INCOME, userId, order);
        });
    }

    /** @return 下一个可用的排序号 */
    private static int insertTree(CategoryDao dao, Node[] tree, Direction direction,
                                  long userId, int startOrder) {
        int order = startOrder;
        for (Node node : tree) {
            CategoryEntity parent = new CategoryEntity();
            parent.userId = userId;
            parent.parentId = null;
            parent.level = 1;
            parent.name = node.name;
            parent.isSystem = true;
            parent.isArchived = false;
            parent.sortOrder = order++;
            // 方向只记在一级上，二级靠父级继承（见 CategoryEntity#direction）
            parent.direction = direction;
            long parentId = dao.insert(parent);

            int childOrder = 0;
            for (String childName : node.children) {
                CategoryEntity child = new CategoryEntity();
                child.userId = userId;
                child.parentId = parentId;
                child.level = 2;
                child.name = childName;
                child.isSystem = true;
                child.isArchived = false;
                child.sortOrder = childOrder++;
                child.direction = null;
                dao.insert(child);
            }
        }
        return order;
    }
}
