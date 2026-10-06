package com.wisebook.app.ui;

import android.content.ContentResolver;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import com.wisebook.app.input.image.ImageInput;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

/**
 * 把照片选择器给的 {@link Uri} 读成字节，再交给回调。
 *
 * <p><b>为什么单开一个类而不放进 ViewModel</b>：ViewModel 至今不依赖任何 Android 类型
 * （它的测试跑在 JVM 上）。为了一个 Uri 把它拉进 Android，之后每加一个入口都会顺手再塞一个。
 * 而"读字节"是纯 IO：在后台线程做完，把结果交给 ViewModel 即可。
 *
 * <p><b>回调一定在主线程</b>：调用方（ViewModel）在回调里会直接改 LiveData 状态，
 * 而 {@code setValue} 必须在主线程——把这层保证放在这里，调用方就不用各自记得切线程。
 *
 * <p>读完就丢：字节只在这条链路里活一次。用户明确决定「截图原图不留」
 * （HANDOFF 决策 37），所以这里不写文件、不缓存、不返回 Uri。
 */
public final class ImageInputLoader {

    /**
     * 单张截图的大小上限。超过就拒掉：一次记账用不上几十 MB 的图，
     * 而它会被 base64 之后塞进请求体——真发出去只会得到一个又慢又贵的 400。
     */
    public static final int MAX_BYTES = 8 * 1024 * 1024;

    private ImageInputLoader() {
    }

    /**
     * @param executor 后台线程（用应用唯一的数据库线程即可，那里本来就在等网络）
     * @param onLoaded 主线程回调；参数含字节与 mimeType
     * @param onFailed 主线程回调；参数是给用户看的一句话
     */
    public static void loadAsync(ContentResolver resolver, Uri uri, ExecutorService executor,
                                 Consumer<ImageInput> onLoaded, Consumer<String> onFailed) {
        Handler main = new Handler(Looper.getMainLooper());
        executor.execute(() -> {
            try {
                byte[] bytes = readAll(resolver, uri);
                if (bytes.length == 0) {
                    main.post(() -> onFailed.accept("这张图读出来是空的，换一张试试"));
                    return;
                }
                if (bytes.length > MAX_BYTES) {
                    main.post(() -> onFailed.accept(
                            "这张图太大了（超过 8 MB），先用系统相册裁一下再试"));
                    return;
                }
                String mimeType = resolver.getType(uri);
                ImageInput image = new ImageInput(bytes,
                        mimeType == null ? "image/jpeg" : mimeType, "相册截图");
                main.post(() -> onLoaded.accept(image));
            } catch (IOException e) {
                main.post(() -> onFailed.accept("读不到这张图：" + e.getMessage()));
            } catch (SecurityException e) {
                // 选图产生的是一次性读权限；真走到这里通常是 Uri 已经失效（换过一次选择）
                main.post(() -> onFailed.accept("这张图的访问权限已失效，重新选一次"));
            }
        });
    }

    private static byte[] readAll(ContentResolver resolver, Uri uri) throws IOException {
        try (InputStream input = resolver.openInputStream(uri)) {
            if (input == null) {
                throw new IOException("打开图片失败");
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }
}
