package com.researchagent.model;

import java.util.List;

/** Output of the fetch stage for one input URL or local file. Written to runs/&lt;id&gt;/01-fetch.json. */
public record Source(
        String id,
        String input,             // exactly what the user gave us
        String fetchedUrl,        // URL actually requested (differs from input if repaired)
        String finalUrl,          // after redirects
        boolean urlRepaired,
        SourceStatus status,
        String reason,            // human-readable explanation of the status
        Integer httpStatus,
        long fetchMillis,
        String title,
        String siteName,
        String author,
        String published,
        String extractionMethod,  // which strategy produced the text (ARTICLE, MAIN, DENSEST_BLOCK, JSON_LD, NEXT_DATA, ...)
        int wordCount,
        List<Paragraph> paragraphs) {
}
