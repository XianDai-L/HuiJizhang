package com.wisebook.app.ui.chat;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import com.wisebook.app.data.DraftRepository;
import com.wisebook.app.data.remote.LlmRuntime;

import java.util.concurrent.ExecutorService;

/**
 * 输入栏的状态持有者（D2 §1：ViewModel + LiveData）。
 *
 * <p>它挂在首页上（原来是独立的对话页，2026-10-02 并入首页，见 HANDOFF 决策 33）：
 * 首页底部那条输入栏发出去的每一句话都由它解析，解析结果回到同一页的
 * 结果行上。**少了一次页面跳转**，用户说完话眼睛不用换地方。
 *
 * <p><b>线程边界很明确</b>：解析与落库是阻塞的，全部提交到
 * {@code WiseBookApp.databaseExecutor()}（单线程）；结果用
 * {@link MutableLiveData#postValue} 回到主线程。Activity 里因此一行线程代码都没有。
 *
 * <p>状态是个不可变对象而不是一堆散字段：旋转屏幕时 LiveData 会把最后一次状态
 * 重放给新的观察者，界面自然恢复，不需要在 {@code onSaveInstanceState} 里手工搬运。
 *
 * <p><b>"AI 处理详情"不在这里了。</b>那份底牌（模型名、分类依据、原始 JSON）
 * 现在只在「账目详情页」展开——解析完那一刻用户不需要看这些，
 * 想看的时候账目已经在账本里了，从账本进去看比在输入栏旁边展开更符合"事后追查"的时机。
 */
public class ChatViewModel extends ViewModel {

    /** 界面状态。字段全为 final，改状态就换一个新对象 */
    public static final class UiState {

        public final boolean parsing;
        /** 结论一句话；为空表示还没有结果 */
        public final String summary;
        /** 补充说明（需要确认的原因、降级原因、调用统计），可空 */
        public final String detail;
        public final boolean error;
        /** 需要用户在确认页上核对的那笔草稿 id；没有则为 -1 */
        public final long draftId;
        /** 已经写进账本的那笔账目 id；没有则为 -1 */
        public final long entryId;

        private UiState(boolean parsing, String summary, String detail, boolean error,
                        long draftId, long entryId) {
            this.parsing = parsing;
            this.summary = summary;
            this.detail = detail;
            this.error = error;
            this.draftId = draftId;
            this.entryId = entryId;
        }

        static UiState idle() {
            return new UiState(false, null, null, false, -1L, -1L);
        }

        static UiState parsing() {
            return new UiState(true, null, null, false, -1L, -1L);
        }

        static UiState result(String summary, String detail, long draftId, long entryId) {
            return new UiState(false, summary, detail, false, draftId, entryId);
        }

        static UiState error(String message) {
            return new UiState(false, message, null, true, -1L, -1L);
        }

        public boolean hasResult() {
            return summary != null;
        }

        /**
         * 这一行结果点得动吗。
         *
         * <p>点得动的前提是<b>真有地方可去</b>：自动落账的进「账目详情页」，
         * 需要核对的进「确认页」，而"没配 Key"或"解析失败"两种错误状态哪儿也去不了
         * ——那时这一行只该是一条提示，不该看起来像能点。
         */
        public boolean canOpen() {
            return entryId > 0L || draftId > 0L;
        }
    }

    private final MutableLiveData<UiState> state = new MutableLiveData<>(UiState.idle());
    private final DraftRepository draftRepository;
    private final ExecutorService executor;
    private final LlmRuntime llmRuntime;

    public ChatViewModel(DraftRepository draftRepository, ExecutorService executor,
                         LlmRuntime llmRuntime) {
        this.draftRepository = draftRepository;
        this.executor = executor;
        this.llmRuntime = llmRuntime;
    }

    public LiveData<UiState> state() {
        return state;
    }

    /** 提交一句输入。参数与线程调度都在这里收口，Activity 只管调用与渲染 */
    public void submit(String rawInput) {
        String input = rawInput == null ? "" : rawInput.trim();
        if (input.isEmpty()) {
            state.setValue(UiState.error("先写一句要记的账"));
            return;
        }
        // 没配 Key 就不要白跑一次网络：直接告诉用户去哪儿配
        if (!llmRuntime.isReady()) {
            state.setValue(UiState.error(
                    "还没有可用的 API Key。到「设置」里填一个，再回来记这笔账"));
            return;
        }

        state.setValue(UiState.parsing());
        executor.execute(() -> {
            DraftRepository.SubmitReport report = draftRepository.submit(input);
            state.postValue(toState(report));
        });
    }

    private static UiState toState(DraftRepository.SubmitReport report) {
        StringBuilder detail = new StringBuilder();
        if (report.detail != null && !report.detail.isEmpty()) {
            detail.append(report.detail);
        }
        if (detail.length() > 0) {
            detail.append('\n');
        }
        // 把调用次数亮出来是有意的：答辩演示时能直接看到「重试一次后通过」
        // 这条容错设计真的在起作用，而不是只写在文档里
        detail.append("模型调用 ").append(report.attemptCount).append(" 次");
        if (report.firstAttemptSucceeded) {
            detail.append("，首次就通过");
        }

        return UiState.result(report.summary, detail.toString(),
                report.outcome.draftId, report.outcome.entryId);
    }

    /** 手动构造依赖的 ViewModel 工厂（P1 不用依赖注入框架，D2 §1） */
    public static final class Factory implements ViewModelProvider.Factory {

        private final DraftRepository draftRepository;
        private final ExecutorService executor;
        private final LlmRuntime llmRuntime;

        public Factory(DraftRepository draftRepository, ExecutorService executor,
                       LlmRuntime llmRuntime) {
            this.draftRepository = draftRepository;
            this.executor = executor;
            this.llmRuntime = llmRuntime;
        }

        @NonNull
        @Override
        @SuppressWarnings("unchecked")
        public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
            return (T) new ChatViewModel(draftRepository, executor, llmRuntime);
        }
    }
}
