package exp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * 读取 WiseBook 的 {@code local.properties}。
 *
 * <p>刻意不给 Key 任何默认值：缺 Key 时要一眼看见"没配"，而不是跑出一堆鉴权失败。
 * Key 只在本进程内存里，不写日志、不进结果文件。
 */
public final class Env {

    private static final Path LOCAL_PROPERTIES = Paths.get("D:/HuiJi/WiseBook/local.properties");

    private Env() {
    }

    public static String deepSeekKey() {
        return property("wisebook.deepseek.key");
    }

    /** 硅基流动 Key：路线 B 的 DeepSeek-OCR 挂在它下面；没配就返回 null */
    public static String siliconFlowKey() {
        return property("wisebook.siliconflow.key");
    }

    private static String property(String key) {
        if (!Files.isRegularFile(LOCAL_PROPERTIES)) {
            return null;
        }
        try {
            List<String> lines = Files.readAllLines(LOCAL_PROPERTIES, StandardCharsets.UTF_8);
            String prefix = key + "=";
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.startsWith(prefix)) {
                    String value = trimmed.substring(prefix.length()).trim();
                    return value.isEmpty() ? null : value;
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("读取 local.properties 失败：" + e.getMessage(), e);
        }
        return null;
    }
}
