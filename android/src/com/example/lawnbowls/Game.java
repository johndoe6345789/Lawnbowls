package com.example.lawnbowls;

import java.util.ArrayList;
import java.util.Locale;
import java.util.Random;

/**
 * The match: order of play, jack and bowl delivery, dead ends, scoring and game formats, following
 * the World Bowls laws for singles. No Android classes here, so it can be tested on a plain JVM.
 */
final class Game {
    // ---- settings --------------------------------------------------------------------------
    static final String[] FORMATS = {"First to 7 shots", "First to 21 shots", "10 ends"};
    static final String[] SKILLS = {"Easy", "Medium", "Hard"};
    static final String[] GREENS = {"Slow", "Medium", "Fast"};
    static final double[] SKILL_TH = {0.035, 0.016, 0.006};
    static final double[] SKILL_V = {0.07, 0.035, 0.014};
    static final int BOWLS_EACH = 4, FIXED_ENDS = 10;

    int format = 0, skill = 1, green = 1;
    int side = -1;                 // your bias: -1 curves left, +1 curves right

    // ---- states ----------------------------------------------------------------------------
    static final int ST_IDLE = 0, ST_TOSS = 1, ST_TOSS_CHOICE = 2, ST_JACK_AIM = 3, ST_JACK_CPU = 4,
            ST_JACK_ROLL = 5, ST_AIM = 6, ST_CPU_THINK = 7, ST_ROLLING = 8, ST_PAUSE = 9,
            ST_END_OVER = 10, ST_GAME_OVER = 11;
    private static final int P_BEGIN_END = 1, P_JACK_START = 2;

    interface Events {
        void matchFinished(int winner);
    }

    static final class Trail {
        final int owner;
        final boolean jack;
        final ArrayList<float[]> pts = new ArrayList<float[]>();
        Trail(int owner, boolean jack) { this.owner = owner; this.jack = jack; }
    }

    final Random rng;
    final ArrayList<Ball> balls = new ArrayList<Ball>();
    final ArrayList<Trail> trails = new ArrayList<Trail>();
    final ArrayList<String> history = new ArrayList<String>();
    final int[] score = new int[2], left = new int[2];
    Physics.Listener listener;
    Events events;
    boolean autoPlayer;            // tests: let the Ai play your bowls too
    boolean spectator;             // both sides are played by personalities; you just watch
    double speed = 1;              // spectator playback speed
    final Ego[] ego = new Ego[2];  // the computer players (ego[0] only when spectating)
    final double[] mood = new double[2];
    String banter = "";            // what someone just said
    int banterWho = -1;
    double banterT;                // seconds left on screen (real time)
    int tossWinner;
    String tossText = "";
    private double endWait;

    int state = ST_IDLE;
    int endNo = 1, endsPlayed = 0, deadEnds = 0;
    int starter = 0, deliverer = 0, turn = 0, jackFails = 0;
    boolean extraEnd, matchOver;
    int winner = -1;
    String msg = "", note = "";
    Rules.EndResult result;
    Ball jackBall, lastDelivered;
    Trail liveTrail;
    double tossT, wait, acc;
    boolean tossPlayerWins;
    private int pending;
    private int trailTick;

    Game(Random rng) { this.rng = rng; }

    // ---- names ---------------------------------------------------------------------------------
    boolean human(int i) { return i == 0 && !spectator; }
    Ego egoOf(int i) { return ego[i] != null ? ego[i] : Ego.DEFAULT; }
    String nameOf(int i) { return human(i) ? "You" : (ego[i] != null ? ego[i].name : "CPU"); }
    String poss(int i) { return human(i) ? "Your" : nameOf(i) + "'s"; }
    /** "You score" / "Dennis scores". */
    String does(int i, String base, String third) { return nameOf(i) + " " + (human(i) ? base : third); }
    String moodOf(int i) { return Ego.moodLabel(mood[i]); }

    private void say(int who, int kind) {
        if (ego[who] == null) return;
        if (rng.nextDouble() > ego[who].banter) return;
        String l = ego[who].line(kind, rng);
        if (l == null) return;
        banter = l; banterWho = who; banterT = kind >= Ego.WIN_MATCH ? 7 : 3.6;
    }

