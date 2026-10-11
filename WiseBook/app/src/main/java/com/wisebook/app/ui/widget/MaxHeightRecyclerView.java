package com.wisebook.app.ui.widget;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

/**
 * 有高度上限的 RecyclerView。
 *
 * <p>首页「最近账目」需要的是「内容多长就多高，但最多到某个高度为止」，
 * 而 RecyclerView 没有 {@code maxHeight} 属性。两种现成做法都不对：
 *
 * <ul>
 *   <li>{@code layout_weight=1}：只有两笔账时卡片被撑满整屏，下面一大块空白；</li>
 *   <li>固定高度：只有一笔账时同样空着一截。</li>
 * </ul>
 *
 * <p>所以这里加一个上限——内容短就随内容，内容长就到此为止、内部滚动。
 * 只重写 {@code onMeasure} 一处，没有别的行为改动。
 */
public class MaxHeightRecyclerView extends RecyclerView {

    /** 0 表示不限制 */
    private int maxHeightPx;

    public MaxHeightRecyclerView(Context context) {
        super(context);
    }

    public MaxHeightRecyclerView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public MaxHeightRecyclerView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public void setMaxHeightPx(int maxHeightPx) {
        this.maxHeightPx = maxHeightPx;
        requestLayout();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        if (maxHeightPx > 0) {
            // 用 AT_MOST 重新包一层：子类自己的高度照旧由内容决定，
            // 只是不允许超过 maxHeightPx
            heightSpec = View.MeasureSpec.makeMeasureSpec(maxHeightPx, View.MeasureSpec.AT_MOST);
        }
        super.onMeasure(widthSpec, heightSpec);
    }
}
