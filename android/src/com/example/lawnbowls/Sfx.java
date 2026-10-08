package com.example.lawnbowls;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

import java.util.Random;

/** Tiny synthesised sound effects (no audio files, no permissions). */
final class Sfx {
    static final int CLACK = 0, THUD = 1, TICK = 2;
    private static final int RATE = 22050, VOICES = 3;

    boolean enabled = true;
    private final AudioTrack[][] pool = new AudioTrack[3][VOICES];
    private final int[] next = new int[3];

    Sfx() {
        short[][] data = {clack(), thud(), tick()};
        for (int k = 0; k < 3; k++) {
            for (int i = 0; i < VOICES; i++) {
                try { pool[k][i] = make(data[k]); } catch (Exception e) { pool[k][i] = null; }
            }
        }
    }

    private static AudioTrack make(short[] pcm) {
        AudioTrack t = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(pcm.length * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build();
        t.write(pcm, 0, pcm.length);
        return t;
    }

    void play(int kind, float volume) {
        if (!enabled) return;
        try {
            AudioTrack t = pool[kind][next[kind]];
            next[kind] = (next[kind] + 1) % VOICES;
            if (t == null) return;
            t.stop();
            t.reloadStaticData();
            t.setVolume(Math.max(0.05f, Math.min(1f, volume)));
            t.play();
        } catch (Exception ignored) {
            // audio is a nicety; never let it break the game
        }
    }

    void release() {
        for (AudioTrack[] row : pool) {
            for (AudioTrack t : row) {
                if (t != null) { try { t.release(); } catch (Exception ignored) { } }
            }
        }
    }

    // ---- synthesis -----------------------------------------------------------------------------
    private static short[] clack() {          // two hard balls meeting
        Random r = new Random(1);
        short[] s = new short[(int) (RATE * 0.10)];
        for (int i = 0; i < s.length; i++) {
            double t = i / (double) RATE;
            double v = Math.exp(-t * 55) * (0.55 * Math.sin(2 * Math.PI * 2100 * t) + 0.3 * Math.sin(2 * Math.PI * 3350 * t))
                    + Math.exp(-t * 380) * 0.45 * (r.nextDouble() * 2 - 1);
            s[i] = (short) (Math.max(-1, Math.min(1, v)) * 26000);
        }
        return s;
    }

    private static short[] thud() {           // a bowl dropping into the ditch
        Random r = new Random(2);
        short[] s = new short[(int) (RATE * 0.26)];
        for (int i = 0; i < s.length; i++) {
            double t = i / (double) RATE;
            double v = Math.exp(-t * 24) * 0.9 * Math.sin(2 * Math.PI * (105 - 40 * t) * t)
                    + Math.exp(-t * 80) * 0.25 * (r.nextDouble() * 2 - 1);
            s[i] = (short) (Math.max(-1, Math.min(1, v)) * 26000);
        }
        return s;
    }

    private static short[] tick() {           // button press
        short[] s = new short[(int) (RATE * 0.05)];
        for (int i = 0; i < s.length; i++) {
            double t = i / (double) RATE;
            s[i] = (short) (Math.exp(-t * 95) * Math.sin(2 * Math.PI * 1250 * t) * 18000);
        }
        return s;
    }
}
