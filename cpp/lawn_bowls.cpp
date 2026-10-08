// Lawn Bowls - native Win32 (GDI) version, single file, no dependencies.
//
// Build (MinGW-w64):  g++ -O2 -std=c++17 -mwindows lawn_bowls.cpp -o lawn_bowls.exe -lgdi32 -luser32
// Build (MSVC):       cl /EHsc /O2 /std:c++17 lawn_bowls.cpp user32.lib gdi32.lib /link /SUBSYSTEM:WINDOWS
//
// Controls
//   Mouse click   bowl towards the clicked spot (distance = weight, angle = line)
//   Left / Right  slide the mat        F  flip hand (curve left / right)
//   G  toggle guide line               S  cycle CPU skill
//   Enter / Space continue after an end
//
// Each end both sides roll four bowls towards the jack. Bowls are biased and curve
// as they slow down - aim off the line! The closest bowl scores one shot for every
// bowl nearer than the opponent's best. First to 7 shots wins.

#ifndef NOMINMAX
#define NOMINMAX
#endif
#define WIN32_LEAN_AND_MEAN
#include <windows.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <random>
#include <string>
#include <vector>

// ----------------------------------------------------------------- geometry
static const int    W = 460, H = 780, PANEL = 250, LW = W + PANEL, LH = H;
static const double RX0 = 90, RX1 = 370, RY0 = 70, RY1 = 745;
static const double CX = (RX0 + RX1) / 2;
static const double BOWL_Y = 695, BOWL_R = 11, JACK_R = 6;

// ------------------------------------------------------------------ physics
static const double DT = 1.0 / 60.0, DECEL = 200.0, BIAS_K = 0.38;
static const double MIN_V = 120.0, MAX_V = 620.0, RESTITUTION = 0.85;
static const int    MAX_STEPS = 700, TARGET = 7, BOWLS_EACH = 4;

static const COLORREF PLAYER_COL = RGB(214, 40, 40), PLAYER_LIGHT = RGB(255, 138, 138);
static const COLORREF CPU_COL = RGB(29, 53, 87), CPU_LIGHT = RGB(127, 163, 217);

static const char*  SKILL_NAMES[3] = {"Easy", "Medium", "Hard"};
static const double SKILL_TH[3] = {0.035, 0.016, 0.006};   // aim error (radians)
static const double SKILL_V[3] = {0.07, 0.035, 0.014};     // weight error (fraction)

static std::mt19937 rng{std::random_device{}()};
static double uniform(double a, double b) { return std::uniform_real_distribution<double>(a, b)(rng); }
static double gauss(double s) { return std::normal_distribution<double>(0.0, s)(rng); }

struct Ball {
    double x = 0, y = 0, vx = 0, vy = 0, r = 0, m = 1;
    int owner = 0;        // 0 = you, 1 = CPU, -1 = jack
    bool jack = false;
    int side = 0;         // active bias: -1 curves left, +1 right, 0 none
    int mark = 0;         // bias shown on the bowl's face
    bool alive = true;
};

