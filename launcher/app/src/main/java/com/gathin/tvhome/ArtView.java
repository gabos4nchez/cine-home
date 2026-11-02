package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.View;

/**
 * The whole screen's background, under the text and the channel row. With a featured picture for the selected
 * channel it fills the right side and melts into the dark ground; without one, the channel's logo sits large on a
 * soft glow in its colour. Plain gradients, no blur.
 */
public final class ArtView extends View {
    private static final int BASE = 0xFF0A0A26;
    private static final int DEFAULT_COLOR = 0xFF2A4BE0;
    private static final float PICTURE_LEFT = .30f, LOGO_CX = .72f, LOGO_CY = .36f, LOGO_W = .30f;
    // A chosen picture is brought towards PICTURE_TARGET mean brightness, within these bounds; store screenshots,
    // already picked for being dark, always get STORE_DIM.
    private static final float PICTURE_TARGET = .32f, PICTURE_LIFT = 1.45f, STORE_DIM = .85f;

    private final Paint ground = new Paint();
    private final Paint glow = newPaint(), glowLow = newPaint();
    private final Paint scrim = newPaint(), scrimLow = newPaint(), scrimTop = newPaint();
    private final Paint picturePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private float gain = Float.NaN;
    private final Path logoClip = new Path();
    private final RectF logoBox = new RectF();
    private final Rect pictureSrc = new Rect();
    private final RectF pictureDst = new RectF();
    private int color = DEFAULT_COLOR;
    private boolean neutral = true;
    private Bitmap picture;
    private Drawable logo;

    public ArtView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    /**
     * How much to brighten or dim a picture: a dim app background needs a lift to read as the screen's main image, a
     * bright photo would glare behind the title. Measured on a tiny copy, off the main thread.
     */
    static float gainFor(Bitmap picture, boolean store) {
        if (store) return STORE_DIM;
        Bitmap tiny = Bitmap.createScaledBitmap(picture, 32, 18, true);
        float sum = 0;
        for (int y = 0; y < 18; y++) {
            for (int x = 0; x < 32; x++) {
                int p = tiny.getPixel(x, y);
                sum += .299f * Color.red(p) + .587f * Color.green(p) + .114f * Color.blue(p);
            }
        }
        if (tiny != picture) tiny.recycle();
        float mean = sum / (32 * 18 * 255f);
        return Math.max(STORE_DIM, Math.min(PICTURE_LIFT, PICTURE_TARGET / Math.max(.01f, mean)));
    }

    private void setGain(float k) {
        if (k == gain) return;
        gain = k;
        picturePaint.setColorFilter(new ColorMatrixColorFilter(new ColorMatrix(new float[] {
                k, 0, 0, 0, 0,
                0, k, 0, 0, 0,
                0, 0, k, 0, 0,
                0, 0, 0, 1, 0})));
    }

    private static Paint newPaint() {
        Paint paint = new Paint();
        paint.setDither(true);     // Long dark gradients band on an 8-bit panel without it.
        return paint;
    }

    /** A channel with no colour of its own (0) gets a dim slate glow, to sit under its silver accent. */
    void show(int dominant, Bitmap newPicture, Drawable newLogo) {
        show(dominant, newPicture, newLogo, 1f);
    }

    /** A picture is shaded at the top too, where a screenshot's own captions would meet the clock. */
    void show(int dominant, Bitmap newPicture, Drawable newLogo, float pictureGain) {
        setGain(pictureGain);
        neutral = dominant == 0;
        color = neutral ? DEFAULT_COLOR : dominant;
        picture = newPicture;
        logo = newLogo;
        build();
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        build();
    }

    private void build() {
        int w = getWidth(), h = getHeight();
        if (w == 0) return;
        ground.setColor(BASE);
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        float k = Math.max(.55f, Math.min(1f, hsv[2] / .9f)) * (neutral ? .65f : 1f);
        float s = neutral ? .40f : .85f;
        int main = Color.HSVToColor(new float[] {hsv[0], s, .72f * k});
        int low = Color.HSVToColor(new float[] {(hsv[0] + 335) % 360, s, .50f * k});
        glow.setShader(new RadialGradient(LOGO_CX * w, LOGO_CY * h, .46f * w, main, main & 0x00FFFFFF, Shader.TileMode.CLAMP));
        glowLow.setShader(new RadialGradient(.96f * w, h, .40f * w, low, low & 0x00FFFFFF, Shader.TileMode.CLAMP));
        int rgb = BASE & 0x00FFFFFF;
        scrim.setShader(new LinearGradient(0, 0, w, 0,
                new int[] {BASE, BASE, rgb | 0xB3000000, rgb},
                new float[] {0f, PICTURE_LEFT, .44f, .66f}, Shader.TileMode.CLAMP));
        // The row of channels sits on the picture itself: only a shade under it, so it stays one surface.
        scrimLow.setShader(new LinearGradient(0, h, 0, h * .58f, rgb | 0xB3000000, rgb, Shader.TileMode.CLAMP));
        scrimTop.setShader(new LinearGradient(0, 0, 0, h * .30f, rgb | 0xE6000000, rgb, Shader.TileMode.CLAMP));
        if (picture != null) {
            // Centre-crop to the area right of PICTURE_LEFT.
            pictureDst.set(PICTURE_LEFT * w, 0, w, h);
            float scale = Math.max(pictureDst.width() / picture.getWidth(), pictureDst.height() / picture.getHeight());
            int srcW = Math.round(pictureDst.width() / scale), srcH = Math.round(pictureDst.height() / scale);
            int x = (picture.getWidth() - srcW) / 2, y = (picture.getHeight() - srcH) / 2;
            pictureSrc.set(x, y, x + srcW, y + srcH);
        } else if (logo != null) {
            int iw = logo.getIntrinsicWidth(), ih = logo.getIntrinsicHeight();
            float aspect = iw > 0 && ih > 0 ? (float) iw / ih : 16f / 9f;
            float bw = LOGO_W * w * (aspect < 1.3f ? .5f : 1f), bh = bw / aspect;
            logoBox.set(LOGO_CX * w - bw / 2, LOGO_CY * h - bh / 2, LOGO_CX * w + bw / 2, LOGO_CY * h + bh / 2);
            logoClip.reset();
            float radius = .012f * w;
            logoClip.addRoundRect(logoBox, radius, radius, Path.Direction.CW);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        canvas.drawRect(0, 0, w, h, ground);
        if (picture != null) {
            canvas.drawBitmap(picture, pictureSrc, pictureDst, picturePaint);
            canvas.drawRect(0, 0, w, h, scrim);
            canvas.drawRect(0, 0, w, h, scrimLow);
            canvas.drawRect(0, 0, w, h * .30f, scrimTop);
            return;
        }
        canvas.drawRect(0, 0, w, h, glow);
        canvas.drawRect(0, 0, w, h, glowLow);
        canvas.drawRect(0, 0, w, h, scrim);
        if (logo != null) {
            int save = canvas.save();
            canvas.clipPath(logoClip);
            logo.setBounds((int) logoBox.left, (int) logoBox.top, (int) logoBox.right, (int) logoBox.bottom);
            logo.draw(canvas);
            canvas.restoreToCount(save);
        }
    }
}
