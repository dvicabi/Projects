package com.feurstagram.extension;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;

/** Small resource-free controls that share the host's light/dark appearance. */
public final class CabessaUi {
    private CabessaUi() {}

    public static final class Palette {
        public final int background, surface, raised, text, secondary, divider, ripple, border;
        public final int primary = 0xFF4A5DF9;
        public final int onPrimary = Color.WHITE;
        public final boolean dark;

        private Palette(boolean dark, int background, int surface, int raised, int text,
                        int secondary, int divider, int ripple, int border) {
            this.dark = dark;
            this.background = background;
            this.surface = surface;
            this.raised = raised;
            this.text = text;
            this.secondary = secondary;
            this.divider = divider;
            this.ripple = ripple;
            this.border = border;
        }
    }

    public static final Palette DARK = new Palette(true, 0xFF000000, 0xFF121212,
            0xFF262626, 0xFFF5F5F5, 0xFFA8A8A8, 0xFF262626, 0x24FFFFFF, 0xFF363636);
    public static final Palette LIGHT = new Palette(false, 0xFFFFFFFF, 0xFFFFFFFF,
            0xFFEFEFEF, 0xFF121212, 0xFF666666, 0xFFDBDBDB, 0x18000000, 0xFFD6D6D6);

    public static Palette colors(Context context) {
        // Resolve the host's theme first: its app-level appearance can differ
        // from the device preference. A missing/non-color attribute falls back
        // to the effective Configuration, never to a global static setting.
        TypedValue value = new TypedValue();
        if (context.getTheme().resolveAttribute(android.R.attr.colorBackground, value, true)
                && value.type >= TypedValue.TYPE_FIRST_COLOR_INT
                && value.type <= TypedValue.TYPE_LAST_COLOR_INT
                && Color.alpha(value.data) == 255) {
            return isLight(value.data) ? LIGHT : DARK;
        }
        int mode = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES ? DARK : LIGHT;
    }

    static boolean isLight(int color) {
        return Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114 >= 128000;
    }

    public static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    public static Button primaryButton(Context context, String text) {
        Palette palette = colors(context);
        return button(context, text, palette.primary, palette.onPrimary, palette, true);
    }

    public static Button primaryButton(Context context, String text, boolean onDark) {
        Palette palette = onDark ? DARK : colors(context);
        return button(context, text, palette.primary, palette.onPrimary, palette, true);
    }

    public static Button secondaryButton(Context context, String text) {
        return secondaryButton(context, text, false);
    }

    public static Button secondaryButton(Context context, String text, boolean onDark) {
        Palette palette = onDark ? DARK : colors(context);
        return button(context, text, palette.raised, palette.text, palette, true);
    }

    public static Button textButton(Context context, String text) {
        Palette palette = colors(context);
        return button(context, text, Color.TRANSPARENT, palette.primary, palette, false);
    }