static bool stepWorld(std::vector<Ball>& balls, double dt) {
    bool moving = false;
    for (auto& b : balls) {
        if (!b.alive || (b.vx == 0 && b.vy == 0)) continue;
        double sp0 = std::hypot(b.vx, b.vy);
        double sp = sp0 - DECEL * dt;
        if (sp <= 4) { b.vx = b.vy = 0; continue; }
        if (b.side) {
            double th = std::atan2(b.vx, -b.vy) + b.side * BIAS_K / (sp / 100.0 + 0.6) * dt;
            b.vx = sp * std::sin(th);
            b.vy = -sp * std::cos(th);
        } else {
            double k = sp / sp0;
            b.vx *= k; b.vy *= k;
        }
        b.x += b.vx * dt;
        b.y += b.vy * dt;
        moving = true;
        if (b.jack) {
            if (b.x < RX0 + b.r) { b.x = RX0 + b.r; b.vx = std::fabs(b.vx) * 0.5; }
            else if (b.x > RX1 - b.r) { b.x = RX1 - b.r; b.vx = -std::fabs(b.vx) * 0.5; }
            if (b.y < RY0 + b.r) { b.y = RY0 + b.r; b.vx = b.vy = 0; }
        } else if (b.x < RX0 || b.x > RX1 || b.y < RY0) {
            b.alive = false;   // dead bowl
            b.vx = b.vy = 0;
        }
    }
    size_t n = balls.size();
    for (size_t i = 0; i < n; ++i) {
        Ball& a = balls[i];
        if (!a.alive) continue;
        for (size_t j = i + 1; j < n; ++j) {
            Ball& c = balls[j];
            if (!c.alive) continue;
            if (a.vx == 0 && a.vy == 0 && c.vx == 0 && c.vy == 0) continue;
            double dx = c.x - a.x, dy = c.y - a.y, rr = a.r + c.r;
            if (dx * dx + dy * dy >= rr * rr) continue;
            double d = std::hypot(dx, dy), nx, ny;
            if (d < 1e-6) { nx = 0; ny = -1; d = 1e-6; } else { nx = dx / d; ny = dy / d; }
            double overlap = rr - d, tot = a.m + c.m;
            a.x -= nx * overlap * c.m / tot; a.y -= ny * overlap * c.m / tot;
            c.x += nx * overlap * a.m / tot; c.y += ny * overlap * a.m / tot;
            double rel = (c.vx - a.vx) * nx + (c.vy - a.vy) * ny;
            if (rel < 0) {
                double imp = -(1 + RESTITUTION) * rel / (1 / a.m + 1 / c.m);
                a.vx -= imp * nx / a.m; a.vy -= imp * ny / a.m;
                c.vx += imp * nx / c.m; c.vy += imp * ny / c.m;
            }
            a.side = c.side = 0;   // collisions knock the bias out of a bowl
            moving = true;
        }
    }
    return moving;
}

// Roll one bowl through a copy of the world. The new bowl is world.back().
static void simulate(const std::vector<Ball>& balls, int owner, double x0, int side, double theta,
                     double v, std::vector<Ball>& world, std::vector<POINT>* path = nullptr) {
    world.clear();
    for (auto& b : balls) if (b.alive) world.push_back(b);
    Ball nb;
    nb.x = x0; nb.y = BOWL_Y; nb.vx = v * std::sin(theta); nb.vy = -v * std::cos(theta);
    nb.r = BOWL_R; nb.m = 1.0; nb.owner = owner; nb.side = nb.mark = side;
    world.push_back(nb);
    size_t idx = world.size() - 1;
    if (path) { path->clear(); path->push_back({(LONG)nb.x, (LONG)nb.y}); }
    for (int i = 0; i < MAX_STEPS; ++i) {
        bool moving = stepWorld(world, DT);
        if (path && i % 4 == 0) path->push_back({(LONG)world[idx].x, (LONG)world[idx].y});
        if (!moving || (path && !world[idx].alive)) break;
    }
    if (path) path->push_back({(LONG)world[idx].x, (LONG)world[idx].y});
}

static const Ball* findJack(const std::vector<Ball>& balls) {
    for (auto& b : balls) if (b.jack) return &b;
    return nullptr;
}

static std::vector<const Ball*> rankedBowls(const std::vector<Ball>& balls) {
    std::vector<const Ball*> live;
    const Ball* j = findJack(balls);
    if (!j) return live;
    for (auto& b : balls) if (b.alive && !b.jack) live.push_back(&b);
    std::sort(live.begin(), live.end(), [j](const Ball* a, const Ball* b) {
        return std::hypot(a->x - j->x, a->y - j->y) < std::hypot(b->x - j->x, b->y - j->y);
    });
    return live;
}

// Returns owner of the nearest bowl and the number of shots; owner -1 = no score.
static void endScore(const std::vector<Ball>& balls, int& owner, int& n) {
    auto live = rankedBowls(balls);
    owner = -1; n = 0;
    if (live.empty()) return;
    owner = live[0]->owner;
    for (auto* b : live) { if (b->owner != owner) break; ++n; }
}

