package com.example.lawnbowls;

import java.util.*;

public class Sim {
    static int jackImproper, jackCentred, deadEndsTotal, tiesTotal, touchers, deadBowlNotes, endsTotal, extraEnds;
    static final Map<String,Integer> reasons = new TreeMap<>();

    public static void main(String[] a) {
        long t0 = System.nanoTime();
        int matches = 0, p0 = 0;
        long maxAiNs = 0;
        for (int fmt = 0; fmt < 3; fmt++) {
            for (int skill = 0; skill < 3; skill++) {
                for (int green = 0; green < 3; green++) {
                    for (int rep = 0; rep < 2; rep++) {
                        Game g = new Game(new Random(1000 * fmt + 100 * skill + 10 * green + rep));
                        g.format = fmt; g.skill = skill; g.green = green; g.autoPlayer = true;
                        g.startMatch();
                        int guard = 0;
                        String lastMsg = "";
                        while (g.state != Game.ST_GAME_OVER) {
                            long s = System.nanoTime();
                            g.update(1.0 / 60);
                            long dt = System.nanoTime() - s;
                            if (dt > maxAiNs) maxAiNs = dt;
                            if (!g.msg.equals(lastMsg)) { track(g.msg); lastMsg = g.msg; }
                            if (g.state == Game.ST_END_OVER) g.tapContinue();
                            if (++guard > 3_000_000) throw new IllegalStateException("match did not finish: fmt=" + fmt + " state=" + g.state + " msg=" + g.msg);
                            for (Ball b : g.balls) {
                                if (Double.isNaN(b.x) || Double.isNaN(b.y)) throw new IllegalStateException("NaN");
                            }
                        }
                        matches++;
                        endsTotal += g.endsPlayed; deadEndsTotal += g.deadEnds;
                        if (g.winner == 0) p0++;
                        if (fmt == 2) {
                            if (g.endsPlayed < 1) throw new IllegalStateException("no ends");
                            if (g.score[0] == g.score[1]) throw new IllegalStateException("drawn final in ends format");
                            if (g.extraEnd) throw new IllegalStateException("extraEnd flag left on");
                            if (g.endsPlayed > Game.FIXED_ENDS) extraEnds++;
                        }
                        if (fmt == 0 && Math.max(g.score[0], g.score[1]) < 7) throw new IllegalStateException("bad 7 finish");
                        if (fmt == 1 && Math.max(g.score[0], g.score[1]) < 21) throw new IllegalStateException("bad 21 finish");
                    }
                }
            }
        }
        System.out.printf("matches=%d (player0 wins %d)  ends=%d  deadEnds=%d  extraEndMatches=%d%n", matches, p0, endsTotal, deadEndsTotal, extraEnds);
        System.out.printf("jackImproper=%d jackCentred=%d touchers=%d%n", jackImproper, jackCentred, touchers);
        for (Map.Entry<String,Integer> e : reasons.entrySet()) System.out.println("  " + e.getValue() + "x " + e.getKey());
        System.out.printf("total %.1fs, slowest single update %.0f ms%n", (System.nanoTime()-t0)/1e9, maxAiNs/1e6);

        // --- targeted rule tests ---
        test();
        System.out.println("rule tests passed");
    }

    static void track(String m) {
        if (m.contains("improper delivery")) jackImproper++;
        if (m.contains("Law 10.3")) jackCentred++;
        if (m.contains("toucher")) touchers++;
        for (String line : m.split("\n")) {
            if (line.contains("dead bowl")) {
                String k = line.replaceAll("[0-9]+\\.[0-9]", "N").replace("Your ", "").replace("CPU's ", "");
                reasons.merge(k, 1, Integer::sum);
            }
        }
        if (m.contains("tied")) tiesTotal++;
    }

