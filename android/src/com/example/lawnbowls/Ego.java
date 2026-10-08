package com.example.lawnbowls;

import java.util.Random;

/**
 * A computer player's personality. It changes how the Ai chooses and plays its deliveries, how steady
 * its hand is, how long it takes, and what it says. Pure Java (no Android classes).
 */
final class Ego {
    static final int GOOD = 0, BAD = 1, WIN_END = 2, LOSE_END = 3, WIN_MATCH = 4, LOSE_MATCH = 5, KINDS = 6;

    final String name;        // short name used in messages
    final String title;       // full billing
    final String blurb;       // one line about their game
    final double aggression;  // 0 never drives .. 1 loves a drive
    final double caution;     // 0 happy to throw a bowl away .. 1 hates wasting one
    final double accuracy;    // multiplies the delivery noise: below 1 is sharper, above 1 is shakier
    final double showboat;    // chance of an unprompted trick drive
    final double temper;      // how badly a lost end rattles them
    final double tempo;       // multiplies thinking time
    final double banter;      // how often they talk
    final int sidePref;       // -1 prefers curving left, +1 right, 0 none
    final double jackMin, jackMax;   // the jack lengths (metres) they like to roll
    private final String[][] lines;

    private Ego(String name, String title, String blurb, double aggression, double caution, double accuracy,
                double showboat, double temper, double tempo, double banter, int sidePref,
                double jackMin, double jackMax, String[][] lines) {
        this.name = name; this.title = title; this.blurb = blurb;
        this.aggression = aggression; this.caution = caution; this.accuracy = accuracy;
        this.showboat = showboat; this.temper = temper; this.tempo = tempo; this.banter = banter;
        this.sidePref = sidePref; this.jackMin = jackMin; this.jackMax = jackMax; this.lines = lines;
    }

    /** What the Ai did before personalities existed: used by the tests and as a fallback. */
    static final Ego DEFAULT = new Ego("CPU", "The CPU", "Plain and steady.", 0, 0.5, 1, 0, 0.3, 1, 0, 0,
            24.2, 29.8, new String[KINDS][0]);

    private static Ego e(String name, String title, String blurb, double ag, double ca, double ac, double sh,
                         double te, double tempo, double ba, int side, double jmin, double jmax,
                         String[] good, String[] bad, String winEnd, String loseEnd, String winMatch, String loseMatch) {
        return new Ego(name, title, blurb, ag, ca, ac, sh, te, tempo, ba, side, jmin, jmax,
                new String[][]{good, bad, {winEnd}, {loseEnd}, {winMatch}, {loseMatch}});
    }