static double evaluateForCpu(const std::vector<Ball>& world) {
    const Ball* j = findJack(world);
    int owner, n;
    endScore(world, owner, n);
    double val = owner < 0 ? 0.0 : (owner == 1 ? 1000.0 * n : -1000.0 * n);
    double best[2] = {400.0, 400.0};
    for (auto& b : world) {
        if (b.alive && !b.jack) {
            double d = std::hypot(b.x - j->x, b.y - j->y);
            if (d < best[b.owner]) best[b.owner] = d;
        }
    }
    return val - best[1] + 0.3 * best[0];
}

struct Shot { int side; double th, v; };

static Shot cpuPickShot(const std::vector<Ball>& balls, double x0) {
    const Ball* j = findJack(balls);
    double dj = std::hypot(j->x - x0, j->y - BOWL_Y);
    std::vector<double> speeds;
    for (double f : {0.85, 0.95, 1.02, 1.1, 1.25}) speeds.push_back(std::sqrt(2 * DECEL * dj * f));
    speeds.push_back(480.0); speeds.push_back(600.0);

    std::vector<Ball> world;
    auto score = [&](int side, double th, double v) {
        v = std::max(MIN_V, std::min(MAX_V, v));
        simulate(balls, 1, x0, side, th, v, world);
        return evaluateForCpu(world);
    };
    double bestVal = -1e18;
    Shot best{-1, 0, 300};
    for (int side : {-1, 1})
        for (int ai = -6; ai <= 6; ++ai)
            for (double v : speeds) {
                double th = ai * 0.05, val = score(side, th, v);
                if (val > bestVal) { bestVal = val; best = {side, th, v}; }
            }
    Shot c = best;
    for (int dth = -4; dth <= 4; ++dth)
        for (int dv = -3; dv <= 3; ++dv) {
            double th = c.th + dth * 0.01, v = c.v * (1 + dv * 0.02);
            double val = score(c.side, th, v);
            if (val > bestVal) { bestVal = val; best = {c.side, th, v}; }
        }
    return best;
}

// --------------------------------------------------------------------- game
enum State { S_JACK_WAIT, S_JACK_ROLL, S_AIM, S_CPU_THINK, S_ROLLING, S_END_OVER, S_GAME_OVER };

static std::vector<Ball> g_balls;
static State g_state = S_JACK_WAIT;
static int g_score[2], g_left[2], g_endNo = 1, g_starter = 0, g_turn = 0, g_side = -1, g_skill = 1;
static int g_lastBowl = -1;
static double g_matX = CX, g_playerMat = CX, g_acc = 0, g_wait = 0, g_scale = 1.0;
static bool g_guide = true;
static std::string g_msg, g_note;
static POINT g_mouse = {(LONG)CX, 300};

static void say(const std::string& s) { g_msg = s; }

static void startTurn();
static void newEnd() {
    g_balls.clear();
    g_left[0] = g_left[1] = BOWLS_EACH;
    g_turn = g_starter;
    g_lastBowl = -1;
    g_note.clear();
    g_matX = g_playerMat;
    g_state = S_JACK_WAIT;
    g_wait = 0.5;
    say("Rolling the jack...");
}

static void newGame() {
    g_score[0] = g_score[1] = 0;
    g_endNo = 1; g_starter = 0; g_side = -1;
    g_playerMat = g_matX = CX;
    newEnd();
}

static void rollJack() {
    double d = uniform(380, 500), v = std::sqrt(2 * DECEL * d);
    Ball j;
    j.x = CX; j.y = BOWL_Y; j.vy = -v; j.r = JACK_R; j.m = 0.5; j.owner = -1; j.jack = true;
    g_balls.push_back(j);
    g_acc = 0;
    g_state = S_JACK_ROLL;
}

static void deliver(int owner, double x0, int side, double theta, double v) {
    v = std::max(MIN_V, std::min(MAX_V, v));
    Ball b;
    b.x = x0; b.y = BOWL_Y; b.vx = v * std::sin(theta); b.vy = -v * std::cos(theta);
    b.r = BOWL_R; b.m = 1.0; b.owner = owner; b.side = b.mark = side;
    g_balls.push_back(b);
    g_lastBowl = (int)g_balls.size() - 1;
    g_left[owner]--;
    g_acc = 0;
    g_state = S_ROLLING;
    say("Rolling...");
}

