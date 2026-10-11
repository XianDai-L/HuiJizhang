package com.wisebook.app.ui.settings;

import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.wisebook.app.R;
import com.wisebook.app.WiseBookApp;
import com.wisebook.app.data.local.entity.CategoryEntity;
import com.wisebook.app.data.local.entity.LedgerEntity;
import com.wisebook.app.domain.classify.CategoryTree;
import com.wisebook.app.domain.model.CategoryScheme;
import com.wisebook.app.domain.model.Direction;
import com.wisebook.app.ui.EdgeToEdgeInsets;
import com.wisebook.app.ui.ledger.MainActivity;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 账本选择（2026-10-11，多账本）。
 *
 * <p>版式借鉴"游戏存档选择"：一个账本一张卡，点卡片即切换，<b>立刻生效</b>。
 *
 * <p><b>卡片只占三行</b>（名字与笔数 / 创建时间与分类方案 / 预览·重命名·删除）。
 * 初版把整个分类清单铺在卡里，一个账本占掉两屏多——分类现在挪进
 * {@code dialog_category_preview} 的弹框里，点「预览分类」才看。
 *
 * <p><b>切换之后整个任务栈重来一遍</b>（{@code NEW_TASK | CLEAR_TASK} 回到首页）。
 * 原因不是偷懒：账本页、报表、待处理各自持有一条按旧账本构造的 LiveData 查询，
 * 就地改帐本需要给每个 ViewModel 都加一条"换账本重订阅"的路径，漏一处就出现
 * "首页已经换了、账本页还是旧的"这种半新半旧的状态。整体重来是唯一不会漏的做法，
 * 也正好对应"换存档"这个动作本身。
 *
 * <p>删除账本<b>允许非空</b>（用户要求），但要先把「确认删除」四个字打出来——
 * 它连带账目、分类与原图一起永久删除，不可恢复。
 */
public class LedgerPickerActivity extends AppCompatActivity {

    /** 一张卡要展示的数据：账本 + 笔数 + 它的分类方案 */
    private static final class Card {

        final LedgerEntity ledger;
        final int entryCount;
        final CategoryScheme scheme;

        Card(LedgerEntity ledger, int entryCount, CategoryScheme scheme) {
            this.ledger = ledger;
            this.entryCount = entryCount;
            this.scheme = scheme;
        }
    }

    private static final DateTimeFormatter CREATED_AT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** 删除确认要用户照抄的四个字 */
    private static final String DELETE_WORD = "确认删除";

