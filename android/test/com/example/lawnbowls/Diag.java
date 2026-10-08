package com.example.lawnbowls;
import java.util.*;
public class Diag {
    public static void main(String[] a) {
        int[][] ditch = new int[3][8], tot = new int[3][8];
        int[] drives = new int[3];
        for (int skill = 0; skill < 3; skill++) {
            Physics.setGreen(1);
            Random rng = new Random(7 + skill);
            for (int e = 0; e < 120; e++) {
                ArrayList<Ball> balls = new ArrayList<>();
                double m = 24.5 + rng.nextDouble() * 5;
                Ball j = Physics.release(-1, 0, 0, Math.sqrt(2 * Physics.decel * m * Physics.M), true);
                ArrayList<Ball> w = Physics.simulate(balls, j, null);
                Ball jj = w.get(0); jj.x = Physics.CX; jj.vx = jj.vy = 0; jj.original = false;
                balls.add(jj);
                int turn = 0;
                for (int k = 0; k < 8; k++) {
                    if (Rules.jack(balls) == null) break;   // jack was killed: dead end
                    Ai.Shot s = Ai.bowlShot(balls, turn);
                    double th = s.th + rng.nextGaussian() * Game.SKILL_TH[skill];
                    double v = s.v * (1 + rng.nextGaussian() * Game.SKILL_V[skill]);
                    if (s.v > 450) drives[skill]++;
                    Ball nb = Physics.release(turn, s.side, th, Ai.clampV(v), false);
                    ArrayList<Ball> ww = Physics.simulate(balls, nb, null);
                    Ball d = ww.get(ww.size() - 1);
                    Rules.settleBowl(ww, d);
                    boolean jackOk = true; for (Ball b : ww) if (b.jack && !b.alive) jackOk = false;
                    tot[skill][k]++;
                    if (!d.alive) ditch[skill][k]++;
                    balls.clear();
                    if (jackOk) for (Ball b : ww) if (b.alive) { balls.add(b); }
                    turn = 1 - turn;
                }
            }
            System.out.printf("skill %d: bowl-lost rate by bowl #: ", skill);
            for (int k = 0; k < 8; k++) System.out.printf("%.0f%% ", 100.0 * ditch[skill][k] / tot[skill][k]);
            System.out.printf("  drives chosen=%d of %d%n", drives[skill], 8 * 120);
        }
    }
}
