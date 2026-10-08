package com.example.lawnbowls;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Rule checks from the World Bowls "Laws of the Sport of Bowls" (Crystal Mark, 4th edition),
 * singles play. Law numbers in comments refer to that edition.
 */
final class Rules {
    private Rules() {}

    static final double JACK_MIN_M = 23;   // Law 10.1.3: improperly delivered if nearer than this to the mat line
    static final double JACK_DEAD_M = 20;  // Law 19.1.3: a jack under this distance is dead
    static final double BOWL_DEAD_M = 14;  // Law 17.1: a bowl stopping nearer than this is dead
    static final double JACK_SHORT_M = 2;  // Law 9.2 / 10.3: jack placed 2 m from the front ditch

    /** Tolerance (px) for "touching" / "equidistant" when counting shots (Law 24.1). */
    static final double EPS = 0.75;

    static double metresFromMat(Ball b) { return (Physics.MAT_Y - b.y) / Physics.M; }

    static boolean outsideSides(Ball b) {
        return b.x < Physics.RX0 - b.r || b.x > Physics.RX1 + b.r;
    }

    static Ball jack(List<Ball> balls) {
        for (Ball b : balls) if (b.jack) return b;
        return null;
    }

    /** Why a freshly delivered jack is improper (Law 10.1), or null if it is good. */
    static String jackFault(Ball j) {
        if (!j.alive) return "left the green";
        if (j.y < Physics.RY0) return "ended in the ditch";
        if (outsideSides(j)) return "finished outside the rink";
        if (metresFromMat(j) < JACK_MIN_M) return String.format(Locale.UK, "stopped short at %.1f m (23 m needed)", metresFromMat(j));
        return null;
    }

    static final class Settle {
        boolean jackDead;
        String jackReason;
        final ArrayList<String> notes = new ArrayList<String>();
    }

    static final String[] poss = {"Your", "CPU's"};   // set by Game for the current match
    static String who(Ball b) { return poss[b.owner == 0 ? 0 : 1]; }

    /**
     * Applies the dead bowl / dead jack laws once everything has stopped after a delivery.
     * Dead balls get {@code alive = false}; the caller removes them.
     */
    static Settle settleBowl(List<Ball> balls, Ball delivered) {
        Settle s = new Settle();
        // Balls that left the playing area altogether while rolling.
        for (Ball b : balls) {
            if (!b.alive && !b.jack) s.notes.add(who(b) + " bowl left the green - dead bowl.");
        }
        for (Ball b : balls) {
            if (b.jack) {
                if (!b.alive) {
                    s.jackDead = true; s.jackReason = "The jack left the green";
                } else if (outsideSides(b)) {
                    b.alive = false;
                    s.jackDead = true; s.jackReason = "The jack finished outside the side boundary";
                } else if (b.y >= Physics.RY0 && metresFromMat(b) < JACK_DEAD_M) {   // Law 19.1.3
                    b.alive = false;
                    s.jackDead = true;
                    s.jackReason = String.format(Locale.UK, "The jack was driven back to %.1f m, under 20 m", metresFromMat(b));
                }
                // A jack resting in the front ditch inside the side boundaries stays live (Law 18.1).
                continue;
            }
            if (!b.alive) continue;
            String why = null;
            if (b.y < Physics.RY0 && !b.toucher) why = "finished in the ditch";                 // Law 17.1
            else if (outsideSides(b)) why = "stopped outside the side boundary";                 // Law 17.1
            else if (b == delivered && !b.toucher && metresFromMat(b) < BOWL_DEAD_M)
                why = String.format(Locale.UK, "stopped short at %.1f m (14 m needed)", metresFromMat(b));
            if (why != null) {
                b.alive = false;
                s.notes.add(who(b) + " bowl " + why + " - dead bowl (Law 17.1).");
            }
        }
        for (Ball b : balls) b.original = false;   // everything is at rest again
        return s;
    }

    static final class EndResult {
        int owner = -1;                 // who scores, -1 = nobody
        int shots;
        boolean tie;                    // no shot (Law 24.1)
        String note = "";
        final double[] gap = {1e9, 1e9};        // nearest-point gap to the jack, per owner (px)
        final Ball[] best = new Ball[2];
        final ArrayList<Ball> shotBowls = new ArrayList<Ball>();
    }

    static double gap(Ball jack, Ball b) {
        return Math.hypot(b.x - jack.x, b.y - jack.y) - jack.r - b.r;   // Law 23.4: nearest points
    }

    /** Counts the shots (Laws 22 and 24). Only live bowls should be in {@code balls}. */
    static EndResult score(List<Ball> balls) {
        EndResult r = new EndResult();
        final Ball j = jack(balls);
        ArrayList<Ball> live = new ArrayList<Ball>();
        for (Ball b : balls) if (b.alive && !b.jack) live.add(b);
        if (j == null || live.isEmpty()) {
            r.tie = true;
            r.note = "No live bowls - no shot, end tied.";
            return r;
        }
        final Ball jj = j;
        Collections.sort(live, new Comparator<Ball>() {
            @Override public int compare(Ball a, Ball b) { return Double.compare(gap(jj, a), gap(jj, b)); }
        });
        for (Ball b : live) {
            if (r.best[b.owner] == null) { r.best[b.owner] = b; r.gap[b.owner] = gap(j, b); }
        }
        Ball first = live.get(0);
        int other = 1 - first.owner;
        double oppGap = r.gap[other];
        if (r.best[other] != null && Math.abs(r.gap[first.owner] - oppGap) <= EPS) {
            r.tie = true;
            r.note = "Nearest bowls are equidistant - no shot, end tied (Law 24.1).";
            return r;
        }
        r.owner = first.owner;
        for (Ball b : live) {
            if (b.owner == first.owner && (r.best[other] == null || gap(j, b) < oppGap - EPS)) {
                r.shots++;
                r.shotBowls.add(b);
            }
        }
        return r;
    }
}