    private WiseBookApp app;
    private LinearLayout cards;
    private TextView empty;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_ledger_picker);
        EdgeToEdgeInsets.apply(findViewById(R.id.ledger_picker_root));

        app = WiseBookApp.from(this);
        cards = findViewById(R.id.ledger_cards);
        empty = findViewById(R.id.empty);
        findViewById(R.id.btn_back).setOnClickListener(view -> finish());
        findViewById(R.id.btn_new_ledger).setOnClickListener(view -> createLedger());

        reload();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从重命名 / 切回来的场景：列表可能已经变了，回来刷一次
        reload();
    }

    // ------------------------------------------------------------------ 加载

    private void reload() {
        app.databaseExecutor().execute(() -> {
            long current = app.currentLedgerId();
            List<Card> loaded = new ArrayList<>();
            for (LedgerEntity ledger : app.ledgerRepository().list()) {
                loaded.add(new Card(ledger,
                        app.ledgerRepository().entryCount(ledger.ledgerId),
                        app.ledgerRepository().schemeOf(ledger.ledgerId)));
            }
            runOnUiThread(() -> render(loaded, current));
        });
    }

    private void render(List<Card> loaded, long currentId) {
        cards.removeAllViews();
        empty.setVisibility(loaded.isEmpty() ? View.VISIBLE : View.GONE);
        cards.setVisibility(loaded.isEmpty() ? View.GONE : View.VISIBLE);

        LayoutInflater inflater = LayoutInflater.from(this);
        for (Card card : loaded) {
            View view = inflater.inflate(R.layout.item_ledger_card, cards, false);
            bindCard(view, card, card.ledger.ledgerId == currentId, loaded.size());
            cards.addView(view);
        }
    }

    private void bindCard(View view, Card card, boolean isCurrent, int totalCount) {
        ((TextView) view.findViewById(R.id.card_name)).setText(card.ledger.name);
        view.findViewById(R.id.card_current).setVisibility(isCurrent ? View.VISIBLE : View.GONE);
        ((TextView) view.findViewById(R.id.card_entries))
                .setText(getString(R.string.ledger_picker_entries, card.entryCount));
        ((TextView) view.findViewById(R.id.card_meta)).setText(getString(
                R.string.ledger_card_meta, formatCreated(card.ledger.createdAt),
                schemeLabel(card.scheme)));

        // 点卡片 = 切换。点当前账本没有意义，直接关掉这一页
        view.setOnClickListener(v -> {
            if (isCurrent) {
                finish();
            } else {
                switchTo(card.ledger);
            }
        });
        view.findViewById(R.id.card_preview).setOnClickListener(v -> previewCategories(card));
        view.findViewById(R.id.card_rename).setOnClickListener(v -> renameLedger(card.ledger));
        view.findViewById(R.id.card_delete).setOnClickListener(
                v -> confirmDelete(card.ledger, totalCount));
    }

    private String schemeLabel(CategoryScheme scheme) {
        return getString(scheme == CategoryScheme.SIMPLE
                ? R.string.settings_scheme_simple_short
                : R.string.settings_scheme_standard_short);
    }

    // ------------------------------------------------------------------ 分类预览

    /**
     * 预览某个账本的分类。
     *
     * <p>分类树<b>按需读</b>：卡片上不显示它了，就没必要在进页面时把每个账本
     * 那几十行分类都查一遍——账本一多，那是整屏白等。
     */
    private void previewCategories(Card card) {
        app.databaseExecutor().execute(() -> {
            CategoryTree tree = app.categoryRepository().loadTree(card.ledger.ledgerId);
            runOnUiThread(() -> showPreview(tree, card.scheme));
        });
    }

    /**
     * 铺开分类清单。
     *
     * <p>深浅跟着这个账本的方案走：<b>简单方案下不渲染二级</b>——不是"藏起来"，
     * 而是这个账本本来就只能选一级。所以连"N 个子分类"那句汇总也一并省掉，
     * 免得报出一堆用户根本选不到的分类。
     */
    private void showPreview(CategoryTree tree, CategoryScheme scheme) {
        boolean withChildren = scheme != CategoryScheme.SIMPLE;
        View content = LayoutInflater.from(this)
                .inflate(R.layout.dialog_category_preview, null, false);

        ((TextView) content.findViewById(R.id.preview_note)).setText(withChildren
                ? R.string.ledger_preview_standard_note : R.string.ledger_preview_simple_note);

        List<CategoryEntity> expense = tree == null
                ? new ArrayList<>() : tree.topLevel(Direction.EXPENSE);
        List<CategoryEntity> income = tree == null
                ? new ArrayList<>() : tree.topLevel(Direction.INCOME);

        int children = 0;
        if (withChildren && tree != null) {
            for (CategoryEntity parent : expense) {
                children += tree.children(parent.categoryId).size();
            }
            for (CategoryEntity parent : income) {
                children += tree.children(parent.categoryId).size();
            }
        }
        ((TextView) content.findViewById(R.id.preview_summary)).setText(
                withChildren
                        ? getString(R.string.category_overview_summary,
                                expense.size() + income.size(), children)
                        : getString(R.string.category_overview_summary_simple,
                                expense.size() + income.size()));

        fillGroups(content, R.id.preview_expense_groups, R.id.preview_expense_header,
                tree, expense, withChildren);
        fillGroups(content, R.id.preview_income_groups, R.id.preview_income_header,
                tree, income, withChildren);

        new AlertDialog.Builder(this)
                .setTitle(R.string.ledger_preview_title)
                .setView(content)
                .setPositiveButton(R.string.action_confirm, null)
                .show();
    }

    /**
     * 把一组一级分类铺成若干行。
     *
     * <p>子分类拼成一行用「·」连起来：它们是同类并列项，各占一行的话，
     * 十六七个一级分类的清单会长到看不过来。
     *
     * <p>一组里一个分类都没有时，连表头一起藏掉——留着「收入分类」下面空一片，
     * 会让人以为加载失败了。
     */
    private void fillGroups(View content, int containerId, int headerId,
                            CategoryTree tree, List<CategoryEntity> parents,
                            boolean withChildren) {
        LinearLayout container = content.findViewById(containerId);
        TextView header = content.findViewById(headerId);
        if (parents.isEmpty()) {
            container.setVisibility(View.GONE);
            header.setVisibility(View.GONE);
            return;
        }
        LayoutInflater inflater = LayoutInflater.from(this);
        for (CategoryEntity parent : parents) {
            View row = inflater.inflate(R.layout.item_category_group, container, false);
            ((TextView) row.findViewById(R.id.group_name)).setText(parent.name);

            TextView childNames = row.findViewById(R.id.group_children);
            TextView childCount = row.findViewById(R.id.group_count);
            if (!withChildren) {
                // 简单方案：只列一级，右侧的"几个子分类"与下面那行一起藏掉
                childNames.setVisibility(View.GONE);
                childCount.setVisibility(View.GONE);
                container.addView(row);
                continue;
            }

            List<CategoryEntity> children = tree.children(parent.categoryId);
            if (children.isEmpty()) {
                // 右侧已经写了「无子分类」，下面那行就藏掉：
                // 同一句话说两遍，还会让这一组比别的高出一截
                childNames.setVisibility(View.GONE);
                childCount.setText(R.string.category_overview_no_child);
            } else {
                StringBuilder names = new StringBuilder();
                for (CategoryEntity child : children) {
                    if (names.length() > 0) {
                        names.append(" · ");
                    }
                    names.append(child.name);
                }
                childNames.setText(names.toString());
                childCount.setText(getString(R.string.category_overview_children, children.size()));
            }
            container.addView(row);
        }
    }

    // ------------------------------------------------------------------ 动作

    private void switchTo(LedgerEntity ledger) {
        app.switchLedger(ledger.ledgerId);
        Toast.makeText(this, getString(R.string.ledger_picker_switched, ledger.name),
                Toast.LENGTH_SHORT).show();
        restartToHome();
    }

    /**
     * 新建账本：名字 + 分类方案，方案旁边能先预览一下。
     *
     * <p>预览读的是<b>预置分类</b>（每个新账本播的种子都一样），所以不必先建再预览。
     */
    private void createLedger() {
        View content = LayoutInflater.from(this).inflate(R.layout.dialog_ledger_new, null, false);
        final EditText input = content.findViewById(R.id.new_ledger_name);
        final RadioGroup group = content.findViewById(R.id.new_ledger_scheme);
        // 默认「标准」：D1-A §2 的二级清单已定稿，说明它是主推形态（与 t_setting 默认一致）
        group.check(R.id.new_ledger_scheme_standard);

        content.findViewById(R.id.new_ledger_preview).setOnClickListener(view ->
                previewSeedCategories(currentScheme(group)));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.ledger_picker_new_title)
                .setView(content)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_confirm, null)
                .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String name = input.getText().toString().trim();
            if (name.isEmpty()) {
                Toast.makeText(this, R.string.ledger_picker_name_empty, Toast.LENGTH_SHORT).show();
                return;
            }
            hideKeyboard(input);
            dialog.dismiss();
            createLedger(name, currentScheme(group));
        });
    }

    private void createLedger(String name, CategoryScheme scheme) {
        app.databaseExecutor().execute(() -> {
            app.ledgerRepository().create(name, scheme);
            runOnUiThread(() -> {
                Toast.makeText(this, getString(R.string.ledger_picker_created_toast, name),
                        Toast.LENGTH_SHORT).show();
                // 新建的账本不自动切过去：用户可能只是先建好，
                // 真正要切的时候点一下那张卡就行（"点了才生效"更符合直觉）
                reload();
            });
        });
    }

    /**
     * 预览"将要新建的这个账本"的分类。
     *
     * <p>它还不存在于数据库里，所以不能按 id 读树——新账本播的就是
     * {@link com.wisebook.app.data.local.seed.CategorySeeder} 那套预置分类，
     * 这里直接把<b>任意一个已有账本</b>的树拿来当样本（分类树与方案无关，
     * 两套方案共享同一棵树）。
     */
    private void previewSeedCategories(CategoryScheme scheme) {
        app.databaseExecutor().execute(() -> {
            List<LedgerEntity> all = app.ledgerRepository().list();
            if (all.isEmpty()) {
                return;
            }
            CategoryTree sample = app.categoryRepository().loadTree(all.get(0).ledgerId);
            runOnUiThread(() -> showPreview(sample, scheme));
        });
    }

    private static CategoryScheme currentScheme(RadioGroup group) {
        return group.getCheckedRadioButtonId() == R.id.new_ledger_scheme_simple
                ? CategoryScheme.SIMPLE : CategoryScheme.STANDARD;
    }

    private void renameLedger(LedgerEntity ledger) {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint(R.string.ledger_picker_name_hint);
        input.setText(ledger.name == null ? "" : ledger.name);
        input.setSelection(input.getText().length());

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.ledger_picker_rename_title)
                .setView(padded(input))
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_confirm, null)
                .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String name = input.getText().toString().trim();
            if (name.isEmpty()) {
                Toast.makeText(this, R.string.ledger_picker_name_empty, Toast.LENGTH_SHORT).show();
                return;
            }
            hideKeyboard(input);
            dialog.dismiss();
            app.databaseExecutor().execute(() -> {
                app.ledgerRepository().rename(ledger.ledgerId, name);
                runOnUiThread(this::reload);
            });
        });
    }

    /**
     * 删除账本：内容会被永久清空，所以要求用户把「确认删除」四个字原样打出来。
     *
     * <p>这是刻意提高门槛——普通的"确定/取消"弹框挡不住误触，
     * 而这一下点下去，几十上百笔账连同原图就没了。
     */
    private void confirmDelete(LedgerEntity ledger, int totalCount) {
        if (totalCount <= 1) {
            Toast.makeText(this, R.string.ledger_picker_delete_only_one, Toast.LENGTH_SHORT).show();
            return;
        }
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint(R.string.ledger_picker_delete_word_hint);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.ledger_picker_delete_title)
                .setMessage(getString(R.string.ledger_picker_delete_message, ledger.name))
                .setView(padded(input))
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.ledger_picker_action_delete, null)
                .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            if (!DELETE_WORD.equals(input.getText().toString().trim())) {
                Toast.makeText(this, R.string.ledger_picker_delete_word_wrong,
                        Toast.LENGTH_SHORT).show();
                return;
            }
            dialog.dismiss();
            deleteLedger(ledger);
        });
    }

    private void deleteLedger(LedgerEntity ledger) {
        final boolean deletedCurrent = ledger.ledgerId == app.currentLedgerId();
        app.databaseExecutor().execute(() -> {
            app.ledgerRepository().delete(ledger.ledgerId);
            if (deletedCurrent) {
                // 删掉的正是当前账本：落回列表里的第一个，再整体重启，
                // 否则界面还停在"当前账本"这个已经不存在的 id 上
                List<LedgerEntity> remaining = app.ledgerRepository().list();
                if (!remaining.isEmpty()) {
                    app.switchLedger(remaining.get(0).ledgerId);
                }
                runOnUiThread(() -> {
                    Toast.makeText(this, getString(R.string.ledger_picker_deleted, ledger.name),
                            Toast.LENGTH_SHORT).show();
                    restartToHome();
                });
            } else {
                runOnUiThread(() -> {
                    Toast.makeText(this, getString(R.string.ledger_picker_deleted, ledger.name),
                            Toast.LENGTH_SHORT).show();
                    reload();
                });
            }
        });
    }

    /** 清掉整个任务栈、从首页重新开始；换账本 / 删当前账本之后调用 */
    private void restartToHome() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finishAffinity();
    }

    // ------------------------------------------------------------------ 小工具

    /** AlertDialog 直接 setView 一个 EditText 会贴边，包一层带内边距的容器 */
    private View padded(View content) {
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        FrameLayout container = new FrameLayout(this);
        container.setPadding(pad, pad / 2, pad, 0);
        container.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return container;
    }

    private void hideKeyboard(View view) {
        InputMethodManager manager = getSystemService(InputMethodManager.class);
        if (manager != null) {
            manager.hideSoftInputFromWindow(view.getWindowToken(), 0);
        }
    }

    private static String formatCreated(long millis) {
        LocalDateTime time = LocalDateTime.ofInstant(Instant.ofEpochMilli(millis),
                ZoneId.systemDefault());
        return CREATED_AT.format(time);
    }
}