    private void sayRaw(int who, String l) {
        banter = l; banterWho = who; banterT = 3.0;
    }

    // ---- helpers for the UI ----------------------------------------------------------------
    boolean inProgress() { return state != ST_IDLE; }
    boolean canAim() { return state == ST_JACK_AIM || state == ST_AIM; }
    boolean rolling() { return state == ST_JACK_ROLL || state == ST_ROLLING; }
    boolean isJackTurn() { return state == ST_JACK_AIM; }
    String formatName() { return FORMATS[format]; }

    /** Angle and weight for a drag to point (px, py) in field coordinates. */
    static double[] aim(double px, double py) {
        double dx = px - Physics.CX, dy = Math.max(30.0, Physics.MAT_Y - py);
        double theta = Math.max(-0.6, Math.min(0.6, Math.atan2(dx, dy)));
        double v = Ai.clampV(Math.sqrt(2 * Physics.decel * Math.hypot(dx, dy)));
        return new double[]{theta, v};
    }

    /** The path a delivery would take over an empty green (for the guide line). */
    ArrayList<float[]> guidePath(boolean jack, double theta, double v) {
        ArrayList<float[]> path = new ArrayList<float[]>();
        Ball nb = Physics.release(0, side, theta, v, jack);
        Physics.simulate(new ArrayList<Ball>(), nb, path);
        return path;
    }

    // ---- match flow --------------------------------------------------------------------------
    void startMatch() {
        Physics.setGreen(green);
        ego[1] = Ego.random(rng, null);
        ego[0] = spectator ? Ego.random(rng, ego[1]) : null;
        Rules.poss[0] = poss(0);
        Rules.poss[1] = poss(1);
        mood[0] = mood[1] = 0;
        banter = ""; banterWho = -1; banterT = 0;
        balls.clear(); trails.clear(); history.clear();
        score[0] = score[1] = 0;
        endNo = 1; endsPlayed = 0; deadEnds = 0;
        extraEnd = false; matchOver = false; winner = -1;
        note = ""; result = null; pending = 0;
        state = ST_TOSS;
        tossT = 0;
        tossPlayerWins = rng.nextBoolean();
        tossWinner = tossPlayerWins ? 0 : 1;
        tossText = (human(tossWinner) ? "You win" : nameOf(tossWinner) + " wins") + " the toss";
        msg = spectator ? nameOf(0) + " v " + nameOf(1) + ". Coin toss (Law 5.2): the winner chooses who delivers the jack and plays first."
                : "Coin toss (Law 5.2): the winner chooses who delivers the jack and plays first.";
    }

    /** You won the toss and chose who starts (Law 5.2.1). */
    void chooseStarter(boolean playerStarts) {
        if (state != ST_TOSS_CHOICE) return;
        starter = playerStarts ? 0 : 1;
        msg = playerStarts ? "You start: you deliver the jack and play first."
                : "You asked " + nameOf(1) + " to start: it delivers the jack and plays first.";
        schedule(1.6, P_BEGIN_END);
    }

    void tapContinue() {
        if (state == ST_END_OVER) {
            endNo++;
            if (extraEnd) {   // Law 28: a coin toss decides who plays first in an extra end
                starter = rng.nextBoolean() ? 0 : 1;
                note = "Extra end - coin toss: " + does(starter, "start", "starts") + ". ";
            }
            beginEnd();
        } else if (state == ST_GAME_OVER) {
            state = ST_IDLE;
        }
    }

    private void schedule(double sec, int action) {
        state = ST_PAUSE;
        wait = sec;
        pending = action;
    }

    private void beginEnd() {
        balls.clear(); trails.clear();
        mood[0] *= 0.6; mood[1] *= 0.6;
        left[0] = left[1] = BOWLS_EACH;
        jackFails = 0;
        deliverer = starter;
        result = null;
        startJackDelivery();
    }

