package com.researchagent.model;

/**
 * One arguable assertion about the topic ("AI is already causing net job losses"), written before any
 * source is read. Every claim is judged against these, so sources are compared on the same rows.
 */
public record Statement(String id, String text, String whyItMatters) {
}
