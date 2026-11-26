package com.feurstagram.extension;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.PathInterpolator;
import android.widget.CompoundButton;

/** Compact switch with a 48dp target and the host's light/dark palette. */
final class ExpressiveSwitch extends CompoundButton {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF track = new RectF();
    private float position;
    private float press;
    private ValueAnimator movement;
    private ValueAnimator pressure;

    ExpressiveSwitch(Context context) {
        super(context);
        setButtonDrawable(null);
        setBackground(null);
        setPadding(0, 0, 0, 0);
        setClickable(true);
        setFocusable(true);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        setMeasuredDimension(resolveSize(dp(56), widthSpec), resolveSize(dp(48), heightSpec));
    }

    @Override
    public void setChecked(boolean checked) {
        super.setChecked(checked);
        float target = isChecked() ? 1f : 0f;
        if (movement != null) movement.cancel();
        if (!isLaidOut() || !isAttachedToWindow() || !ValueAnimator.areAnimatorsEnabled()) {
            position = target;
            invalidate();
            return;
        }
        movement = animateValue(position, target, value -> position = value);
    }

    @Override
    public void setPressed(boolean pressed) {
        super.setPressed(pressed);
        if (pressure != null) pressure.cancel();
        float target = pressed && isEnabled() ? 1f : 0f;
        if (!isLaidOut() || !ValueAnimator.areAnimatorsEnabled()) {
            press = target;
            invalidate();
        } else {
            pressure = animateValue(press, target, value -> press = value);
        }
    }

    private interface Frame { void apply(float value); }

    private ValueAnimator animateValue(float from, float to, Frame frame) {
        ValueAnimator animator = ValueAnimator.ofFloat(from, to);
        animator.setDuration(200L);
        animator.setInterpolator(new PathInterpolator(0.2f, 0f, 0f, 1f));
        animator.addUpdateListener(value -> {
            frame.apply((float) value.getAnimatedValue());
            invalidate();
        });
        animator.start();
        return animator;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float scale = getResources().getDisplayMetrics().density;
        CabessaUi.Palette palette = CabessaUi.colors(getContext());
        float left = (getWidth() - 44f * scale) / 2f;
        float centerY = getHeight() / 2f;
        track.set(left, centerY - 13f * scale, left + 44f * scale, centerY + 13f * scale);
        paint.setStyle(Paint.Style.FILL);
        int off = palette.dark ? 0xFF555555 : 0xFFA8A8A8;
        paint.setColor(blend(off, palette.primary, position));
        paint.setAlpha(isEnabled() ? 255 : 100);
        canvas.drawRoundRect(track, 13f * scale, 13f * scale, paint);

        float offset = getLayoutDirection() == LAYOUT_DIRECTION_RTL ? 1f - position : position;
        float centerX = left + (13f + 18f * offset) * scale;
        paint.setStyle(Paint.Style.FILL);
        if (press > 0f || isFocused()) {
            paint.setColor(palette.text);
            paint.setAlpha((int) (26f * Math.max(press, isFocused() ? 1f : 0f)));
            canvas.drawCircle(centerX, centerY, 22f * scale, paint);
        }
        paint.setColor(Color.WHITE);
        paint.setAlpha(isEnabled() ? 255 : 160);
        canvas.drawCircle(centerX, centerY, 10f * scale, paint);
    }

    @Override
    public CharSequence getAccessibilityClassName() {
        return "android.widget.Switch";
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setCheckable(true);
        info.setChecked(isChecked());
    }

    @Override
    public void jumpDrawablesToCurrentState() {
        super.jumpDrawablesToCurrentState();
        if (movement != null) movement.cancel();
        if (pressure != null) pressure.cancel();
        position = isChecked() ? 1f : 0f;
        press = isPressed() ? 1f : 0f;
        invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (movement != null) movement.cancel();
        if (pressure != null) pressure.cancel();
        super.onDetachedFromWindow();
    }

    private int dp(int value) { return Settings.dp(getContext(), value); }

    private static int blend(int from, int to, float amount) {
        return Color.rgb(
                (int) (Color.red(from) + (Color.red(to) - Color.red(from)) * amount),
                (int) (Color.green(from) + (Color.green(to) - Color.green(from)) * amount),
                (int) (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * amount));
    }
}
