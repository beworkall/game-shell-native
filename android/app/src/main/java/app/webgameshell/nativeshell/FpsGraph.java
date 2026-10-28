package app.webgameshell.nativeshell;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** Tiny FPS history graph for the in-game HUD (one sample every half second). */
final class FpsGraph extends View {
    private final float[] samples = new float[90];
    private int count, head;
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG), grid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    FpsGraph(Context c, int accent) {
        super(c);
        line.setColor(accent); line.setStyle(Paint.Style.STROKE); line.setStrokeWidth(3f);
        grid.setColor(0x44FFFFFF); grid.setStrokeWidth(1f);
        setBackgroundColor(0x88000000);
    }

    void add(float fps) {
        samples[head] = Math.max(0, fps);
        head = (head + 1) % samples.length;
        count = Math.min(samples.length, count + 1);
        invalidate();
    }

    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), max = 60f;
        for (int i = 0; i < count; i++) max = Math.max(max, samples[i]);
        float y30 = h - h * 30f / max;
        c.drawLine(0, y30, w, y30, grid);
        if (count < 2) return;
        path.reset();
        for (int i = 0; i < count; i++) {
            float v = samples[(head - count + i + samples.length) % samples.length];
            float x = w * i / (samples.length - 1), y = h - h * v / max;
            if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        c.drawPath(path, line);
    }
}