    private void startJackDelivery() {
        if (human(deliverer) || (deliverer == 0 && autoPlayer)) {
            state = ST_JACK_AIM;
            wait = 0.8;
            msg = note + "Your jack: drag to aim, release to roll it. It must stop 23 m or more from the mat, on the green.";
        } else {
            state = ST_JACK_CPU;
            wait = 0.9;
            wait = 0.9 * egoOf(deliverer).tempo;
            msg = note + nameOf(deliverer) + " is delivering the jack...";
        }
        note = "";
    }

    void update(double real) {
        if (banterT > 0) { banterT -= real; if (banterT <= 0) { banter = ""; banterWho = -1; } }
        double dt = spectator ? real * speed : real;
        switch (state) {
            case ST_TOSS:
                tossT += dt;
                if (tossT > 1.8) afterToss();
                break;
            case ST_PAUSE:
                wait -= dt;
                if (wait <= 0) {
                    int p = pending;
                    pending = 0;
                    if (p == P_BEGIN_END) beginEnd();
                    else if (p == P_JACK_START) startJackDelivery();
                }
                break;
            case ST_TOSS_CHOICE:
                if (autoPlayer) chooseStarter(rng.nextBoolean());
                break;
            case ST_JACK_CPU:
                wait -= dt;
                if (wait <= 0) {
                    Ego e = egoOf(deliverer);
                    double k = e.accuracyFor(mood[deliverer]);
                    Ai.Shot s = Ai.jackShot(rng, SKILL_TH[skill] * k, SKILL_V[skill] * k, e);
                    deliverJack(s.th, s.v);
                }
                break;
            case ST_JACK_AIM:
                if (autoPlayer) {
                    wait -= dt;
                    if (wait <= 0) {
                        Ai.Shot s = Ai.jackShot(rng, SKILL_TH[skill], SKILL_V[skill]);
                        deliverJack(s.th, s.v);
                    }
                }
                break;
            case ST_CPU_THINK:
                wait -= dt;
                if (wait <= 0) cpuBowl(turn);
                break;
            case ST_END_OVER:
                if (spectator) {            // nobody to tap: carry on after a moment to read the result
                    endWait -= real;
                    if (endWait <= 0) tapContinue();
                }
                break;
            case ST_AIM:
                if (autoPlayer) {
                    wait -= dt;
                    if (wait <= 0) cpuBowl(0);
                }
                break;
            case ST_JACK_ROLL:
            case ST_ROLLING:
                acc += dt;
                while (acc >= Physics.DT) {
                    acc -= Physics.DT;
                    boolean moving = Physics.step(balls, Physics.DT, listener);
                    if (liveTrail != null && ++trailTick % 2 == 0 && lastMover() != null) {
                        Ball m = lastMover();
                        liveTrail.pts.add(new float[]{(float) m.x, (float) m.y});
                    }
                    if (!moving) {
                        if (state == ST_JACK_ROLL) jackSettled(); else bowlSettled();
                        break;
                    }
                }
                break;
            default:
                break;
        }
    }

    private Ball lastMover() { return state == ST_JACK_ROLL ? jackBall : lastDelivered; }

    private void afterToss() {
        if (spectator) {
            starter = rng.nextDouble() < 0.75 ? tossWinner : 1 - tossWinner;
            msg = nameOf(tossWinner) + " won the toss and " + (starter == tossWinner ? "will start."
                    : "asks " + nameOf(starter) + " to start.");
            schedule(2.4, P_BEGIN_END);
        } else if (tossPlayerWins) {
            state = ST_TOSS_CHOICE;
            msg = "You won the toss! Who should deliver the jack and play first?";
        } else {
            starter = rng.nextDouble() < 0.75 ? 1 : 0;
            msg = nameOf(1) + " won the toss and " + (starter == 1 ? "will start." : "asks you to start.");
            schedule(2.0, P_BEGIN_END);
        }
    }

    // ---- delivering --------------------------------------------------------------------------
    /** Player release (jack or bowl, depending on whose turn it is). */
    void playerRelease(double theta, double v) {
        if (state == ST_JACK_AIM) deliverJack(theta, v);
        else if (state == ST_AIM) deliverBowl(0, side, theta, v);
    }

