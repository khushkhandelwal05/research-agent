package com.researchagent.render;

import com.researchagent.model.Brief;
import com.researchagent.model.Brief.ConsensusClaim;
import com.researchagent.model.Brief.ContradictedClaim;
import com.researchagent.model.Brief.SingleSourceClaim;
import com.researchagent.model.Brief.SourceSummary;
import com.researchagent.model.Citation;
import com.researchagent.model.Stance;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Renders a {@link Brief} as Markdown for a PM or analyst. Plain code, no LLM: every sentence in the
 * output is either fixed template text or a verified quote, so the rendering step cannot invent anything.
 */
public final class BriefRenderer {

    /** Quotes shown per source per side; the rest stay in 04-brief.json. */
    static final int QUOTES_PER_SOURCE = 2;
    /** Words of the quote used to build a link that jumps to the passage (browser text fragments). */
    static final int FRAGMENT_WORDS = 8;

    public String render(Brief brief, String model, String generatedAt) {
        Map<String, SourceSummary> sources = new LinkedHashMap<>();
        brief.sources().forEach(s -> sources.put(s.id(), s));
        StringBuilder md = new StringBuilder();

        md.append("# Research brief: ").append(brief.topic()).append("\n\n");
        md.append("_").append(brief.sourcesUsable()).append(" of ").append(brief.sourcesTotal())
                .append(" sources usable · generated ").append(generatedAt).append(" · model `").append(model).append("`_\n\n");

        atAGlance(md, brief);
        consensus(md, brief, sources);
        contradictions(md, brief, sources);
        singleSource(md, brief, sources);
        unaddressed(md, brief);
        matrix(md, brief, sources);
        sourceList(md, brief);
        limitations(md, brief);
        return md.toString();
    }

    // ---- sections ------------------------------------------------------------------------------

    private void atAGlance(StringBuilder md, Brief b) {
        long singlesOnStatements = b.singleSourceClaims().stream().filter(c -> c.statementId() != null).count();
        md.append("## At a glance\n\n");
        md.append("- **").append(b.consensusClaims().size()).append(" consensus claims**: several sources take the same side.\n");
        md.append("- **").append(b.contradictedClaims().size()).append(" contradictions**: sources take opposite sides.\n");
        md.append("- **").append(singlesOnStatements).append(" outlier claims** (only one source) on the topic statements, plus ")
                .append(b.singleSourceClaims().size() - singlesOnStatements).append(" other points only one source makes.\n");
        md.append("- **").append(b.unaddressedClaims().size()).append(" unaddressed**: statements no source takes a position on.\n");
        if (!b.contradictedClaims().isEmpty()) {
            ContradictedClaim top = b.contradictedClaims().getFirst();
            md.append("\nThe sharpest disagreement: **").append(top.statement()).append("** (").append(top.rankReason()).append(").\n");
        }
        md.append('\n');
    }

    private void consensus(StringBuilder md, Brief b, Map<String, SourceSummary> sources) {
        md.append("## Where sources agree\n\n");
        if (b.consensusClaims().isEmpty()) {
            md.append("_No statement has two or more sources on the same side._\n\n");
            return;
        }
        for (ConsensusClaim c : b.consensusClaims()) {
            md.append("### ").append(c.statementId()).append(" · ").append(c.statement()).append("\n\n");
            md.append("**").append(verdict(c.stance())).append("** · ").append(c.agreeingSources().size()).append(" of ")
                    .append(c.usableSources()).append(" sources (").append(String.join(", ", c.agreeingSources())).append(")\n\n");
            quotes(md, c.agreeingSources(), c.citations(), sources);
            md.append("<sub>").append(c.rankReason()).append("</sub>\n\n");
        }
    }

