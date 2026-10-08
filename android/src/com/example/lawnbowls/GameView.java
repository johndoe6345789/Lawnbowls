package com.example.lawnbowls;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.Locale;
import java.util.Random;

/** Rendering, touch input, menus and settings. The rules live in {@link Game}. */
public class GameView extends View implements Physics.Listener, Game.Events {
    static final int W = Physics.W, H = Physics.H, HUD = 190, LW = W, LH = H + HUD;
    static final int BG_SCALE = 2;

    // button ids
    static final int B_HAND = 1, B_GUIDE = 2, B_SOUND = 3, B_MENU = 4, B_SPEED = 5;
    static final int M_RESUME = 10, M_NEW = 11, M_FORMAT = 12, M_SKILL = 13, M_GREEN = 14, M_SOUND = 15,
            M_RULES = 16, M_CARD = 17, O_BACK = 18, M_SPECTATE = 19, T_ME = 20, T_CPU = 21;

    static final class Btn {
        final int id; final RectF r;
        Btn(int id, RectF r) { this.id = id; this.r = r; }
    }

    final Game game;
    final Sfx sfx = new Sfx();
    final SharedPreferences prefs;
    final Random rng = new Random();

    // view state
    boolean menu = true;
    int overlay = 0;                 // 0 none, 1 rules, 2 scorecard
    float overlayScroll = 0f, lastTouchY = 0f;
    boolean guide = true;
    int wins, losses;
    boolean aiming = false;
    double aimX, aimY;
    long lastNs = System.nanoTime(), lastClackNs = 0, lastThudNs = 0;
    double clock = 0;

    // layout
    float scale = 1f, offX = 0f, offY = 0f;
    int insL, insT, insR, insB;
    final ArrayList<Btn> btns = new ArrayList<Btn>();

    // paints
    final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint bmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    final TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    final Paint bowlRed = new Paint(Paint.ANTI_ALIAS_FLAG), bowlBlue = new Paint(Paint.ANTI_ALIAS_FLAG),
            jackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    final Typeface bold = Typeface.create("sans-serif", Typeface.BOLD);
    final Typeface regular = Typeface.create("sans-serif", Typeface.NORMAL);

    Bitmap bg;
    StaticLayout msgLayout, rulesLayout, cardLayout, bubbleLayout;
    String msgKey = "", cardKey = "", bubbleKey = "";
    ArrayList<float[]> guidePath = new ArrayList<float[]>();
    String guideKey = "";

    public GameView(Context ctx) {
        super(ctx);
        prefs = ctx.getSharedPreferences("lawnbowls", Context.MODE_PRIVATE);
        game = new Game(rng);
        game.listener = this;
        game.events = this;
        game.format = clampPref("format", 0, 2, 0);
        game.skill = clampPref("skill", 0, 2, 1);
        game.green = clampPref("green", 0, 2, 1);
        game.side = prefs.getInt("side", -1) >= 0 ? 1 : -1;
        sfx.enabled = prefs.getBoolean("sound", true);
        guide = prefs.getBoolean("guide", true);
        wins = Math.max(0, prefs.getInt("wins", 0));
        losses = Math.max(0, prefs.getInt("losses", 0));
        Physics.setGreen(game.green);
        fill.setStyle(Paint.Style.FILL);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        buildBallPaints();
        setFilterTouchesWhenObscured(true);
    }

    private int clampPref(String k, int lo, int hi, int def) {
        int v = prefs.getInt(k, def);
        return v < lo || v > hi ? def : v;
    }

    private void savePrefs() {
        prefs.edit().putInt("format", game.format).putInt("skill", game.skill).putInt("green", game.green)
                .putInt("side", game.side).putBoolean("sound", sfx.enabled).putBoolean("guide", guide)
                .putInt("wins", wins).putInt("losses", losses).apply();
    }

    void release() { sfx.release(); }

    void setInsets(int l, int t, int r, int b) {
        insL = l; insT = t; insR = r; insB = b;
        invalidate();
    }

    // ---- Physics.Listener / Game.Events ---------------------------------------------------------
    @Override public void onContact(double speed, boolean withJack) {
        long now = System.nanoTime();
        if (speed < 20 || now - lastClackNs < 35_000_000L) return;
        lastClackNs = now;
        sfx.play(Sfx.CLACK, (float) (speed / 240.0));
        if (speed > 110) performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
    }

    @Override public void onDitch(boolean jack) {
        long now = System.nanoTime();
        if (now - lastThudNs < 80_000_000L) return;
        lastThudNs = now;
        sfx.play(Sfx.THUD, 0.8f);
    }

    @Override public void matchFinished(int winner) {
        if (game.spectator) return;   // watching does not touch your record
        if (winner == 0) wins++; else losses++;
        savePrefs();
    }

