package com.wisebook.app.domain.classify;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.wisebook.app.TestFixtures;
import com.wisebook.app.data.local.entity.CategoryEntity;
import com.wisebook.app.domain.model.CategoryScheme;
import com.wisebook.app.domain.model.Direction;

import org.junit.Test;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 分类树（D1 §6.2 / §6.3）。
 */
public class CategoryTreeTest {

    private final CategoryTree tree = TestFixtures.tree();

    @Test
    public void pathOfHandlesBothLevels() {
        assertEquals("餐饮", tree.pathOf(TestFixtures.CAT_FOOD));
        assertEquals("餐饮>咖啡", tree.pathOf(TestFixtures.CAT_COFFEE));
    }

    @Test
    public void idOfPathResolvesTopLevelAndSecondLevel() {
        assertEquals(Long.valueOf(TestFixtures.CAT_FOOD),
                tree.idOfPath("餐饮", Direction.EXPENSE));
        assertEquals(Long.valueOf(TestFixtures.CAT_COFFEE),
                tree.idOfPath("餐饮>咖啡", Direction.EXPENSE));
        assertEquals("路径里带空格也应能解析",
                Long.valueOf(TestFixtures.CAT_COFFEE),
                tree.idOfPath("餐饮 > 咖啡", Direction.EXPENSE));
    }

    @Test
    public void idOfPathRespectsDirection() {
        assertNull("「工资」是收入分类，不能用在一笔支出上",
                tree.idOfPath("工资", Direction.EXPENSE));
        assertEquals(Long.valueOf(TestFixtures.CAT_SALARY),
                tree.idOfPath("工资", Direction.INCOME));
    }

    @Test
    public void sameNameInTwoDirectionsIsDisambiguated() {
        // D1-A 里「红包」既是一级收入分类，又是「人情」下的二级分类。
        // 只给名字无法区分，方向一给就唯一了——这正是 idOfPath 必须带 direction 的原因。
        assertEquals(Long.valueOf(TestFixtures.CAT_RED_PACKET_IN),
                tree.idOfPath("红包", Direction.INCOME));
        assertEquals(Long.valueOf(TestFixtures.CAT_RED_PACKET_OUT),
                tree.idOfPath("人情>红包", Direction.EXPENSE));
        assertNull("光说「红包」在一笔支出里是歧义的，解析不出来",
                tree.idOfPath("红包", Direction.EXPENSE));
    }

    @Test
    public void idOfPathReturnsNullForUnknown() {
        assertNull(tree.idOfPath("不存在的分类", Direction.EXPENSE));
        assertNull(tree.idOfPath("餐饮>不存在的二级", Direction.EXPENSE));
        assertNull(tree.idOfPath("工资>什么都好", Direction.INCOME));
        assertNull(tree.idOfPath(null, Direction.EXPENSE));
        assertNull(tree.idOfPath("餐饮", null));
        assertNull(tree.idOfPath("", Direction.EXPENSE));
    }

    @Test
    public void rootIdOfMapsSecondLevelToItsParent() {
        assertEquals(Long.valueOf(TestFixtures.CAT_FOOD), tree.rootIdOf(TestFixtures.CAT_COFFEE));
        assertEquals(Long.valueOf(TestFixtures.CAT_FOOD), tree.rootIdOf(TestFixtures.CAT_FOOD));
        assertNull(tree.rootIdOf(999L));
    }

    @Test
    public void standardSchemeOffersSecondLevelAndChildlessTopLevel() {
        List<String> names = namesOf(tree.selectable(Direction.EXPENSE, CategoryScheme.STANDARD));
        assertTrue("有二级的一级：账目挂到二级", names.contains("咖啡"));
        assertTrue(names.contains("外卖"));
        assertTrue(names.contains("日用"));
        assertTrue("没有二级的一级本身就是末级（D1 §6.2）", names.contains("其他"));
        assertTrue("一级分类自己不作为选项出现", !names.contains("餐饮"));
    }

    @Test
    public void simpleSchemeOffersTopLevelOnly() {
        List<String> names = namesOf(tree.selectable(Direction.EXPENSE, CategoryScheme.SIMPLE));
        assertEquals(List.of("餐饮", "其他", "购物", "人情"), names);
    }

    @Test
    public void incomeHasNoSecondLevelInEitherScheme() {
        assertEquals(List.of("工资", "红包", "其他收入"),
                namesOf(tree.selectable(Direction.INCOME, CategoryScheme.STANDARD)));
        assertEquals(List.of("工资", "红包", "其他收入"),
                namesOf(tree.selectable(Direction.INCOME, CategoryScheme.SIMPLE)));
    }

    @Test
    public void switchBetweenSchemesChangesNothingInTheData() {
        // D1 §6.3「双向自由切换、零迁移」：两套方案读的是同一棵树，
        // 差别只在选择范围，切换不用动任何账目数据
        assertTrue(tree.selectable(Direction.EXPENSE, CategoryScheme.SIMPLE).stream()
                .allMatch(CategoryEntity::isTopLevel));
        assertTrue(tree.selectable(Direction.EXPENSE, CategoryScheme.STANDARD).size()
                > tree.selectable(Direction.EXPENSE, CategoryScheme.SIMPLE).size());
    }

    @Test
    public void unknownDirectionSafety() {
        assertTrue(tree.topLevel(null).isEmpty());
        assertTrue(tree.children(999L).isEmpty());
    }

    private static List<String> namesOf(List<CategoryEntity> categories) {
        return categories.stream().map(c -> c.name).collect(Collectors.toList());
    }
}
