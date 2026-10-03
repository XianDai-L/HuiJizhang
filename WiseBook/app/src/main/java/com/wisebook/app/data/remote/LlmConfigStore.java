package com.wisebook.app.data.remote;

import android.content.Context;
import android.content.SharedPreferences;

import com.wisebook.app.BuildConfig;
import com.wisebook.app.domain.model.LlmProvider;

/**
 * 大模型连接配置的读写：服务商、模型名、API Key。
 *
 * <p>三者有共同的优先级规则：<b>运行时覆盖值优先，其次编译期默认值</b>。
 * 编译期默认值来自 {@code local.properties} → {@code BuildConfig}
 * （Key 绝不进源码、不进 git，见 HANDOFF §9）；设置页填的值存在本机，
 * 于是"答辩当天被限流"时可以现场换 Key 而不用重新打包（D2 §6.5）。
 *
 * <p><b>为什么用 SharedPreferences 而不是 {@code t_setting} 或 DataStore：</b>
 * <ul>
 *   <li>不进 {@code t_setting}：那是 D1 §3.5 定稿的领域设置表，
 *       为了塞一个密钥去改定稿的表结构不划算</li>
 *   <li>不用 DataStore：P1 已经决定设置统一走 Room（HANDOFF 决策 1），
 *       为一个凭证再引入一套存储机制不值当</li>
 *   <li>SharedPreferences 是平台自带、零依赖，正好适合放"这么一个小东西"</li>
 * </ul>
 *
 * <p>加密：P1 不做（D2 §5 已定）。Key 明文落在应用私有目录里，
 * 能挡住普通用户与应用间窥探，挡不住 root 与逆向——这一点在论文里主动说明即可。
 */
public final class LlmConfigStore {

    private static final String PREFS_NAME = "wisebook_llm_credentials";
    private static final String KEY_PROVIDER = "provider";
    private static final String KEY_MODEL = "model";
    private static final String KEY_API_KEY = "api_key";

    private final SharedPreferences preferences;

    public LlmConfigStore(Context context) {
        this.preferences = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /**
     * 当前服务商。
     *
     * <p>没在设置页指定过时，<b>跟着实际配了 Key 的那一家走</b>：
     * 只往 {@code local.properties} 里配了 DeepSeek 的 Key，默认就是 DeepSeek，
     * 不需要同时记着去改代码——否则「配了 A 家的 Key 却还往 B 家发请求」
     * 会表现成 401，排查半天才发现是两处配置没对齐。
     *
     * <p>两家都没配 Key 时退回聚合平台（D2 §6 定的主力链路），
     * 界面会提示去设置页填。
     */
    public LlmProvider provider() {
        String code = preferences.getString(KEY_PROVIDER, null);
        if (code != null && !code.trim().isEmpty()) {
            try {
                return LlmProvider.fromCode(code);
            } catch (IllegalArgumentException e) {
                // 存的值不认识了（改过枚举），退回下面的推断而不是崩在启动路径上
            }
        }
        if (!BuildConfig.DEEPSEEK_API_KEY.trim().isEmpty()) {
            return LlmProvider.DEEPSEEK;
        }
        return LlmProvider.SILICONFLOW;
    }

    /** 没覆盖过时用该服务商的默认模型 */
    public String model() {
        String override = preferences.getString(KEY_MODEL, null);
        return (override == null || override.trim().isEmpty())
                ? provider().defaultModel()
                : override.trim();
    }

    /** 没覆盖过时用编译期注入的默认 Key；两者都没有则返回空串 */
    public String apiKey() {
        String override = preferences.getString(KEY_API_KEY, null);
        if (override != null && !override.trim().isEmpty()) {
            return override.trim();
        }
        return buildConfigKey(provider());
    }

    /** 是否已具备可用的 Key。界面据此决定是先提示"去设置页填 Key"还是直接发起解析 */
    public boolean hasApiKey() {
        return !apiKey().isEmpty();
    }

    /**
     * 仅运行时覆盖的 Key，<b>不含</b>编译期默认值；没设置过返回 {@code null}。
     *
     * <p>设置页要的是这个而不是 {@link #apiKey()}：预填时必须区分
     * 「用户自己填过」与「来自 local.properties」，否则一进设置页点保存，
     * 就会把编译期默认值固化成一条覆盖记录，之后重新打包换 Key 反而不生效。
     */
    public String apiKeyOverride() {
        return preferences.getString(KEY_API_KEY, null);
    }

    /** 仅运行时覆盖的模型名，没设置过返回 {@code null} */
    public String modelOverride() {
        return preferences.getString(KEY_MODEL, null);
    }

    /** 写入运行时覆盖值；传空串或 null 表示"这一项改用默认值" */
    public void saveOverride(LlmProvider provider, String model, String apiKey) {
        SharedPreferences.Editor editor = preferences.edit();
        // putString 传 null 等价于 remove，正好是"清除覆盖"的语义
        editor.putString(KEY_PROVIDER, provider == null ? null : provider.code());
        putOrRemove(editor, KEY_MODEL, model);
        putOrRemove(editor, KEY_API_KEY, apiKey);
        editor.apply();
    }

    public void clearOverride() {
        preferences.edit().clear().apply();
    }

    private static void putOrRemove(SharedPreferences.Editor editor, String key, String value) {
        if (value == null || value.trim().isEmpty()) {
            editor.remove(key);
        } else {
            editor.putString(key, value.trim());
        }
    }

    /**
     * 编译期默认 Key。
     *
     * <p>取值来自 {@code app/build.gradle.kts} 里从 {@code local.properties} 读出的
     * {@code wisebook.siliconflow.key} / {@code wisebook.deepseek.key}。
     * 没配就是空串——不影响构建，只是需要去设置页填。
     */
    static String buildConfigKey(LlmProvider provider) {
        switch (provider) {
            case DEEPSEEK:
                return BuildConfig.DEEPSEEK_API_KEY;
            case SILICONFLOW:
            default:
                return BuildConfig.SILICONFLOW_API_KEY;
        }
    }
}
