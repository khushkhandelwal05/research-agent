package com.researchagent.model;

import java.util.Locale;

/** What backs a claim. Used for ranking: data outranks an expert view, which outranks a bare assertion. */
public enum Evidence {
    DATA,       // cites numbers, a study, a survey or a dataset
    EXPERT,     // a named expert's or institution's judgment, without data
    ASSERTION;  // unsupported opinion, prediction or marketing

    public int strength() {
        return switch (this) {
            case DATA -> 3;
            case EXPERT -> 2;
            case ASSERTION -> 1;
        };
    }

    /** Lenient parse of the model's output; anything unrecognised counts as the weakest evidence. */
    public static Evidence parse(String s) {
        try {
            return valueOf(s.strip().toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            return ASSERTION;
        }
    }
}
