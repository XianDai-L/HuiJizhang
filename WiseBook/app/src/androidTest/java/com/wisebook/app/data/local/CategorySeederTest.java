package com.wisebook.app.data.local;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import android.content.Context;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.wisebook.app.data.local.entity.CategoryEntity;
import com.wisebook.app.data.local.seed.CategorySeeder;
import com.wisebook.app.domain.model.Direction;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

/**
 * 预置分类播种的验证（D1-A §1 / §2 / §6）。
 *
 * <p>用内存库：数据不必落盘，用例之间天然隔离，跑完即消失。
 * {@code allowMainThreadQueries} 在这里是必要的——instrumentation 测试默认跑在主线程，
 * 而生产代码里刻意不加这个开关，就是为了防止有人误在主线程碰数据库。
 */
@RunWith(AndroidJUnit4.class)
public class CategorySeederTest {

    private static final long USER_ID = WiseBookDatabase.DEFAULT_USER_ID;

    /** 10 项支出一级 + 36 项支出二级 + 6 项收入一级 */
    private static final int EXPECTED_TOTAL = 52;

    private WiseBookDatabase db;

    @Before
    public void setUp() {
        Context context = ApplicationProvider.getApplicationContext();
        db = Room.inMemoryDatabaseBuilder(context, WiseBookDatabase.class)
                .allowMainThreadQueries()
                .build();
    }

    @After
    public void tearDown() {
        db.close();
    }

    @Test
    public void seedsOnceAndIsIdempotent() {
        CategorySeeder.seedIfEmpty(db);
        List<CategoryEntity> afterFirst = db.categoryDao().findAll(USER_ID);
        assertEquals("10 项支出一级 + 36 项支出二级 + 6 项收入一级",
                EXPECTED_TOTAL, afterFirst.size());

        // 再播一次不应产生重复：判据是「表是否为空」，天然幂等
        CategorySeeder.seedIfEmpty(db);
        assertEquals("重复播种不得产生重复行",
                EXPECTED_TOTAL, db.categoryDao().findAll(USER_ID).size());
    }

    @Test
    public void onlyTopLevelCategoriesCarryDirection() {
        CategorySeeder.seedIfEmpty(db);

        for (CategoryEntity category : db.categoryDao().findAll(USER_ID)) {
            if (category.isTopLevel()) {
                assertNotNull("一级分类必须带方向：" + category.name, category.direction);
            } else {
                assertNull("二级分类的方向应为 null（继承父级）：" + category.name,
                        category.direction);
            }
        }
    }

    @Test
    public void incomeCategoriesHaveNoChildren() {
        CategorySeeder.seedIfEmpty(db);

        List<CategoryEntity> tops = db.categoryDao().findTopLevel(USER_ID);
        assertEquals("支出一级 10 项 + 收入一级 6 项", 16, tops.size());

        for (CategoryEntity top : tops) {
            List<CategoryEntity> children = db.categoryDao().findChildren(top.categoryId);
            if (top.direction == Direction.INCOME) {
                assertEquals("收入不设二级（D1-A §1.2）：" + top.name, 0, children.size());
            }
        }
        // 「其他」是支出里的兜底项，同样不设二级
        for (CategoryEntity top : tops) {
            if ("其他".equals(top.name)) {
                assertEquals("「其他」不设二级", 0,
                        db.categoryDao().findChildren(top.categoryId).size());
            }
        }
    }
}
