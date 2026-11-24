package com.researchagent.fetch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.researchagent.model.Paragraph;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Turns a parsed HTML page into metadata plus clean, numbered paragraphs.
 *
 * Strategy, in order: strip page chrome -> pick the main content container -> collect paragraphs.
 * If that yields too little text, fall back to text embedded in the page as data
 * (schema.org JSON-LD, then Next.js __NEXT_DATA__), which often holds content that JavaScript renders.
 */
public final class ContentExtractor {

    /** Below this many words we try the embedded-data fallbacks. */
    static final int FALLBACK_BELOW_WORDS = 300;
    /** A paragraph needs at least this many words (filters menu items, captions, bylines). */
    private static final int MIN_PARAGRAPH_WORDS = 8;
    /** Pick the most specific container that still holds this share of the best container's text. */
    private static final double CONTAINER_SHARE = 0.6;

    /** Always removed: these never hold article text. */
    private static final String CHROME_TAGS = String.join(", ",
            "script", "style", "noscript", "template", "svg", "iframe", "form", "button",
            "nav", "header", "footer", "aside",
            "[role=navigation]", "[role=banner]", "[role=contentinfo]", "[aria-hidden=true]");
    /**
     * Elements whose class/id mentions a chrome keyword ("related-posts", "primary-navigation-sections").
     * Substring matching is aggressive, so stripChrome keeps any match that holds a big share of the
     * page's text (e.g. the Brookings wrapper "article-content -with-sidebars").
     */
    private static final String CHROME_KEYWORDS = "(cookie|consent|newsletter|social|share|related|promo|advert"
            + "|breadcrumb|sidebar|modal|popup|navigation|menu)";
    private static final String CHROME_BY_NAME = String.join(", ",
            "[class~=(?i)" + CHROME_KEYWORDS + "]",
            "[id~=(?i)" + CHROME_KEYWORDS + "]");
    /** Never strip a "chrome" element that holds at least this share of the page's paragraph text. */
    private static final double MAX_CHROME_SHARE = 0.3;
    /** Skip paragraphs where most of the text is link text (menus, link cards, "read more" lists). */
    private static final double MAX_LINK_DENSITY = 0.5;
    private static final java.util.regex.Pattern BOILERPLATE = java.util.regex.Pattern.compile(
            "(?i)©|all rights reserved|privacy (and security )?polic|terms of use|cookie"
                    + "|is provided for your information only|proprietary and confidential");

    private final ObjectMapper mapper = new ObjectMapper();

    public record Extracted(
            String title, String siteName, String author, String published,
            String method, List<Paragraph> paragraphs, int wordCount,
            boolean hasNextData, boolean looksLikeJsShell, int scriptCount) {
    }

    public Extracted extract(Document original, String url) {
        List<JsonNode> jsonLd = readJsonLd(original);
        Element nextDataScript = original.selectFirst("script#__NEXT_DATA__");
        int scriptCount = original.select("script").size();
        boolean jsShell = nextDataScript != null
                || original.selectFirst("#root, #__next, #app, [ng-app], [data-reactroot]") != null
                || scriptCount >= 15;

        String title = firstNonBlank(
                meta(original, "meta[property=og:title]"),
                original.title(),
                textOf(original.selectFirst("h1")));
        String siteName = firstNonBlank(meta(original, "meta[property=og:site_name]"), hostOf(url));
        String author = firstNonBlank(
                meta(original, "meta[name=author]"),
                jsonLdField(jsonLd, "author"),
                meta(original, "meta[property=article:author]"));
        String published = firstNonBlank(
                meta(original, "meta[property=article:published_time]"),
                jsonLdField(jsonLd, "datePublished"),
                attr(original.selectFirst("time[datetime]"), "datetime"));

        // 1. DOM extraction on a copy with page chrome removed.
        Document doc = original.clone();
        stripChrome(doc);
        Choice choice = chooseContainer(doc);
        List<String> texts = paragraphsFrom(choice.element());
        String method = choice.method();

        // 2. JSON-LD articleBody / reviewBody, if the DOM gave us too little.
        if (wordCount(texts) < FALLBACK_BELOW_WORDS) {
            List<String> fromJsonLd = jsonLdText(jsonLd);
            if (wordCount(fromJsonLd) > wordCount(texts)) {
                texts = fromJsonLd;
                method = "JSON_LD";
            }
        }
        // 3. Long strings inside Next.js page data.
        if (wordCount(texts) < FALLBACK_BELOW_WORDS && nextDataScript != null) {
            List<String> fromNextData = nextDataText(nextDataScript.data());
            if (wordCount(fromNextData) > wordCount(texts)) {
                texts = fromNextData;
                method = "NEXT_DATA";
            }
        }

        List<Paragraph> paragraphs = new ArrayList<>();
        for (String t : texts) {
            paragraphs.add(new Paragraph("P" + (paragraphs.size() + 1), t));
        }
        return new Extracted(title, siteName, author, published, method, paragraphs,
                wordCount(texts), nextDataScript != null, jsShell, scriptCount);
    }

