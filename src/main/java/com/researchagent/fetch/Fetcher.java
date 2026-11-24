package com.researchagent.fetch;

import com.researchagent.model.SourceStatus;
import org.jsoup.Connection;
import org.jsoup.Jsoup;

import javax.net.ssl.SSLException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.MalformedURLException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

/** Plain HTTP GET with one retry. Never throws for HTTP error codes; those come back in {@link Response}. */
public final class Fetcher {

    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/130.0 Safari/537.36";
    private static final int TIMEOUT_MS = 15_000;
    private static final int MAX_BODY_BYTES = 10 * 1024 * 1024;
    private static final long RETRY_DELAY_MS = 1_000;

    public record Response(int status, String finalUrl, String contentType, String body) {
    }

    /** A failure before we got any HTTP response (timeout, DNS, TLS, bad URL). */
    public static final class FetchException extends Exception {
        private final SourceStatus status;

        FetchException(SourceStatus status, String message, Throwable cause) {
            super(message, cause);
            this.status = status;
        }

        public SourceStatus status() {
            return status;
        }

        boolean retryable() {
            return status == SourceStatus.TIMEOUT || status == SourceStatus.NETWORK_ERROR;
        }
    }

    /** GET with one retry on timeouts, network errors and 5xx responses. */
    public Response get(String url) throws FetchException {
        FetchException lastError = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                Response response = getOnce(url);
                if (response.status() >= 500 && attempt == 1) {
                    pause();
                    continue;
                }
                return response;
            } catch (FetchException e) {
                lastError = e;
                if (!e.retryable() || attempt == 2) {
                    throw e;
                }
                pause();
            }
        }
        throw lastError; // unreachable in practice
    }

    private Response getOnce(String url) throws FetchException {
        try {
            Connection.Response r = Jsoup.connect(url)
                    .userAgent(USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .timeout(TIMEOUT_MS)
                    .maxBodySize(MAX_BODY_BYTES)
                    .followRedirects(true)
                    .ignoreHttpErrors(true)
                    .ignoreContentType(true)
                    .execute();
            return new Response(r.statusCode(), r.url().toString(), r.contentType(), r.body());
        } catch (SocketTimeoutException e) {
            throw new FetchException(SourceStatus.TIMEOUT, "No response within " + TIMEOUT_MS / 1000 + "s", e);
        } catch (UnknownHostException e) {
            throw new FetchException(SourceStatus.NETWORK_ERROR, "Unknown host: " + e.getMessage(), e);
        } catch (MalformedURLException | IllegalArgumentException e) {
            throw new FetchException(SourceStatus.INVALID_INPUT, "Malformed URL: " + e.getMessage(), e);
        } catch (ConnectException | SSLException e) {
            throw new FetchException(SourceStatus.NETWORK_ERROR, e.getClass().getSimpleName() + ": " + e.getMessage(), e);
        } catch (IOException e) {
            throw new FetchException(SourceStatus.NETWORK_ERROR, e.getClass().getSimpleName() + ": " + e.getMessage(), e);
        }
    }

    private static void pause() {
        try {
            Thread.sleep(RETRY_DELAY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
