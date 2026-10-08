package com.example.lawnbowls;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** The computer opponent: chooses deliveries by trying them out in simulation. */
final class Ai {
    private Ai() {}

    static final class Shot {
        int side;
        double th, v;
        Shot(int side, double th, double v) { this.side = side; this.th = th; this.v = v; }
    }

    static double clampV(double v) { return Math.max(Physics.MIN_V, Math.min(Physics.MAX_V, v)); }

    /** A jack delivery aimed at 24-30 m. Skill noise can make it improper. */
    static Shot jackShot(Random rng, double skillTh, double skillV) {
        return jackShot(rng, skillTh, skillV, Ego.DEFAULT);
    }

    /** As above, at the length this personality likes to roll. */
    static Shot jackShot(Random rng, double skillTh, double skillV, Ego ego) {
        double metres = ego.jackMin + rng.nextDouble() * (ego.jackMax - ego.jackMin);
        double v = Math.sqrt(2 * Physics.decel * metres * Physics.M) * (1 + rng.nextGaussian() * skillV * 0.5);
        return new Shot(0, rng.nextGaussian() * skillTh * 0.5, clampV(v));
    }

    /** Value of a finished delivery from the point of view of {@code me}. */
    static double evaluate(List<Ball> world, Ball delivered, int me) {
        return evaluate(world, delivered, me, 800);
    }

    static double evaluate(List<Ball> world, Ball delivered, int me, double wastePenalty) {
        Rules.Settle s = Rules.settleBowl(world, delivered);
        if (s.jackDead) return -3000;
        Rules.EndResult r = Rules.score(world);
        double val = r.owner < 0 ? 0 : (r.owner == me ? 1000.0 * r.shots : -1000.0 * r.shots);
        double mine = Math.min(r.gap[me], 400), theirs = Math.min(r.gap[1 - me], 400);
        // Throwing a bowl away (ditch, off the green) costs a bowl, so only do it for a good reason.
        double waste = delivered.alive ? 0 : wastePenalty;
        return val - mine + 0.3 * theirs - waste;
    }

    /** Weight above which a delivery counts as a drive; drives are risky and cost a penalty. */
    private static double driveSpeed, drivePenalty = 700, wastePenalty = 800, sideBonus;

    private static double score(List<Ball> balls, int me, int side, double th, double v) {
        Ball nb = Physics.release(me, side, th, clampV(v), false);
        ArrayList<Ball> w = Physics.simulate(balls, nb, null);
        double val = evaluate(w, w.get(w.size() - 1), me, wastePenalty) + side * sideBonus;
        return v > driveSpeed ? val - drivePenalty : val;   // a drive must win back more than it risks
    }

    /** The best delivery found: a coarse sweep of line, weight and hand, then a fine search. */
    static Shot bowlShot(List<Ball> balls, int me) {
        return bowlShot(balls, me, Ego.DEFAULT, false);
    }

    /**
     * Personality changes what a good delivery is worth: a drive-lover pays little or nothing for the
     * risk, a cautious player hates throwing a bowl away, and {@code showOff} forces a trick drive.
     */
    static Shot bowlShot(List<Ball> balls, int me, Ego ego, boolean showOff) {
        drivePenalty = showOff ? 0 : 700 * (1 - 1.6 * ego.aggression);
        wastePenalty = showOff ? 150 : 800 * (0.4 + 1.2 * ego.caution) / 1.0;
        sideBonus = ego.sidePref * 45;
        Ball jack = Rules.jack(balls);
        double dj = Math.hypot(jack.x - Physics.CX, jack.y - Physics.MAT_Y);
        double[] fs = {0.9, 0.97, 1.03, 1.1, 1.2};
        double[] speeds = new double[fs.length + 2];
        for (int i = 0; i < fs.length; i++) speeds[i] = Math.sqrt(2 * Physics.decel * dj * fs[i]);
        speeds[fs.length] = 480;       // drives
        speeds[fs.length + 1] = 600;
        driveSpeed = Math.sqrt(2 * Physics.decel * dj * 1.45);
        if (showOff) {                 // only firm deliveries
            speeds = new double[]{Math.max(driveSpeed * 1.05, 440), 520, 600};
        }

        double bestVal = -1e18;
        Shot best = new Shot(-1, 0, speeds[2]);
        for (int side = -1; side <= 1; side += 2) {
            for (int ai = -7; ai <= 7; ai++) {
                for (double v : speeds) {
                    double th = ai * 0.05, val = score(balls, me, side, th, v);
                    if (val > bestVal) { bestVal = val; best = new Shot(side, th, v); }
                }
            }
        }
        Shot c = best;
        for (int dth = -4; dth <= 4; dth++) {
            for (int dv = -3; dv <= 3; dv++) {
                double th = c.th + dth * 0.01, v = c.v * (1 + dv * 0.02);
                double val = score(balls, me, c.side, th, v);
                if (val > bestVal) { bestVal = val; best = new Shot(c.side, th, v); }
            }
        }
        return best;
    }
}
