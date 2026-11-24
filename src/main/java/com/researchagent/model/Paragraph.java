package com.researchagent.model;

/** One cleaned paragraph of a source. Citations point to "S1:P4" (source id + paragraph id). */
public record Paragraph(String id, String text) {
}
