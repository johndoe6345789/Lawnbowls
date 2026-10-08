package com.example.lawnbowls;
import java.util.*;
public class Probe {
    public static void main(String[] a) {
        Physics.setGreen(1);
        // jack at ~26 m, one CPU(1) bowl 10 px from jack, ask player 0 what to do
        ArrayList<Ball> balls = new ArrayList<>();
        Ball j = Physics.release(-1,0,0,0,true); j.vx=j.vy=0; j.y = Physics.MAT_Y - 26*Physics.M; j.original=false; balls.add(j);
        Ball b = Physics.release(1,0,0,0,false); b.vx=b.vy=0; b.x=Physics.CX+16; b.y=j.y; b.original=false; balls.add(b);
        Ai.Shot s = Ai.bowlShot(balls, 0);
        System.out.printf("side=%d th=%.3f v=%.0f%n", s.side, s.th, s.v);
        Ball nb = Physics.release(0, s.side, s.th, s.v, false);
        ArrayList<Ball> w = Physics.simulate(balls, nb, null);
        Ball d = w.get(w.size()-1);
        System.out.printf("delivered ends x=%.0f y=%.0f (jack y=%.0f) toucher=%b%n", d.x, d.y, j.y, d.toucher);
        for (Ball q: w) System.out.printf(" ball owner=%d x=%.0f y=%.0f alive=%b%n", q.owner,q.x,q.y,q.alive);
        Rules.settleBowl(w,d);
        System.out.println("after settle delivered alive=" + d.alive + " eval=" + Ai.evaluate(Physics.simulate(balls, nb, null), null==null?Physics.simulate(balls,nb,null).get(0):null,0));
    }
}
