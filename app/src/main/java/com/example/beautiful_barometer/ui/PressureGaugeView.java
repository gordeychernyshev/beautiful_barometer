// app/src/main/java/com/example/beautiful_barometer/ui/PressureGaugeView.java
package com.example.beautiful_barometer.ui;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;
import androidx.core.graphics.ColorUtils;


import androidx.annotation.Nullable;

import com.example.beautiful_barometer.util.Units;
import com.google.android.material.color.MaterialColors;

public class PressureGaugeView extends View {

    private float minHpa = 950f;
    private float maxHpa = 1050f;
    private float valueHpa = 1013.25f;

    private Units.System units = Units.System.METRIC;

    private final Paint arcBg  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tick   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint needle = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF oval = new RectF();

    public PressureGaugeView(Context c) { super(c); init(); }
    public PressureGaugeView(Context c, @Nullable AttributeSet a) { super(c, a); init(); }
    public PressureGaugeView(Context c, @Nullable AttributeSet a, int s) { super(c, a, s); init(); }

    private void init() {
        // Базовые стили
        arcBg.setStyle(Paint.Style.STROKE);
        arcBg.setStrokeCap(Paint.Cap.ROUND);

        tick.setStyle(Paint.Style.STROKE);
        tick.setStrokeCap(Paint.Cap.ROUND);

        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        text.setSubpixelText(true);
        text.setLinearText(true);

        needle.setStyle(Paint.Style.STROKE);
        needle.setStrokeCap(Paint.Cap.ROUND);

        applyThemeColors();
    }

    /** Подтягиваем цвета из текущей темы (динамически для light/dark/Material You). */
    private void applyThemeColors() {
        int onSurface      = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface);
        int outlineVariant = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutlineVariant);
        int primary        = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary);

        arcBg.setColor(outlineVariant);
// было: tick.setColor(MaterialColors.layer(this, onSurface, 0.65f));
        tick.setColor(ColorUtils.setAlphaComponent(onSurface, (int) (0.65f * 255)));
        text.setColor(onSurface);
        needle.setColor(primary);

        invalidate();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        applyThemeColors();
    }

    @Override public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        applyThemeColors();
    }

    public void setUnits(Units.System u) { this.units = u; invalidate(); }

    public void setRange(float minHpa, float maxHpa) {
        this.minHpa = minHpa;
        this.maxHpa = maxHpa;
        invalidate();
    }

    public void setPressure(double hpa) {
        float newVal = (float) hpa;
        valueHpa = clamp(newVal, minHpa - 20, maxHpa + 20);
        invalidate();
    }

    private float clamp(float v, float a, float b) { return Math.max(a, Math.min(b, v)); }
    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }


    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int widthMode = MeasureSpec.getMode(widthMeasureSpec);
        int widthSize = MeasureSpec.getSize(widthMeasureSpec);

        int targetWidth = widthSize;
        if (widthMode == MeasureSpec.UNSPECIFIED || targetWidth <= 0) {
            targetWidth = (int) dp(320f);
        }

        int desiredHeight = Math.max(getSuggestedMinimumHeight(), Math.round(targetWidth * 0.56f));

        int heightMode = MeasureSpec.getMode(heightMeasureSpec);
        int heightSize = MeasureSpec.getSize(heightMeasureSpec);
        int measuredHeight;
        if (heightMode == MeasureSpec.EXACTLY) {
            measuredHeight = Math.min(heightSize, desiredHeight);
        } else if (heightMode == MeasureSpec.AT_MOST) {
            measuredHeight = Math.min(heightSize, desiredHeight);
        } else {
            measuredHeight = desiredHeight;
        }

        setMeasuredDimension(resolveSize(targetWidth, widthMeasureSpec), measuredHeight);
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float padding = dp(10);
        float radius = Math.max(1f, Math.min((w - 2f * padding) / 2f, h - padding));

        arcBg.setStrokeWidth(Math.max(dp(2f), radius * 0.025f));
        tick.setStrokeWidth(Math.max(dp(1.5f), radius * 0.012f));
        needle.setStrokeWidth(Math.max(dp(3f), radius * 0.022f));
        text.setTextSize(radius * 0.12f);
    }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);

        int w = getWidth();
        int h = getHeight();
        float padding = dp(10);
        float cx = w / 2f;

        // Верхняя полудуга растягивается по высоте view, без пустого хвоста снизу.
        float radius = Math.max(1f, Math.min((w - 2f * padding) / 2f, h - padding));
        float cy = h - padding;

        oval.set(cx - radius, cy - radius, cx + radius, cy + radius);

        // Верхняя полуокружность (слева → сверху → справа)
        c.drawArc(oval, 180, 180, false, arcBg);

        // Деления и подписи (ИНВЕРСИЯ шкалы: MIN справа → MAX слева)
        int major = 10;
        for (int i = 0; i <= major; i++) {
            float f = (float) i / major;      // 0..1
            float ang = 0f + 180f * f;        // 0° справа → 180° слева
            double rad = Math.toRadians(ang);
            float sin = (float) Math.sin(rad);
            float cos = (float) Math.cos(rad);

            float r1 = radius * 0.86f;
            float r2 = radius * 0.96f;

            float x1 = cx + r1 * cos, y1 = cy - r1 * sin;
            float x2 = cx + r2 * cos, y2 = cy - r2 * sin;
            c.drawLine(x1, y1, x2, y2, tick);

            float hpaVal = minHpa + (maxHpa - minHpa) * f;
            String label = formatTick(hpaVal);

            float tx = cx + (radius * 0.72f) * cos;
            float ty = cy - (radius * 0.72f) * sin + text.getTextSize() * 0.35f;
            c.drawText(label, tx, ty, text);
        }

        // Игла (0° — справа, 180° — слева)
        float frac = (valueHpa - minHpa) / (maxHpa - minHpa);
        frac = Math.max(0f, Math.min(1f, frac));
        float ang = 180f * frac;
        double rad = Math.toRadians(ang);
        float nx = cx + (radius * 0.80f) * (float) Math.cos(rad);
        float ny = cy - (radius * 0.80f) * (float) Math.sin(rad);
        c.drawLine(cx, cy, nx, ny, needle);
        c.drawCircle(cx, cy, Math.max(dp(3f), radius * 0.03f), needle);
    }

    private String formatTick(float hpaVal) {
        switch (units) {
            case MMHG:
                return String.format(java.util.Locale.getDefault(), "%.0f", hpaVal * 0.7500617f);
            case IMPERIAL:
                return String.format(java.util.Locale.getDefault(), "%.2f", hpaVal * 0.0295299831f);
            case METRIC:
            default:
                return String.format(java.util.Locale.getDefault(), "%.0f", hpaVal);
        }
    }
}
