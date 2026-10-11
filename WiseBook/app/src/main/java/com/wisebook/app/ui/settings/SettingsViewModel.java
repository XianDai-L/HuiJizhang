package com.wisebook.app.ui.settings;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import com.wisebook.app.data.SettingsRepository;
import com.wisebook.app.data.local.entity.SettingEntity;
import com.wisebook.app.data.remote.LlmConfigStore;
import com.wisebook.app.domain.model.CategoryScheme;
import com.wisebook.app.domain.model.ConfirmMode;
import com.wisebook.app.domain.model.LlmProvider;

import java.util.concurrent.ExecutorService;

/**
 * 设置页的状态持有者。
 *
 * <p>一次读、逐项写，都是阻塞调用，统一丢到数据库线程上执行。
 *
 * <p><b>2026-10-11：取消"保存按钮"。</b>原来是对话框选完只改内存、最后统一点保存，
 * 现在改成每个选项在自己的对话框里点「确定」就地写库，所以这里提供两个<b>分项保存</b>
 * 的方法（{@link #saveAccounting} / {@link #saveLlm}）而不是一个大而全的 {@code save}。
 * 分项而不是"每次都整行写"是为了副作用准确：换模型 / 换 Key 才需要丢弃缓存的客户端。
 *
 * <p><b>保存动作横跨两处存储</b>：领域设置进 {@code t_setting}（Room），
 * 模型连接配置进 SharedPreferences。两者不在同一个事务里——这是可接受的，
 * 因为它们之间没有一致性依赖：一个只影响"要不要问用户"，一个只影响"用哪个端点"，
 * 任何一半成功都不会让数据变错。
 */
public class SettingsViewModel extends ViewModel {

    /** 设置页需要的一份完整快照 */
    public static final class Form {

        public final ConfirmMode confirmMode;
        public final long largeThresholdCents;
        public final CategoryScheme categoryScheme;
        public final LlmProvider provider;
        /** 仅运行时覆盖的模型名；为 {@code null} 表示用该服务商的默认模型 */
        public final String modelOverride;
        /** 仅运行时覆盖的 Key；为 {@code null} 表示用编译期注入的默认值 */
        public final String apiKeyOverride;
        /** 当前是否已具备可用的 Key（含编译期默认值） */
        public final boolean llmReady;

        Form(ConfirmMode confirmMode, long largeThresholdCents, CategoryScheme categoryScheme,
             LlmProvider provider, String modelOverride, String apiKeyOverride, boolean llmReady) {
            this.confirmMode = confirmMode;
            this.largeThresholdCents = largeThresholdCents;
            this.categoryScheme = categoryScheme;
            this.provider = provider;
            this.modelOverride = modelOverride;
            this.apiKeyOverride = apiKeyOverride;
            this.llmReady = llmReady;
        }
    }

    private final MutableLiveData<Form> form = new MutableLiveData<>();
    private final MutableLiveData<String> status = new MutableLiveData<>();

    private final SettingsRepository settingsRepository;
    private final LlmConfigStore llmConfigStore;
    private final ExecutorService executor;
    private final Runnable onLlmConfigChanged;
    private final long userId;

    public SettingsViewModel(SettingsRepository settingsRepository, LlmConfigStore llmConfigStore,
                             ExecutorService executor, Runnable onLlmConfigChanged, long userId) {
        this.settingsRepository = settingsRepository;
        this.llmConfigStore = llmConfigStore;
        this.executor = executor;
        this.onLlmConfigChanged = onLlmConfigChanged;
        this.userId = userId;
    }

    public LiveData<Form> form() {
        return form;
    }

    public LiveData<String> status() {
        return status;
    }

    /** <b>异步</b>读取当前配置 */
    public void load() {
        executor.execute(() -> {
            SettingEntity settings = settingsRepository.loadOrInit(userId);
            Form snapshot = new Form(
                    settings.confirmMode,
                    settings.largeThresholdCents,
                    settings.categoryScheme,
                    llmConfigStore.provider(),
                    llmConfigStore.modelOverride(),
                    llmConfigStore.apiKeyOverride(),
                    llmConfigStore.hasApiKey());
            form.postValue(snapshot);
        });
    }

    /**
     * <b>异步</b>保存「记账行为」两项（2026-10-11 分项保存）。
     *
     * <p>只动 {@code t_setting} 里的这两列，其余列原样保留——
     * 用「先读整行再改」而不是新造一行，免得把别人写的字段冲掉。
     * 这一点在 2026-10-11 之后更要紧了：{@code category_scheme} 已经没有界面入口
     * （新建账本时定死），要是这里整行覆盖，等于用默认值把它冲掉。
     */
    public void saveAccounting(ConfirmMode confirmMode, long largeThresholdCents) {
        executor.execute(() -> {
            SettingEntity settings = settingsRepository.loadOrInit(userId);
            settings.confirmMode = confirmMode;
            settings.largeThresholdCents = largeThresholdCents;
            settingsRepository.save(settings);
            status.postValue("已保存");
        });
    }

    /**
     * <b>异步</b>保存模型连接配置（服务商 / 模型名 / Key）。
     *
     * @param model  传空表示改用服务商默认模型
     * @param apiKey 传空表示改用编译期注入的默认 Key
     */
    public void saveLlm(LlmProvider provider, String model, String apiKey) {
        executor.execute(() -> {
            llmConfigStore.saveOverride(provider, model, apiKey);
            // 配置变了，通知组装点把缓存的客户端丢掉，下次调用按新配置重建
            onLlmConfigChanged.run();
            status.postValue("已保存");
        });
    }

    /** 手动构造依赖的 ViewModel 工厂（P1 不用依赖注入框架，D2 §1） */
    public static final class Factory implements ViewModelProvider.Factory {

        private final SettingsRepository settingsRepository;
        private final LlmConfigStore llmConfigStore;
        private final ExecutorService executor;
        private final Runnable onLlmConfigChanged;
        private final long userId;

        public Factory(SettingsRepository settingsRepository, LlmConfigStore llmConfigStore,
                       ExecutorService executor, Runnable onLlmConfigChanged, long userId) {
            this.settingsRepository = settingsRepository;
            this.llmConfigStore = llmConfigStore;
            this.executor = executor;
            this.onLlmConfigChanged = onLlmConfigChanged;
            this.userId = userId;
        }

        @NonNull
        @Override
        @SuppressWarnings("unchecked")
        public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
            return (T) new SettingsViewModel(settingsRepository, llmConfigStore, executor,
                    onLlmConfigChanged, userId);
        }
    }
}
