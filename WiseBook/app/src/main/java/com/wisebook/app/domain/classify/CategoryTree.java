package com.wisebook.app.domain.classify;

import com.wisebook.app.data.local.entity.CategoryEntity;
import com.wisebook.app.domain.model.CategoryScheme;
import com.wisebook.app.domain.model.Direction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 分类树（D1 §6）。
 *
 * <p>把一张平铺的分类表在内存里组装成可查询的树。分类总量只有几十条，
 * 一次性取回内存再组装，比在 SQL 里递归查询简单得多，也<b>便于脱离数据库单测</b>。
 *
 * <p>两个关键能力：
 * <ul>
 *   <li><b>路径 ↔ id 互查</b>：路径形如 {@code 餐饮>咖啡}。模型只知道分类名、
 *       不知道本地自增 id，所以「按路径换 id」是模型输出落地的必经一步</li>
 *   <li><b>按方向取可选分类</b>：简单方案只到一级，标准方案到二级；
 *       收入一级本身没有二级，它在两种方案下都是末级。这正是 D1 §6.2
 *       「账目一律挂最末级」能成立的前提</li>
 * </ul>
 *
 * <p><b>为什么按路径查必须带上方向：</b>D1-A 里「红包」既是一级收入分类，
 * 又是「人情」下的二级分类。只给名字无法区分，方向一给就唯一了。
 */
public final class CategoryTree {

    /** 分类路径分隔符，如 {@code 餐饮>咖啡}，与 D1-A 的写法一致 */
    public static final String PATH_SEPARATOR = ">";

    private final List<CategoryEntity> all;
    private final Map<Long, CategoryEntity> byId;
    private final Map<Long, List<CategoryEntity>> childrenByParent;
    private final Map<Direction, List<CategoryEntity>> topLevelByDirection;

    /**
     * @param categories 未停用的分类，顺序应是 {@code level, sort_order, category_id}，
     *                   这样构造出来的树天然是排好序的
     */
    public CategoryTree(List<CategoryEntity> categories) {
        // 本文件是 Collections/Arrays 那套写法的示例：不用 List.of / List.copyOf，
        // 它们在 Android 上分别是 API 30 / API 31 才有的，而本项目 minSdk 26（详见 HANDOFF §8）
        List<CategoryEntity> source = categories == null ? Collections.emptyList() : categories;
        // 这里必须真拷一份：调用方可能持有传入的那个 list
        this.all = Collections.unmodifiableList(new ArrayList<>(source));

        Map<Long, CategoryEntity> index = new LinkedHashMap<>();
        Map<Long, List<CategoryEntity>> children = new LinkedHashMap<>();
        Map<Direction, List<CategoryEntity>> tops = new EnumMap<>(Direction.class);
        for (Direction direction : Direction.values()) {
            tops.put(direction, new ArrayList<>());
        }

        for (CategoryEntity category : source) {
            index.put(category.categoryId, category);
            if (category.parentId == null) {
                // 一级分类必须带方向；direction 为空的一级是脏数据，进不了树而不报错
                if (category.direction != null) {
                    tops.get(category.direction).add(category);
                }
            } else {
                children.computeIfAbsent(category.parentId, key -> new ArrayList<>())
                        .add(category);
            }
        }

        this.byId = Collections.unmodifiableMap(index);
        Map<Long, List<CategoryEntity>> frozenChildren = new LinkedHashMap<>();
        for (Map.Entry<Long, List<CategoryEntity>> entry : children.entrySet()) {
            frozenChildren.put(entry.getKey(),
                    Collections.unmodifiableList(new ArrayList<>(entry.getValue())));
        }
        this.childrenByParent = Collections.unmodifiableMap(frozenChildren);

        Map<Direction, List<CategoryEntity>> frozenTops = new EnumMap<>(Direction.class);
        for (Map.Entry<Direction, List<CategoryEntity>> entry : tops.entrySet()) {
            frozenTops.put(entry.getKey(),
                    Collections.unmodifiableList(new ArrayList<>(entry.getValue())));
        }
        this.topLevelByDirection = Collections.unmodifiableMap(frozenTops);
    }

    public List<CategoryEntity> all() {
        return all;
    }

    public boolean isEmpty() {
        return all.isEmpty();
    }

    /** @return 找不到返回 {@code null} */
    public CategoryEntity byId(long categoryId) {
        return byId.get(categoryId);
    }

