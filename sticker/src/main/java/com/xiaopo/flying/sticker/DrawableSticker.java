package com.xiaopo.flying.sticker;

import android.content.res.Resources;
import android.graphics.*;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

import androidx.annotation.IntRange;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * @author wupanjie
 */
public class DrawableSticker extends Sticker {
    private static final ColorMatrixColorFilter GRAYSCALE_FILTER;

    static {
        ColorMatrix matrix = new ColorMatrix();
        matrix.setSaturation(0f);
        GRAYSCALE_FILTER = new ColorMatrixColorFilter(matrix);
    }

    private Drawable drawable;
    // Key of the original image data in BlobStore; created lazily for icons etc.
    @Nullable
    private String blobKey;

    public DrawableSticker(Drawable drawable) {
        this(drawable, null);
    }

    public DrawableSticker(Drawable drawable, @Nullable String blobKey) {
        this.drawable = drawable;
        this.realBounds = new Rect(0, 0, getWidth(), getHeight());
        this.croppedBounds = new RectF(this.realBounds);
        this.blobKey = blobKey;
    }

    public DrawableSticker(DrawableSticker other) {
        this(other, false);
    }

    private DrawableSticker(DrawableSticker other, boolean sameIdentity) {
        super(other);
        adoptIdentity(other, sameIdentity);
        Drawable.ConstantState state = other.drawable.getConstantState();
        this.drawable = state != null ? state.newDrawable().mutate() : other.drawable;
        this.blobKey = other.blobKey;
    }

    @NonNull
    @Override
    public DrawableSticker copy(boolean sameIdentity) {
        return new DrawableSticker(this, sameIdentity);
    }

    public boolean isCropped() {
        return (croppedBounds.left != realBounds.left
                || croppedBounds.top != realBounds.top
                || croppedBounds.right != realBounds.right
                || croppedBounds.bottom != realBounds.bottom);
    }

    @Nullable
    public Bitmap getBitmap() {
        if (drawable instanceof BitmapDrawable) {
            return ((BitmapDrawable) drawable).getBitmap();
        }
        return null;
    }

    /** The visible (cropped) pixels, unflipped. */
    @NonNull
    public Bitmap getCroppedBitmap() {
        Bitmap bitmap = StickerViewSerializer.Companion.drawableToBitmap(this.drawable);
        if (!isCropped()) {
            return bitmap;
        }
        int left = Math.max(0, (int) croppedBounds.left);
        int top = Math.max(0, (int) croppedBounds.top);
        int width = Math.min(bitmap.getWidth() - left, Math.max(1, (int) croppedBounds.width()));
        int height = Math.min(bitmap.getHeight() - top, Math.max(1, (int) croppedBounds.height()));
        return Bitmap.createBitmap(bitmap, left, top, width, height);
    }

    public void cropDestructively(Resources resources) {
        if (!isCropped()) {
            return;
        }

        Bitmap cropped = getCroppedBitmap();
        // Flips and rotation live in the matrix, so shifting the local origin to
        // the crop corner keeps the image exactly where it was.
        getMatrix().preTranslate(croppedBounds.left - realBounds.left,
                croppedBounds.top - realBounds.top);
        this.drawable = new BitmapDrawable(resources, cropped);
        this.realBounds = new Rect(0, 0, getWidth(), getHeight());
        this.croppedBounds = new RectF(this.realBounds);
        this.recalcFinalMatrix();
        this.blobKey = BlobStore.INSTANCE.putBitmap(cropped);
    }

    /** Replaces the image, keeping the item's place, size and properties. */
    public void replaceDrawable(@NonNull Drawable replacement, @Nullable String key) {
        RectF before = getWorldBounds();
        this.drawable = replacement;
        this.realBounds = new Rect(0, 0, getWidth(), getHeight());
        this.croppedBounds = new RectF(this.realBounds);
        this.blobKey = key;
        RectF after = getWorldBounds();
        float scale = Math.min(before.width() / Math.max(1f, after.width()),
                before.height() / Math.max(1f, after.height()));
        getMatrix().postScale(scale, scale, after.centerX(), after.centerY());
        getMatrix().postTranslate(before.centerX() - after.centerX(), before.centerY() - after.centerY());
        recalcFinalMatrix();
    }

    /**
     * Key of the original image data in {@link BlobStore}, encoding the current
     * pixels as PNG if the image did not come from a file.
     */
    @NonNull
    public String getBlobKey() {
        if (blobKey == null) {
            blobKey = BlobStore.INSTANCE.putBitmap(
                    StickerViewSerializer.Companion.drawableToBitmap(this.drawable));
        }
        return blobKey;
    }

    @NonNull
    @Override
    public Drawable getDrawable() {
        return drawable;
    }

    @Override
    public DrawableSticker setDrawable(@NonNull Drawable drawable) {
        this.drawable = drawable;
        return this;
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        canvas.save();
        canvas.concat(getFinalMatrix());
        canvas.clipRect(croppedBounds);
        drawable.setBounds(realBounds);
        drawable.setAlpha(getOpacity());
        drawable.setColorFilter(isGrayscale() ? GRAYSCALE_FILTER : null);
        drawable.setFilterBitmap(isSmooth());
        drawable.draw(canvas);
        canvas.restore();
    }

    @NonNull
    @Override
    public DrawableSticker setAlpha(@IntRange(from = 0, to = 255) int alpha) {
        drawable.setAlpha(alpha);
        return this;
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
    public String stateSignature() {
        return super.stateSignature() + '|' + System.identityHashCode(getBitmap()) + blobKey;
    }
}