static void finishEnd() {
    int owner, n;
    endScore(g_balls, owner, n);
    std::string text;
    if (owner < 0) {
        text = "No bowls in play - no score this end.";
    } else {
        g_score[owner] += n;
        g_starter = owner;
        text = std::string(owner == 0 ? "You score " : "CPU scores ") + std::to_string(n) +
               (n == 1 ? " shot!" : " shots!");
    }
    if (std::max(g_score[0], g_score[1]) >= TARGET) {
        g_state = S_GAME_OVER;
        say(text + "\n" + (g_score[0] > g_score[1] ? "You win the match!" : "CPU wins the match."));
    } else {
        g_state = S_END_OVER;
        say(text + "\nPress Enter or click to play the next end.");
    }
}

static void startTurn() {
    if (g_left[0] == 0 && g_left[1] == 0) { finishEnd(); return; }
    if (g_turn == 0) {
        g_matX = g_playerMat;
        g_state = S_AIM;
        say(g_note + "Your bowl - click where to send it.");
    } else {
        g_state = S_CPU_THINK;
        g_wait = 0.7;
        say(g_note + "CPU is lining up its bowl...");
    }
    g_note.clear();
}

static void rollFinished() {
    if (g_state == S_JACK_ROLL) {
        for (auto& b : g_balls) if (b.jack) b.x = CX;   // the jack is centred
        say("Jack is set.");
        startTurn();
        return;
    }
    if (g_lastBowl >= 0 && !g_balls[g_lastBowl].alive)
        g_note = std::string(g_balls[g_lastBowl].owner == 0 ? "Your" : "CPU's") +
                 " bowl went out - dead bowl.\n";
    g_balls.erase(std::remove_if(g_balls.begin(), g_balls.end(), [](const Ball& b) { return !b.alive; }),
                  g_balls.end());
    g_lastBowl = -1;
    g_turn = 1 - g_turn;
    startTurn();
}

static void cpuPlay() {
    static const double mats[3] = {CX - 45, CX, CX + 45};
    g_matX = mats[std::uniform_int_distribution<int>(0, 2)(rng)];
    Shot s = cpuPickShot(g_balls, g_matX);
    double th = s.th + gauss(SKILL_TH[g_skill]);
    double v = s.v * (1 + gauss(SKILL_V[g_skill]));
    deliver(1, g_matX, s.side, th, v);
}

static void continueGame() {
    if (g_state == S_END_OVER) { ++g_endNo; newEnd(); }
    else if (g_state == S_GAME_OVER) newGame();
}

static void tick(double dt) {
    if (g_state == S_JACK_WAIT) {
        g_wait -= dt;
        if (g_wait <= 0) rollJack();
    } else if (g_state == S_CPU_THINK) {
        g_wait -= dt;
        if (g_wait <= 0) cpuPlay();
    } else if (g_state == S_JACK_ROLL || g_state == S_ROLLING) {
        g_acc += dt;
        while (g_acc >= DT) {
            g_acc -= DT;
            if (!stepWorld(g_balls, DT)) { rollFinished(); break; }
        }
    }
}

static void aimParams(int mx, int my, double& theta, double& v) {
    double dx = mx - g_matX, dy = std::max(30.0, BOWL_Y - my);
    theta = std::max(-0.6, std::min(0.6, std::atan2(dx, dy)));
    v = std::max(MIN_V, std::min(MAX_V, std::sqrt(2 * DECEL * std::hypot(dx, dy))));
}

// ------------------------------------------------------------------ drawing
static const COLORREF CLR_NONE = 0xFFFFFFFF;   // "no outline"

struct Btn { int l, t, r, b; };
static const int PX = W + 16;
static const Btn BTN_HAND{PX, 268, LW - 16, 298}, BTN_GUIDE{PX, 308, LW - 16, 334},
    BTN_SKILL{PX, 342, LW - 16, 372}, BTN_CONT{PX, 390, LW - 16, 422}, BTN_NEW{PX, 430, LW - 16, 462};
static bool inBtn(const Btn& b, int x, int y) { return x >= b.l && x < b.r && y >= b.t && y < b.b; }

static HFONT fTitle, fBig, fNorm, fSmall, fBold, fTiny;
static HDC g_mem = nullptr;
static HBITMAP g_bmp = nullptr, g_oldBmp = nullptr;

