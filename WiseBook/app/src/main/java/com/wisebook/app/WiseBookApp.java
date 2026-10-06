package com.wisebook.app;

import android.app.Application;
import android.content.Context;
import android.util.Log;

import com.wisebook.app.data.CategoryRepository;
import com.wisebook.app.data.DraftRepository;
import com.wisebook.app.data.EntryRepository;
import com.wisebook.app.data.ReportRepository;
import com.wisebook.app.data.SettingsRepository;
import com.wisebook.app.data.local.WiseBookDatabase;
import com.wisebook.app.data.local.seed.CategorySeeder;
import com.wisebook.app.data.remote.LlmClientFactory;
import com.wisebook.app.data.remote.LlmConfigStore;
import com.wisebook.app.data.remote.LlmRuntime;
import com.wisebook.llm.ImageReader;
import com.wisebook.llm.LlmClient;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * 应用的组装点（D2 §1：P1 用「手动构造」，不引入依赖注入框架）。
 *
 * <p>这里做三件事：建库、把仓储与「模型访问能力」装配好、提供唯一的数据库线程。
 *
 * <p><b>为什么是单线程的 {@link ExecutorService}</b>：SQLite 的写操作本来就是串行的，
 * 开多线程只会把时间花在抢写锁上。单线程让「所有库操作按提交顺序执行」成为一个简单事实，
 * 排查问题时不必考虑并发交叉。
 */
public class WiseBookApp extends Application {

    private static final String TAG = "WiseBookApp";

    private ExecutorService databaseExecutor;
    private WiseBookDatabase database;

    private SettingsRepository settingsRepository;
    private CategoryRepository categoryRepository;
    private EntryRepository entryRepository;
    private DraftRepository draftRepository;
    private ReportRepository reportRepository;

    private LlmConfigStore llmConfigStore;
    private LlmRuntime llmRuntime;

    /**
     * 模型客户端缓存。
     *
     * <p>之所以要缓存：{@code OkHttpClient} 自带连接池与调度线程池，
     * 每次提交都新建一个就是白白泄漏线程。配置改了（换服务商 / 换 Key）由
     * {@link #invalidateLlmClient()} 置空，下次用到时重建。
     */
    private volatile LlmClient llmClient;

    /**
     * 截图转写器缓存（图 → 文字）。
     *
     * <p>与 {@link #llmClient} 分开缓存，是因为它用的是<b>另一家</b>的端点：
     * 转写固定走硅基流动的 OCR 模型，而结构化那一步跟着用户选的「当前服务商」走
     * ——两者是两条独立的链路（路线 B 的架构主张）。
     * 配了硅基流动 Key 之后这里才会被填上，否则一直是 {@code null}。
     */
    private volatile ImageReader imageReader;

    @Override
    public void onCreate() {
        super.onCreate();

        databaseExecutor = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable task) {
                return new Thread(task, "wisebook-db");
            }
        });

        database = WiseBookDatabase.get(this);
        llmConfigStore = new LlmConfigStore(this);

        settingsRepository = new SettingsRepository(database);
        categoryRepository = new CategoryRepository(database);
        entryRepository = new EntryRepository(database);
        reportRepository = new ReportRepository(database);
        llmRuntime = createLlmRuntime();
        draftRepository = new DraftRepository(database, settingsRepository, categoryRepository,
                entryRepository, llmRuntime);

        // 启动初始化放后台：建库、播种、归档超时草稿都是磁盘操作，不能占主线程
        databaseExecutor.execute(() -> {
            CategorySeeder.seedIfEmpty(database);
            settingsRepository.loadOrInit(WiseBookDatabase.DEFAULT_USER_ID);
            int archived = draftRepository.archiveStale();
            if (archived > 0) {
                Log.i(TAG, "已归档 " + archived + " 笔超时草稿（D1 §4.3：不删除，仍可在待处理里接管）");
            }
        });
    }

    /** 取组装好的应用实例 */
    public static WiseBookApp from(Context context) {
        return (WiseBookApp) context.getApplicationContext();
    }

    /** <b>所有数据库操作都应提交到这个线程上执行</b> */
    public ExecutorService databaseExecutor() {
        return databaseExecutor;
    }

    public WiseBookDatabase database() {
        return database;
    }

    public SettingsRepository settingsRepository() {
        return settingsRepository;
    }

    public CategoryRepository categoryRepository() {
        return categoryRepository;
    }

    public EntryRepository entryRepository() {
        return entryRepository;
    }

    public DraftRepository draftRepository() {
        return draftRepository;
    }

    public ReportRepository reportRepository() {
        return reportRepository;
    }

    public LlmConfigStore llmConfigStore() {
        return llmConfigStore;
    }

    public LlmRuntime llmRuntime() {
        return llmRuntime;
    }

    /** 设置页改完配置后调用：把缓存的客户端丢掉，下次用到时按新配置重建 */
    public void invalidateLlmClient() {
        llmClient = null;
        imageReader = null;
    }

    // ------------------------------------------------------------------ 内部

    private LlmRuntime createLlmRuntime() {
        return new LlmRuntime() {
            @Override
            public LlmClient client() {
                LlmClient cached = llmClient;
                if (cached == null) {
                    synchronized (WiseBookApp.this) {
                        cached = llmClient;
                        if (cached == null) {
                            cached = LlmClientFactory.create(llmConfigStore);
                            llmClient = cached;
                        }
                    }
                }
                return cached;
            }

            @Override
            public boolean isReady() {
                return llmConfigStore.hasApiKey();
            }

            @Override
            public String modelLabel() {
                // 刻意从同一份配置算出标签，保证写进 t_draft.model 的
                // 就是这次实际使用的端点与模型，而不是另算一遍
                return LlmClientFactory.modelConfig(llmConfigStore.provider(),
                        llmConfigStore.model(), llmConfigStore.apiKey()).label();
            }

            @Override
            public ImageReader imageReader() {
                ImageReader cached = imageReader;
                if (cached == null) {
                    synchronized (WiseBookApp.this) {
                        cached = imageReader;
                        if (cached == null) {
                            cached = LlmClientFactory.createImageReader(llmConfigStore);
                            imageReader = cached;
                        }
                    }
                }
                return cached;
            }

            @Override
            public boolean isImageReady() {
                return llmConfigStore.hasImageKey();
            }
        };
    }
}
