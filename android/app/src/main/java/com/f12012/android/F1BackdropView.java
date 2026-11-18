package com.f12012.android;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * Animated F1 2012 telemetry / carbon backdrop for the launcher.
 */
final class F1BackdropView extends View {
    private static final int STREAK_COUNT = 10;

    private final Paint bgPaint = new Paint();
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint();
    private final Paint streakPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final float[] streakY = new float[STREAK_COUNT];
    private final float[] streakLen = new float[STREAK_COUNT];
    private final float[] streakSpeed = new float[STREAK_COUNT];
    private final float[] streakAlpha = new float[STREAK_COUNT];
    private final boolean[] streakRed = new boolean[STREAK_COUNT];

    private ValueAnimator animator;
    private float phase;
    private int cachedW;
    private int cachedH;

    F1BackdropView(Context context) {
        super(context);
        gridPaint.setColor(0x0DFFFFFF);
        gridPaint.setStrokeWidth(1f);

        for (int i = 0; i < STREAK_COUNT; i++) {
            streakY[i] = 0.08f + (i * 0.86f / STREAK_COUNT);
            streakLen[i] = 0.14f + ((i * 37) % 100) / 360f;
            streakSpeed[i] = 0.35f + ((i * 53) % 100) / 140f;
            streakAlpha[i] = 0.12f + ((i * 29) % 100) / 260f;
            streakRed[i] = (i % 3 == 0);
        }
    }

    private void ensureShaders(int w, int h) {
        if (w == cachedW && h == cachedH) return;
        cachedW = w;
        cachedH = h;
        bgPaint.setShader(new LinearGradient(
                0, 0, w, h,
                new int[] {0xFF07090E, 0xFF101520, 0xFF06070B},
                new float[] {0f, 0.55f, 1f},
                Shader.TileMode.CLAMP));
        glowPaint.setShader(new RadialGradient(
                w * 0.82f, h * 0.88f, Math.max(w, h) * 0.65f,
                new int[] {0x38E10600, 0x12E10600, 0x00E10600},
                new float[] {0f, 0.45f, 1f},
                Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;
        ensureShaders(w, h);

        canvas.drawRect(0, 0, w, h, bgPaint);
        canvas.drawRect(0, 0, w, h, glowPaint);

        // Subtle diagonal carbon weave lines
        float step = 36f * getResources().getDisplayMetrics().density;
        for (float x = -h; x < w + h; x += step) {
            canvas.drawLine(x, 0, x + h * 0.45f, h, gridPaint);
        }

        // Animated horizontal F1 speed streaks
        float stroke = 1.6f * getResources().getDisplayMetrics().density;
        streakPaint.setStrokeWidth(stroke);
        for (int i = 0; i < STREAK_COUNT; i++) {
            float progress = (phase * streakSpeed[i] + i * 0.17f) % 1.4f - 0.2f;
            float x1 = progress * w;
            float x2 = x1 + streakLen[i] * w;
            float y = streakY[i] * h;
            int baseColor = streakRed[i] ? 0x00E10600 : 0x00FFFFFF;
            int a = Math.round(streakAlpha[i] * 255f);
            streakPaint.setShader(new LinearGradient(
                    x1, y, x2, y,
                    new int[] {baseColor, (a << 24) | baseColor, baseColor},
                    new float[] {0f, 0.65f, 1f},
                    Shader.TileMode.CLAMP));
            canvas.drawLine(x1, y, x2, y, streakPaint);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        startAnim();
    }

    @Override
    protected void onDetachedFromWindow() {
        stopAnim();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility == VISIBLE && isAttachedToWindow()) startAnim();
        else stopAnim();
    }

    private void startAnim() {
        if (animator != null) return;
        animator = ValueAnimator.ofFloat(0f, 100f);
        animator.setDuration(100000L);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(a -> {
            phase = (float) a.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    private void stopAnim() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
    }
}
