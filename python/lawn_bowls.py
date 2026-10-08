#!/usr/bin/env python3
"""
Lawn Bowls - a top-down lawn bowls game for tkinter (standard library only).

You play against the CPU. Each end both sides roll four bowls towards the jack
(the little white ball). Bowls are weighted on one side ("bias"), so they curve
as they slow down - aim off the line! After all eight bowls are down, the side
with the bowl closest to the jack scores one shot for every bowl that is closer
than the opponent's nearest. First to 7 shots wins.

Controls
    Mouse click   bowl towards the clicked spot (distance = weight, angle = line)
    Left / Right  slide the mat left or right
    F             flip hand (curve left / curve right)
    G             toggle the guide line
    Enter / Space continue after an end
"""
import math
import random
import time
import tkinter as tk

# ----------------------------------------------------------------- geometry
W, H = 460, 780
RX0, RX1 = 90, 370            # left / right edge of the rink
RY0, RY1 = 70, 745            # ditch edge (top) / bottom of the green
CX = (RX0 + RX1) / 2
BOWL_Y = 695                  # bowls leave the mat from here
BOWL_R, JACK_R = 11, 6

# ------------------------------------------------------------------ physics
DT = 1 / 60
DECEL = 200.0                 # rolling friction (px / s^2)
BIAS_K = 0.38                 # how strongly bowls curve
MIN_V, MAX_V = 120.0, 620.0
MAX_STEPS = 700
RESTITUTION = 0.85

TARGET = 7                    # shots needed to win
BOWLS_EACH = 4

PLAYER_COL, PLAYER_LIGHT = "#d62828", "#ff8a8a"
CPU_COL, CPU_LIGHT = "#1d3557", "#7fa3d9"

SKILL = {"Easy": (0.035, 0.07), "Medium": (0.016, 0.035), "Hard": (0.006, 0.014)}


class Ball:
    __slots__ = ("x", "y", "vx", "vy", "r", "m", "owner", "jack", "side", "mark", "alive")

    def __init__(self, x, y, vx, vy, r, m, owner, jack, side):
        self.x, self.y, self.vx, self.vy = x, y, vx, vy
        self.r, self.m, self.owner, self.jack = r, m, owner, jack
        self.side = side      # active bias (-1 curves left, +1 right, 0 none)
        self.mark = side      # bias shown on the bowl's face
        self.alive = True

    def copy(self):
        b = Ball(self.x, self.y, self.vx, self.vy, self.r, self.m, self.owner, self.jack, self.side)
        b.mark = self.mark
        b.alive = self.alive
        return b


def step(balls, dt):
    """Advance the world one step. Returns True while anything is still moving."""
    moving = False
    for b in balls:
        if not b.alive:
            continue
        vx, vy = b.vx, b.vy
        if vx == 0 and vy == 0:
            continue
        sp0 = math.hypot(vx, vy)
        sp = sp0 - DECEL * dt
        if sp <= 4:
            b.vx = b.vy = 0.0
            continue
        if b.side:
            th = math.atan2(vx, -vy) + b.side * BIAS_K / (sp / 100.0 + 0.6) * dt
            b.vx = sp * math.sin(th)
            b.vy = -sp * math.cos(th)
        else:
            k = sp / sp0
            b.vx, b.vy = vx * k, vy * k
        b.x += b.vx * dt
        b.y += b.vy * dt
        moving = True
        if b.jack:
            if b.x < RX0 + b.r:
                b.x, b.vx = RX0 + b.r, abs(b.vx) * 0.5
            elif b.x > RX1 - b.r:
                b.x, b.vx = RX1 - b.r, -abs(b.vx) * 0.5
            if b.y < RY0 + b.r:
                b.y, b.vx, b.vy = RY0 + b.r, 0.0, 0.0
        elif b.x < RX0 or b.x > RX1 or b.y < RY0:
            b.alive = False   # dead bowl
            b.vx = b.vy = 0.0

    n = len(balls)
    for i in range(n):
        a = balls[i]
        if not a.alive:
            continue
        for j in range(i + 1, n):
            c = balls[j]
            if not c.alive:
                continue
            if a.vx == 0 and a.vy == 0 and c.vx == 0 and c.vy == 0:
                continue
            dx, dy = c.x - a.x, c.y - a.y
            rr = a.r + c.r
            if dx * dx + dy * dy >= rr * rr:
                continue
            d = math.hypot(dx, dy)
            if d < 1e-6:
                nx, ny, d = 0.0, -1.0, 1e-6
            else:
                nx, ny = dx / d, dy / d
            overlap = rr - d
            tot = a.m + c.m
            a.x -= nx * overlap * c.m / tot
            a.y -= ny * overlap * c.m / tot
            c.x += nx * overlap * a.m / tot
            c.y += ny * overlap * a.m / tot
            rel = (c.vx - a.vx) * nx + (c.vy - a.vy) * ny
            if rel < 0:
                j_imp = -(1 + RESTITUTION) * rel / (1 / a.m + 1 / c.m)
                a.vx -= j_imp * nx / a.m
                a.vy -= j_imp * ny / a.m
                c.vx += j_imp * nx / c.m
                c.vy += j_imp * ny / c.m
            a.side = c.side = 0   # collisions knock the bias out of a bowl
            moving = True
    return moving