    static final Ego[] LIBRARY = {
        e("Dennis", "Dennis \"The Draw\" Fothergill", "Patient. Never drives, never wastes a bowl.",
                0.0, 0.95, 0.75, 0.0, 0.2, 1.3, 0.35, -1, 25.0, 28.0,
                new String[]{"Weight is everything, lad.", "Sweet as a nut."},
                new String[]{"Hm. The green moved.", "That was the wind."},
                "Told you. Draw, draw, draw.", "Fair play. Well bowled.",
                "Forty years of practice, that.", "I'll be back next Tuesday."),
        e("Brenda", "Brenda \"Bomber\" Hargreaves", "If it's in the way, it's coming out.",
                0.95, 0.15, 1.25, 0.4, 0.6, 0.8, 0.6, 1, 26.0, 30.0,
                new String[]{"BOOM! Clean as you like.", "Get out of my head!"},
                new String[]{"Ooh, a touch heavy.", "It was going to be brilliant."},
                "Scattered them!", "That is NOT how I drew it up.",
                "Drive for show, drive for dough.", "Rematch. Right now."),
        e("Reg", "Reg Pickles", "Seventy years on the green and no hurry.",
                0.1, 0.8, 1.2, 0.0, 0.1, 1.8, 0.5, -1, 24.0, 27.0,
                new String[]{"Not bad for an old'un.", "That'll do nicely."},
                new String[]{"My knee twinged.", "Where did that go?"},
                "Well, would you look at that.", "Ah, beginner's luck.",
                "Time for a cup of tea.", "Good game. Who's putting the kettle on?"),
        e("Cressida", "Cressida Vane-Wright", "Flair first, arithmetic later.",
                0.55, 0.3, 1.0, 0.75, 0.5, 1.0, 0.8, 1, 26.0, 30.0,
                new String[]{"Divine, darling.", "Simply marvellous."},
                new String[]{"The green is frightfully uneven.", "Not my best."},
                "Naturally.", "I shall pretend that did not happen.",
                "Champagne all round!", "Tragic. I'll be in the clubhouse."),
        e("Gaz", "Gaz \"Smash\" Mulligan", "One speed: full.",
                1.0, 0.0, 1.5, 0.6, 0.7, 0.7, 0.7, 1, 27.0, 30.0,
                new String[]{"Yeah! Smashed it!", "Take that!"},
                new String[]{"Nah, that never...", "Bowls are rubbish."},
                "Absolutely clattered them.", "Lucky. Total fluke.",
                "Easy, mate.", "Rigged."),
        e("Doreen", "Doreen Tuttle", "Short jack, short temper for waste.",
                0.05, 1.0, 0.9, 0.0, 0.3, 1.1, 0.5, -1, 23.3, 25.5,
                new String[]{"Lovely and snug.", "Right where I wanted it."},
                new String[]{"Oh, sugar.", "Tsk. Careless."},
                "Another one for the cardigan.", "Never mind, dear.",
                "Home for scones, then.", "You played ever so well."),
        e("Humphrey", "Sir Humphrey Blount", "Pompous, accurate and long on the jack.",
                0.3, 0.7, 0.8, 0.2, 0.4, 1.2, 0.9, 1, 28.0, 30.0,
                new String[]{"As one would expect.", "Precisely as calculated."},
                new String[]{"An aberration.", "The green is clearly at fault."},
                "The better man prevails.", "A temporary reversal.",
                "A victory for good breeding.", "I demand a recount."),
        e("Mo", "Maurice \"Mo\" Delaney", "Gambler. Loves a long shot.",
                0.7, 0.2, 1.3, 0.85, 0.5, 0.9, 0.6, -1, 25.0, 30.0,
                new String[]{"Pay up!", "Never in doubt."},
                new String[]{"Double or nothing next time.", "The odds were good."},
                "House always wins.", "Easy come, easy go.",
                "Fortune favours the bold.", "I'll get it back."),
        e("Priya", "Priya Nair", "Analytical. Quiet. Frighteningly accurate.",
                0.25, 0.75, 0.6, 0.0, 0.15, 1.0, 0.2, 1, 25.5, 28.5,
                new String[]{"As modelled."},
                new String[]{"Variance."},
                "Expected value, realised.", "Noted.",
                "Thank you for the game.", "Interesting. I'll adjust."),
        e("Tam", "Big Tam McAllister", "Strong arm, long jacks, big opinions.",
                0.65, 0.35, 1.1, 0.3, 0.55, 0.9, 0.7, -1, 27.5, 30.0,
                new String[]{"Aye, that's the stuff!", "Get in!"},
                new String[]{"Och, away.", "That wee bowl had a mind of its own."},
                "Haw, that's how it's done.", "Ach, fair enough.",
                "Pint's on you, pal.", "I'll get you at the Highland Games."),
        e("Joyce", "Nana Joyce", "Sweet. Chatty. Utterly unpredictable.",
                0.2, 0.6, 1.5, 0.2, 0.1, 1.4, 1.0, 1, 23.5, 29.5,
                new String[]{"Oh, I did that! Did you see?", "Goodness, how lucky!"},
                new String[]{"Oopsie.", "Where's it off to now?"},
                "Wonderful! Lovely game, dear.", "Oh well. More biscuits for me.",
                "I'll tell the grandchildren.", "Wasn't that fun?"),
        e("Kev", "Kiwi Kev Tane", "Relaxed, quick, plays it as it comes.",
                0.45, 0.4, 1.0, 0.3, 0.3, 0.55, 0.6, 1, 25.0, 29.0,
                new String[]{"Sweet as!", "Good as gold."},
                new String[]{"Yeah, nah.", "She'll be right."},
                "Chur!", "No worries, mate.",
                "Chur, bro. Beers on me.", "All good, good game."),
        e("Ethel", "Ethel \"The Ice Queen\" Crabtree", "Near flawless. Speaks only when it hurts.",
                0.2, 0.8, 0.5, 0.0, 0.0, 1.5, 0.15, -1, 25.5, 28.0,
                new String[]{"Naturally."},
                new String[]{"Hm."},
                "Next.", "Acceptable.",
                "As it should be.", "Congratulations. Do not get used to it."),
        e("Lola", "Lucky Lola Ferreira", "Wild swings, sunny outlook.",
                0.6, 0.25, 1.35, 0.8, 0.35, 0.8, 0.75, 1, 24.5, 30.0,
                new String[]{"Ha! Told you I was lucky!", "Gorgeous!"},
                new String[]{"Sooo close!", "The bowl has a mind of its own."},
                "Called it.", "Next end, next luck.",
                "Lucky? Maybe. Good? Definitely.", "Brilliant game. Again?"),
    };

    static final String[] SHOWOFF = {"Watch this!", "Hold my tea.", "Here goes nothing.", "Stand back.",
            "Time for a bit of theatre."};

    /** A random personality, not the same one as {@code not} (pass null for no exclusion). */
    static Ego random(Random rng, Ego not) {
        Ego e;
        do { e = LIBRARY[rng.nextInt(LIBRARY.length)]; } while (e == not);
        return e;
    }

    /** One of this ego's lines for an occasion, or null if it has none. */
    String line(int kind, Random rng) {
        String[] l = lines[kind];
        return l.length == 0 ? null : l[rng.nextInt(l.length)];
    }

    /** Shown beside the name when the player is in good or bad form. */
    static String moodLabel(double mood) {
        if (mood >= 0.6) return "on fire";
        if (mood >= 0.25) return "confident";
        if (mood <= -0.6) return "rattled";
        if (mood <= -0.25) return "tense";
        return "steady";
    }

    /** Accuracy multiplier after a good or bad run (confidence helps, a rattled temper hurts). */
    double accuracyFor(double mood) {
        double f = mood >= 0 ? 1 - 0.15 * mood : 1 + 0.5 * -mood * (0.4 + temper * 1.2);
        return accuracy * f;
    }
}