static HFONT mkFont(int h, int weight) {
    return CreateFontA(-h, 0, 0, 0, weight, 0, 0, 0, DEFAULT_CHARSET, 0, 0, CLEARTYPE_QUALITY, 0, "Segoe UI");
}

static void circ(HDC dc, double x, double y, double r, COLORREF fill, COLORREF line, int lw = 1, bool hollow = false) {
    HPEN pen = line == CLR_NONE ? (HPEN)GetStockObject(NULL_PEN) : CreatePen(PS_SOLID, lw, line);
    HBRUSH br = hollow ? (HBRUSH)GetStockObject(NULL_BRUSH) : CreateSolidBrush(fill);
    HGDIOBJ op = SelectObject(dc, pen), ob = SelectObject(dc, br);
    Ellipse(dc, (int)std::lround(x - r), (int)std::lround(y - r), (int)std::lround(x + r) + 1, (int)std::lround(y + r) + 1);
    SelectObject(dc, op); SelectObject(dc, ob);
    if (line != CLR_NONE) DeleteObject(pen);
    if (!hollow) DeleteObject(br);
}

static void rect(HDC dc, int l, int t, int r, int b, COLORREF c) {
    HBRUSH br = CreateSolidBrush(c);
    RECT rc{l, t, r, b};
    FillRect(dc, &rc, br);
    DeleteObject(br);
}

static void line(HDC dc, double x1, double y1, double x2, double y2, COLORREF c, int w = 1, int style = PS_SOLID) {
    HPEN pen = CreatePen(style, w, c);
    HGDIOBJ op = SelectObject(dc, pen);
    MoveToEx(dc, (int)std::lround(x1), (int)std::lround(y1), nullptr);
    LineTo(dc, (int)std::lround(x2), (int)std::lround(y2));
    SelectObject(dc, op);
    DeleteObject(pen);
}

static void text(HDC dc, HFONT f, COLORREF c, const std::string& s, RECT r, UINT fmt = DT_LEFT | DT_TOP | DT_WORDBREAK) {
    SelectObject(dc, f);
    SetTextColor(dc, c);
    DrawTextA(dc, s.c_str(), (int)s.size(), &r, fmt | DT_NOPREFIX);
}

static void button(HDC dc, const Btn& b, const std::string& label, bool enabled = true, bool hot = false) {
    HBRUSH br = CreateSolidBrush(enabled ? (hot ? RGB(62, 100, 48) : RGB(46, 74, 36)) : RGB(32, 40, 30));
    HPEN pen = CreatePen(PS_SOLID, 1, enabled ? RGB(110, 160, 90) : RGB(60, 70, 56));
    HGDIOBJ op = SelectObject(dc, pen), ob = SelectObject(dc, br);
    RoundRect(dc, b.l, b.t, b.r, b.b, 8, 8);
    SelectObject(dc, op); SelectObject(dc, ob);
    DeleteObject(pen); DeleteObject(br);
    text(dc, fBold, enabled ? RGB(232, 240, 224) : RGB(110, 120, 104), label, RECT{b.l, b.t, b.r, b.b},
         DT_CENTER | DT_VCENTER | DT_SINGLELINE);
}

static void drawBackground(HDC dc) {
    rect(dc, 0, 0, W, H, RGB(31, 61, 26));
    rect(dc, (int)RX0 - 10, (int)RY0 - 38, (int)RX1 + 10, (int)RY0, RGB(75, 58, 38));
    text(dc, fSmall, RGB(138, 115, 82), "D I T C H", RECT{(int)RX0, (int)RY0 - 38, (int)RX1, (int)RY0},
         DT_CENTER | DT_VCENTER | DT_SINGLELINE);
    int i = 0;
    for (int y = (int)RY0; y < (int)RY1; y += 36, ++i)
        rect(dc, (int)RX0, y, (int)RX1, std::min(y + 36, (int)RY1), i % 2 ? RGB(82, 165, 68) : RGB(74, 154, 60));
    for (int y = (int)RY0; y < (int)RY1; y += 12) line(dc, CX, y, CX, y + 2, RGB(95, 179, 81));
    COLORREF white = RGB(242, 242, 242);
    line(dc, RX0, RY0, RX0, RY1, white, 3);
    line(dc, RX1, RY0, RX1, RY1, white, 3);
    line(dc, RX0, RY0, RX1, RY0, white, 3);
    for (double f : {0.25, 0.5, 0.75}) {
        double yy = RY0 + (BOWL_Y - RY0) * f;
        line(dc, RX0 - 6, yy, RX0 + 6, yy, white, 2);
        line(dc, RX1 - 6, yy, RX1 + 6, yy, white, 2);
    }
}