    public List<CategoryEntity> children(long parentId) {
        List<CategoryEntity> children = childrenByParent.get(parentId);
        return children == null ? Collections.emptyList() : children;
    }

    /**
     * 某方向下的一级分类。
     *
     * <p><b>转账方向复用支出分类树。</b>D1-A §1 只定义了支出与收入两套一级分类，
     * 转账没有自己的一套——但转账确实需要能选分类，否则记一笔转账时
     * 分类选择器会是一片空白。
     *
     * <p>复用的依据是业务语义：转账是<b>资金流出</b>，落在支出侧的分类框架里才说得通；
     * 「人情」是其中最贴近转账语义的一级（红包、礼物、请客也都是钱的往来），
     * 所以解析时转账的默认分类取它。
     *
     * <p>这样处理而不是给 {@code t_category} 加一套 {@code direction=transfer} 的记录，
     * 是因为后者要动已定稿的分类清单（D1-A §1）并配一版迁移，
     * 只为了让几个分类名多一个副本——收益对不上代价。
     */
    public List<CategoryEntity> topLevel(Direction direction) {
        List<CategoryEntity> tops = topLevelByDirection.get(direction);
        if ((tops == null || tops.isEmpty()) && direction == Direction.TRANSFER) {
            tops = topLevelByDirection.get(Direction.EXPENSE);
        }
        return tops == null ? Collections.emptyList() : tops;
    }

    /**
     * 所属一级分类 id。一级返回自身，二级返回父级。
     *
     * @return 分类不存在时返回 {@code null}
     */
    public Long rootIdOf(long categoryId) {
        CategoryEntity category = byId.get(categoryId);
        if (category == null) {
            return null;
        }
        return category.parentId == null ? category.categoryId : category.parentId;
    }

    /**
     * 展示路径，如 {@code 餐饮>咖啡}；一级分类就是它自己。
     *
     * @return 分类不存在时返回 {@code null}
     */
    public String pathOf(long categoryId) {
        CategoryEntity category = byId.get(categoryId);
        if (category == null) {
            return null;
        }
        if (category.parentId == null) {
            return category.name;
        }
        CategoryEntity parent = byId.get(category.parentId);
        return parent == null
                ? category.name
                : parent.name + PATH_SEPARATOR + category.name;
    }

    /**
     * 路径 → 分类 id。
     *
     * @param path      形如 {@code 餐饮} 或 {@code 餐饮>咖啡}
     * @param direction 用于消歧：同名分类（如「红包」）在不同方向下是不同的一行
     * @return 找不到返回 {@code null}
     */
    public Long idOfPath(String path, Direction direction) {
        if (path == null || direction == null) {
            return null;
        }
        String trimmed = path.replace(" ", "").trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        int separator = trimmed.indexOf(PATH_SEPARATOR);
        if (separator < 0) {
            for (CategoryEntity top : topLevel(direction)) {
                if (top.name.equals(trimmed)) {
                    return top.categoryId;
                }
            }
            return null;
        }
        String parentName = trimmed.substring(0, separator);
        String childName = trimmed.substring(separator + PATH_SEPARATOR.length());
        for (CategoryEntity top : topLevel(direction)) {
            if (!top.name.equals(parentName)) {
                continue;
            }
            for (CategoryEntity child : children(top.categoryId)) {
                if (child.name.equals(childName)) {
                    return child.categoryId;
                }
            }
        }
        return null;
    }

    /**
     * 某方向下用户/模型可以选的<b>末级</b>分类（D1 §6.2）。
     *
     * <ul>
     *   <li>简单方案：只返回一级——账目挂在末级即一级</li>
     *   <li>标准方案：有二级的一级返回其二级；没有二级的一级（如收入各项、「其他」）
     *       本身就是末级，直接返回它自己</li>
     * </ul>
     */
    public List<CategoryEntity> selectable(Direction direction, CategoryScheme scheme) {
        List<CategoryEntity> result = new ArrayList<>();
        for (CategoryEntity top : topLevel(direction)) {
            List<CategoryEntity> children = children(top.categoryId);
            if (scheme == CategoryScheme.SIMPLE || children.isEmpty()) {
                result.add(top);
            } else {
                result.addAll(children);
            }
        }
        // result 是本地新建的，包装成不可变视图即可
        return Collections.unmodifiableList(result);
    }
}