    private void stripChrome(Document doc) {
        doc.select(CHROME_TAGS).remove();
        int pageWords = wordCount(paragraphsFrom(doc.body()));
        for (Element e : doc.select(CHROME_BY_NAME)) {
            if (e.parent() == null) continue; // already removed with an ancestor
            if (wordCount(paragraphsFrom(e)) < pageWords * MAX_CHROME_SHARE) {
                e.remove();
            }
        }
    }

    // ---- container choice -------------------------------------------------------------------

    private record Choice(Element element, String method) {
    }

    private Choice chooseContainer(Document doc) {
        // Candidates from most to least specific.
        List<Choice> candidates = new ArrayList<>();
        Element articleBody = doc.selectFirst("[itemprop=articleBody]");
        if (articleBody != null) candidates.add(new Choice(articleBody, "ARTICLE_BODY"));
        Element article = largest(doc.select("article"));
        if (article != null) candidates.add(new Choice(article, "ARTICLE"));
        Element main = largest(doc.select("main, [role=main]"));
        if (main != null) candidates.add(new Choice(main, "MAIN"));
        Element densest = densestBlock(doc);
        if (densest != null) candidates.add(new Choice(densest, "DENSEST_BLOCK"));
        candidates.add(new Choice(doc.body(), "BODY"));

        int best = candidates.stream().mapToInt(c -> wordCount(paragraphsFrom(c.element()))).max().orElse(0);
        for (Choice c : candidates) {
            if (wordCount(paragraphsFrom(c.element())) >= best * CONTAINER_SHARE) {
                return c;
            }
        }
        return candidates.getLast();
    }

    private Element largest(Elements elements) {
        Element best = null;
        int bestWords = -1;
        for (Element e : elements) {
            int w = wordCount(paragraphsFrom(e));
            if (w > bestWords) {
                best = e;
                bestWords = w;
            }
        }
        return best;
    }

    /** The element whose direct &lt;p&gt; children hold the most words. */
    private Element densestBlock(Document doc) {
        Element best = null;
        int bestWords = 0;
        for (Element p : doc.select("p")) {
            Element parent = p.parent();
            if (parent == null) continue;
            int w = 0;
            for (Element child : parent.children()) {
                if (child.normalName().equals("p")) w += words(child.text());
            }
            if (w > bestWords) {
                best = parent;
                bestWords = w;
            }
        }
        return best;
    }

    // ---- paragraphs ----------------------------------------------------------------------------

    private List<String> paragraphsFrom(Element container) {
        Set<String> seen = new LinkedHashSet<>();
        if (container == null) return new ArrayList<>();
        for (Element e : container.select("p, li, blockquote")) {
            // Skip wrappers (an <li> holding <p>s) so text isn't counted twice.
            if (e.select("p, li, blockquote").size() > 1) continue;
            String text = e.text().strip();
            if (words(text) < MIN_PARAGRAPH_WORDS) continue;
            if (BOILERPLATE.matcher(text).find()) continue;
            int linkChars = e.select("a").stream().mapToInt(a -> a.text().length()).sum();
            if (linkChars > text.length() * MAX_LINK_DENSITY) continue;
            seen.add(text);
        }
        return dropContained(new ArrayList<>(seen));
    }