static void drawBall(HDC dc, const Ball& b, bool ring) {
    COLORREF shadow = RGB(47, 107, 38);
    circ(dc, b.x + 2, b.y + 3, b.r, shadow, CLR_NONE);
    if (b.jack) { circ(dc, b.x, b.y, b.r, RGB(247, 247, 240), RGB(119, 119, 119)); return; }
    COLORREF col = b.owner == 0 ? PLAYER_COL : CPU_COL, light = b.owner == 0 ? PLAYER_LIGHT : CPU_LIGHT;
    circ(dc, b.x, b.y, b.r, col, RGB(16, 16, 16));
    circ(dc, b.x, b.y, b.r * 0.6, 0, light, 1, true);
    if (b.mark) circ(dc, b.x + b.mark * b.r * 0.62, b.y, 2, RGB(255, 255, 255), CLR_NONE);
    if (ring) circ(dc, b.x, b.y, b.r + 4, 0, RGB(255, 230, 109), 2, true);
}

static void drawPanel(HDC dc) {
    rect(dc, W, 0, LW, H, RGB(20, 33, 15));
    text(dc, fTitle, RGB(155, 227, 127), "LAWN BOWLS", RECT{PX, 14, LW - 10, 46}, DT_LEFT | DT_SINGLELINE);
    char buf[128];
    std::snprintf(buf, sizeof buf, "You %d  -  %d CPU", g_score[0], g_score[1]);
    text(dc, fBig, RGB(232, 240, 224), buf, RECT{PX, 56, LW - 10, 84}, DT_LEFT | DT_SINGLELINE);
    std::snprintf(buf, sizeof buf, "End %d  |  first to %d\nBowls left:  you %d,  CPU %d", g_endNo, TARGET,
                  g_left[0], g_left[1]);
    text(dc, fNorm, RGB(169, 189, 158), buf, RECT{PX, 90, LW - 10, 130});

    rect(dc, PX, 138, LW - 16, 252, RGB(31, 51, 24));
    text(dc, fNorm, RGB(255, 243, 176), g_msg, RECT{PX + 8, 146, LW - 24, 248});

    POINT p = g_mouse;  // hover uses logical panel coordinates
    button(dc, BTN_HAND, g_side < 0 ? "Bowl curves: LEFT  (F)" : "Bowl curves: RIGHT  (F)", true, inBtn(BTN_HAND, p.x, p.y));
    button(dc, BTN_GUIDE, g_guide ? "[x] Show guide line (G)" : "[  ] Show guide line (G)", true, inBtn(BTN_GUIDE, p.x, p.y));
    button(dc, BTN_SKILL, std::string("CPU skill: ") + SKILL_NAMES[g_skill] + "  (S)", true, inBtn(BTN_SKILL, p.x, p.y));
    bool canCont = g_state == S_END_OVER || g_state == S_GAME_OVER;
    button(dc, BTN_CONT, g_state == S_GAME_OVER ? "New game" : "Next end", canCont, canCont && inBtn(BTN_CONT, p.x, p.y));
    button(dc, BTN_NEW, "Restart match", true, inBtn(BTN_NEW, p.x, p.y));
    text(dc, fTiny, RGB(127, 150, 116),
         "Click where you want the bowl to go.\nBowls curve towards their weighted\nside as they slow - aim off the line!\n"
         "Arrow keys slide the mat.",
         RECT{PX, 486, LW - 10, 580});
}

