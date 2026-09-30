package com.xiaopo.flying.sticker;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.MotionEvent;

import androidx.annotation.DrawableRes;
import androidx.annotation.IntDef;
import androidx.annotation.IntRange;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * @author wupanjie
 */
public class BitmapStickerIcon extends Sticker implements StickerIconEvent {
    public static final float DEFAULT_ICON_RADIUS = 35f;
    public static final float DEFAULT_ICON_EXTRA_RADIUS = 10f;

    @IntDef({LEFT_TOP, RIGHT_TOP, LEFT_BOTTOM, RIGHT_BOTTOM})
    @Retention(RetentionPolicy.SOURCE)
    public @interface Gravity {

    }

    public static final int LEFT_TOP = 0;
    public static final int RIGHT_TOP = 1;
    public static final int LEFT_BOTTOM = 2;
    public static final int RIGHT_BOTTOM = 3;

    private float iconRadius = DEFAULT_ICON_RADIUS;
    private float iconExtraRadius = DEFAULT_ICON_EXTRA_RADIUS;
    private float x;
    private float y;
    @Gravity
    private int position;

    private String iconResName;

    private StickerIconEvent iconEvent;

    private final Drawable drawable;

    public BitmapStickerIcon(Drawable drawable, @Gravity int gravity) {
        this.drawable = drawable;
        this.realBounds = new Rect(0, 0, getWidth(), getHeight());
        this.croppedBounds = new RectF(this.realBounds);
        this.position = gravity;
    }

    public BitmapStickerIcon(Context context, @DrawableRes int drawableRes, @Gravity int gravity) {
        this(ContextCompat.getDrawable(context, drawableRes), gravity);
        this.iconResName = context.getResources().getResourceEntryName(drawableRes);
    }

    public void draw(Canvas canvas, Paint paint) {
        canvas.save();
        canvas.concat(getCanvasMatrix());
        canvas.drawCircle(x, y, iconRadius, paint);
        canvas.restore();
        draw(canvas);
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        canvas.save();
        canvas.concat(getFinalMatrix());
        drawable.setBounds(realBounds);
        drawable.setAlpha(getOpacity());
        drawable.draw(canvas);
        canvas.restore();
    }

    /** Icons are never copied: they belong to the view, not the board. */
    @NonNull
    @Override
    public Sticker copy(boolean sameIdentity) {
        throw new UnsupportedOperationException("Icons can't be copied");
    }

    @Override
    public int getWidth() {
        return drawable.getIntrinsicWidth();
    }

    @Override
    public int getHeight() {
        return drawable.getIntrinsicHeight();
    }

    @NonNull
    @Override
    public BitmapStickerIcon setAlpha(@IntRange(from = 0, to = 255) int alpha) {
        drawable.setAlpha(alpha);
        return this;
    }

    public float getX() {
        return x;
    }

    public void setX(float x) {
        this.x = x;
    }

    public float getY() {
        return y;
    }

    public void setY(float y) {
        this.y = y;
    }

    public PointF getMappedPos() {
        float[] pt = {x, y};
        getCanvasMatrix().mapPoints(pt);
        return new PointF(pt[0], pt[1]);
    }

    public float getIconRadius() {
        return iconRadius;
    }

    public void setIconRadius(float iconRadius) {
        this.iconRadius = iconRadius;
    }

    public float getIconExtraRadius() {
        return iconExtraRadius;
    }

    public void setIconExtraRadius(float iconExtraRadius) {
        this.iconExtraRadius = iconExtraRadius;
    }

    @Override
    public void onActionDown(StickerView stickerView, StickerViewModel viewModel, MotionEvent event) {
        if (iconEvent != null) {
            iconEvent.onActionDown(stickerView, viewModel, event);
        }
    }

    @Override
    public void onActionMove(StickerView stickerView, StickerViewModel viewModel, MotionEvent event) {
        if (iconEvent != null) {
            iconEvent.onActionMove(stickerView, viewModel, event);
        }
    }

    @Override
    public void onActionUp(StickerView stickerView, StickerViewModel viewModel, MotionEvent event) {
        if (iconEvent != null) {
            iconEvent.onActionUp(stickerView, viewModel, event);
        }
    }

    @Override
    public void onActionLongPress(StickerView stickerView, StickerViewModel viewModel, MotionEvent event) {
        if (iconEvent != null) {
            iconEvent.onActionLongPress(stickerView, viewModel, event);
        }
    }

    public StickerIconEvent getIconEvent() {
        return iconEvent;
    }

    public void setIconEvent(StickerIconEvent iconEvent) {
        this.iconEvent = iconEvent;
    }

    @Gravity
    public int getPosition() {
        return position;
    }

    public void setPosition(@Gravity int position) {
        this.position = position;
    }

    public String getDrawableName() {
        return iconResName;
    }
}