def simulate(balls, owner, x0, side, theta, v, record=False):
    """Roll one bowl through a copy of the world. Returns (world, bowl, path)."""
    world = [b.copy() for b in balls if b.alive]
    nb = Ball(x0, BOWL_Y, v * math.sin(theta), -v * math.cos(theta),
              BOWL_R, 1.0, owner, False, side)
    world.append(nb)
    path = [(nb.x, nb.y)]
    for i in range(MAX_STEPS):
        moving = step(world, DT)
        if record and i % 4 == 0:
            path.append((nb.x, nb.y))
        if not moving or (record and not nb.alive):
            break
    if record:
        path.append((nb.x, nb.y))
    return world, nb, path


def find_jack(balls):
    for b in balls:
        if b.jack:
            return b
    return None


def ranked_bowls(balls):
    jack = find_jack(balls)
    if jack is None:
        return []
    live = [b for b in balls if b.alive and not b.jack]
    live.sort(key=lambda b: math.hypot(b.x - jack.x, b.y - jack.y))
    return live


def end_score(balls):
    """Return (owner, shots) for the current layout; (None, 0) if nobody scores."""
    live = ranked_bowls(balls)
    if not live:
        return None, 0
    owner, n = live[0].owner, 0
    for b in live:
        if b.owner != owner:
            break
        n += 1
    return owner, n


def evaluate_for_cpu(world):
    """Heuristic value of a layout from the CPU's (owner 1) point of view."""
    jack = find_jack(world)
    owner, n = end_score(world)
    val = 0.0 if owner is None else (1000.0 * n if owner == 1 else -1000.0 * n)
    best = {0: 400.0, 1: 400.0}
    for b in world:
        if b.alive and not b.jack:
            d = math.hypot(b.x - jack.x, b.y - jack.y)
            if d < best[b.owner]:
                best[b.owner] = d
    return val - best[1] + 0.3 * best[0]


def cpu_pick_shot(balls, x0):
    """Search for a good (side, angle, speed) by simulating candidate deliveries."""
    jack = find_jack(balls)
    dj = math.hypot(jack.x - x0, jack.y - BOWL_Y)
    speeds = [math.sqrt(2 * DECEL * dj * f) for f in (0.85, 0.95, 1.02, 1.1, 1.25)]
    speeds += [480.0, 600.0]

    def score(side, th, v):
        v = max(MIN_V, min(MAX_V, v))
        world, _, _ = simulate(balls, 1, x0, side, th, v)
        return evaluate_for_cpu(world)

    best = None
    for side in (-1, 1):
        for ai in range(-6, 7):
            th = ai * 0.05
            for v in speeds:
                val = score(side, th, v)
                if best is None or val > best[0]:
                    best = (val, side, th, v)
    _, side, th0, v0 = best
    for dth in range(-4, 5):
        for dv in range(-3, 4):
            th = th0 + dth * 0.01
            v = v0 * (1 + dv * 0.02)
            val = score(side, th, v)
            if val > best[0]:
                best = (val, side, th, v)
    return best[1], best[2], best[3]


