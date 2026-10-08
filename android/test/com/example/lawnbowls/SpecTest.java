package com.example.lawnbowls;

import java.util.*;

public class SpecTest {
    public static void main(String[] a) {
        Map<String,int[]> st = new TreeMap<>();   // name -> {bowls, drives, dead, wins, matches, lines}
        Set<String> said = new HashSet<>();
        long t0 = System.nanoTime();
        int matches = 0, withBanter = 0, maxMs = 0;
        for (int rep = 0; rep < 40; rep++) {
            Game g = new Game(new Random(77 + rep));
            g.spectator = true; g.speed = 4; g.format = rep % 3; g.skill = 1; g.green = rep % 3;
            g.startMatch();
            if (g.ego[0] == null || g.ego[1] == null || g.ego[0] == g.ego[1]) throw new IllegalStateException("egos");
            int guard = 0; Ball prev = null; boolean sawBanter = false;
            while (g.state != Game.ST_GAME_OVER) {
                long s = System.nanoTime();
                g.update(1.0 / 60);
                maxMs = Math.max(maxMs, (int) ((System.nanoTime() - s) / 1_000_000));
                if (g.state == Game.ST_TOSS_CHOICE || g.state == Game.ST_AIM || g.state == Game.ST_JACK_AIM)
                    throw new IllegalStateException("spectator waited for input: state " + g.state);
                if (g.state == Game.ST_ROLLING && g.lastDelivered != prev) {
                    prev = g.lastDelivered;
                    double sp = Math.hypot(prev.vx, prev.vy);
                    Ball jk = Rules.jack(g.balls);
                    double dj = jk == null ? 600 : Math.hypot(jk.x - Physics.CX, jk.y - Physics.MAT_Y);
                    sp = sp > Math.sqrt(2 * Physics.decel * dj * 1.45) ? 999 : 0;
                    int[] r = st.computeIfAbsent(g.ego[prev.owner].name, k -> new int[6]);
                    r[0]++; if (sp > 0) r[1]++;
                }
                if (!g.banter.isEmpty()) { sawBanter = true; said.add(g.banter); }
                if (++guard > 6_000_000) throw new IllegalStateException("stuck " + g.state + " " + g.msg);
                if (g.msg.contains("CPU") || (g.msg.contains("You ") && !g.msg.startsWith("You") && g.msg.contains("You score")))
                    throw new IllegalStateException("human wording in spectator: " + g.msg);
            }
            matches++; if (sawBanter) withBanter++;
            for (int i = 0; i < 2; i++) {
                int[] r = st.computeIfAbsent(g.ego[i].name, k -> new int[6]);
                r[4]++; if (g.winner == i) r[3]++;
            }
            if (g.winner < 0) throw new IllegalStateException("no winner");
        }
        System.out.printf("%d spectator matches, %d with banter, %d distinct lines, slowest update %d ms, %.1fs%n",
                matches, withBanter, said.size(), maxMs, (System.nanoTime() - t0) / 1e9);
        for (Map.Entry<String,int[]> e : st.entrySet()) {
            int[] r = e.getValue();
            System.out.printf("  %-9s bowls %3d  drives %3d (%2d%%)  matches %2d  won %2d%n", e.getKey(), r[0], r[1],
                    r[0] == 0 ? 0 : 100 * r[1] / r[0], r[4], r[3]);
        }
    }
}
