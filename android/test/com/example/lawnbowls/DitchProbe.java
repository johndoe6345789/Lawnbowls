package com.example.lawnbowls;
import java.util.*;
public class DitchProbe {
    static void run(Game g, int max) { for (int i = 0; i < max; i++) { g.update(1.0/60); } }
    public static void main(String[] a) {
        // 1. Opening jack thrown hard into the ditch by the human
        Game g = new Game(new Random(Long.getLong("seed", 5L))); g.autoPlayer = false; g.startMatch();
        while (g.state != Game.ST_JACK_AIM && g.state != Game.ST_JACK_CPU) { g.update(1.0/60); if (g.state == Game.ST_TOSS_CHOICE) g.chooseStarter(true); }
        System.out.println("state=" + g.state + " deliverer=" + g.deliverer);
        if (g.state == Game.ST_JACK_CPU) { System.out.println("cpu jack first; skipping"); }
        else {
            for (int i = 0; i < 60; i++) g.update(1.0/60);
            g.playerRelease(0.0, 600);
            for (int i = 0; i < 1200 && g.state == Game.ST_JACK_ROLL; i++) g.update(1.0/60);
            Ball j = g.jackBall;
            System.out.printf("after hard jack: state=%d y=%.1f RY0=%.1f alive=%b%nmsg=%s%n", g.state, j.y, Physics.RY0, j.alive, g.msg);
            for (int i = 0; i < 400; i++) g.update(1.0/60);
            System.out.printf("later: state=%d deliverer=%d balls=%d%nmsg=%s%n", g.state, g.deliverer, g.balls.size(), g.msg);
        }
        // 2. Jack in the ditch after a drive, mid-end
        Game h = new Game(new Random(9)); h.autoPlayer = true; h.startMatch();
        int seen = 0, ditchJackEnds = 0;
        Ball lastJack = null;
        for (int i = 0; i < 4_000_000 && h.state != Game.ST_GAME_OVER; i++) {
            h.update(1.0/60);
            if (h.state == Game.ST_END_OVER) h.tapContinue();
            Ball jk = Rules.jack(h.balls);
            if (jk != null && jk.alive && jk.y < Physics.RY0 && (h.state == Game.ST_AIM || h.state == Game.ST_CPU_THINK) && jk != lastJack) {
                lastJack = jk; ditchJackEnds++;
                if (ditchJackEnds <= 3) System.out.printf("live jack in ditch mid-end: state=%d left=%d/%d msg=%s%n", h.state, h.left[0], h.left[1], h.msg.replace("\n", " | "));
            }
        }
        System.out.println("mid-end ditch jacks seen: " + ditchJackEnds);
    }
}