    static Button button(Context context, String text, int background, int foreground,
                         Palette palette, boolean filled) {
        Button button = new Button(context);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setLetterSpacing(0f);
        button.setGravity(Gravity.CENTER);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(dp(context, 48));
        button.setMinimumHeight(dp(context, 48));
        // Wrap text at large font sizes instead of clipping the action label.
        button.setPaddingRelative(dp(context, 16), dp(context, 10), dp(context, 16), dp(context, 10));
        button.setStateListAnimator(null);
        button.setBackgroundTintList(null);
        button.setTextColor(new ColorStateList(new int[][]{
                new int[]{-android.R.attr.state_enabled}, new int[]{}
        }, new int[]{palette.secondary, foreground}));
        StateListDrawable surfaces = new StateListDrawable();
        surfaces.addState(new int[]{-android.R.attr.state_enabled},
                rounded(context, filled ? palette.raised : Color.TRANSPARENT, 8));
        surfaces.addState(new int[]{}, rounded(context, background, 8));
        int feedback = foreground == Color.WHITE && background == palette.primary ? 0x33FFFFFF : palette.ripple;
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(feedback), surfaces,
                rounded(context, Color.WHITE, 8)));
        return button;
    }

    static GradientDrawable rounded(Context context, int color, float radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(context, radius));
        return shape;
    }

    public enum Icon { CLOSE, PLAY, PAUSE, REPLAY, CHEVRON, SLIDERS, EYE_OFF, CHECK }

    public static ImageButton iconButton(Context context, Icon icon, String description, boolean onDark) {
        Palette palette = onDark ? DARK : colors(context);
        ImageButton button = new ImageButton(context);
        button.setContentDescription(description);
        button.setMinimumWidth(dp(context, 48));
        button.setMinimumHeight(dp(context, 48));
        button.setPadding(dp(context, 12), dp(context, 12), dp(context, 12), dp(context, 12));
        button.setScaleType(ImageView.ScaleType.FIT_CENTER);
        button.setStateListAnimator(null);
        button.setBackgroundTintList(null);
        GradientDrawable mask = rounded(context, Color.WHITE, 24);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(palette.ripple), null, mask));
        setIcon(button, icon, onDark);
        return button;
    }

    public static void setIcon(ImageButton button, Icon icon, boolean onDark) {
        Palette palette = onDark ? DARK : colors(button.getContext());
        button.setImageDrawable(new LineIcon(icon, palette.text));
        button.setImageTintList(new ColorStateList(new int[][]{
                new int[]{-android.R.attr.state_enabled}, new int[]{}
        }, new int[]{palette.secondary, palette.text}));
    }

    static Drawable rowBackground(Context context, boolean divider) {
        Palette palette = colors(context);
        return new RippleDrawable(ColorStateList.valueOf(palette.ripple),
                new RowSurface(palette.background, palette.divider, divider),
                rounded(context, Color.WHITE, 0));
    }

    private static final class RowSurface extends Drawable {
        private final Paint paint = new Paint();
        private final int background, divider;
        private final boolean line;
        RowSurface(int background, int divider, boolean line) {
            this.background = background;
            this.divider = divider;
            this.line = line;
        }
        @Override public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            paint.setColor(background);
            canvas.drawRect(bounds, paint);
            if (line) {
                paint.setColor(divider);
                canvas.drawRect(bounds.left, bounds.bottom - 1, bounds.right, bounds.bottom, paint);
            }
        }
        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }
        @Override public int getOpacity() { return PixelFormat.OPAQUE; }
    }

    /** Consistent 24-unit line icons; no font-dependent symbols or emoji. */
    public static final class LineIcon extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF rect = new RectF();
        private final Icon icon;
        private ColorStateList tint;

        public LineIcon(Icon icon, int color) {
            this.icon = icon;
            paint.setColor(color);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.8f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            setAutoMirrored(icon == Icon.CHEVRON);
        }

        @Override public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            float scale = Math.min(bounds.width(), bounds.height()) / 24f;
            canvas.save();
            canvas.translate(bounds.exactCenterX() - 12f * scale, bounds.exactCenterY() - 12f * scale);
            canvas.scale(scale, scale);
            if (icon == Icon.CHEVRON && getLayoutDirection() == View.LAYOUT_DIRECTION_RTL) {
                canvas.translate(24, 0);
                canvas.scale(-1, 1);
            }
            path.reset();
            switch (icon) {
                case CLOSE:
                    canvas.drawLine(6, 6, 18, 18, paint);
                    canvas.drawLine(18, 6, 6, 18, paint);
                    break;
                case PLAY:
                    path.moveTo(8, 5); path.lineTo(19, 12); path.lineTo(8, 19); path.close();
                    canvas.drawPath(path, paint);
                    break;
                case PAUSE:
                    canvas.drawLine(8, 5, 8, 19, paint); canvas.drawLine(16, 5, 16, 19, paint);
                    break;
                case REPLAY:
                    rect.set(4, 4, 20, 20); canvas.drawArc(rect, -60, 300, false, paint);
                    path.moveTo(9, 3); path.lineTo(4, 5); path.lineTo(5, 10);
                    canvas.drawPath(path, paint);
                    break;
                case CHEVRON:
                    path.moveTo(9, 5); path.lineTo(16, 12); path.lineTo(9, 19);
                    canvas.drawPath(path, paint);
                    break;
                case SLIDERS:
                    canvas.drawLine(4, 7, 8, 7, paint); canvas.drawLine(14, 7, 20, 7, paint);
                    canvas.drawCircle(11, 7, 3, paint);
                    canvas.drawLine(4, 17, 11, 17, paint); canvas.drawLine(17, 17, 20, 17, paint);
                    canvas.drawCircle(14, 17, 3, paint);
                    break;
                case EYE_OFF:
                    path.moveTo(3, 10); path.cubicTo(8, 4, 16, 4, 21, 12);
                    path.moveTo(21, 12); path.cubicTo(16, 20, 8, 20, 3, 12);
                    canvas.drawPath(path, paint);
                    canvas.drawLine(4, 4, 20, 20, paint);
                    break;
                case CHECK:
                    path.moveTo(5, 12); path.lineTo(10, 17); path.lineTo(19, 7);
                    canvas.drawPath(path, paint);
                    break;
            }
            canvas.restore();
        }
        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
        @Override public void setTint(int color) { paint.setColor(color); invalidateSelf(); }
        @Override public void setTintList(ColorStateList colors) {
            tint = colors;
            if (colors != null) setTint(colors.getColorForState(getState(),colors.getDefaultColor()));
        }
        @Override public boolean isStateful() { return tint != null && tint.isStateful(); }
        @Override protected boolean onStateChange(int[] state) {
            if (tint == null) return false;
            int color=tint.getColorForState(state,tint.getDefaultColor());
            if (paint.getColor() == color) return false;
            setTint(color);
            return true;
        }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        @Override public int getIntrinsicWidth() { return 24; }
        @Override public int getIntrinsicHeight() { return 24; }
    }
}
