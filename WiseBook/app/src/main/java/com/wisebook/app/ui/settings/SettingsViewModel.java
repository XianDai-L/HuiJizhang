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
 * <p>一次读、一次写，都是阻塞调用，统一丢到数据库线程上执行。
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
             LlmProvider provider, String modelOverride, String apiKeyOverride,
             boolean llmReady) {
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
            status.postValue(snapshot.llmReady
                    ? "当前已有可用的 API Key"
                    : "当前还没有可用的 API Key，解析会直接提示先配置");
        });
    }

    /**
     * <b>异步</b>保存。
     *
     * @param model    传空表示改用服务商默认模型
     * @param apiKey   传空表示改用编译期注入的默认 Key
     */
    public void save(ConfirmMode confirmMode, long largeThresholdCents, CategoryScheme scheme,
                     LlmProvider provider, String model, String apiKey) {
        executor.execute(() -> {
            SettingEntity settings = settingsRepository.loadOrInit(userId);
            settings.confirmMode = confirmMode;
            settings.largeThresholdCents = largeThresholdCents;
            settings.categoryScheme = scheme;
            settingsRepository.save(settings);

            llmConfigStore.saveOverride(provider, model, apiKey);
            // 配置变了，通知组装点把缓存的 OkHttp 客户端丢掉，下次调用按新配置重建
            onLlmConfigChanged.run();

            status.postValue("已保存。确认档位与分类方案从下一笔记账开始生效");
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
