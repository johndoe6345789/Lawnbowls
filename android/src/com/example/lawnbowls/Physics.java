package com.example.lawnbowls;

import java.util.ArrayList;
import java.util.List;

/**
 * Green geometry and ball physics.
 *
 * The rink is drawn compressed sideways so it fits a phone: 1 metre of length is {@link #M} pixels,
 * and the 5 m wide rink is 280 px wide. All rule distances along the rink (23 m, 14 m, 2 m ...)
 * use {@link #M}. Ball-to-ball contact is plain circle physics in pixel space.
 */
final class Physics {
    private Physics() {}

    // ---- field geometry (pixels) ------------------------------------------------------------
    static final int W = 460, H = 780;
    static final double M = 18.5;                     // pixels per metre along the rink
    static final double RX0 = 90, RX1 = 370, CX = 230; // side boundaries / centre line
    static final double RY0 = 70;                     // front ditch edge (end of the green)
    static final double DITCH = 30;                   // ditch depth
    static final double MAT_Y = RY0 + 34 * M;         // mat line (edge nearest the front ditch)
    static final double RY1 = RY0 + 36 * M;           // rear ditch edge

    static final double BOWL_R = 10, JACK_R = 5.3;
    static final double DT = 1.0 / 60.0, RESTITUTION = 0.85;
    static final double MIN_V = 110, MAX_V = 640;
    static final int MAX_STEPS = 1000;

    // ---- green speed -------------------------------------------------------------------------
    static double decel = 200.0;   // rolling resistance (px/s^2)
    static double biasK = 0.95;    // how strongly bowls curve

    static void setGreen(int g) {  // 0 slow, 1 medium, 2 fast
        decel = new double[]{250, 200, 160}[g];
        biasK = new double[]{0.80, 0.95, 1.10}[g];
    }

    interface Listener {
        void onContact(double speed, boolean withJack);
        void onDitch(boolean jack);
    }

    /** Creates a ball at the front edge of the mat, on the centre line, moving along {@code theta}. */
    static Ball release(int owner, int side, double theta, double v, boolean jack) {
        Ball b = new Ball();
        b.x = CX; b.y = MAT_Y;
        b.vx = v * Math.sin(theta); b.vy = -v * Math.cos(theta);
        b.r = jack ? JACK_R : BOWL_R;
        b.m = jack ? 0.5 : 1.0;
        b.owner = jack ? -1 : owner;
        b.jack = jack;
        b.side = jack ? 0 : side;
        b.mark = b.side;
        b.original = true;
        return b;
    }

    static boolean step(List<Ball> balls, double dt, Listener ls) {
        boolean moving = false;
        for (int i = 0; i < balls.size(); i++) {
            Ball b = balls.get(i);
            if (!b.alive || (b.vx == 0 && b.vy == 0)) continue;
            double sp0 = Math.hypot(b.vx, b.vy);
            double sp = sp0 - decel * dt;
            if (sp <= 4) { b.vx = 0; b.vy = 0; continue; }
            if (b.side != 0) {
                double th = Math.atan2(b.vx, -b.vy) + b.side * biasK / (sp / 100.0 + 0.6) * dt;
                b.vx = sp * Math.sin(th);
                b.vy = -sp * Math.cos(th);
            } else {
                double k = sp / sp0;
                b.vx *= k; b.vy *= k;
            }
            b.x += b.vx * dt;
            b.y += b.vy * dt;
            moving = true;
            if (b.y < RY0) {   // over the edge of the green: drops into the ditch and stops
                b.y = Math.max(b.y, RY0 - DITCH + b.r);
                b.vx = 0; b.vy = 0;
                if (ls != null) ls.onDitch(b.jack);
            }
            if (b.x < 8 || b.x > W - 8 || b.y > RY1 + 24) {   // well off the playing area
                b.alive = false;
                b.vx = 0; b.vy = 0;
            }
        }

        int n = balls.size();
        for (int i = 0; i < n; i++) {
            Ball a = balls.get(i);
            if (!a.alive) continue;
            for (int j = i + 1; j < n; j++) {
                Ball c = balls.get(j);
                if (!c.alive) continue;
                boolean am = a.moving(), cm = c.moving();
                if (!am && !cm) continue;
                double dx = c.x - a.x, dy = c.y - a.y, rr = a.r + c.r;
                if (dx * dx + dy * dy >= rr * rr) continue;
                double d = Math.hypot(dx, dy), nx, ny;
                if (d < 1e-6) { nx = 0; ny = -1; d = 1e-6; } else { nx = dx / d; ny = dy / d; }
                double overlap = rr - d, tot = a.m + c.m;
                a.x -= nx * overlap * c.m / tot; a.y -= ny * overlap * c.m / tot;
                c.x += nx * overlap * a.m / tot; c.y += ny * overlap * a.m / tot;
                double rel = (c.vx - a.vx) * nx + (c.vy - a.vy) * ny;
                if (rel < 0) {
                    double imp = -(1 + RESTITUTION) * rel / (1 / a.m + 1 / c.m);
                    a.vx -= imp * nx / a.m; a.vy -= imp * ny / a.m;
                    c.vx += imp * nx / c.m; c.vy += imp * ny / c.m;
                    if (ls != null) ls.onContact(-rel, a.jack || c.jack);
                }
                a.side = 0; c.side = 0;   // a collision knocks the bias out of a bowl

                // Law 14: a bowl that touches the jack in its original course is a toucher,
                // unless the jack is in the ditch (Law 14.3). A bowl that was at rest when hit
                // is no longer in an original course.
                if (!am) a.original = false;
                if (!cm) c.original = false;
                if (a.jack && !c.jack && cm && c.original && a.y >= RY0) c.toucher = true;
                if (c.jack && !a.jack && am && a.original && c.y >= RY0) a.toucher = true;
                moving = true;
            }
        }
        return moving;
    }

    /**
     * Rolls {@code nb} through a copy of {@code balls} until everything is at rest. The returned
     * world holds copies; the delivered ball is its last element. Silent (no listener).
     */
    static ArrayList<Ball> simulate(List<Ball> balls, Ball nb, ArrayList<float[]> path) {
        ArrayList<Ball> w = new ArrayList<Ball>();
        for (Ball b : balls) if (b.alive) w.add(b.copy());
        Ball d = nb.copy();
        w.add(d);
        if (path != null) { path.clear(); path.add(new float[]{(float) d.x, (float) d.y}); }
        for (int i = 0; i < MAX_STEPS; i++) {
            boolean moving = step(w, DT, null);
            if (path != null && i % 3 == 0) path.add(new float[]{(float) d.x, (float) d.y});
            if (!moving || (path != null && !d.alive)) break;
        }
        if (path != null) path.add(new float[]{(float) d.x, (float) d.y});
        return w;
    }
}
