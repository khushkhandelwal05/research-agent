package com.researchagent.model;

/** A source's position on one topic statement. MIXED = the source itself argues both sides. */
public enum Stance {
    SUPPORTS,
    OPPOSES,
    MIXED;

    /** True when the two stances conflict (SUPPORTS vs OPPOSES). MIXED conflicts with nothing. */
    public boolean opposes(Stance other) {
        return (this == SUPPORTS && other == OPPOSES) || (this == OPPOSES && other == SUPPORTS);
    }
}
