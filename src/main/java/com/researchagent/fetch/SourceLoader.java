package com.researchagent.fetch;

import com.researchagent.model.Paragraph;
import com.researchagent.model.Source;
import com.researchagent.model.SourceStatus;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Fetch stage for one input: load it (URL or local HTML file), extract the text, and decide a status.
 * Never throws: every failure becomes a {@link Source} with a non-usable status and a reason.
 */
public final class SourceLoader {

    /** Under this many words a page with a paywall or bot-check marker is treated as that failure. */
    private static final int GATED_BELOW_WORDS = 400;
    private static final int EMPTY_BELOW_WORDS = 150;
    private static final int PARTIAL_BELOW_WORDS = 300;

    /** Checked against the page's VISIBLE text: many normal pages load a captcha script for their forms. */
    private static final Pattern BOT_CHALLENGE_TEXT = Pattern.compile(
            "verify you are (a )?human|are you a robot|complete the captcha|just a moment\\.\\.\\.|access denied"
                    + "|enable javascript and cookies to continue|pardon our interruption|request unsuccessful");
    /** Checked against raw HTML: markers only challenge pages carry (Cloudflare, PerimeterX, DataDome). */
    private static final Pattern BOT_CHALLENGE_HTML = Pattern.compile(
            "cf-chl|challenge-platform|_pxcaptcha|px-captcha|captcha-delivery\\.com");
    private static final Pattern PAYWALL = Pattern.compile(
            "subscribe to (continue|read)|to continue reading|already a subscriber|subscribers only"
                    + "|for subscribers|sign in to (continue|read)|create a free account to (continue|read)"
                    + "|class=\"[^\"]*paywall");
    /** Sites that answer a missing page with HTTP 200 usually still say so in the title. */
    private static final Pattern SOFT_404_TITLE = Pattern.compile("(?i)\\b404\\b|not found|page (doesn.t|does not) exist");
    /** Junk that often trails a URL pasted from a document: dashes, punctuation, quotes, brackets. */
    private static final Pattern TRAILING_JUNK = Pattern.compile("[-–—.,;:!*_'\"<>)\\]}]+$");

    private final Fetcher fetcher = new Fetcher();
    private final ContentExtractor extractor = new ContentExtractor();

    /** The source plus the raw HTML we got (saved for debugging and as a test fixture). */
    public record Result(Source source, String rawHtml) {
    }

    public Result load(String id, String input) {
        long start = System.currentTimeMillis();
        String trimmed = input.strip();

        if (!trimmed.matches("(?i)^https?://.*")) {
            return loadLocalFile(id, input, trimmed, start);
        }

        String url = trimmed;
        Result result = fetchAndClassify(id, input, url, false, start);

        // If it failed and the URL ends in junk (e.g. "---" pasted from a doc), retry once without it.
        // Many sites answer a bad URL with 200 and a "not found" page, so we retry on any failure.
        String repaired = TRAILING_JUNK.matcher(url).replaceAll("");
        if (!result.source().status().usable() && !repaired.equals(url)) {
            Result retry = fetchAndClassify(id, input, repaired, true, start);
            if (retry.source().status().usable()) {
                return retry;
            }
        }
        return result;
    }

    private Result fetchAndClassify(String id, String input, String url, boolean repaired, long start) {
        Attempt attempt = attempt(url);
        if (attempt.response() == null) {
            return new Result(failed(id, input, url, null, attempt.failureStatus(), attempt.failureReason(), start), null);
        }
        return classify(id, input, url, repaired, attempt.response(), start);
    }

    // ---- fetching ------------------------------------------------------------------------------

    /** Either a response (any HTTP status) or a failure status before any response. */
    private record Attempt(Fetcher.Response response, SourceStatus failureStatus, String failureReason) {
    }

    private Attempt attempt(String url) {
        try {
            URI.create(url).toURL(); // reject malformed URLs before touching the network
        } catch (Exception e) {
            return new Attempt(null, SourceStatus.INVALID_INPUT, "Malformed URL: " + e.getMessage());
        }
        try {
            Fetcher.Response response = fetcher.get(url);
            SourceStatus httpFailure = httpFailure(response.status());
            return new Attempt(response, httpFailure, null);
        } catch (Fetcher.FetchException e) {
            return new Attempt(null, e.status(), e.getMessage());
        }
    }

    private static SourceStatus httpFailure(int status) {
        if (status < 400) return null;
        return switch (status) {
            case 402 -> SourceStatus.PAYWALL;
            case 401, 403, 429 -> SourceStatus.BLOCKED;
            case 404, 410 -> SourceStatus.NOT_FOUND;
            default -> SourceStatus.HTTP_ERROR;
        };
    }

