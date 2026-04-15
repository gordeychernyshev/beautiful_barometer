// app/src/main/java/com/example/beautiful_barometer/ui/forecast/ForecastSparklineView.java
package com.example.beautiful_barometer.ui.forecast;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import com.google.android.material.color.MaterialColors;

import java.util.ArrayList;
import java.util.List;

/**
 * Мини-таймлайн (sparkline) по давлению.
 * Без внешних библиотек, рисуется быстро и стабильно.
 */
public class ForecastSparklineView extends View {

    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final List<Float> values = new ArrayList<>(); // pressure values
    private float minV = 0f;
    private float maxV = 0f;

    public ForecastSparklineView(Context context) {
        super(context);
        init();
    }

    public ForecastSparklineView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public ForecastSparklineView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        int onSurface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface);
        int outline = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutlineVariant);

        linePaint.setColor(onSurface);
        linePaint.setStrokeWidth(dp(1.6f));
        linePaint.setStyle(Paint.Style.STROKE);

        dotPaint.setColor(outline);
        dotPaint.setStyle(Paint.Style.FILL);
    }

    /** Передай уже “сжатые” точки (например 24–40 шт). */
    public void setSeries(List<Float> series) {
        values.clear();
        if (series != null) values.addAll(series);

        // min/max
        if (values.isEmpty()) {
            minV = maxV = 0f;
        } else {
            minV = Float.POSITIVE_INFINITY;
            maxV = Float.NEGATIVE_INFINITY;
            for (Float v : values) {
                if (v == null) continue;
                if (v < minV) minV = v;
                if (v > maxV) maxV = v;
            }
            if (minV == Float.POSITIVE_INFINITY) {
                minV = maxV = 0f;
            }
            if (Math.abs(maxV - minV) < 0.01f) {
                // чтобы линия не стала “плоской точкой”
                maxV = minV + 0.01f;
            }
        }
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (values.size() < 2) return;

        float w = getWidth();
        float h = getHeight();
        float pad = dp(2f);

        float left = pad;
        float right = w - pad;
        float top = pad;
        float bottom = h - pad;

        int n = values.size();
        float dx = (right - left) / (n - 1);

        float prevX = left;
        float prevY = mapY(values.get(0), top, bottom);

        for (int i = 1; i < n; i++) {
            float x = left + i * dx;
            float y = mapY(values.get(i), top, bottom);
            canvas.drawLine(prevX, prevY, x, y, linePaint);
            prevX = x;
            prevY = y;
        }

        // точки по краям (чтобы читалось)
        canvas.drawCircle(left, mapY(values.get(0), top, bottom), dp(2.2f), dotPaint);
        canvas.drawCircle(right, mapY(values.get(n - 1), top, bottom), dp(2.2f), dotPaint);
    }

    private float mapY(Float v, float top, float bottom) {
        if (v == null) return bottom;
        float t = (v - minV) / (maxV - minV); // 0..1
        return bottom - t * (bottom - top);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
