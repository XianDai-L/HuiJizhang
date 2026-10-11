package com.wisebook.app.ui.widget;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.NumberPicker;

import androidx.appcompat.app.AlertDialog;

import com.wisebook.app.R;

import java.time.LocalDate;

/**
 * 「选月份」的两个滚轮。
 *
 * <p>点顶栏中间那串年月就弹它——翻月份只靠左右箭头的话，从 2026 年 10 月回到 3 月要点七次，
 * 有个能直接跳的入口省事得多。
 *
 * <p>用 {@link NumberPicker} 而不是 Material 的日期选择器：这里只需要年 + 月两列，
 * 套一个"选某一天"的控件会让用户以为自己得挑个日子。
 */
public final class MonthPicker {

    /** 年份下界。比这个应用可能出现的账目宽一些就够了，不需要无限往前 */
    private static final int MIN_YEAR = 2000;

    public interface OnPicked {
        void onPicked(int year, int month);
    }

    private MonthPicker() {
    }

    public static void show(Context context, int year, int month, OnPicked callback) {
        View content = LayoutInflater.from(context)
                .inflate(R.layout.dialog_month_picker, null, false);

        NumberPicker yearPicker = content.findViewById(R.id.picker_year);
        NumberPicker monthPicker = content.findViewById(R.id.picker_month);

        // 上界留给未来一年：补记、或者把账目时间写在将来，都不该被滚轮挡住
        int maxYear = Math.max(year, LocalDate.now().getYear()) + 1;
        yearPicker.setMinValue(MIN_YEAR);
        yearPicker.setMaxValue(maxYear);
        yearPicker.setValue(Math.min(Math.max(year, MIN_YEAR), maxYear));

        monthPicker.setMinValue(1);
        monthPicker.setMaxValue(12);
        monthPicker.setValue(Math.min(Math.max(month, 1), 12));
        // 年份那一列是纯数字，月份这一列加个后缀，两列挨着才分得清哪个是哪个
        monthPicker.setFormatter(value -> value + " 月");

        new AlertDialog.Builder(context)
                .setTitle(R.string.month_picker_title)
                .setView(content)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_confirm, (dialog, which) ->
                        callback.onPicked(yearPicker.getValue(), monthPicker.getValue()))
                .show();
    }
}