static void render(HDC dc) {
    SetBkMode(dc, TRANSPARENT);
    drawBackground(dc);

    rect(dc, (int)g_matX - 24, (int)BOWL_Y - 6, (int)g_matX + 24, (int)BOWL_Y + 36, RGB(176, 176, 176));
    rect(dc, (int)g_matX - 22, (int)BOWL_Y - 4, (int)g_matX + 22, (int)BOWL_Y + 34, RGB(38, 38, 38));
    text(dc, fTiny, RGB(119, 119, 119), "MAT", RECT{(int)g_matX - 24, (int)BOWL_Y - 6, (int)g_matX + 24, (int)BOWL_Y + 36},
         DT_CENTER | DT_VCENTER | DT_SINGLELINE);

    std::vector<const Ball*> shots;
    if (g_state == S_END_OVER || g_state == S_GAME_OVER) {
        int owner, n;
        endScore(g_balls, owner, n);
        auto r = rankedBowls(g_balls);
        for (int i = 0; i < n && i < (int)r.size(); ++i) shots.push_back(r[i]);
    }
    for (auto& b : g_balls) if (b.jack && b.alive) drawBall(dc, b, false);
    for (auto& b : g_balls)
        if (!b.jack && b.alive) drawBall(dc, b, std::find(shots.begin(), shots.end(), &b) != shots.end());

    if (g_state == S_AIM) {
        int mx = g_mouse.x, my = g_mouse.y;
        double theta, v;
        aimParams(mx, my, theta, v);
        COLORREF yel = RGB(255, 230, 109);
        if (g_guide) {
            static std::vector<POINT> path;
            static double kx = -1, kth = 0, kv = 0; static int kside = 0;
            if (kx != g_matX || kside != g_side || kth != theta || kv != v) {
                std::vector<Ball> world;
                simulate({}, 0, g_matX, g_side, theta, v, world, &path);
                kx = g_matX; kside = g_side; kth = theta; kv = v;
            }
            for (size_t i = 0; i + 1 < path.size(); i += 2)   // dashed
                line(dc, path[i].x, path[i].y, path[i + 1].x, path[i + 1].y, yel, 2);
            if (!path.empty()) circ(dc, path.back().x, path.back().y, BOWL_R, 0, yel, 2, true);
        } else {
            for (int d = 0; d < 80; d += 8)
                line(dc, g_matX + d * std::sin(theta), BOWL_Y - d * std::cos(theta),
                     g_matX + (d + 4) * std::sin(theta), BOWL_Y - (d + 4) * std::cos(theta), yel, 2);
        }
        if (mx < W) { line(dc, mx - 7, my, mx + 7, my, RGB(255, 255, 255)); line(dc, mx, my - 7, mx, my + 7, RGB(255, 255, 255)); }
    }
    drawPanel(dc);
}

// ------------------------------------------------------------------- window
static void ensureBackbuffer(HWND hwnd) {
    if (g_mem) return;
    HDC wdc = GetDC(hwnd);
    g_mem = CreateCompatibleDC(wdc);
    g_bmp = CreateCompatibleBitmap(wdc, LW, LH);
    g_oldBmp = (HBITMAP)SelectObject(g_mem, g_bmp);
    ReleaseDC(hwnd, wdc);
}

static void handleClick(int x, int y) {
    if (x < W) {
        if (g_state == S_AIM) {
            double theta, v;
            aimParams(x, y, theta, v);
            deliver(0, g_matX, g_side, theta, v);
        } else if (g_state == S_END_OVER || g_state == S_GAME_OVER) {
            continueGame();
        }
        return;
    }
    if (inBtn(BTN_HAND, x, y)) g_side = -g_side;
    else if (inBtn(BTN_GUIDE, x, y)) g_guide = !g_guide;
    else if (inBtn(BTN_SKILL, x, y)) g_skill = (g_skill + 1) % 3;
    else if (inBtn(BTN_CONT, x, y)) continueGame();
    else if (inBtn(BTN_NEW, x, y)) newGame();
}

static void moveMat(double dx) {
    if (g_state == S_AIM) {
        g_playerMat = std::max(RX0 + 28, std::min(RX1 - 28, g_playerMat + dx));
        g_matX = g_playerMat;
    }
}