    // ---- ball paints ------------------------------------------------------------------------------
    private void buildBallPaints() {
        float r = (float) Physics.BOWL_R, jr = (float) Physics.JACK_R;
        bowlRed.setShader(new RadialGradient(-0.35f * r, -0.4f * r, 1.7f * r,
                new int[]{Color.rgb(255, 150, 130), Color.rgb(214, 40, 40), Color.rgb(96, 12, 14)},
                new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        bowlBlue.setShader(new RadialGradient(-0.35f * r, -0.4f * r, 1.7f * r,
                new int[]{Color.rgb(160, 195, 255), Color.rgb(47, 90, 168), Color.rgb(12, 30, 66)},
                new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        jackPaint.setShader(new RadialGradient(-0.35f * jr, -0.4f * jr, 1.7f * jr,
                new int[]{Color.WHITE, Color.rgb(244, 238, 214), Color.rgb(176, 168, 132)},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
    }

    // ---- static background ------------------------------------------------------------------------
    private void buildBackground() {
        bg = Bitmap.createBitmap(W * BG_SCALE, H * BG_SCALE, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bg);
        c.scale(BG_SCALE, BG_SCALE);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Random r = new Random(42);
        double RY0 = Physics.RY0, RY1 = Physics.RY1;

        // green with mowing stripes along the rink
        p.setStyle(Paint.Style.FILL);
        for (int x = 0, i = 0; x < W; x += 28, i++) {
            p.setColor(i % 2 == 0 ? Color.rgb(66, 140, 54) : Color.rgb(75, 152, 62));
            c.drawRect(x, (float) RY0, x + 28, (float) RY1, p);
        }
        // fine grass texture
        p.setStrokeWidth(0.8f);
        for (int i = 0; i < 9000; i++) {
            float x = r.nextFloat() * W, y = (float) (RY0 + r.nextFloat() * (RY1 - RY0));
            p.setColor(r.nextBoolean() ? Color.argb(34, 255, 255, 255) : Color.argb(40, 10, 50, 10));
            c.drawLine(x, y, x + (r.nextFloat() - 0.5f) * 1.2f, y - 2.4f, p);
        }
        // the neighbouring rinks are a little dimmer than the rink being played
        p.setStyle(Paint.Style.FILL);
        p.setColor(Color.argb(70, 0, 25, 0));
        c.drawRect(0, (float) RY0, (float) Physics.RX0, (float) RY1, p);
        c.drawRect((float) Physics.RX1, (float) RY0, W, (float) RY1, p);
        // soft vignette towards the ditches
        p.setShader(new LinearGradient(0, (float) RY0, 0, (float) RY0 + 60, Color.argb(70, 0, 0, 0), Color.TRANSPARENT, Shader.TileMode.CLAMP));
        c.drawRect(0, (float) RY0, W, (float) RY0 + 60, p);
        p.setShader(new LinearGradient(0, (float) RY1 - 50, 0, (float) RY1, Color.TRANSPARENT, Color.argb(70, 0, 0, 0), Shader.TileMode.CLAMP));
        c.drawRect(0, (float) RY1 - 50, W, (float) RY1, p);
        p.setShader(null);

        // front bank and ditch
        float dt = (float) (RY0 - Physics.DITCH);
        p.setShader(new LinearGradient(0, 0, 0, dt, Color.rgb(46, 44, 38), Color.rgb(92, 84, 70), Shader.TileMode.CLAMP));
        c.drawRect(0, 0, W, dt, p);
        p.setShader(new LinearGradient(0, dt, 0, (float) RY0, Color.rgb(22, 16, 10), Color.rgb(74, 58, 40), Shader.TileMode.CLAMP));
        c.drawRect(0, dt, W, (float) RY0, p);
        p.setShader(null);
        p.setColor(Color.argb(160, 235, 235, 225));
        c.drawRect(0, (float) RY0 - 1.2f, W, (float) RY0 + 0.6f, p);          // edge of the green
        // rear ditch and bank
        p.setShader(new LinearGradient(0, (float) RY1, 0, (float) (RY1 + Physics.DITCH), Color.rgb(74, 58, 40), Color.rgb(22, 16, 10), Shader.TileMode.CLAMP));
        c.drawRect(0, (float) RY1, W, (float) (RY1 + Physics.DITCH), p);
        p.setShader(new LinearGradient(0, (float) (RY1 + Physics.DITCH), 0, H, Color.rgb(92, 84, 70), Color.rgb(46, 44, 38), Shader.TileMode.CLAMP));
        c.drawRect(0, (float) (RY1 + Physics.DITCH), W, H, p);
        p.setShader(null);

        // rink boundary lines, pegs and centre line
        stroke.setColor(Color.argb(235, 245, 245, 240));
        stroke.setStrokeWidth(1.8f);
        c.drawLine((float) Physics.RX0, (float) RY0, (float) Physics.RX0, (float) RY1, stroke);
        c.drawLine((float) Physics.RX1, (float) RY0, (float) Physics.RX1, (float) RY1, stroke);
        p.setColor(Color.WHITE);
        for (float y = (float) RY0; y <= RY1 + 1; y += 5 * (float) Physics.M) {
            c.drawRect((float) Physics.RX0 - 3, y - 1.5f, (float) Physics.RX0 + 3, y + 1.5f, p);
            c.drawRect((float) Physics.RX1 - 3, y - 1.5f, (float) Physics.RX1 + 3, y + 1.5f, p);
        }
        stroke.setColor(Color.argb(60, 255, 255, 255));
        stroke.setStrokeWidth(1f);
        stroke.setPathEffect(new DashPathEffect(new float[]{3, 9}, 0));
        c.drawLine((float) Physics.CX, (float) RY0, (float) Physics.CX, (float) RY1, stroke);
        stroke.setPathEffect(null);

        // distance marks from the mat line
        tp.setTypeface(medium);
        tp.setTextSize(9f);
        tp.setColor(Color.argb(150, 255, 255, 255));
        tp.setTextAlign(Paint.Align.RIGHT);
        for (int m = 5; m <= 30; m += 5) {
            float y = (float) (Physics.MAT_Y - m * Physics.M);
            c.drawText(m + " m", (float) Physics.RX0 - 8, y + 3, tp);
        }

        // mat
        float mx = (float) Physics.CX, my = (float) Physics.MAT_Y;
        p.setColor(Color.rgb(150, 150, 146));
        c.drawRoundRect(mx - 25, my, mx + 25, my + 25, 3, 3, p);
        p.setColor(Color.rgb(30, 30, 30));
        c.drawRoundRect(mx - 23.5f, my + 1.5f, mx + 23.5f, my + 23.5f, 2, 2, p);
        stroke.setColor(Color.argb(40, 255, 255, 255));
        stroke.setStrokeWidth(0.8f);
        for (float yy = my + 5; yy < my + 24; yy += 4) c.drawLine(mx - 22, yy, mx + 22, yy, stroke);
    }

    // ---- main loop ----------------------------------------------------------------------------------
    @Override
    protected void onDraw(Canvas c) {
        long now = System.nanoTime();
        double dt = Math.min(0.05, (now - lastNs) / 1e9);
        lastNs = now;
        clock += dt;
        if (game.state == Game.ST_IDLE) menu = true;
        if (!menu && overlay == 0) game.update(dt);
        if (bg == null) buildBackground();

        int vw = getWidth(), vh = getHeight();
        int aw = Math.max(1, vw - insL - insR), ah = Math.max(1, vh - insT - insB);
        scale = Math.min(aw / (float) LW, ah / (float) LH);
        offX = insL + (aw - LW * scale) / 2f;
        offY = insT + (ah - LH * scale) / 2f;

        btns.clear();
        c.drawColor(Color.rgb(14, 24, 11));
        c.save();
        c.translate(offX, offY);
        c.scale(scale, scale);
        c.clipRect(0, 0, LW, LH);
        if (menu) {
            drawMenu(c);
        } else {
            drawHud(c);
            c.save();
            c.translate(0, HUD);
            drawField(c);
            c.restore();
            if (overlay != 0) drawOverlay(c);
        }
        c.restore();
        postInvalidateOnAnimation();
    }

    // ---- primitives -----------------------------------------------------------------------------------
    void label(Canvas c, String s, float x, float y, float size, int color, Typeface tf, Paint.Align al) {
        tp.setTypeface(tf);
        tp.setTextSize(size);
        tp.setColor(color);
        tp.setTextAlign(al);
        c.drawText(s, x, y, tp);
    }

    void button(Canvas c, int id, String text, float x, float y, float w, float h, boolean primary, boolean enabled) {
        RectF r = new RectF(x, y, x + w, y + h);
        if (enabled) btns.add(new Btn(id, r));
        int top = primary ? Color.rgb(96, 168, 70) : Color.rgb(52, 84, 41);
        int bot = primary ? Color.rgb(60, 124, 44) : Color.rgb(36, 60, 29);
        if (!enabled) { top = Color.rgb(34, 42, 31); bot = Color.rgb(28, 34, 26); }
        fill.setShader(new LinearGradient(x, y, x, y + h, top, bot, Shader.TileMode.CLAMP));
        c.drawRoundRect(r, 9, 9, fill);
        fill.setShader(null);
        stroke.setColor(enabled ? Color.argb(120, 190, 240, 160) : Color.argb(50, 160, 180, 150));
        stroke.setStrokeWidth(1f);
        c.drawRoundRect(r, 9, 9, stroke);
        label(c, text, r.centerX(), r.centerY() + h * 0.17f, Math.min(15f, h * 0.38f),
                enabled ? Color.rgb(240, 248, 232) : Color.rgb(110, 124, 104), bold, Paint.Align.CENTER);
    }

    void drawBall(Canvas c, Ball b, boolean ring) {
        float r = (float) b.r;
        c.save();
        c.translate((float) b.x, (float) b.y);
        fill.setColor(Color.argb(80, 0, 0, 0));
        c.drawOval(-r + 1.2f, -r * 0.8f + 3.4f, r + 2.6f, r * 0.8f + 3.4f + r * 0.45f, fill);   // soft shadow
        if (b.jack) {
            c.drawCircle(0, 0, r, jackPaint);
            stroke.setColor(Color.argb(120, 80, 70, 40));
            stroke.setStrokeWidth(0.7f);
            c.drawCircle(0, 0, r, stroke);
        } else {
            c.drawCircle(0, 0, r, b.owner == 0 ? bowlRed : bowlBlue);
            stroke.setColor(Color.argb(170, 0, 0, 0));
            stroke.setStrokeWidth(0.9f);
            c.drawCircle(0, 0, r, stroke);
            stroke.setColor(Color.argb(190, 255, 255, 255));        // the large disc on the bowl
            stroke.setStrokeWidth(1.1f);
            c.drawCircle(0, 0, r * 0.62f, stroke);
            if (b.mark != 0) {                                      // the small disc marks the bias side
                fill.setColor(Color.argb(235, 255, 255, 255));
                c.drawCircle(b.mark * r * 0.62f, 0, r * 0.2f, fill);
            }
            fill.setColor(Color.argb(95, 255, 255, 255));
            c.drawCircle(-r * 0.36f, -r * 0.42f, r * 0.2f, fill);   // highlight
            if (b.toucher && b.alive) {                             // chalk mark (Law 15.1)
                stroke.setColor(Color.argb(240, 255, 255, 255));
                stroke.setStrokeWidth(1.5f);
                c.drawLine(-3.2f, -1f, 3.2f, 1f, stroke);
                c.drawLine(-1f, -3.2f, 1f, 3.2f, stroke);
            }
        }
        if (ring) {
            stroke.setColor(Color.rgb(255, 224, 102));
            stroke.setStrokeWidth(2f);
            c.drawCircle(0, 0, r + 4.5f, stroke);
        }
        c.restore();
    }

    // ---- HUD --------------------------------------------------------------------------------------------
    void drawHud(Canvas c) {
        fill.setShader(new LinearGradient(0, 0, 0, HUD, Color.rgb(24, 40, 19), Color.rgb(14, 24, 11), Shader.TileMode.CLAMP));
        c.drawRect(0, 0, LW, HUD, fill);
        fill.setShader(null);
        String n0 = game.nameOf(0), n1 = game.nameOf(1);
        String info;
        if (game.spectator) {
            label(c, n0 + " " + game.score[0], 14, 31, 20, Color.rgb(255, 150, 140), bold, Paint.Align.LEFT);
            label(c, "v", LW / 2f, 31, 14, Color.rgb(160, 190, 150), regular, Paint.Align.CENTER);
            label(c, game.score[1] + " " + n1, LW - 14, 31, 20, Color.rgb(150, 190, 255), bold, Paint.Align.RIGHT);
            info = String.format(Locale.UK, "End %d%s  |  %s  |  %s %s, %s %s", game.endNo,
                    game.extraEnd ? " (extra)" : "", game.formatName(), n0, game.moodOf(0), n1, game.moodOf(1));
        } else {
            label(c, "LAWN BOWLS", 14, 31, 21, Color.rgb(160, 228, 130), bold, Paint.Align.LEFT);
            label(c, "You " + game.score[0] + "  -  " + game.score[1] + " " + n1, LW - 14, 31, 21,
                    Color.rgb(236, 244, 228), bold, Paint.Align.RIGHT);
            info = String.format(Locale.UK, "End %d%s  |  %s  |  bowls left: you %d, %s %d", game.endNo,
                    game.extraEnd ? " (extra)" : "", game.formatName(), game.left[0], n1, game.left[1]);
        }
        label(c, info, 14, 54, 12.5f, Color.rgb(170, 190, 160), regular, Paint.Align.LEFT);

        fill.setColor(Color.rgb(28, 48, 22));
        c.drawRoundRect(new RectF(10, 62, LW - 10, 134), 9, 9, fill);
        String m = game.msg;
        if (msgLayout == null || !m.equals(msgKey)) {
            tp.setTypeface(regular);
            tp.setTextSize(13.5f);
            tp.setTextAlign(Paint.Align.LEFT);
            msgLayout = new StaticLayout(m, tp, LW - 36, Layout.Alignment.ALIGN_NORMAL, 1.0f, 0f, false);
            msgKey = m;
        }
        tp.setColor(Color.rgb(255, 243, 176));
        tp.setTypeface(regular);
        tp.setTextSize(13.5f);
        c.save();
        c.translate(18, 68);
        c.clipRect(0, 0, LW - 36, 62);
        msgLayout.draw(c);
        c.restore();

        float bw = (LW - 20 - 18) / 4f;
        String[] labels = {game.spectator ? "Speed: " + (int) game.speed + "x" : "Curve: " + (game.side < 0 ? "Left" : "Right"),
                "Guide: " + (guide ? "On" : "Off"),
                "Sound: " + (sfx.enabled ? "On" : "Off"), "Menu"};
        int[] ids = {game.spectator ? B_SPEED : B_HAND, B_GUIDE, B_SOUND, B_MENU};
        for (int i = 0; i < 4; i++) button(c, ids[i], labels[i], 10 + i * (bw + 6), 142, bw, 40, false, true);
    }

    // ---- field ------------------------------------------------------------------------------------------
    void drawField(Canvas c) {
        c.drawBitmap(bg, null, new RectF(0, 0, W, H), bmpPaint);
        final Game g = game;

        // trails of this end's deliveries, like flattened grass
        stroke.setStrokeWidth(2.6f);
        for (int t = 0; t < g.trails.size(); t++) {
            Game.Trail tr = g.trails.get(t);
            if (tr.pts.size() < 2) continue;
            boolean live = t == g.trails.size() - 1;
            int a = live ? 70 : 38;
            stroke.setColor(tr.jack ? Color.argb(a, 255, 255, 255)
                    : (tr.owner == 0 ? Color.argb(a, 255, 190, 170) : Color.argb(a, 170, 205, 255)));
            Path path = new Path();
            float[] p0 = tr.pts.get(0);
            path.moveTo(p0[0], p0[1]);
            for (int i = 1; i < tr.pts.size(); i++) path.lineTo(tr.pts.get(i)[0], tr.pts.get(i)[1]);
            c.drawPath(path, stroke);
        }

        // rule markers while delivering
        if (g.state == Game.ST_JACK_AIM || g.state == Game.ST_JACK_ROLL || g.state == Game.ST_JACK_CPU) drawJackZone(c);
        else if (guide && (g.state == Game.ST_AIM || g.state == Game.ST_ROLLING || g.state == Game.ST_CPU_THINK))
            drawLine14(c);

        // pegs marking a live jack or a toucher in the ditch (Laws 14.4 and 18.2)
        for (Ball b : g.balls) {
            if (b.alive && b.y < Physics.RY0 && (b.jack || b.toucher)) {
                fill.setColor(Color.WHITE);
                c.drawRect((float) b.x - 2.5f, (float) (Physics.RY0 - Physics.DITCH) - 9, (float) b.x + 2.5f,
                        (float) (Physics.RY0 - Physics.DITCH) - 1, fill);
                if (b.jack) label(c, "LIVE JACK (Law 18)", (float) Math.max(70, Math.min(W - 70, b.x)),
                        (float) (Physics.RY0 - Physics.DITCH) - 14, 10.5f, Color.rgb(255, 236, 150), bold, Paint.Align.CENTER);
            }
        }

        boolean over = g.state == Game.ST_END_OVER || g.state == Game.ST_GAME_OVER;
        ArrayList<Ball> shots = over && g.result != null ? g.result.shotBowls : null;
        for (Ball b : g.balls) if (b.jack && b.alive) drawBall(c, b, false);
        for (Ball b : g.balls) if (!b.jack && b.alive) drawBall(c, b, shots != null && shots.contains(b));

        if (aiming && g.canAim()) drawAim(c);

        if (g.state == Game.ST_TOSS || g.state == Game.ST_TOSS_CHOICE) drawToss(c);
        if (over) drawMeasure(c);
        if (!g.banter.isEmpty() && !over) drawBubble(c);
    }

    /** A speech bubble above the green: what a computer player just said. */
    void drawBubble(Canvas c) {
        Game g = game;
        String text = g.banter;
        String key = g.banterWho + text;
        if (bubbleLayout == null || !key.equals(bubbleKey)) {
            tp.setTypeface(medium); tp.setTextSize(14f); tp.setTextAlign(Paint.Align.LEFT);
            bubbleLayout = new StaticLayout(text, tp, 250, Layout.Alignment.ALIGN_NORMAL, 1.0f, 0f, false);
            bubbleKey = key;
        }
        float tw = 0;
        for (int i = 0; i < bubbleLayout.getLineCount(); i++) tw = Math.max(tw, bubbleLayout.getLineWidth(i));
        float bw = tw + 24, bh = bubbleLayout.getHeight() + 34;
        boolean left = g.banterWho == 0;
        float bx = left ? 14 : W - 14 - bw, by = 8;
        int alpha = (int) (255 * Math.min(1.0, g.banterT / 0.5));
        fill.setColor(Color.argb(alpha * 240 / 255, 250, 248, 238));
        c.drawRoundRect(new RectF(bx, by, bx + bw, by + bh), 12, 12, fill);
        Path tail = new Path();
        float tx = left ? bx + 22 : bx + bw - 22;
        tail.moveTo(tx - 7, by + bh - 1); tail.lineTo(tx + 7, by + bh - 1); tail.lineTo(tx + (left ? 2 : -2), by + bh + 11);
        tail.close();
        c.drawPath(tail, fill);
        label(c, g.nameOf(g.banterWho), bx + 12, by + 17, 11, Color.argb(alpha, left ? 190 : 40, left ? 40 : 80, left ? 40 : 170), bold, Paint.Align.LEFT);
        tp.setColor(Color.argb(alpha, 40, 40, 36));
        tp.setTypeface(medium); tp.setTextSize(14f);
        c.save();
        c.translate(bx + 12, by + 23);
        bubbleLayout.draw(c);
        c.restore();
    }

    void dashed(Canvas c, float x1, float y1, float x2, float y2, int color, float w) {
        stroke.setColor(color);
        stroke.setStrokeWidth(w);
        stroke.setPathEffect(new DashPathEffect(new float[]{7, 6}, 0));
        c.drawLine(x1, y1, x2, y2, stroke);
        stroke.setPathEffect(null);
    }

    void drawJackZone(Canvas c) {
        float y23 = (float) (Physics.MAT_Y - Rules.JACK_MIN_M * Physics.M);
        float y2 = (float) (Physics.RY0 + Rules.JACK_SHORT_M * Physics.M);
        fill.setColor(Color.argb(34, 255, 224, 102));
        c.drawRect((float) Physics.RX0, (float) Physics.RY0, (float) Physics.RX1, y23, fill);
        dashed(c, (float) Physics.RX0, y23, (float) Physics.RX1, y23, Color.argb(210, 255, 224, 102), 1.5f);
        label(c, "23 m: the jack must stop beyond this line", (float) Physics.CX, y23 - 5, 10.5f,
                Color.argb(230, 255, 236, 150), medium, Paint.Align.CENTER);
        dashed(c, (float) Physics.RX0, y2, (float) Physics.RX1, y2, Color.argb(90, 255, 255, 255), 1f);
    }

    void drawLine14(Canvas c) {
        float y14 = (float) (Physics.MAT_Y - Rules.BOWL_DEAD_M * Physics.M);
        dashed(c, (float) Physics.RX0, y14, (float) Physics.RX1, y14, Color.argb(110, 255, 255, 255), 1.2f);
        label(c, "14 m: a bowl stopping short of this is dead", (float) Physics.CX, y14 + 12, 10f,
                Color.argb(150, 255, 255, 255), medium, Paint.Align.CENTER);
    }

    void drawAim(Canvas c) {
        boolean jack = game.isJackTurn();
        double[] a = Game.aim(aimX, aimY);
        int yel = Color.rgb(255, 230, 109);
        String key = (jack ? "j" : "b") + game.side + ":" + Math.round(a[0] * 1000) + ":" + Math.round(a[1]) + ":" + Physics.decel;
        if (!key.equals(guideKey)) {
            guidePath = game.guidePath(jack, a[0], a[1]);
            guideKey = key;
        }
        float[] end = guidePath.get(guidePath.size() - 1);
        double metres = (Physics.MAT_Y - end[1]) / Physics.M;
        int ringCol = yel;
        if (jack) ringCol = (metres >= Rules.JACK_MIN_M && end[1] >= Physics.RY0 && end[0] > Physics.RX0 && end[0] < Physics.RX1)
                ? Color.rgb(140, 235, 120) : Color.rgb(255, 110, 100);
        else if (metres < Rules.BOWL_DEAD_M) ringCol = Color.rgb(255, 110, 100);

        if (guide) {
            Path p = new Path();
            float[] f0 = guidePath.get(0);
            p.moveTo(f0[0], f0[1]);
            for (int i = 1; i < guidePath.size(); i++) p.lineTo(guidePath.get(i)[0], guidePath.get(i)[1]);
            stroke.setColor(Color.argb(235, 255, 230, 109));
            stroke.setStrokeWidth(2f);
            stroke.setPathEffect(new DashPathEffect(new float[]{5, 5}, (float) (clock * 24)));
            c.drawPath(p, stroke);
            stroke.setPathEffect(null);
            stroke.setColor(ringCol);
            stroke.setStrokeWidth(2f);
            c.drawCircle(end[0], end[1], jack ? (float) Physics.JACK_R + 3 : (float) Physics.BOWL_R, stroke);
            label(c, String.format(Locale.UK, "%.1f m", metres), end[0], end[1] + 26, 11.5f, ringCol, bold, Paint.Align.CENTER);
        } else {
            float dx = (float) Math.sin(a[0]), dy = (float) -Math.cos(a[0]);
            dashed(c, (float) Physics.CX, (float) Physics.MAT_Y, (float) Physics.CX + dx * 90, (float) Physics.MAT_Y + dy * 90, yel, 2f);
        }
        // the finger point, offset above the touch so it stays visible
        stroke.setColor(Color.argb(200, 255, 255, 255));
        stroke.setStrokeWidth(1.2f);
        float ax = (float) aimX, ay = (float) aimY;
        c.drawLine(ax - 7, ay, ax + 7, ay, stroke);
        c.drawLine(ax, ay - 7, ax, ay + 7, stroke);
    }

    void drawToss(Canvas c) {
        Game g = game;
        float cx = (float) Physics.CX, cy = 330;
        fill.setColor(Color.argb(150, 0, 0, 0));
        c.drawRoundRect(new RectF(70, 240, 390, 640), 16, 16, fill);
        label(c, "COIN TOSS", cx, 280, 18, Color.rgb(255, 224, 102), bold, Paint.Align.CENTER);
        label(c, g.spectator ? g.nameOf(0) + " calls HEADS" : "You call HEADS", cx, 305, 13, Color.rgb(230, 240, 220), regular, Paint.Align.CENTER);
        boolean settled = g.state == Game.ST_TOSS_CHOICE || g.tossT > 1.3;
        double t = Math.min(g.tossT, 1.3);
        float flip = settled ? 1f : (float) Math.abs(Math.cos(t * 14));
        float lift = settled ? 0f : (float) (Math.sin(t / 1.3 * Math.PI) * 60);
        c.save();
        c.translate(cx, cy + 70 - lift);
        c.scale(1f, Math.max(0.08f, flip));
        fill.setShader(new RadialGradient(-10, -12, 50, Color.rgb(255, 238, 150), Color.rgb(196, 148, 30), Shader.TileMode.CLAMP));
        c.drawCircle(0, 0, 38, fill);
        fill.setShader(null);
        stroke.setColor(Color.rgb(140, 100, 20));
        stroke.setStrokeWidth(2f);
        c.drawCircle(0, 0, 38, stroke);
        c.drawCircle(0, 0, 30, stroke);
        c.restore();
        if (settled) {
            label(c, g.tossPlayerWins ? "HEADS" : "TAILS", cx, cy + 74, 20, Color.rgb(120, 82, 12), bold, Paint.Align.CENTER);
            label(c, g.tossText, cx, cy + 142, 16, Color.WHITE, bold, Paint.Align.CENTER);
        }
        if (g.state == Game.ST_TOSS_CHOICE) {
            label(c, "Who delivers the jack and plays first?", cx, 500, 13.5f, Color.rgb(230, 240, 220), regular, Paint.Align.CENTER);
            button(c, T_ME, "I'll start", 80, 520 + HUD, 135, 46, true, true);
            button(c, T_CPU, g.nameOf(1) + " starts", 225, 520 + HUD, 155, 46, false, true);
        }
    }

    /** Magnified view of the head with the gaps in centimetres (Law 23.4: nearest points). */
    void drawMeasure(Canvas c) {
        Game g = game;
        Ball j = Rules.jack(g.balls);
        Rules.EndResult r = g.result;
        if (j == null || r == null) return;
        RectF box = new RectF(22, 350, 438, 660);
        fill.setColor(Color.argb(205, 8, 16, 6));
        c.drawRoundRect(box, 16, 16, fill);
        stroke.setColor(Color.argb(200, 255, 224, 102));
        stroke.setStrokeWidth(1.3f);
        c.drawRoundRect(box, 16, 16, stroke);
        label(c, g.state == Game.ST_GAME_OVER ? "FINAL END" : "MEASURING THE HEAD", box.centerX(), box.top + 26, 14,
                Color.rgb(255, 224, 102), bold, Paint.Align.CENTER);
        RectF win = new RectF(box.left + 12, box.top + 40, box.right - 12, box.bottom - 70);

        double maxDx = 12, maxDy = 12;
        for (Ball b : g.balls) {
            if (b.jack || !b.alive) continue;
            double dx = Math.abs(b.x - j.x), dy = Math.abs(b.y - j.y);
            if (dx < 150 && dy < 150) { maxDx = Math.max(maxDx, dx); maxDy = Math.max(maxDy, dy); }
        }
        float z = (float) Math.min(3.2, Math.min((win.width() / 2 - 14) / (maxDx + 14), (win.height() / 2 - 14) / (maxDy + 14)));
        z = Math.max(1f, z);

        c.save();
        c.clipRect(win);
        fill.setColor(Color.rgb(62, 132, 50));
        c.drawRect(win, fill);
        c.translate(win.centerX(), win.centerY());
        c.scale(z, z);
        c.translate((float) -j.x, (float) -j.y);
        drawBall(c, j, false);
        for (Ball b : g.balls) {
            if (!b.jack && b.alive && Math.abs(b.x - j.x) < 150 && Math.abs(b.y - j.y) < 150)
                drawBall(c, b, r.shotBowls.contains(b));
        }
        c.restore();

        // measuring lines to the nearest bowl of each side
        for (int o = 0; o < 2; o++) {
            Ball b = r.best[o];
            if (b == null || Math.abs(b.x - j.x) >= 150 || Math.abs(b.y - j.y) >= 150) continue;
            double d = Math.hypot(b.x - j.x, b.y - j.y);
            double ux = d < 1e-6 ? 0 : (b.x - j.x) / d, uy = d < 1e-6 ? -1 : (b.y - j.y) / d;
            float sx = win.centerX() + (float) ((j.x + ux * j.r - j.x) * z), sy = win.centerY() + (float) ((j.y + uy * j.r - j.y) * z);
            float ex = win.centerX() + (float) ((b.x - ux * b.r - j.x) * z), ey = win.centerY() + (float) ((b.y - uy * b.r - j.y) * z);
            int col = o == 0 ? Color.rgb(255, 160, 150) : Color.rgb(160, 200, 255);
            stroke.setColor(col);
            stroke.setStrokeWidth(1.6f);
            if (r.gap[o] > 0.8) c.drawLine(sx, sy, ex, ey, stroke);
            double cm = Math.max(0, r.gap[o]) * 6.1 / Physics.BOWL_R;
            label(c, String.format(Locale.UK, "%s %.1f cm", g.nameOf(o), cm),
                    (sx + ex) / 2 + (o == 0 ? -6 : 6), Math.min(win.bottom - 6, Math.max(win.top + 14, (sy + ey) / 2 - 6)),
                    11f, col, bold, o == 0 ? Paint.Align.RIGHT : Paint.Align.LEFT);
        }
        String line;
        if (r.owner >= 0) line = g.does(r.owner, "score", "scores") + " " + r.shots + (r.shots == 1 ? " shot" : " shots");
        else line = "No shot - end tied";
        label(c, line, box.centerX(), box.bottom - 40, 19, Color.WHITE, bold, Paint.Align.CENTER);
        label(c, g.state == Game.ST_GAME_OVER
                        ? (g.does(g.winner, "win", "wins") + " the match  ").toUpperCase(Locale.UK) + g.score[0] + "-" + g.score[1]
                        : (g.spectator ? "Next end shortly - tap to skip ahead" : "Tap anywhere to continue"),
                box.centerX(), box.bottom - 16, 13, g.state == Game.ST_GAME_OVER ? Color.rgb(255, 224, 102) : Color.rgb(200, 215, 190),
                g.state == Game.ST_GAME_OVER ? bold : regular, Paint.Align.CENTER);
    }

    // ---- menu and overlays ---------------------------------------------------------------------------------
    void drawMenu(Canvas c) {
        fill.setShader(new LinearGradient(0, 0, 0, LH, Color.rgb(34, 72, 28), Color.rgb(10, 20, 8), Shader.TileMode.CLAMP));
        c.drawRect(0, 0, LW, LH, fill);
        fill.setShader(null);
        // a little head: jack and three bowls
        c.save();
        c.translate(230, 118);
        c.scale(2.6f, 2.6f);
        Ball j = new Ball(); j.jack = true; j.r = Physics.JACK_R; j.owner = -1;
        drawBall(c, j, false);
        Ball b1 = new Ball(); b1.owner = 0; b1.r = Physics.BOWL_R; b1.x = -17; b1.y = 9; b1.mark = -1;
        Ball b2 = new Ball(); b2.owner = 1; b2.r = Physics.BOWL_R; b2.x = 19; b2.y = 5; b2.mark = 1;
        Ball b3 = new Ball(); b3.owner = 0; b3.r = Physics.BOWL_R; b3.x = 6; b3.y = -19; b3.mark = 1;
        drawBall(c, b1, false); drawBall(c, b2, false); drawBall(c, b3, false);
        c.restore();
        label(c, "LAWN BOWLS", LW / 2f, 232, 38, Color.rgb(176, 240, 146), bold, Paint.Align.CENTER);
        label(c, "Singles, played to the World Bowls laws", LW / 2f, 258, 13.5f, Color.rgb(190, 210, 180), regular, Paint.Align.CENTER);
        label(c, "Record  " + wins + " won  -  " + losses + " lost", LW / 2f, 284, 12.5f, Color.rgb(150, 175, 140), regular, Paint.Align.CENTER);

        float x = 50, w = LW - 100, h = 44, y = 308;
        if (game.inProgress()) { button(c, M_RESUME, "Resume match", x, y, w, h, true, true); y += h + 10; }
        button(c, M_NEW, "New match vs a random opponent", x, y, w, h, !game.inProgress(), true); y += h + 10;
        button(c, M_SPECTATE, "Spectate: AI v AI", x, y, w, h, false, true); y += h + 18;
        button(c, M_FORMAT, "Game: " + Game.FORMATS[game.format], x, y, w, h, false, true); y += h + 10;
        button(c, M_SKILL, "AI skill: " + Game.SKILLS[game.skill], x, y, w, h, false, true); y += h + 10;
        button(c, M_GREEN, "Green speed: " + Game.GREENS[game.green], x, y, w, h, false, true); y += h + 10;
        button(c, M_SOUND, "Sound: " + (sfx.enabled ? "On" : "Off"), x, y, w, h, false, true); y += h + 18;
        button(c, M_RULES, "Laws of bowls and how to play", x, y, w, h, false, true); y += h + 10;
        button(c, M_CARD, "Scorecard", x, y, w, h, false, !game.history.isEmpty()); y += h + 22;
        label(c, "Rules after the World Bowls Laws of the Sport of Bowls,", LW / 2f, y, 11, Color.rgb(130, 154, 122), regular, Paint.Align.CENTER);
        label(c, "Crystal Mark Fourth Edition. Green speed changes how far", LW / 2f, y + 15, 11, Color.rgb(130, 154, 122), regular, Paint.Align.CENTER);
        label(c, "bowls run and how much they curve.", LW / 2f, y + 30, 11, Color.rgb(130, 154, 122), regular, Paint.Align.CENTER);
    }

    static final String RULES_TEXT =
            "HOW TO PLAY\n"
            + "Drag on the green to aim and release to bowl. The distance of your finger sets the weight, the angle sets the line. "
            + "Bowls are biased: they curve towards the side of the small disc as they slow, so aim wide of the jack. "
            + "Flip hand with the Curve button. The dashed guide shows where a delivery would stop. "
            + "Release near the mat to cancel.\n\n"
            + "SPECTATOR MODE AND PERSONALITIES\n"
            + "Spectate: AI v AI lets two computer players bowl a whole match while you watch; use the Speed button for 2x or 4x. "
            + "Every computer player, including your opponent in a normal match, gets a random personality from a library of 14. "
            + "A personality changes how often they drive, how much they hate wasting a bowl, how steady their hand is, "
            + "the jack length they like, and how they talk. A good run makes them steadier; a lost end can rattle them.\n\n"
            + "LAWS IMPLEMENTED (World Bowls, Crystal Mark 4th edition, singles)\n\n"
            + "Start of play (Law 5)\n"
            + "A coin toss decides who delivers the jack and plays the first bowl, or passes that to the opponent (5.2). "
            + "The winner of a scoring end starts the next end (5.4). After a tied end the same player plays first (24.3, 24.4).\n\n"
            + "The jack (Laws 9, 10)\n"
            + "The mat sits on the centre line. A jack that stops in the ditch, outside the rink or less than 23 m from the mat line "
            + "is improperly delivered (10.1). The opponent re-delivers the jack but the first bowl stays with the original player (10.2). "
            + "If both fail, the jack is centred 2 m from the front ditch (10.3). A good jack is centred on the rink (C.1), "
            + "and one stopping within 2 m of the ditch is placed 2 m from it (9.2).\n\n"
            + "Touchers (Laws 14, 15)\n"
            + "A bowl that touches the jack in its original course is a toucher and gets a chalk mark. "
            + "A toucher that ends in the ditch stays live and is marked by a peg on the bank. "
            + "No bowl becomes a toucher by hitting a jack that is in the ditch (14.3).\n\n"
            + "Dead bowls (Law 17)\n"
            + "A bowl that is not a toucher is dead if it stops in the ditch, stops outside a side boundary, "
            + "or comes to rest less than 14 m from the mat line (17.1). A bowl that curves outside the boundary "
            + "but returns inside the rink is live (17.2). Dead bowls are removed.\n\n"
            + "Jack in the ditch (Law 18)\n"
            + "A jack driven into the front ditch within the side boundaries stays live, marked by a white peg (18.1, 18.2).\n\n"
            + "Dead jack and dead ends (Laws 19, 20)\n"
            + "A jack that goes outside the side boundary, or is driven back to under 20 m from the mat, is dead (19.1.3). "
            + "A dead end does not count and is replayed in the same direction by the same first player (19.4, 20.1).\n\n"
            + "Scoring (Laws 22 to 24)\n"
            + "After the last bowl, a shot is scored for each bowl of the side with the nearest bowl that is nearer than the "
            + "opponent's nearest (22.1). Distances are measured between the nearest points of the jack and bowl (23.4). "
            + "If the nearest bowls are equidistant or both touch the jack there is no shot and the end is tied (24.1).\n\n"
            + "Game formats (Laws 2.2, 26, 28)\n"
            + "A game is played to a set number of shots or ends. In a fixed number of ends, a drawn game goes to an extra end and "
            + "a coin toss decides who plays first (28). Play stops early when a win is no longer possible (26.3).\n\n"
            + "NOT SIMULATED\n"
            + "Foot faults and mat placement choices, time limits, trial ends, nominating touchers, bowls displaced by players or "
            + "neutral objects, marking and measuring disputes, declining the last bowl, and bowls falling onto the jack after "
            + "stopping.\n\n"
            + "Laws of the Sport of Bowls, Crystal Mark Fourth Edition (World Bowls, 2022). The full laws are available from your "
            + "national bowls authority and worldbowls.com.";

    void drawOverlay(Canvas c) {
        fill.setColor(Color.argb(238, 8, 16, 6));
        c.drawRect(0, 0, LW, LH, fill);
        String title = overlay == 1 ? "Laws of bowls" : "Scorecard";
        label(c, title, LW / 2f, 44, 22, Color.rgb(176, 240, 146), bold, Paint.Align.CENTER);
        StaticLayout lay;
        if (overlay == 1) {
            if (rulesLayout == null) {
                tp.setTypeface(regular); tp.setTextSize(12.5f); tp.setTextAlign(Paint.Align.LEFT);
                rulesLayout = new StaticLayout(RULES_TEXT, tp, LW - 44, Layout.Alignment.ALIGN_NORMAL, 1.12f, 0f, false);
            }
            lay = rulesLayout;
        } else {
            StringBuilder sb = new StringBuilder();
            sb.append("Format: ").append(game.formatName()).append("\n");
            if (game.ego[1] != null) {
                sb.append(game.spectator ? game.ego[0].title + " (red)\n" : "You (red)\n");
                sb.append("v ").append(game.ego[1].title).append(" (blue)\n");
                sb.append(game.ego[1].blurb).append("\n");
            }
            sb.append("Score: ").append(game.nameOf(0)).append(' ').append(game.score[0]).append(" - ").append(game.score[1])
                    .append(' ').append(game.nameOf(1)).append("\n\n");
            for (String h : game.history) sb.append(h).append('\n');
            if (game.deadEnds > 0) sb.append("\nDead ends replayed: ").append(game.deadEnds);
            String key = sb.toString();
            if (cardLayout == null || !key.equals(cardKey)) {
                tp.setTypeface(regular); tp.setTextSize(15f); tp.setTextAlign(Paint.Align.LEFT);
                cardLayout = new StaticLayout(key, tp, LW - 44, Layout.Alignment.ALIGN_NORMAL, 1.2f, 0f, false);
                cardKey = key;
            }
            lay = cardLayout;
        }
        float top = 64, bottom = LH - 78;
        float maxScroll = Math.max(0, lay.getHeight() - (bottom - top));
        overlayScroll = Math.max(0, Math.min(maxScroll, overlayScroll));
        c.save();
        c.clipRect(0, top, LW, bottom);
        c.translate(22, top - overlayScroll);
        tp.setColor(Color.rgb(226, 238, 216));
        tp.setTypeface(regular);
        tp.setTextSize(overlay == 1 ? 12.5f : 15f);
        lay.draw(c);
        c.restore();
        button(c, O_BACK, "Back", 130, LH - 62, LW - 260, 44, true, true);
    }

    // ---- input ---------------------------------------------------------------------------------------------
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        double lx = (e.getX() - offX) / scale, ly = (e.getY() - offY) / scale, fy = ly - HUD;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                for (int i = btns.size() - 1; i >= 0; i--) {
                    Btn b = btns.get(i);
                    if (b.r.contains((float) lx, (float) ly)) { sfx.play(Sfx.TICK, 0.5f); onButton(b.id); return true; }
                }
                if (overlay != 0) { lastTouchY = (float) ly; return true; }
                if (menu) return true;
                Game g = game;
                if (g.state == Game.ST_END_OVER || g.state == Game.ST_GAME_OVER) { g.tapContinue(); return true; }
                if (g.canAim() && !g.spectator && fy >= 0) { aiming = true; aimX = lx; aimY = fy - 50; }
                return true;
            }
            case MotionEvent.ACTION_MOVE:
                if (overlay != 0) { overlayScroll -= (float) ly - lastTouchY; lastTouchY = (float) ly; }
                else if (aiming) { aimX = lx; aimY = fy - 50; }
                return true;
            case MotionEvent.ACTION_UP:
                if (aiming && game.canAim() && fy < Physics.MAT_Y - 20) {
                    double[] a = Game.aim(aimX, aimY);
                    game.playerRelease(a[0], a[1]);
                    performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
                }
                aiming = false;
                return true;
            case MotionEvent.ACTION_CANCEL:
                aiming = false;
                return true;
            default:
                return true;
        }
    }

    private void onButton(int id) {
        switch (id) {
            case B_HAND: game.side = -game.side; break;
            case B_SPEED: game.speed = game.speed < 2 ? 2 : game.speed < 4 ? 4 : 1; break;
            case B_GUIDE: guide = !guide; break;
            case B_SOUND: case M_SOUND: sfx.enabled = !sfx.enabled; break;
            case B_MENU: menu = true; aiming = false; break;
            case M_RESUME: menu = false; break;
            case M_NEW: menu = false; aiming = false; overlay = 0; game.spectator = false; game.startMatch(); break;
            case M_SPECTATE: menu = false; aiming = false; overlay = 0; game.spectator = true; game.startMatch(); break;
            case M_FORMAT: game.format = (game.format + 1) % 3; break;
            case M_SKILL: game.skill = (game.skill + 1) % 3; break;
            case M_GREEN: game.green = (game.green + 1) % 3; Physics.setGreen(game.green); guideKey = ""; break;
            case M_RULES: overlay = 1; overlayScroll = 0; menu = false; break;
            case M_CARD: overlay = 2; overlayScroll = 0; menu = false; break;
            case O_BACK: overlay = 0; menu = true; break;
            case T_ME: game.chooseStarter(true); break;
            case T_CPU: game.chooseStarter(false); break;
            default: break;
        }
        savePrefs();
    }
}