# ---------------------------------------------------------------------- game
class BowlsGame:
    def __init__(self, root):
        self.root = root
        root.title("Lawn Bowls")
        root.resizable(False, False)
        root.configure(bg="#14210f")

        self.canvas = tk.Canvas(root, width=W, height=H, bg="#1f3d1a", highlightthickness=0)
        self.canvas.grid(row=0, column=0)
        self._build_panel()
        self.draw_background()

        self.canvas.bind("<Motion>", self.on_motion)
        self.canvas.bind("<Button-1>", self.on_click)
        root.bind("<Left>", lambda e: self.move_mat(-10))
        root.bind("<Right>", lambda e: self.move_mat(10))
        root.bind("<f>", lambda e: self.flip_hand())
        root.bind("<g>", lambda e: self.toggle_guide())
        root.bind("<Return>", lambda e: self.continue_game())
        root.bind("<space>", lambda e: self.continue_game())

        self.guide_cache = (None, None)
        self.mouse = (CX, 300)
        self.new_game()
        self.last = time.perf_counter()
        self.acc = 0.0
        self.tick()

    # ------------------------------------------------------------------ UI
    def _build_panel(self):
        bg, fg = "#14210f", "#e8f0e0"
        p = tk.Frame(self.root, bg=bg, padx=16, pady=16, width=250)
        p.grid(row=0, column=1, sticky="ns")
        p.grid_propagate(False)

        tk.Label(p, text="LAWN BOWLS", bg=bg, fg="#9be37f",
                 font=("Helvetica", 18, "bold")).pack(anchor="w")
        self.score_var = tk.StringVar()
        tk.Label(p, textvariable=self.score_var, bg=bg, fg=fg,
                 font=("Helvetica", 15, "bold"), justify="left").pack(anchor="w", pady=(10, 2))
        self.info_var = tk.StringVar()
        tk.Label(p, textvariable=self.info_var, bg=bg, fg="#a9bd9e",
                 font=("Helvetica", 10), justify="left").pack(anchor="w")

        self.msg_var = tk.StringVar()
        tk.Label(p, textvariable=self.msg_var, bg="#1f3318", fg="#fff3b0", wraplength=210,
                 justify="left", anchor="nw", font=("Helvetica", 11), padx=8, pady=8,
                 height=6, width=24).pack(anchor="w", pady=12, fill="x")

        self.hand_btn = tk.Button(p, command=self.flip_hand, font=("Helvetica", 10, "bold"))
        self.hand_btn.pack(fill="x", pady=2)

        self.show_guide = tk.BooleanVar(value=True)
        tk.Checkbutton(p, text="Show guide line (G)", variable=self.show_guide, bg=bg, fg=fg,
                       selectcolor="#1f3318", activebackground=bg, activeforeground=fg,
                       command=lambda: None).pack(anchor="w", pady=4)

        row = tk.Frame(p, bg=bg)
        row.pack(fill="x", pady=2)
        tk.Label(row, text="CPU skill:", bg=bg, fg=fg).pack(side="left")
        self.skill = tk.StringVar(value="Medium")
        tk.OptionMenu(row, self.skill, *SKILL.keys()).pack(side="left", padx=6)

        self.cont_btn = tk.Button(p, text="Continue", command=self.continue_game,
                                  font=("Helvetica", 10, "bold"), state="disabled")
        self.cont_btn.pack(fill="x", pady=(10, 2))
        tk.Button(p, text="New game", command=self.new_game).pack(fill="x", pady=2)

        help_text = ("Click where you want the bowl to go.\n"
                     "Bowls curve towards their weighted\nside as they slow - aim off the line!\n"
                     "Arrow keys slide the mat.\nF flips hand.")
        tk.Label(p, text=help_text, bg=bg, fg="#7f9674", justify="left",
                 font=("Helvetica", 9)).pack(anchor="w", pady=(14, 0))

    def refresh_info(self):
        self.score_var.set(f"You {self.score[0]}  -  {self.score[1]} CPU")
        self.info_var.set(f"End {self.end_no}  |  first to {TARGET}\n"
                          f"Bowls left:  you {self.left[0]},  CPU {self.left[1]}")
        self.hand_btn.config(text="Bowl curves: LEFT  (F)" if self.side < 0 else "Bowl curves: RIGHT  (F)")

    def set_state(self, state):
        self.state = state
        self.cont_btn.config(state="normal" if state in ("end_over", "game_over") else "disabled")
        self.cont_btn.config(text="New game" if state == "game_over" else "Next end")
        self.refresh_info()

    # ------------------------------------------------------------ game flow
    def new_game(self):
        self.score = [0, 0]
        self.end_no = 1
        self.starter = 0
        self.side = -1
        self.player_mat = CX
        self.mat_x = CX
        self.note = ""
        self.new_end()

    def new_end(self):
        self.balls = []
        self.left = [BOWLS_EACH, BOWLS_EACH]
        self.turn = self.starter
        self.jack = None
        self.last_bowl = None
        self.note = ""
        self.mat_x = self.player_mat
        self.set_state("jack_roll")
        self.say("Rolling the jack...")
        self.root.after(500, self.roll_jack)

    def roll_jack(self):
        d = random.uniform(380, 500)
        v = math.sqrt(2 * DECEL * d)
        self.jack = Ball(CX, BOWL_Y, 0.0, -v, JACK_R, 0.5, -1, True, 0)
        self.balls.append(self.jack)
        self.acc = 0.0
        self.last = time.perf_counter()

    def start_turn(self):
        if self.left[0] == 0 and self.left[1] == 0:
            self.finish_end()
            return
        if self.turn == 0:
            self.mat_x = self.player_mat
            self.set_state("aim")
            self.say(self.note + "Your bowl - click where to send it.")
        else:
            self.set_state("cpu_think")
            self.say(self.note + "CPU is lining up its bowl...")
            self.root.after(700, self.cpu_play)
        self.note = ""

    def deliver(self, owner, x0, side, theta, v):
        v = max(MIN_V, min(MAX_V, v))
        b = Ball(x0, BOWL_Y, v * math.sin(theta), -v * math.cos(theta),
                 BOWL_R, 1.0, owner, False, side)
        self.balls.append(b)
        self.last_bowl = b
        self.left[owner] -= 1
        self.acc = 0.0
        self.last = time.perf_counter()
        self.set_state("rolling")
        self.say("Rolling...")

    def roll_finished(self):
        if self.state == "jack_roll":
            self.jack.x = CX      # the jack is centred on the rink
            self.say("Jack is set.")
            self.start_turn()
            return
        b = self.last_bowl
        if b is not None and not b.alive:
            who = "Your" if b.owner == 0 else "CPU's"
            self.note = f"{who} bowl went out - dead bowl.\n"
        self.balls = [x for x in self.balls if x.alive]
        self.turn = 1 - self.turn
        self.start_turn()

    def cpu_play(self):
        if self.state != "cpu_think":
            return
        self.mat_x = random.choice((CX - 45, CX, CX + 45))
        self.root.update_idletasks()
        side, th, v = cpu_pick_shot(self.balls, self.mat_x)
        nth, nv = SKILL[self.skill.get()]
        th += random.gauss(0, nth)
        v *= 1 + random.gauss(0, nv)
        self.deliver(1, self.mat_x, side, th, v)

    def finish_end(self):
        owner, n = end_score(self.balls)
        if owner is None:
            text = "No bowls in play - no score this end."
        else:
            self.score[owner] += n
            self.starter = owner
            who = "You score" if owner == 0 else "CPU scores"
            text = f"{who} {n} shot{'s' if n != 1 else ''}!"
        if max(self.score) >= TARGET:
            winner = "You win the match!" if self.score[0] > self.score[1] else "CPU wins the match."
            self.set_state("game_over")
            self.say(f"{text}\n{winner}")
        else:
            self.set_state("end_over")
            self.say(f"{text}\nPress Enter or click to play the next end.")

    def continue_game(self):
        if self.state == "end_over":
            self.end_no += 1
            self.new_end()
        elif self.state == "game_over":
            self.new_game()

    def say(self, text):
        self.msg_var.set(text)

    # ---------------------------------------------------------------- input
    def flip_hand(self):
        self.side = -self.side
        self.refresh_info()

    def toggle_guide(self):
        self.show_guide.set(not self.show_guide.get())

    def move_mat(self, dx):
        if self.state == "aim":
            self.player_mat = max(RX0 + 28, min(RX1 - 28, self.player_mat + dx))
            self.mat_x = self.player_mat

    def on_motion(self, e):
        self.mouse = (e.x, e.y)

    def aim_params(self, mx, my):
        dx = mx - self.mat_x
        dy = max(30.0, BOWL_Y - my)
        theta = max(-0.6, min(0.6, math.atan2(dx, dy)))
        dist = math.hypot(dx, dy)
        v = max(MIN_V, min(MAX_V, math.sqrt(2 * DECEL * dist)))
        return theta, v

    def on_click(self, e):
        if self.state == "aim":
            theta, v = self.aim_params(e.x, e.y)
            self.deliver(0, self.mat_x, self.side, theta, v)
        elif self.state in ("end_over", "game_over"):
            self.continue_game()

    # -------------------------------------------------------------- drawing
    def draw_background(self):
        c = self.canvas
        c.create_rectangle(0, 0, W, H, fill="#1f3d1a", outline="", tags="bg")
        c.create_rectangle(RX0 - 10, RY0 - 38, RX1 + 10, RY0, fill="#4b3a26", outline="", tags="bg")
        c.create_text(CX, RY0 - 19, text="D I T C H", fill="#8a7352",
                      font=("Helvetica", 10, "bold"), tags="bg")
        band, y, i = 36, RY0, 0
        while y < RY1:
            c.create_rectangle(RX0, y, RX1, min(y + band, RY1),
                               fill="#4a9a3c" if i % 2 == 0 else "#52a544", outline="", tags="bg")
            y += band
            i += 1
        c.create_line(CX, RY0, CX, RY1, fill="#5fb351", dash=(2, 10), tags="bg")
        for x in (RX0, RX1):
            c.create_line(x, RY0, x, RY1, fill="#f2f2f2", width=3, tags="bg")
        c.create_line(RX0, RY0, RX1, RY0, fill="#f2f2f2", width=3, tags="bg")
        for frac in (0.25, 0.5, 0.75):
            yy = RY0 + (BOWL_Y - RY0) * frac
            for x in (RX0, RX1):
                c.create_line(x - 6, yy, x + 6, yy, fill="#f2f2f2", width=2, tags="bg")

    def draw_ball(self, b, ring=False):
        c = self.canvas
        x, y, r = b.x, b.y, b.r
        if b.jack:
            c.create_oval(x - r + 2, y - r + 3, x + r + 2, y + r + 3, fill="#2f6b26", outline="", tags="dyn")
            c.create_oval(x - r, y - r, x + r, y + r, fill="#f7f7f0", outline="#777", tags="dyn")
            return
        col, light = (PLAYER_COL, PLAYER_LIGHT) if b.owner == 0 else (CPU_COL, CPU_LIGHT)
        c.create_oval(x - r + 2, y - r + 3, x + r + 2, y + r + 3, fill="#2f6b26", outline="", tags="dyn")
        c.create_oval(x - r, y - r, x + r, y + r, fill=col, outline="#101010", width=1, tags="dyn")
        c.create_oval(x - r * .6, y - r * .6, x + r * .6, y + r * .6, outline=light, tags="dyn")
        if b.mark:
            dx = b.mark * r * 0.62
            c.create_oval(x + dx - 2, y - 2, x + dx + 2, y + 2, fill="white", outline="", tags="dyn")
        if ring:
            c.create_oval(x - r - 4, y - r - 4, x + r + 4, y + r + 4, outline="#ffe66d", width=2, tags="dyn")

    def draw(self):
        c = self.canvas
        c.delete("dyn")
        mx = self.mat_x
        c.create_rectangle(mx - 24, BOWL_Y - 6, mx + 24, BOWL_Y + 36, fill="#262626",
                           outline="#b0b0b0", width=2, tags="dyn")
        c.create_text(mx, BOWL_Y + 15, text="MAT", fill="#777", font=("Helvetica", 8, "bold"), tags="dyn")

        shots = set()
        if self.state in ("end_over", "game_over"):
            owner, n = end_score(self.balls)
            if owner is not None:
                shots = set(id(b) for b in ranked_bowls(self.balls)[:n])
        for b in self.balls:
            if b.jack and b.alive:
                self.draw_ball(b)
        for b in self.balls:
            if not b.jack and b.alive:
                self.draw_ball(b, ring=id(b) in shots)

        if self.state == "aim":
            mxp, myp = self.mouse
            theta, v = self.aim_params(mxp, myp)
            if self.show_guide.get():
                key = (round(self.mat_x), self.side, round(theta, 3), round(v))
                if self.guide_cache[0] != key:
                    _, _, path = simulate([], 0, self.mat_x, self.side, theta, v, record=True)
                    self.guide_cache = (key, path)
                path = self.guide_cache[1]
                if len(path) >= 2:
                    pts = [coord for p in path for coord in p]
                    c.create_line(*pts, fill="#ffe66d", width=2, dash=(4, 4), tags="dyn")
                ex, ey = path[-1]
                c.create_oval(ex - BOWL_R, ey - BOWL_R, ex + BOWL_R, ey + BOWL_R,
                              outline="#ffe66d", width=2, dash=(3, 3), tags="dyn")
            else:
                c.create_line(self.mat_x, BOWL_Y, self.mat_x + 80 * math.sin(theta),
                              BOWL_Y - 80 * math.cos(theta), fill="#ffe66d", width=2,
                              dash=(4, 4), tags="dyn")
            c.create_line(mxp - 7, myp, mxp + 7, myp, fill="white", tags="dyn")
            c.create_line(mxp, myp - 7, mxp, myp + 7, fill="white", tags="dyn")

    def tick(self):
        now = time.perf_counter()
        self.acc += min(now - self.last, 0.05)
        self.last = now
        while self.acc >= DT:
            self.acc -= DT
            if self.state in ("jack_roll", "rolling") and self.balls:
                if not step(self.balls, DT):
                    self.roll_finished()
        self.draw()
        self.root.after(16, self.tick)


def main():
    root = tk.Tk()
    BowlsGame(root)
    root.mainloop()


if __name__ == "__main__":
    main()
