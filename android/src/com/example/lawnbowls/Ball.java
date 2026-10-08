package com.example.lawnbowls;

/** A bowl or the jack. Positions are in "green pixels" (see {@link Physics}). */
final class Ball {
    double x, y, vx, vy, r, m = 1;
    int owner;            // 0 = you, 1 = CPU, -1 = jack
    boolean jack;
    int side, mark;       // active bias (-1 curves left, +1 right) / bias shown on the bowl's face
    boolean alive = true; // false once a ball has left the green
    boolean original;     // still in the course of its own delivery (Law 14.1)
    boolean toucher;      // touched the jack in its original course (Law 14.1)

    Ball copy() {
        Ball b = new Ball();
        b.x = x; b.y = y; b.vx = vx; b.vy = vy; b.r = r; b.m = m;
        b.owner = owner; b.jack = jack; b.side = side; b.mark = mark;
        b.alive = alive; b.original = original; b.toucher = toucher;
        return b;
    }

    boolean moving() { return vx != 0 || vy != 0; }
}