    private void contradictions(StringBuilder md, Brief b, Map<String, SourceSummary> sources) {
        md.append("## Where sources disagree\n\n");
        if (b.contradictedClaims().isEmpty()) {
            md.append("_No statement has sources on opposite sides._\n\n");
            return;
        }
        for (ContradictedClaim c : b.contradictedClaims()) {
            md.append("### ").append(c.statementId()).append(" · ").append(c.statement()).append("\n\n");
            for (ContradictedClaim.Side side : c.sides()) {
                md.append("**").append(sideLabel(side.stance())).append("** (").append(String.join(", ", side.sources())).append(")\n\n");
                quotes(md, side.sources(), side.citations(), sources);
            }
            md.append("<sub>").append(c.rankReason()).append("</sub>\n\n");
        }
    }

    private void singleSource(StringBuilder md, Brief b, Map<String, SourceSummary> sources) {
        md.append("## Outlier claims (only one source makes them)\n\n");
        List<SingleSourceClaim> onStatements = b.singleSourceClaims().stream().filter(c -> c.statementId() != null).toList();
        List<SingleSourceClaim> other = b.singleSourceClaims().stream().filter(c -> c.statementId() == null).toList();
        if (onStatements.isEmpty() && other.isEmpty()) {
            md.append("_None._\n\n");
            return;
        }
        for (SingleSourceClaim c : onStatements) {
            md.append("### ").append(c.statementId()).append(" · ").append(c.statement()).append("\n\n");
            md.append("**").append(verdict(c.stance())).append("**, by ").append(c.sourceId()).append(" only. Unconfirmed.");
            if (c.note() != null) md.append(" ").append(c.note()).append('.');
            md.append("\n\n");
            quotes(md, List.of(c.sourceId()), c.citations(), sources);
        }
        if (!other.isEmpty()) {
            md.append("### Other points, not compared across sources\n\n");
            md.append("These fit none of the topic statements, so no other source was checked against them. Strongest evidence first.\n\n");
            for (SingleSourceClaim c : other) {
                Citation q = c.citations().getFirst();
                md.append("- **").append(c.statement()).append("** (").append(c.sourceId()).append(", ")
                        .append(evidence(q)).append("): ").append(quoteLink(q, sources)).append('\n');
            }
            md.append('\n');
        }
    }

    private void unaddressed(StringBuilder md, Brief b) {
        md.append("## What no source addresses\n\n");
        if (b.unaddressedClaims().isEmpty()) {
            md.append("_Every topic statement was addressed by at least one source._\n\n");
            return;
        }
        for (Brief.UnaddressedClaim u : b.unaddressedClaims()) {
            md.append("- **").append(u.statementId()).append(" · ").append(u.statement()).append("**");
            if (u.whyItMatters() != null && !u.whyItMatters().isBlank()) md.append(" ").append(u.whyItMatters());
            md.append('\n');
        }
        md.append('\n');
    }

    private void matrix(StringBuilder md, Brief b, Map<String, SourceSummary> sources) {
        md.append("## Evidence matrix\n\n");
        md.append("`+` supports · `−` opposes · `±` mixed · `·` silent · `⛔` source unusable\n\n");
        md.append("| Statement |");
        sources.keySet().forEach(id -> md.append(' ').append(id).append(" |"));
        md.append("\n|---|");
        sources.keySet().forEach(id -> md.append(":---:|"));
        md.append('\n');
        for (Brief.MatrixRow row : b.matrix()) {
            md.append("| ").append(row.statementId()).append(" ").append(cell(row.statement())).append(" |");
            row.cells().values().forEach(v -> md.append(' ').append(v).append(" |"));
            md.append('\n');
        }
        md.append('\n');
    }

    private void sourceList(StringBuilder md, Brief b) {
        md.append("## Sources\n\n| ID | Source | Status |\n|---|---|---|\n");
        for (SourceSummary s : b.sources()) {
            String title = s.title() == null || s.title().isBlank() ? s.url() : s.title();
            md.append("| ").append(s.id()).append(" | [").append(cell(title)).append("](").append(s.url()).append(") | ")
                    .append(s.status()).append(": ").append(cell(s.reason())).append(" |\n");
        }
        md.append('\n');
    }

