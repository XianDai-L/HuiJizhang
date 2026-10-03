package com.wisebook.app.ui;

import android.os.Build;
import android.view.View;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * 让内容避让状态栏、导航栏与输入法。
 *
 * <p><b>坑一：{@code View.setPadding()} 是整体覆盖，不是累加。</b>
 * 模板生成的写法是
 * <pre>v.setPadding(bars.left, bars.top, bars.right, bars.bottom)</pre>
 * 它作用在没有 XML padding 的容器上没问题；一旦容器自己写了 {@code android:padding="24dp"}，
 * 那 24dp 就会被悄悄抹掉——表现是文字顶到屏幕边缘。
 *
 * <p><b>坑二：{@code windowSoftInputMode="adjustResize"} 在全面屏（edge-to-edge）下不生效。</b>
 * {@code EdgeToEdge.enable()} 内部会调 {@code setDecorFitsSystemWindows(false)}，
 * 从那以后系统<b>不再</b>替窗口让出键盘的位置，IME 直接盖在内容上。
 * 表现就是"输入框被键盘盖住"——而 manifest 里明明写着 adjustResize，
 * 所以这个 bug 从配置上完全看不出来（2026-10-02 首页输入栏踩到，见 HANDOFF §8-29）。
 *
 * <p>所以键盘也得当成一条"系统栏"来避让：把 IME insets 一起算进底部 padding。
 * 用 {@code systemBars() | ime()} 取并集，底部自然取两者中较大的那个
 * ——键盘没弹出时 ime 为 0，不会多出空白。
 *
 * <p><b>只在 API 30+ 这么干。</b>更早的版本系统不派发 IME insets，
 * androidx 的兼容实现是用"窗口被压扁了多少"倒推出来的，而那时 adjustResize
 * 又确实在生效——两边一起算会多出一个键盘高度的空白。
 */
public final class EdgeToEdgeInsets {

    private EdgeToEdgeInsets() {
    }

    /** 给 {@code view} 加上避让系统栏（与键盘）的额外 padding，并保留它原本的 padding */
    public static void apply(View view) {
        final int initialLeft = view.getPaddingLeft();
        final int initialTop = view.getPaddingTop();
        final int initialRight = view.getPaddingRight();
        final int initialBottom = view.getPaddingBottom();

        ViewCompat.setOnApplyWindowInsetsListener(view, (target, windowInsets) -> {
            Insets bars = windowInsets.getInsets(insetsToAvoid());
            target.setPadding(
                    initialLeft + bars.left,
                    initialTop + bars.top,
                    initialRight + bars.right,
                    initialBottom + bars.bottom);
            return windowInsets;
        });
    }

    private static int insetsToAvoid() {
        int types = WindowInsetsCompat.Type.systemBars();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            types |= WindowInsetsCompat.Type.ime();
        }
        return types;
    }
}
