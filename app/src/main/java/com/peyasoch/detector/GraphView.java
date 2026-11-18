package com.peyasoch.detector;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

import java.util.ArrayDeque;

public class GraphView extends View {
    private double[][] pts = new double[0][];
    private double base, thr;
    private long now;
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG), grid = new Paint(), dash = new Paint(Paint.ANTI_ALIAS_FLAG), txt = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    public GraphView(Context c) {
        super(c);
        float d = c.getResources().getDisplayMetrics().density;
        line.setColor(0xFFFF4D5E); line.setStrokeWidth(1.8f * d); line.setStyle(Paint.Style.STROKE);
        grid.setColor(0xFF2A1418); grid.setStrokeWidth(d);
        dash.setColor(0x66FFB020); dash.setStrokeWidth(d); dash.setStyle(Paint.Style.STROKE);
        dash.setPathEffect(new DashPathEffect(new float[]{4 * d, 4 * d}, 0));
        txt.setColor(0xFF9A8487); txt.setTextSize(10 * d);
    }

    public void update(ArrayDeque<double[]> buf, double base, long now, double thr) {
        this.pts = buf.toArray(new double[0][]);
        this.base = base; this.now = now; this.thr = thr;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        c.drawLine(0, h - 1, w, h - 1, grid);
        if (pts.length == 0 || Double.isNaN(base)) return;
        double mx = 10;
        for (double[] p : pts) mx = Math.max(mx, Math.abs(p[1] - base));
        mx *= 1.15;
        float ty = (float) (h - thr / mx * h);
        Path dp = new Path(); dp.moveTo(0, ty); dp.lineTo(w, ty);
        c.drawPath(dp, dash);
        c.drawText(String.format(java.util.Locale.US, "%.0f µT", mx), 4, txt.getTextSize() + 2, txt);
        path.reset();
        boolean first = true;
        for (double[] p : pts) {
            float x = (float) (w - (now - p[0]) / 30000.0 * w);
            float y = (float) (h - Math.abs(p[1] - base) / mx * h);
            if (first) { path.moveTo(x, y); first = false; } else path.lineTo(x, y);
        }
        c.drawPath(path, line);
    }
}
