package com.wisebook.app.ui.widget;

import android.app.Activity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.wisebook.app.R;
import com.wisebook.app.data.local.entity.CategoryEntity;
import com.wisebook.app.domain.classify.CategoryTree;
import com.wisebook.app.domain.model.CategoryScheme;
import com.wisebook.app.domain.model.Direction;

import java.util.Collections;
import java.util.List;

/**
 * 分类选择器：上面一排「一级分类」，选中哪个就在下面列出它的二级分类。
 *
 * <p>为什么不做成一张几十项的长列表：末级分类有四十来个，
 * 一次全铺出来用户要在一堆不相干的项里找。按一级分组之后，
 * 视线范围从四十项缩到十项以内，而且是「先想大类、再想小类」——
 * 这本来就是人回忆一笔账时天然的思路顺序。
 *
 * <p><b>只列末级、只返回末级</b>，与 D1 §6.2「账目一律挂最末级」一致。
 * 三种情形走同一段渲染逻辑，不写三套分支：
 * <ul>
 *   <li>简单方案：一级分类本身就是末级</li>
 *   <li>标准方案且该一级有二级：列二级</li>
 *   <li>标准方案但该一级没有二级（如收入各项、「其他」）：一级本身就是末级</li>
 * </ul>
 *
 * <p>「取消」是对话框自带的负向按钮。刻意不配任何"点这里退出"的说明文字：
 * 按钮本身已经说清楚了，多一句话只是噪音。
 */
public final class CategoryPicker {

    /** 用户选定了某个分类，参数是可读路径，如 {@code 餐饮>外卖} */
    public interface OnPicked {
        void onPicked(String path);
    }

    private CategoryPicker() {
    }

    /**
     * @param direction 决定用哪一套分类（同名分类在不同方向下是不同的一行）
     * @param scheme    决定一级是否即末级
     */
    public static void show(Activity activity, CategoryTree tree, Direction direction,
                            CategoryScheme scheme, OnPicked onPicked) {
        if (tree == null || direction == null) {
            return;
        }
        List<CategoryEntity> roots = tree.topLevel(direction);
        if (roots.isEmpty()) {
            return;
        }
        new Picker(activity, tree, scheme, roots, onPicked).show();
    }

    private static final class Picker {

        private final Activity activity;
        private final CategoryTree tree;
        private final CategoryScheme scheme;
        private final List<CategoryEntity> roots;
        private final OnPicked onPicked;

        private AlertDialog dialog;
        private LinearLayout tabs;
        private LinearLayout childList;

        Picker(Activity activity, CategoryTree tree, CategoryScheme scheme,
               List<CategoryEntity> roots, OnPicked onPicked) {
            this.activity = activity;
            this.tree = tree;
            this.scheme = scheme == null ? CategoryScheme.STANDARD : scheme;
            this.roots = roots;
            this.onPicked = onPicked;
        }

        void show() {
            View content = LayoutInflater.from(activity)
                    .inflate(R.layout.dialog_pick_category, null);
            tabs = content.findViewById(R.id.root_tabs);
            childList = content.findViewById(R.id.child_list);

            LayoutInflater inflater = LayoutInflater.from(activity);
            for (int i = 0; i < roots.size(); i++) {
                final int index = i;
                TextView tab = (TextView) inflater.inflate(R.layout.item_category_tab, tabs, false);
                tab.setText(roots.get(i).name);
                tab.setOnClickListener(view -> selectRoot(index));
                tabs.addView(tab);
            }

            dialog = new AlertDialog.Builder(activity)
                    .setTitle(R.string.pick_category_title)
                    .setView(content)
                    .setNegativeButton(R.string.action_cancel, null)
                    .create();
            dialog.show();
            selectRoot(0);
        }

        private void selectRoot(int index) {
            // 选中态交给 drawable 的 selector 处理，这里只切换 selected——
            // 不必记住"上一个选中的是哪个"再手动还原
            for (int i = 0; i < tabs.getChildCount(); i++) {
                tabs.getChildAt(i).setSelected(i == index);
            }
            renderLeaves(roots.get(index));
        }

        private void renderLeaves(CategoryEntity root) {
            childList.removeAllViews();
            List<CategoryEntity> children = tree.children(root.categoryId);
            List<CategoryEntity> leaves = (scheme == CategoryScheme.SIMPLE || children.isEmpty())
                    ? Collections.singletonList(root)
                    : children;

            LayoutInflater inflater = LayoutInflater.from(activity);
            for (CategoryEntity leaf : leaves) {
                View row = inflater.inflate(R.layout.item_category_choice, childList, false);
                ((TextView) row.findViewById(R.id.choice_name)).setText(leaf.name);

                TextView pathView = row.findViewById(R.id.choice_path);
                if (leaf.parentId == null) {
                    // 一级即末级时路径就是它自己的名字，再显示一遍只是噪音
                    pathView.setVisibility(View.GONE);
                } else {
                    pathView.setText(root.name + " " + CategoryTree.PATH_SEPARATOR + " " + leaf.name);
                }

                row.setOnClickListener(view -> {
                    onPicked.onPicked(tree.pathOf(leaf.categoryId));
                    dialog.dismiss();
                });
                childList.addView(row);
            }
        }
    }
}