    static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); }

    static Ball bowl(int owner, double x, double y) {
        Ball b = new Ball(); b.x = x; b.y = y; b.r = Physics.BOWL_R; b.owner = owner; return b;
    }
    static Ball jackAt(double x, double y) {
        Ball j = new Ball(); j.x = x; j.y = y; j.r = Physics.JACK_R; j.m = 0.5; j.owner = -1; j.jack = true; return j;
    }

    static void test() {
        Physics.setGreen(1);
        double mat = Physics.MAT_Y, M = Physics.M;
        // jack legality (Law 10.1)
        check(Rules.jackFault(jackAt(Physics.CX, mat - 22 * M)) != null, "22 m jack must be improper");
        check(Rules.jackFault(jackAt(Physics.CX, mat - 23.5 * M)) == null, "23.5 m jack must be fine");
        check(Rules.jackFault(jackAt(Physics.CX, Physics.RY0 - 5)) != null, "ditch jack improper");
        check(Rules.jackFault(jackAt(Physics.RX1 + 30, mat - 26 * M)) != null, "outside jack improper");
        // dead bowls (Law 17.1)
        List<Ball> w = new ArrayList<>();
        Ball j = jackAt(Physics.CX, mat - 26 * M); w.add(j);
        Ball ditchNon = bowl(0, Physics.CX, Physics.RY0 - 10);
        Ball ditchToucher = bowl(1, Physics.CX + 20, Physics.RY0 - 10); ditchToucher.toucher = true;
        Ball side = bowl(0, Physics.RX0 - 30, mat - 26 * M);
        Ball edge = bowl(1, Physics.RX0 - 5, mat - 26 * M);   // partly inside: live
        Ball shortB = bowl(0, Physics.CX, mat - 12 * M);
        w.add(ditchNon); w.add(ditchToucher); w.add(side); w.add(edge); w.add(shortB);
        Rules.settleBowl(w, shortB);
        check(!ditchNon.alive, "non-toucher in ditch dead");
        check(ditchToucher.alive, "toucher in ditch live");
        check(!side.alive, "bowl fully outside side dead");
        check(edge.alive, "bowl partly inside side boundary live");
        check(!shortB.alive, "bowl short of 14 m dead");
        // dead jack under 20 m (Law 19.1.3)
        List<Ball> w2 = new ArrayList<>(); Ball j2 = jackAt(Physics.CX, mat - 19 * M); w2.add(j2);
        check(Rules.settleBowl(w2, null).jackDead, "jack under 20 m dead");
        // live jack in the ditch (Law 18.1)
        List<Ball> w3 = new ArrayList<>(); Ball j3 = jackAt(Physics.CX, Physics.RY0 - 12); w3.add(j3);
        check(!Rules.settleBowl(w3, null).jackDead, "jack in ditch within sides stays live");
        // scoring (Law 22, 24)
        List<Ball> s = new ArrayList<>(); Ball jj = jackAt(Physics.CX, 200); s.add(jj);
        s.add(bowl(0, Physics.CX + 17, 200));  // 17-15.3 = 1.7 gap
        s.add(bowl(0, Physics.CX - 25, 200));  // gap 9.7
        s.add(bowl(1, Physics.CX, 232));       // gap 16.7
        Rules.EndResult r = Rules.score(s);
        check(r.owner == 0 && r.shots == 2, "player scores 2, got owner=" + r.owner + " shots=" + r.shots);
        s.add(bowl(1, Physics.CX, 168 + 0)); // another far CPU bowl, same gap 16.7
        check(Rules.score(s).shots == 2, "still 2");
        List<Ball> t = new ArrayList<>(); t.add(jackAt(Physics.CX, 200));
        t.add(bowl(0, Physics.CX + 20, 200)); t.add(bowl(1, Physics.CX - 20, 200));
        check(Rules.score(t).tie, "equidistant bowls tie (Law 24.1)");
        List<Ball> only = new ArrayList<>(); only.add(jackAt(Physics.CX, 200)); only.add(bowl(1, Physics.CX + 40, 200)); only.add(bowl(1, Physics.CX - 50, 200));
        Rules.EndResult ro = Rules.score(only);
        check(ro.owner == 1 && ro.shots == 2, "all bowls score when opponent has none");
        // toucher logic (Law 14): a delivered bowl running onto the jack becomes a toucher; jack in ditch does not
        List<Ball> p = new ArrayList<>(); p.add(jackAt(Physics.CX, 200));
        Ball nb = Physics.release(0, 0, 0, Math.sqrt(2 * Physics.decel * (mat - 205)), false);
        ArrayList<Ball> done = Physics.simulate(p, nb, null);
        check(done.get(done.size() - 1).toucher, "bowl hitting jack is toucher");
        List<Ball> p2 = new ArrayList<>(); p2.add(jackAt(Physics.CX, Physics.RY0 - 10));
        Ball nb2 = Physics.release(0, 0, 0, Math.sqrt(2 * Physics.decel * (mat - Physics.RY0 + 4)), false);
        ArrayList<Ball> done2 = Physics.simulate(p2, nb2, null);
        check(!done2.get(done2.size() - 1).toucher, "no toucher onto a jack in the ditch (Law 14.3)");
        // bias sanity: a left-hand bowl aimed straight ends left of centre, right-hand ends right
        Ball l = Physics.release(0, -1, 0, Math.sqrt(2 * Physics.decel * 25 * M), false);
        Ball rr = Physics.release(0, 1, 0, Math.sqrt(2 * Physics.decel * 25 * M), false);
        ArrayList<Ball> wl = Physics.simulate(new ArrayList<Ball>(), l, null), wr = Physics.simulate(new ArrayList<Ball>(), rr, null);
        double xl = wl.get(0).x, xr = wr.get(0).x;
        System.out.printf("25 m bowl drift: left hand ends at x=%.0f (%.2f m), right hand x=%.0f%n", xl, (Physics.CX - xl) / 56.0, xr);
        check(xl < Physics.CX - 20 && xr > Physics.CX + 20, "bias direction");
    }
}
