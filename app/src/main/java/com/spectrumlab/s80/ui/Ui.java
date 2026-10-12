package com.spectrumlab.s80.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.spectrumlab.s80.core.Theme;

/** Programmatic UI builders for the dark electric-blue system (docs/05).
 *  All touch targets >= 48dp; telemetry values use monospace. */
public final class Ui {
    private Ui() {}

    public static int dp(Context c, int n) {
        return Math.round(n * c.getResources().getDisplayMetrics().density);
    }

    public static TextView text(Context c, String s, int sp, int color, boolean bold) {
        TextView v = new TextView(c);
        v.setText(s);
        v.setTextSize(sp);
        v.setTextColor(color);
        v.setPadding(0, 0, 0, 0);
        if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return v;
    }

    public static TextView mono(Context c, String s, int sp, int color) {
        TextView v = text(c, s, sp, color, false);
        v.setTypeface(Typeface.MONOSPACE);
        return v;
    }

    public static TextView label(Context c, String s, int sp, int color) {
        TextView v = text(c, s, sp, color, true);
        v.setAllCaps(true);
        v.setLetterSpacing(0.08f);
        return v;
    }

    public static Button button(Context c, String caption, int bgColor, View.OnClickListener l) {
        Button b = new Button(c);
        b.setText(caption);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setTextColor(Theme.FG);
        b.setMinimumHeight(dp(c, 48));
        b.setMinHeight(dp(c, 48));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(bgColor);
        bg.setCornerRadius(dp(c, 4));
        b.setBackground(bg);
        b.setPadding(dp(c, 14), 0, dp(c, 14), 0);
        b.setOnClickListener(l);
        return b;
    }

    /** Rounded surface card container. */
    public static LinearLayout card(Context c) {
        LinearLayout v = new LinearLayout(c);
        v.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(c, 12);
        v.setPadding(pad, dp(c, 10), pad, dp(c, 12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Theme.SURFACE);
        bg.setCornerRadius(dp(c, 6));
        int m = dp(c, 4);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(0, m, 0, m);
        v.setBackground(bg);
        v.setLayoutParams(p);
        return v;
    }

    /** Small status chip (capability pill, docs/05). */
    public static TextView chip(Context c, String caption, int bgColor, int fgColor) {
        TextView v = text(c, caption, 11, fgColor, true);
        v.setPadding(dp(c, 9), dp(c, 5), dp(c, 9), dp(c, 5));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(bgColor);
        bg.setCornerRadius(dp(c, 12));
        int m = dp(c, 3);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2);
        p.setMargins(m, 0, m, 0);
        v.setBackground(bg);
        v.setLayoutParams(p);
        v.setGravity(Gravity.CENTER);
        return v;
    }

    public static HorizontalScrollView chipRow(Context c) {
        HorizontalScrollView h = new HorizontalScrollView(c);
        h.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        int pad = dp(c, 2);
        row.setPadding(0, pad, 0, pad);
        h.addView(row);
        h.setTag(row);
        return h;
    }

    public static LinearLayout row(Context c) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        return row;
    }

    public static void spacer(Context c, LinearLayout parent, int dp) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(c, dp)));
        parent.addView(v);
    }

    public static View divider(Context c) {
        View v = new View(c);
        v.setBackgroundColor(Theme.SURFACE2);
        v.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(c, 1)));
        return v;
    }

    public static LinearLayout.LayoutParams fullWidth() {
        return new LinearLayout.LayoutParams(-1, -2);
    }

    public static LinearLayout.LayoutParams matchWrap(ViewGroup parent) {
        return new LinearLayout.LayoutParams(-1, -2);
    }
}
