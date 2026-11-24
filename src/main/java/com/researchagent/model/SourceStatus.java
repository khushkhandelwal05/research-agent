package com.researchagent.model;

/** Outcome of fetching and parsing one source. Only OK and PARTIAL sources go on to claim extraction. */
public enum SourceStatus {
    OK,             // enough readable text
    PARTIAL,        // readable but thin, or a paywall marker suggests the text is cut off
    PAYWALL,        // paywall marker and too little text to use
    BLOCKED,        // 401/403/429 or a bot-challenge page
    JS_EMPTY,       // page loads but its content is rendered by JavaScript, which we don't run
    EMPTY,          // page loads but has almost no text and no sign of JS rendering
    NOT_FOUND,      // 404 / 410
    HTTP_ERROR,     // other 4xx / 5xx
    TIMEOUT,        // no response within the timeout, after one retry
    NETWORK_ERROR,  // DNS, TLS or connection failure, after one retry
    NOT_HTML,       // a non-HTML response (PDF, image, ...)
    INVALID_INPUT;  // malformed URL or missing local file

    public boolean usable() {
        return this == OK || this == PARTIAL;
    }
}
