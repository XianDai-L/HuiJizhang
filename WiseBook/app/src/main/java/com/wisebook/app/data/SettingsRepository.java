package com.wisebook.app.data;

import com.wisebook.app.data.local.WiseBookDatabase;
import com.wisebook.app.data.local.dao.SettingDao;
import com.wisebook.app.data.local.entity.SettingEntity;
import com.wisebook.app.domain.model.CategoryScheme;
import com.wisebook.app.domain.model.ConfirmMode;

/**
 * 用户设置的读写（D1 §3.5）。
 *
 * <p>本类<b>只做数据访问，不做线程调度</b>：方法都是阻塞式的，
 * 由调用方（ViewModel）放到 {@code ExecutorService} 上执行。
 * 这样切分的好处是仓储层可被直接单测，不必去等一个回调。
 *
 * <p>用 Room 表而不是 DataStore 存设置，是 D1 §3.5 已定稿的结论：
 * 设置与账目同库同事务，且 {@code merchant_map_version} 这类词典版本号
 * 本来就是库内的元数据。
 */
public final class SettingsRepository {

    // ---------------------------------------------------------------- 默认值
    // 这些是「第一次启动时写入 t_setting 的值」。D1 未逐条指定默认档位，
    // 这里的取值与理由都写在字段注释里，改起来只需要动这几行。

    /**
     * 默认档位取「存疑才确认」。
     *
     * <p>这原本是「大额确认」，实机试用后改成现在的取值。原因很具体：
     * 档位二下<b>任何 ≥ 300 元的账目都会进确认页</b>，而日常记账里三百以上的笔数不少，
     * 于是"确认"从"帮你把关"变成了"每次都要点一下"——
     * 用户对确认页的反应会从"看一眼"退化成本能地点确认，把关作用反而没了。
     *
     * <p>档位三只拦「模型可能算错 / 分类可能判错 / 信息不全 / 疑似重复」这四类，
     * 这些才是真正需要人看一眼的。要恢复"大额也确认"，在设置页切换即可。
     *
     * <p><b>注意</b>：这条默认值只影响首次安装。已经装过的设备在 {@code t_setting}
     * 里存着旧值，改这里不会改它——要生效得在设置页手动切换。
     */
    public static final ConfirmMode DEFAULT_CONFIRM_MODE = ConfirmMode.DOUBTFUL;

    /** D1 §3.5 明确写着默认 30000 分 = 300 元 */
    public static final long DEFAULT_LARGE_THRESHOLD_CENTS = 30_000L;

    /** D1 §4.2 明确反问上限 3 轮 */
    public static final int DEFAULT_CLARIFY_MAX_ROUNDS = 3;

    /** 默认「标准」方案：D1-A §2 的二级清单已定稿，说明它是主推形态 */
    public static final CategoryScheme DEFAULT_CATEGORY_SCHEME = CategoryScheme.STANDARD;

    /** 商户映射表版本，对应 D1-A §3 的 v1 清单 */
    public static final int CURRENT_MERCHANT_MAP_VERSION = 1;

    /** 约数词典版本，对应 D1-A §5 的 v1 清单 */
    public static final int CURRENT_APPROX_DICT_VERSION = 1;

    private final WiseBookDatabase db;
    private final SettingDao dao;

    public SettingsRepository(WiseBookDatabase db) {
        this.db = db;
        this.dao = db.settingDao();
    }

    /**
     * 读设置；行还没建立时先写入默认值再返回。
     *
     * <p>「读不到就补默认值」这一步放在仓储里而不是 UI 里：
     * 这样所有调用方拿到的都是完整可用的设置对象，不必各自判空。
     *
     * <p><b>阻塞方法，禁止在主线程调用。</b>
     */
    public SettingEntity loadOrInit(long userId) {
        SettingEntity existing = dao.find(userId);
        if (existing != null) {
            return existing;
        }
        SettingEntity fresh = defaults(userId);
        dao.upsert(fresh);
        return fresh;
    }

    /** <b>阻塞方法，禁止在主线程调用。</b> */
    public void save(SettingEntity settings) {
        dao.upsert(settings);
    }

    /** 首次启动写入的默认设置 */
    public static SettingEntity defaults(long userId) {
        SettingEntity settings = new SettingEntity();
        settings.userId = userId;
        settings.confirmMode = DEFAULT_CONFIRM_MODE;
        settings.largeThresholdCents = DEFAULT_LARGE_THRESHOLD_CENTS;
        settings.clarifyMaxRounds = DEFAULT_CLARIFY_MAX_ROUNDS;
        settings.categoryScheme = DEFAULT_CATEGORY_SCHEME;
        settings.merchantMapVersion = CURRENT_MERCHANT_MAP_VERSION;
        settings.approxDictVersion = CURRENT_APPROX_DICT_VERSION;
        return settings;
    }

    /** 供调试与日志使用 */
    public WiseBookDatabase database() {
        return db;
    }
}
