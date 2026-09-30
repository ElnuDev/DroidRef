package com.xiaopo.flying.sticker;

import android.graphics.*;

import androidx.annotation.IntRange;
import androidx.annotation.NonNull;

/**
 * An image on the board. Holds no pixels itself: {@link ImageCache} supplies
 * them at the resolution the image is shown at.
 *
 * @author wupanjie
 */
public class DrawableSticker extends Sticker {
    private static final ColorMatrixColorFilter GRAYSCALE_FILTER;

    static {
        ColorMatrix matrix = new ColorMatrix();
        matrix.setSaturation(0f);
        GRAYSCALE_FILTER = new ColorMatrixColorFilter(matrix);
    }

    // Key of the original image data in BlobStore.
    @NonNull
    private String blobKey;
    private int width;
    private int height;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final RectF dst = new RectF();

    /**
     * An image placed at {@code width} x {@code height}, the size its crop is
     * measured in (see {@link ImageLoader#canonicalSize}).
     */
    public DrawableSticker(@NonNull String blobKey, int width, int height) {
        this.blobKey = blobKey;
        this.width = width;
        this.height = height;
        this.realBounds = new Rect(0, 0, width, height);
        this.croppedBounds = new RectF(this.realBounds);
    }

    private DrawableSticker(DrawableSticker other, boolean sameIdentity) {
        super(other);
        adoptIdentity(other, sameIdentity);
        this.blobKey = other.blobKey;
        this.width = other.width;
        this.height = other.height;
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

    /** The visible (cropped) pixels at full resolution, unflipped. Decodes, so call off the main thread when possible. */
    @NonNull
    public Bitmap getCroppedBitmap() {
        Bitmap bitmap = ImageCache.INSTANCE.loadFull(blobKey, width, height);
        if (!isCropped()) {
            return bitmap;
        }
        int left = Math.max(0, (int) croppedBounds.left);
        int top = Math.max(0, (int) croppedBounds.top);
        int width = Math.min(bitmap.getWidth() - left, Math.max(1, (int) croppedBounds.width()));
        int height = Math.min(bitmap.getHeight() - top, Math.max(1, (int) croppedBounds.height()));
        Bitmap cropped = Bitmap.createBitmap(bitmap, left, top, width, height);
        if (cropped != bitmap) {
            bitmap.recycle();
        }
        return cropped;
    }

    public void cropDestructively() {
        if (!isCropped()) {
            return;
        }

        Bitmap cropped = getCroppedBitmap();
        // Flips and rotation live in the matrix, so shifting the local origin to
        // the crop corner keeps the image exactly where it was.
        getMatrix().preTranslate(croppedBounds.left - realBounds.left,
                croppedBounds.top - realBounds.top);
        String key = BlobStore.INSTANCE.putBitmap(cropped);
        ImageCache.INSTANCE.prepare(key, null);
        setImage(key, cropped.getWidth(), cropped.getHeight());
        cropped.recycle();
        this.recalcFinalMatrix();
    }

    /** Replaces the image, keeping the item's place, size and properties. */
    public void replaceImage(@NonNull String key, int width, int height) {
        RectF before = getWorldBounds();
        setImage(key, width, height);
        RectF after = getWorldBounds();
        float scale = Math.min(before.width() / Math.max(1f, after.width()),
                before.height() / Math.max(1f, after.height()));
        getMatrix().postScale(scale, scale, after.centerX(), after.centerY());
        getMatrix().postTranslate(before.centerX() - after.centerX(), before.centerY() - after.centerY());
        recalcFinalMatrix();
    }

    private void setImage(@NonNull String key, int width, int height) {
        this.blobKey = key;
        this.width = width;
        this.height = height;
        this.realBounds = new Rect(0, 0, width, height);
        this.croppedBounds = new RectF(this.realBounds);
    }

    /** Key of the original image data in {@link BlobStore}. */
    @NonNull
    public String getBlobKey() {
        return blobKey;
    }

    /** On-screen width of the whole (uncropped) image under the current matrices. */
    public float getOnScreenWidth() {
        return getMatrixScale(getFinalMatrix()) * width;
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        canvas.save();
        canvas.concat(getFinalMatrix());
        canvas.clipRect(croppedBounds);
        paint.setAlpha(getOpacity());
        paint.setColorFilter(isGrayscale() ? GRAYSCALE_FILTER : null);
        paint.setFilterBitmap(isSmooth());
        dst.set(realBounds);
        ImageCache.INSTANCE.draw(canvas, blobKey, getOnScreenWidth(), dst, paint);
        canvas.restore();
    }

    /** Starts loading the pixels drawing at the current scale will need. */
    public void prefetch() {
        ImageCache.INSTANCE.prefetch(blobKey, getOnScreenWidth());
    }

    @NonNull
    @Override
    public DrawableSticker setAlpha(@IntRange(from = 0, to = 255) int alpha) {
        setOpacity(alpha);
        return this;
    }

    @Override
    public int getWidth() {
        return width;
    }

    @Override
    public int getHeight() {
        return height;
    }

    @NonNull
    @Override
    public String stateSignature() {
        return super.stateSignature() + '|' + blobKey;
    }
}
