package com.liskovsoft.smartyoutubetv2.karaokemic;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * Mic input meter (peak with falloff, log scale). Turns red close to clipping.
 */
public class LevelMeterView extends View {
    private static final float MIN_DB = -50;
    private static final float FALLOFF = 0.88f;
    private final Paint mTrackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mLevelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mRect = new RectF();
    private final int mNormalColor;
    private final int mHotColor;
    private float mDisplayed;

    @SuppressWarnings("deprecation") // Context.getColor() requires API 23
    public LevelMeterView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mTrackPaint.setColor(getResources().getColor(R.color.meter_track));
        mNormalColor = getResources().getColor(R.color.live);
        mHotColor = getResources().getColor(R.color.accent);
    }

    /**
     * @param peak 0..1
     */
    public void setLevel(float peak) {
        float position = peak <= 0 ? 0 : Math.max(0, 1 - (float) (20 * Math.log10(peak)) / MIN_DB);
        mDisplayed = Math.max(position, mDisplayed * FALLOFF);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float radius = getHeight() / 2f;
        mRect.set(0, 0, getWidth(), getHeight());
        canvas.drawRoundRect(mRect, radius, radius, mTrackPaint);

        if (mDisplayed > 0.01f) {
            mLevelPaint.setColor(mDisplayed > 0.94f ? mHotColor : mNormalColor);
            mRect.set(0, 0, Math.max(getHeight(), getWidth() * mDisplayed), getHeight());
            canvas.drawRoundRect(mRect, radius, radius, mLevelPaint);
        }
    }
}