    private void deliverJack(double theta, double v) {
        balls.clear();
        jackBall = Physics.release(-1, 0, theta, Ai.clampV(v), true);
        balls.add(jackBall);
        liveTrail = new Trail(-1, true);
        liveTrail.pts.add(new float[]{(float) jackBall.x, (float) jackBall.y});
        trails.add(liveTrail);
        acc = 0;
        state = ST_JACK_ROLL;
        msg = poss(deliverer) + " jack is away...";
    }

    private void deliverBowl(int owner, int sd, double theta, double v) {
        lastDelivered = Physics.release(owner, sd, theta, Ai.clampV(v), false);
        balls.add(lastDelivered);
        liveTrail = new Trail(owner, false);
        liveTrail.pts.add(new float[]{(float) lastDelivered.x, (float) lastDelivered.y});
        trails.add(liveTrail);
        left[owner]--;
        acc = 0;
        state = ST_ROLLING;
        msg = human(owner) ? "Rolling..." : nameOf(owner) + " bowls...";
    }

    private void cpuBowl(int me) {
        Ego e = egoOf(me);
        boolean showOff = balls.size() > 1 && rng.nextDouble() < e.showboat * 0.3;
        if (showOff) sayRaw(me, Ego.SHOWOFF[rng.nextInt(Ego.SHOWOFF.length)]);
        Ai.Shot s = Ai.bowlShot(balls, me, e, showOff);
        double k = e.accuracyFor(mood[me]);
        double th = s.th + rng.nextGaussian() * SKILL_TH[skill] * k;
        double v = s.v * (1 + rng.nextGaussian() * SKILL_V[skill] * k);
        deliverBowl(me, s.side, th, v);
    }

    // ---- after the jack stops ----------------------------------------------------------------
    private void jackSettled() {
        Ball j = jackBall;
        String why = Rules.jackFault(j);
        String who = poss(deliverer);
        if (why != null) {
            balls.clear();
            jackFails++;
            if (jackFails >= 2) {
                // Law 10.3: both players failed - the jack is centred 2 m from the front ditch.
                Ball c = Physics.release(-1, 0, 0, 0, true);
                c.vx = 0; c.vy = 0;
                c.y = Physics.RY0 + Rules.JACK_SHORT_M * Physics.M;
                c.original = false;
                jackBall = c;
                balls.add(c);
                note = who + " jack " + why + " too. Jack centred 2 m from the front ditch (Law 10.3). ";
                beginBowls();
            } else {
                deliverer = 1 - deliverer;
                note = who + " jack " + why + " - improper delivery (Law 10.1). "
                        + does(deliverer, "re-deliver", "re-delivers")
                        + " the jack but the first bowl stays with the same player (Law 10.2). ";
                msg = note;
                note = "";
                schedule(2.6, P_JACK_START);
            }
            return;
        }
        j.vx = 0; j.vy = 0;
        j.x = Physics.CX;                                        // the jack is centred (Law C.1)
        String extra = "";
        double minY = Physics.RY0 + Rules.JACK_SHORT_M * Physics.M;
        if (j.y < minY) { j.y = minY; extra = "Placed 2 m from the ditch (Law 9.2). "; }
        j.original = false;
        note = String.format(Locale.UK, "Jack centred at %.1f m. %s", Rules.metresFromMat(j), extra);
        beginBowls();
    }

    private void beginBowls() {
        turn = starter;
        startTurn();
    }

    private void startTurn() {
        if (left[0] == 0 && left[1] == 0) { scoreEnd(); return; }
        if (human(turn) || (turn == 0 && autoPlayer)) {
            state = ST_AIM;
            wait = 0.8;
            msg = note + "Your bowl: drag to aim, release to bowl. Release near the mat to cancel.";
        } else {
            state = ST_CPU_THINK;
            wait = (spectator ? 1.0 : 0.8) * egoOf(turn).tempo;
            msg = note + nameOf(turn) + " is lining up a bowl...";
        }
        note = "";
    }

