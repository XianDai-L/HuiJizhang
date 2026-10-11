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
 *
 * <p>与 {@code p2-experiment} 里的同名类是一份拷贝：两个实验工程各自独立可跑，
 * 不共享源码目录（共享反而会让"跑不起来"多一种原因）。
 */
public final class Env {

    private static final Path LOCAL_PROPERTIES = Paths.get("D:/HuiJi/WiseBook/local.properties");

    private Env() {
    }

    public static String deepSeekKey() {
        return property("wisebook.deepseek.key");
    }

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