    /**
     * Drops a paragraph whose text appears inside another paragraph (pull quotes, teaser repeats),
     * so the same sentence isn't extracted, and counted, twice. Comparison ignores case and punctuation.
     */
    static List<String> dropContained(List<String> texts) {
        List<String> normalized = texts.stream()
                .map(t -> t.toLowerCase(java.util.Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").strip())
                .toList();
        List<String> kept = new ArrayList<>();
        for (int i = 0; i < texts.size(); i++) {
            boolean contained = false;
            for (int j = 0; j < texts.size() && !contained; j++) {
                contained = i != j
                        && normalized.get(j).length() > normalized.get(i).length()
                        && normalized.get(j).contains(normalized.get(i));
            }
            if (!contained) kept.add(texts.get(i));
        }
        return kept;
    }

    // ---- embedded data fallbacks ---------------------------------------------------------------

    private List<JsonNode> readJsonLd(Document doc) {
        List<JsonNode> nodes = new ArrayList<>();
        for (Element script : doc.select("script[type=application/ld+json]")) {
            try {
                collectJsonLd(mapper.readTree(script.data()), nodes);
            } catch (Exception ignored) {
                // Malformed JSON-LD is common; skip it.
            }
        }
        return nodes;
    }

    private void collectJsonLd(JsonNode node, List<JsonNode> out) {
        if (node == null) return;
        if (node.isArray()) {
            node.forEach(n -> collectJsonLd(n, out));
        } else if (node.isObject()) {
            out.add(node);
            collectJsonLd(node.get("@graph"), out);
        }
    }

    private List<String> jsonLdText(List<JsonNode> nodes) {
        List<String> texts = new ArrayList<>();
        for (JsonNode n : nodes) {
            if (n.hasNonNull("articleBody")) texts.addAll(splitLongText(htmlToText(n.get("articleBody").asText())));
            if (n.hasNonNull("reviewBody")) texts.addAll(splitLongText(htmlToText(n.get("reviewBody").asText())));
            JsonNode reviews = n.get("review");
            if (reviews != null) {
                for (JsonNode r : reviews.isArray() ? reviews : mapper.createArrayNode().add(reviews)) {
                    if (r.hasNonNull("reviewBody")) texts.addAll(splitLongText(htmlToText(r.get("reviewBody").asText())));
                }
            }
        }
        return dedupe(texts);
    }

    private List<String> nextDataText(String json) {
        List<String> texts = new ArrayList<>();
        try {
            collectLongStrings(mapper.readTree(json), texts);
        } catch (Exception ignored) {
            return texts;
        }
        return dedupe(texts);
    }

    private void collectLongStrings(JsonNode node, List<String> out) {
        if (node == null) return;
        if (node.isTextual()) {
            String text = htmlToText(node.asText());
            if (words(text) >= 40) out.addAll(splitLongText(text));
        } else if (node.isContainerNode()) {
            node.forEach(n -> collectLongStrings(n, out));
        }
    }

    private String jsonLdField(List<JsonNode> nodes, String field) {
        for (JsonNode n : nodes) {
            JsonNode v = n.get(field);
            if (v == null) continue;
            if (v.isArray() && !v.isEmpty()) v = v.get(0);
            if (v.isObject() && v.hasNonNull("name")) return v.get("name").asText();
            if (v.isTextual() && !v.asText().isBlank()) return v.asText();
        }
        return null;
    }

    // ---- helpers -------------------------------------------------------------------------------

    /** Splits text on line breaks; a single huge block is cut into ~100-word chunks at sentence ends. */
    private static List<String> splitLongText(String text) {
        List<String> out = new ArrayList<>();
        for (String block : text.split("\\n\\s*\\n|\\r?\\n")) {
            block = block.strip();
            if (words(block) < MIN_PARAGRAPH_WORDS) continue;
            if (words(block) <= 150) {
                out.add(block);
                continue;
            }
            StringBuilder current = new StringBuilder();
            for (String sentence : block.split("(?<=[.!?])\\s+")) {
                current.append(sentence).append(' ');
                if (words(current.toString()) >= 100) {
                    out.add(current.toString().strip());
                    current.setLength(0);
                }
            }
            if (words(current.toString()) >= MIN_PARAGRAPH_WORDS) out.add(current.toString().strip());
        }
        return out;
    }

    private static String htmlToText(String s) {
        if (s.indexOf('<') < 0) return s;
        // Keep paragraph breaks: turn block tags into newlines before stripping tags.
        String withBreaks = s.replaceAll("(?i)</p>|<br\\s*/?>|</li>|</h[1-6]>", "\n");
        return Jsoup.parse(withBreaks).wholeText();
    }

    private static List<String> dedupe(List<String> texts) {
        return dropContained(new ArrayList<>(new LinkedHashSet<>(texts)));
    }

    static int words(String s) {
        if (s == null || s.isBlank()) return 0;
        return s.strip().split("\\s+").length;
    }

    static int wordCount(List<String> texts) {
        return texts.stream().mapToInt(ContentExtractor::words).sum();
    }

    private static String meta(Document doc, String selector) {
        return attr(doc.selectFirst(selector), "content");
    }

    private static String attr(Element e, String name) {
        return e == null ? null : e.attr(name);
    }

    private static String textOf(Element e) {
        return e == null ? null : e.text();
    }

    private static String hostOf(String url) {
        try {
            return URI.create(url).getHost();
        } catch (Exception e) {
            return null;
        }
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v.strip();
        }
        return null;
    }
}