    private Result loadLocalFile(String id, String input, String path, long start) {
        Path file = Path.of(path);
        if (!Files.isRegularFile(file)) {
            return new Result(failed(id, input, null, null, SourceStatus.INVALID_INPUT,
                    "Not an http(s) URL and no such local file", start), null);
        }
        try {
            String html = Files.readString(file, StandardCharsets.UTF_8);
            Fetcher.Response fake = new Fetcher.Response(200, file.toUri().toString(), "text/html", html);
            return classify(id, input, file.toUri().toString(), false, fake, start);
        } catch (IOException e) {
            return new Result(failed(id, input, null, null, SourceStatus.INVALID_INPUT,
                    "Could not read file: " + e.getMessage(), start), null);
        }
    }

    // ---- classification -----------------------------------------------------------------------

    private Result classify(String id, String input, String fetchedUrl, boolean repaired,
                            Fetcher.Response response, long start) {
        String repairNote = repaired ? " (URL repaired: trailing characters removed from the input)" : "";
        String body = response.body();
        String lowerBody = body == null ? "" : body.toLowerCase(Locale.ROOT);

        // HTTP-level failures. A 403 is often a bot-check page, so name that when we see it.
        SourceStatus httpFailure = httpFailure(response.status());
        if (httpFailure != null) {
            String reason = "HTTP " + response.status();
            if (httpFailure == SourceStatus.BLOCKED && isBotChallenge(body, lowerBody)) {
                reason += ": bot-check page";
            }
            return new Result(failed(id, input, fetchedUrl, response, httpFailure, reason + repairNote, start), body);
        }

        String contentType = response.contentType() == null ? "" : response.contentType().toLowerCase(Locale.ROOT);
        if (!contentType.isEmpty() && !contentType.contains("html") && !contentType.contains("xml")) {
            return new Result(failed(id, input, fetchedUrl, response, SourceStatus.NOT_HTML,
                    "Content-Type " + response.contentType() + repairNote, start), body);
        }

        Document doc = Jsoup.parse(body, response.finalUrl());
        ContentExtractor.Extracted ex = extractor.extract(doc, response.finalUrl());
        int words = ex.wordCount();

        SourceStatus status;
        String reason;
        if (words < PARTIAL_BELOW_WORDS && ex.title() != null && SOFT_404_TITLE.matcher(ex.title()).find()) {
            status = SourceStatus.NOT_FOUND;
            reason = "Soft 404: HTTP 200 but the page title is \"" + ex.title() + "\"";
        } else if (words < GATED_BELOW_WORDS && isBotChallenge(body, lowerBody)) {
            status = SourceStatus.BLOCKED;
            reason = "Bot-check page instead of content (" + words + " words)";
        } else if (PAYWALL.matcher(lowerBody).find()) {
            if (words < GATED_BELOW_WORDS) {
                status = SourceStatus.PAYWALL;
                reason = "Paywall marker and only " + words + " words of text";
            } else {
                status = SourceStatus.PARTIAL;
                reason = "Paywall marker found; text may be cut off (" + words + " words)";
            }
        } else if (words < EMPTY_BELOW_WORDS) {
            status = ex.looksLikeJsShell() ? SourceStatus.JS_EMPTY : SourceStatus.EMPTY;
            reason = ex.looksLikeJsShell()
                    ? "Only " + words + " words; content appears to be rendered by JavaScript (" + ex.scriptCount() + " scripts)"
                    : "Only " + words + " words of readable text";
        } else if (words < PARTIAL_BELOW_WORDS) {
            status = SourceStatus.PARTIAL;
            reason = "Thin content: " + words + " words";
        } else {
            status = SourceStatus.OK;
            reason = words + " words via " + ex.method();
        }
        List<Paragraph> paragraphs = status.usable() ? ex.paragraphs() : List.of();
        Source source = new Source(id, input, fetchedUrl, response.finalUrl(), repaired, status, reason + repairNote,
                response.status(), System.currentTimeMillis() - start,
                ex.title(), ex.siteName(), ex.author(), ex.published(), ex.method(), words, paragraphs);
        return new Result(source, body);
    }

    private static boolean isBotChallenge(String body, String lowerBody) {
        if (BOT_CHALLENGE_HTML.matcher(lowerBody).find()) return true;
        String visible = body == null ? "" : Jsoup.parse(body).text().toLowerCase(Locale.ROOT);
        return BOT_CHALLENGE_TEXT.matcher(visible).find();
    }

    private static Source failed(String id, String input, String fetchedUrl, Fetcher.Response response,
                                 SourceStatus status, String reason, long start) {
        return new Source(id, input, fetchedUrl, response == null ? null : response.finalUrl(), false,
                status, reason, response == null ? null : response.status(),
                System.currentTimeMillis() - start, null, null, null, null, null, 0, List.of());
    }
}