    private void limitations(StringBuilder md, Brief b) {
        md.append("## Limitations and method\n\n");
        for (String l : b.limitations()) md.append("- ").append(l).append('\n');
        md.append("- Claims were extracted and matched to statements by a language model. Every quote was checked "
                + "against the source text, but whether a quote supports or opposes a statement is the model's "
                + "judgment: read the quotes before relying on a row.\n");
        md.append("- A source's side on a statement needs at least a 2:1 majority of its claims; otherwise it counts as mixed.\n\n");
    }

    // ---- helpers -------------------------------------------------------------------------------

    /** Up to QUOTES_PER_SOURCE quotes per source, in source order, as block quotes with links. */
    private void quotes(StringBuilder md, List<String> sourceIds, List<Citation> citations, Map<String, SourceSummary> sources) {
        Map<String, List<Citation>> bySource = citations.stream()
                .collect(Collectors.groupingBy(Citation::sourceId, LinkedHashMap::new, Collectors.toList()));
        List<String> lines = new ArrayList<>();
        for (String id : sourceIds) {
            List<Citation> list = bySource.getOrDefault(id, List.of());
            for (Citation q : list.stream().limit(QUOTES_PER_SOURCE).toList()) {
                lines.add("> " + quoteLink(q, sources) + " · " + evidence(q));
            }
            if (list.size() > QUOTES_PER_SOURCE) {
                lines.add("> <sub>+" + (list.size() - QUOTES_PER_SOURCE) + " more from " + id + " in 04-brief.json</sub>");
            }
        }
        if (lines.isEmpty()) return;
        md.append(String.join("\n>\n", lines)).append("\n\n");
    }

    private static String quoteLink(Citation q, Map<String, SourceSummary> sources) {
        SourceSummary s = sources.get(q.sourceId());
        String label = q.sourceId() + " ¶" + q.paragraphId().replace("P", "");
        String quote = "“" + q.quote().strip().replaceAll("^[\"“”']+|[\"“”']+$", "") + "”";
        if (s == null || s.url() == null) return quote + " (" + label + ")";
        return quote + " ([" + label + "](" + textFragmentUrl(s.url(), q.quote()) + "))";
    }

    /**
     * A link that scrolls to and highlights the quote in browsers that support text fragments
     * (Chrome, Edge, Safari); elsewhere it simply opens the page. Uses the quote's first few words,
     * stopping at any quote mark, since pages and models often differ in curly vs straight quotes.
     */
    static String textFragmentUrl(String url, String quote) {
        if (!url.startsWith("http")) return url;
        String firstPiece = quote.strip().replaceAll("^[\"“”'‘’]+", "").split("\\s*(\\.\\.\\.|…)\\s*")[0];
        List<String> words = new ArrayList<>();
        for (String w : firstPiece.split("\\s+")) {
            if (w.matches(".*[\"“”'‘’].*") || words.size() == FRAGMENT_WORDS) break;
            words.add(w);
        }
        if (words.size() < 3) return url;
        String text = String.join(" ", words).replaceAll("[,.;:]+$", "");
        String encoded = URLEncoder.encode(text, StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("-", "%2D"); // '-' is a separator in text-fragment syntax
        return url.replaceAll("#.*$", "") + "#:~:text=" + encoded;
    }

    private static String evidence(Citation q) {
        return q.evidence() == null ? "evidence unknown" : q.evidence().name().toLowerCase(Locale.ROOT);
    }

    private static String verdict(Stance s) {
        return switch (s) {
            case SUPPORTS -> "Supported";
            case OPPOSES -> "Opposed";
            case MIXED -> "Both sides argued";
        };
    }

    private static String sideLabel(Stance s) {
        return switch (s) {
            case SUPPORTS -> "Supports";
            case OPPOSES -> "Opposes";
            case MIXED -> "Mixed";
        };
    }

    /** Escapes a value for a Markdown table cell. */
    private static String cell(String s) {
        return s == null ? "" : s.replace("|", "\\|").replace("\n", " ");
    }
}
