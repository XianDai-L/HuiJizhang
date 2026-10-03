package com.wisebook.app.ui.confirm;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import com.wisebook.app.data.CategoryRepository;
import com.wisebook.app.data.DraftRepository;
import com.wisebook.app.data.EntryRepository;
import com.wisebook.app.data.SettingsRepository;
import com.wisebook.app.data.local.entity.CategoryEntity;
import com.wisebook.app.data.local.entity.DraftEntity;
import com.wisebook.app.data.local.entity.SettingEntity;
import com.wisebook.app.domain.classify.CategoryTree;
import com.wisebook.app.domain.model.AmountRuleCheck;
import com.wisebook.app.domain.model.CategoryScheme;
import com.wisebook.app.domain.model.ClarifyQuestion;
import com.wisebook.app.domain.model.ConfidenceFlag;
import com.wisebook.app.domain.model.Direction;
import com.wisebook.app.domain.model.DraftStatus;
import com.wisebook.app.domain.model.PaymentMethod;
import com.wisebook.app.ui.DraftFormatter;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * 确认页的状态持有者。
 *
 * <p><b>界面上改字段不会立刻落库</b>，只改内存里的那份草稿；点了「保存修改并重新校验」
 * 或「确认并记账」才写回去。这样做是为了守住 D1 §4.2 约束 1：
 * 用户回答/修改之后<b>必须回流 DRAFT 重新跑一遍判据</b>（金额一改，
 * 「是否约数」「规则重算是否一致」「去重键是否命中」全都要重算），
 * 而不是让界面直接把新值塞进账本。
 *
 * <p>「为什么需要确认」那行解释是现算的（{@link DraftRepository#explainWhyNeedsConfirm}），
 * 不是从库里读的存档——所以用户改完之后，理由会跟着变。
 */
public class ConfirmViewModel extends ViewModel {

    /** 确认页需要的一份完整快照 */
    public static final class Form {

        public final long draftId;
        /** 为什么需要确认；免确认条件满足时为空串 */
        public final String why;
        /** 反问清单的文案；没有要问的为空串 */
        public final String questions;
        public final String amountYuan;
        public final Direction direction;
        public final String categoryPath;
        public final String paymentText;
        public final String occurredAtText;
        public final String merchant;
        public final String rawInput;
        /** 已落账的草稿不可再编辑，界面上应把按钮禁掉 */
        public final boolean editable;

        Form(long draftId, String why, String questions, String amountYuan, Direction direction,
             String categoryPath, String paymentText, String occurredAtText, String merchant,
             String rawInput, boolean editable) {
            this.draftId = draftId;
            this.why = why;
            this.questions = questions;
            this.amountYuan = amountYuan;
            this.direction = direction;
            this.categoryPath = categoryPath;
            this.paymentText = paymentText;
            this.occurredAtText = occurredAtText;
            this.merchant = merchant;
            this.rawInput = rawInput;
            this.editable = editable;
        }
    }

    private final MutableLiveData<Form> form = new MutableLiveData<>();
    private final MutableLiveData<String> status = new MutableLiveData<>();
    private final MutableLiveData<Boolean> finished = new MutableLiveData<>(Boolean.FALSE);

    private final DraftRepository draftRepository;
    private final SettingsRepository settingsRepository;
    private final CategoryRepository categoryRepository;
    private final ExecutorService executor;
    private final long userId;

    /** 内存里那份可编辑的草稿 */
    private DraftEntity working;
    private CategoryTree tree;
    private SettingEntity settings;

    public ConfirmViewModel(DraftRepository draftRepository,
                            SettingsRepository settingsRepository,
                            CategoryRepository categoryRepository,
                            ExecutorService executor, long userId) {
        this.draftRepository = draftRepository;
        this.settingsRepository = settingsRepository;
        this.categoryRepository = categoryRepository;
        this.executor = executor;
        this.userId = userId;
    }

    public LiveData<Form> form() {
        return form;
    }

    public LiveData<String> status() {
        return status;
    }

    /** 落账或丢弃完成后置为 true，界面据此关闭自己 */
    public LiveData<Boolean> finished() {
        return finished;
    }

    // ------------------------------------------------------------------ 加载

    /** <b>异步</b>加载草稿 */
    public void load(long draftId) {
        executor.execute(() -> {
            working = draftRepository.findDraft(draftId);
            if (working == null) {
                status.postValue("这笔草稿已经不存在了");
                finished.postValue(Boolean.TRUE);
                return;
            }
            tree = categoryRepository.loadTree(userId);
            settings = settingsRepository.loadOrInit(userId);
            publish();
        });
    }

    /** <b>异步</b>重新读一次（改完字段重新校验之后要用新数据刷新界面） */
    private void reload() {
        if (working == null) {
            return;
        }
        DraftEntity fresh = draftRepository.findDraft(working.draftId);
        if (fresh != null) {
            working = fresh;
        }
        publish();
    }

    private void publish() {
        String why = settings == null ? "" : draftRepository.explainWhyNeedsConfirm(working, settings);

        StringBuilder questions = new StringBuilder();
        if (working.clarifyQuestions != null) {
            for (ClarifyQuestion question : working.clarifyQuestions) {
                if (questions.length() > 0) {
                    questions.append('\n');
                }
                questions.append("· ").append(question.question);
            }
        }

        form.postValue(new Form(
                working.draftId,
                why,
                questions.toString(),
                DraftFormatter.yuan(working.amountCents),
                working.direction,
                DraftFormatter.categoryPath(tree, working.categoryId),
                working.paymentMethod == null ? null : working.paymentMethod.label(),
                DraftFormatter.dateTime(working.occurredAt) + "（"
                        + DraftFormatter.occurredAtLabel(working.occurredAtSource) + "）",
                working.merchant,
                working.rawInput == null ? "" : working.rawInput,
                working.status != DraftStatus.POSTED));
    }

    // ------------------------------------------------------------ 编辑（仅内存）

    /**
     * 把界面上正在编辑的内容同步进草稿。
     *
     * <p>刻意<b>不</b>刷新界面：用户还在输入框里打字，
     * 这时候回写一次会把光标位置和输入法状态一起打乱。
     */
    public void syncEdits(String amountYuan, Direction direction, String merchant) {
        if (working == null) {
            return;
        }
        if (direction != null) {
            working.direction = direction;
        }
        working.merchant = blankToNull(merchant);

        Long cents = parseYuanToCents(amountYuan);
        if (cents == null) {
            return;
        }

        Long previousAmount = working.amountCents;
        working.amountCents = cents;
        if (!amountDoubtsResolvedByEditing(previousAmount, cents)) {
            // 金额没动，就绝不能顺手把「规则校验未通过」改成通过。
            //
            // 这里曾经是无条件清除的（用户报的"第一次要确认、点一下保存第二次就不用确认了"
            // 就是这么来的）：那样「保存修改并重新校验」会变成一张万能通行证——
            // 用户什么都没改，点一下就把双通道校验的结论抹掉了。
            // 双通道校验本来就是 D1 §2 用来防"模型金额算错"的，抹掉它等于把这道防线拆了。
            //
            // 想用"我知道金额有疑问，但就这样记"表达确认，有专门的按钮：「确认并记账」。
            return;
        }

        // 金额确实被用户改过 → 这是人工核过的值，
        // 原先关于金额的那些质疑（约数折算、模型算错、无法安全折算）随之失效
        working.amountIsEstimated = false;
        working.amountRuleCheck = AmountRuleCheck.PASS;
        working.amountLowerCents = null;
        working.amountUpperCents = null;
        removeFlag(ConfidenceFlag.AMOUNT_AMBIGUOUS);
    }

    /** 用户在分类选择器里选了一个（传的是可读路径，如 {@code 餐饮>外卖}） */
    public void setCategoryPath(String path) {
        if (working == null || tree == null) {
            return;
        }
        Long categoryId = tree.idOfPath(path, working.direction);
        if (categoryId == null) {
            return;
        }
        working.categoryId = categoryId;
        working.rootCategoryId = tree.rootIdOf(categoryId);
        // 用户亲手选的分类，摇摆自然消除
        removeFlag(ConfidenceFlag.CATEGORY_SWING);
    }

    /** 支付方式是可选字段，允许传 {@code null} 表示清空 */
    public void setPaymentMethod(PaymentMethod method) {
        if (working != null) {
            working.paymentMethod = method;
        }
    }

    /** 分类选择器要用的树。树是不可变数据，交给界面读没有风险 */
    public CategoryTree tree() {
        return tree;
    }

    /** 当前方向，以内存里那份草稿为准（用户可能刚在界面上改过方向） */
    public Direction direction() {
        return working == null ? null : working.direction;
    }

    /** 分类选择器要用的方案：简单方案下一级即末级 */
    public CategoryScheme scheme() {
        return settings == null ? CategoryScheme.STANDARD : settings.categoryScheme;
    }

    // ------------------------------------------------------------ 三个动作

    /** 确认并记账：{@code ASKING → CONFIRMED → POSTED}，整体在一个事务里 */
    public void post() {
        if (working == null) {
            return;
        }
        long draftId = working.draftId;
        executor.execute(() -> {
            EntryRepository.PostOutcome outcome = draftRepository.confirmAndPost(draftId);
            if (!outcome.isOk()) {
                status.postValue("没能记账：" + outcome.message);
                return;
            }
            status.postValue(outcome.isNewlyPosted() ? "已记账" : "这笔之前就记过了，没有重复记");
            finished.postValue(Boolean.TRUE);
        });
    }

    /** 保存修改并重新校验（D1 §4.2 约束 1） */
    public void saveAndRevalidate() {
        if (working == null || settings == null) {
            return;
        }
        DraftEntity edited = working;
        executor.execute(() -> {
            DraftRepository.SubmitReport report =
                    draftRepository.applyEditsAndRevalidate(edited, settings, tree);
            if (report.outcome.kind == DraftRepository.SubmitOutcome.Kind.AUTO_POSTED) {
                status.postValue("改完之后条件都满足了，已经自动记账");
                finished.postValue(Boolean.TRUE);
                return;
            }
            status.postValue(report.detail == null || report.detail.isEmpty()
                    ? "已保存，但还需要你确认"
                    : "已保存。仍然需要确认：" + report.detail);
            reload();
        });
    }

    /** 丢弃这笔草稿（只改状态，不删行） */
    public void discard() {
        if (working == null) {
            return;
        }
        long draftId = working.draftId;
        executor.execute(() -> {
            boolean ok = draftRepository.discard(draftId);
            status.postValue(ok ? "已丢弃。原始输入与模型信息会保留，便于排查" : "丢弃失败，请重试");
            if (ok) {
                finished.postValue(Boolean.TRUE);
            }
        });
    }

    // ------------------------------------------------------------------ 工具

    /**
     * 用户这次的金额输入，是否足以让原先关于金额的质疑失效。
     *
     * <p>判据只有一条：<b>值真的变了</b>。
     *
     * <p>抽成静态方法是为了能在 JVM 上直接验证。这条判断已经错过一次——
     * 原先写的是"只要输入框里能解析出金额就清除质疑"，于是用户什么都不改、
     * 点一下「保存修改并重新校验」，双通道校验的结论就被抹掉了，
     * 表现为"第一次要确认、第二次不用确认"。这种"看起来像两次判断不一致"的 bug
     * 不该再靠人眼盯出来。
     *
     * @param previous 改之前的金额（分），可为 {@code null}
     * @param current  这次输入解析出的金额（分），{@code null} 表示输入为空或不合法
     */
    static boolean amountDoubtsResolvedByEditing(Long previous, Long current) {
        return current != null && !current.equals(previous);
    }

    private void removeFlag(ConfidenceFlag flag) {
        if (working.confidenceFlags != null) {
            working.confidenceFlags.remove(flag);
        }
    }

    private static String blankToNull(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** @return 解析失败、非正数或超出范围时返回 {@code null}（此时保留原值不动） */
    static Long parseYuanToCents(String text) {
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        try {
            long cents = new BigDecimal(trimmed)
                    .movePointRight(2)
                    .setScale(0, RoundingMode.HALF_UP)
                    .longValueExact();
            return cents <= 0L ? null : cents;
        } catch (ArithmeticException | NumberFormatException e) {
            return null;
        }
    }

    /** 手动构造依赖的 ViewModel 工厂（P1 不用依赖注入框架，D2 §1） */
    public static final class Factory implements ViewModelProvider.Factory {

        private final DraftRepository draftRepository;
        private final SettingsRepository settingsRepository;
        private final CategoryRepository categoryRepository;
        private final ExecutorService executor;
        private final long userId;

        public Factory(DraftRepository draftRepository, SettingsRepository settingsRepository,
                       CategoryRepository categoryRepository, ExecutorService executor,
                       long userId) {
            this.draftRepository = draftRepository;
            this.settingsRepository = settingsRepository;
            this.categoryRepository = categoryRepository;
            this.executor = executor;
            this.userId = userId;
        }

        @NonNull
        @Override
        @SuppressWarnings("unchecked")
        public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
            return (T) new ConfirmViewModel(draftRepository, settingsRepository,
                    categoryRepository, executor, userId);
        }
    }
}