    // ---- after a bowl stops ------------------------------------------------------------------
    private void bowlSettled() {
        Ball d = lastDelivered;
        Rules.Settle s = Rules.settleBowl(balls, d);
        StringBuilder sb = new StringBuilder();
        for (String n : s.notes) sb.append(n).append('\n');
        if (d.alive && d.toucher) {
            sb.append(Rules.who(d)).append(" bowl is a toucher - chalked (Laws 14-15)")
              .append(d.y < Physics.RY0 ? ", live in the ditch.\n" : ".\n");
        }
        if (s.jackDead) {
            deadEnd(s.jackReason);
            return;
        }
        react(d);
        for (int i = balls.size() - 1; i >= 0; i--) if (!balls.get(i).alive) balls.remove(i);
        turn = 1 - turn;
        note = sb.toString();
        startTurn();
    }

    /** The bowler's comment on a delivery that has just stopped. */
    private void react(Ball d) {
        int who = d.owner;
        if (ego[who] == null) return;
        if (!d.alive) { say(who, Ego.BAD); return; }
        Ball j = Rules.jack(balls);
        if (j == null || !j.alive) return;
        double mine = Rules.gap(j, d);
        if (mine < Physics.BOWL_R * 0.9) {
            boolean best = true;
            for (Ball b : balls) if (b.alive && !b.jack && b != d && Rules.gap(j, b) < mine) best = false;
            if (best) say(who, Ego.GOOD);
        } else if (mine > Physics.BOWL_R * 6) {
            say(who, Ego.BAD);
        }
    }

    private void deadEnd(String reason) {
        deadEnds++;
        msg = reason + ": dead end, replayed in the same direction with the same first player (Laws 19-20).";
        note = "";
        schedule(3.2, P_BEGIN_END);
    }

    // ---- scoring ------------------------------------------------------------------------------
    private void scoreEnd() {
        for (int i = balls.size() - 1; i >= 0; i--) if (!balls.get(i).alive) balls.remove(i);
        result = Rules.score(balls);
        endsPlayed++;
        String line;
        if (result.owner >= 0) {
            score[result.owner] += result.shots;
            starter = result.owner;                     // Law 5.4: the scorer starts the next end
            line = does(result.owner, "score", "scores") + " " + result.shots
                    + (result.shots == 1 ? " shot." : " shots.");
            history.add(String.format(Locale.UK, "End %d: %s +%d", endNo, nameOf(result.owner), result.shots));
            int w = result.owner, l = 1 - w;
            mood[w] = Math.min(1, mood[w] + 0.22 * result.shots);
            mood[l] = Math.max(-1, mood[l] - 0.22 * result.shots);
            say(w, Ego.WIN_END);
            if (banter.isEmpty()) say(l, Ego.LOSE_END);
        } else {
            // Law 24.3/24.4: after a tied end the first player stays the same.
            line = result.note;
            history.add(String.format(Locale.UK, "End %d: tied", endNo));
        }
        checkMatchOver();
        if (matchOver) {
            state = ST_GAME_OVER;
            msg = line + "\n" + does(winner, "win", "wins") + " the match " + score[0] + "-" + score[1] + ".";
            banter = ""; banterT = 0;
            say(winner, Ego.WIN_MATCH);
            if (banter.isEmpty()) say(1 - winner, Ego.LOSE_MATCH);
            if (events != null) events.matchFinished(winner);
        } else {
            state = ST_END_OVER;
            endWait = 5.5;
            msg = line + (extraEnd ? "\nScores level - an extra end follows (Law 28). Tap to continue."
                    : "\nTap to play the next end.");
        }
    }

    private void checkMatchOver() {
        matchOver = false;
        if (format <= 1) {
            int target = format == 0 ? 7 : 21;
            if (Math.max(score[0], score[1]) >= target) {
                matchOver = true;
                winner = score[0] > score[1] ? 0 : 1;
            }
        } else if (endsPlayed >= FIXED_ENDS) {
            if (score[0] != score[1]) {
                matchOver = true;
                winner = score[0] > score[1] ? 0 : 1;
                extraEnd = false;
            } else {
                extraEnd = true;                       // Law 28: drawn after the set ends - extra end
            }
        } else if (Math.abs(score[0] - score[1]) > BOWLS_EACH * (FIXED_ENDS - endsPlayed)) {
            matchOver = true;                          // Law 26.3: a win is no longer possible
            winner = score[0] > score[1] ? 0 : 1;
        }
    }
}