static LRESULT CALLBACK WndProc(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp) {
    static auto last = std::chrono::steady_clock::now();
    switch (msg) {
    case WM_CREATE:
        fTitle = mkFont(24, FW_BOLD); fBig = mkFont(21, FW_BOLD); fNorm = mkFont(15, FW_NORMAL);
        fSmall = mkFont(13, FW_BOLD); fBold = mkFont(14, FW_BOLD); fTiny = mkFont(12, FW_NORMAL);
        SetTimer(hwnd, 1, 16, nullptr);
        last = std::chrono::steady_clock::now();
        return 0;
    case WM_TIMER: {
        auto now = std::chrono::steady_clock::now();
        double dt = std::min(0.05, std::chrono::duration<double>(now - last).count());
        last = now;
        tick(dt);
        InvalidateRect(hwnd, nullptr, FALSE);
        return 0;
    }
    case WM_ERASEBKGND:
        return 1;
    case WM_PAINT: {
        PAINTSTRUCT ps;
        HDC dc = BeginPaint(hwnd, &ps);
        ensureBackbuffer(hwnd);
        render(g_mem);
        RECT rc;
        GetClientRect(hwnd, &rc);
        if (rc.right == LW && rc.bottom == LH) {
            BitBlt(dc, 0, 0, LW, LH, g_mem, 0, 0, SRCCOPY);
        } else {
            SetStretchBltMode(dc, HALFTONE);
            SetBrushOrgEx(dc, 0, 0, nullptr);
            StretchBlt(dc, 0, 0, rc.right, rc.bottom, g_mem, 0, 0, LW, LH, SRCCOPY);
        }
        EndPaint(hwnd, &ps);
        return 0;
    }
    case WM_MOUSEMOVE:
        g_mouse = {(LONG)(LOWORD(lp) / g_scale), (LONG)(HIWORD(lp) / g_scale)};
        return 0;
    case WM_LBUTTONDOWN:
        handleClick((int)(LOWORD(lp) / g_scale), (int)(HIWORD(lp) / g_scale));
        return 0;
    case WM_KEYDOWN:
        switch (wp) {
        case VK_LEFT: moveMat(-10); break;
        case VK_RIGHT: moveMat(10); break;
        case 'F': g_side = -g_side; break;
        case 'G': g_guide = !g_guide; break;
        case 'S': g_skill = (g_skill + 1) % 3; break;
        case VK_RETURN: case VK_SPACE: continueGame(); break;
        case VK_ESCAPE: DestroyWindow(hwnd); break;
        }
        return 0;
    case WM_DESTROY:
        KillTimer(hwnd, 1);
        PostQuitMessage(0);
        return 0;
    }
    return DefWindowProcA(hwnd, msg, wp, lp);
}

int WINAPI WinMain(HINSTANCE hInst, HINSTANCE, LPSTR, int show) {
    WNDCLASSA wc{};
    wc.lpfnWndProc = WndProc;
    wc.hInstance = hInst;
    wc.hCursor = LoadCursor(nullptr, IDC_CROSS);
    wc.hIcon = LoadIcon(nullptr, IDI_APPLICATION);
    wc.lpszClassName = "LawnBowlsWnd";
    RegisterClassA(&wc);

    DWORD style = WS_OVERLAPPED | WS_CAPTION | WS_SYSMENU | WS_MINIMIZEBOX;
    RECT wa, nc{0, 0, 0, 0};
    SystemParametersInfoA(SPI_GETWORKAREA, 0, &wa, 0);
    AdjustWindowRect(&nc, style, FALSE);
    int ncW = nc.right - nc.left, ncH = nc.bottom - nc.top;
    // Shrink the whole view to fit small screens.
    g_scale = std::min(1.0, std::min((double)(wa.right - wa.left - ncW) / LW, (double)(wa.bottom - wa.top - ncH) / LH));
    int cw = (int)(LW * g_scale), ch = (int)(LH * g_scale);

    newGame();
    HWND hwnd = CreateWindowA("LawnBowlsWnd", "Lawn Bowls", style, CW_USEDEFAULT, CW_USEDEFAULT,
                              cw + ncW, ch + ncH, nullptr, nullptr, hInst, nullptr);
    if (!hwnd) return 1;
    ShowWindow(hwnd, show);
    UpdateWindow(hwnd);

    MSG m;
    while (GetMessage(&m, nullptr, 0, 0) > 0) {
        TranslateMessage(&m);
        DispatchMessage(&m);
    }
    return (int)m.wParam;
}
