package com.researchagent.analyze;

import com.researchagent.model.Brief;
import com.researchagent.model.Brief.ConsensusClaim;
import com.researchagent.model.Brief.ContradictedClaim;
import com.researchagent.model.Brief.MatrixRow;
import com.researchagent.model.Brief.SingleSourceClaim;
import com.researchagent.model.Brief.SourceSummary;
import com.researchagent.model.Brief.UnaddressedClaim;
import com.researchagent.model.Citation;
import com.researchagent.model.Claim;
import com.researchagent.model.Evidence;
import com.researchagent.model.Source;
import com.researchagent.model.SourceStatus;
import com.researchagent.model.Stance;
import com.researchagent.model.Statement;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Cross-source reasoning, in plain code (no LLM). One row per topic statement (plus one per "other"
 * label); each usable source gets one stance per row; each row is then sorted into consensus /
 * contradicted / single-source, and statements no source touches are unaddressed.
 *
 * Rules, per row, looking only at usable sources that take a position:
 *   1. A source's stance: SUPPORTS or OPPOSES when its claims on the row lean at least 2:1 that way
 *      (a MIXED claim counts half each way); otherwise MIXED. Minority points are reported as caveats.
 *   2. Some source SUPPORTS and some source OPPOSES             -> contradicted
 *   3. Otherwise, 2+ sources share the clear stance             -> consensus (MIXED sources listed, not counted)
 *      Exactly 1 source has a clear stance                      -> single-source (MIXED sources noted)
 *      Only MIXED sources: 2+ -> consensus that it's contested within sources; 1 -> single-source
 * Silent sources count for nothing. Claims from unusable sources are ignored.
 */
public final class Classifier {

    public Brief classify(String topic, List<Source> sources, List<Statement> statements, List<Claim> claims) {
        Map<String, Integer> order = new HashMap<>();
        for (int i = 0; i < sources.size(); i++) order.put(sources.get(i).id(), i);
        Comparator<String> bySourceOrder = Comparator.comparingInt(id -> order.getOrDefault(id, Integer.MAX_VALUE));

        Set<String> usable = sources.stream().filter(s -> s.status().usable()).map(Source::id)
                .collect(Collectors.toSet());
        List<Claim> kept = claims.stream().filter(c -> usable.contains(c.sourceId())).toList();
        Map<String, Long> claimsPerSource = kept.stream().collect(Collectors.groupingBy(Claim::sourceId, Collectors.counting()));

        // Statement rows in the statement order; "other" rows keyed by their label.
        Map<String, Row> statementRows = new LinkedHashMap<>();
        for (Statement s : statements) statementRows.put(s.id(), new Row(s.id(), s.text(), bySourceOrder));
        Map<String, Row> otherRows = new TreeMap<>();
        for (Claim c : kept) {
            Row row = c.matched() ? statementRows.get(c.statementId()) : null;
            if (row == null) {
                String label = c.otherLabel() == null || c.otherLabel().isBlank() ? "unlabeled" : c.otherLabel().strip();
                row = otherRows.computeIfAbsent(key(label), k -> new Row(null, label, bySourceOrder));
            }
            row.add(c);
        }

        List<Ranked<ConsensusClaim>> consensus = new ArrayList<>();
        List<Ranked<ContradictedClaim>> contradicted = new ArrayList<>();
        List<Ranked<SingleSourceClaim>> singles = new ArrayList<>();
        List<UnaddressedClaim> unaddressed = new ArrayList<>();

        for (Statement s : statements) {
            Row row = statementRows.get(s.id());
            if (row.isEmpty()) {
                unaddressed.add(new UnaddressedClaim(s.id(), s.text(), s.whyItMatters()));
            } else {
                classifyRow(row, usable.size(), claimsPerSource, consensus, contradicted, singles);
            }
        }
        for (Row row : otherRows.values()) {
            classifyRow(row, usable.size(), claimsPerSource, consensus, contradicted, singles);
        }

        return new Brief(
                topic,
                usable.size(),
                sources.size(),
                sources.stream().map(s -> new SourceSummary(s.id(), s.title(),
                        s.finalUrl() != null ? s.finalUrl() : s.input(), s.status(), s.reason())).toList(),
                statements,
                matrix(statementRows.values(), sources, usable),
                sorted(consensus),
                sorted(contradicted),
                sorted(singles),
                unaddressed,
                limitations(sources, usable.size(), !otherRows.isEmpty()));
    }

    private void classifyRow(Row row, int usableCount, Map<String, Long> claimsPerSource,
                             List<Ranked<ConsensusClaim>> consensus, List<Ranked<ContradictedClaim>> contradicted,
                             List<Ranked<SingleSourceClaim>> singles) {
        List<String> sup = row.sourcesWith(Stance.SUPPORTS);
        List<String> opp = row.sourcesWith(Stance.OPPOSES);
        List<String> mixed = row.sourcesWith(Stance.MIXED);

        if (!sup.isEmpty() && !opp.isEmpty()) {
            contradicted.add(contradiction(row, sup, opp, mixed));
            return;
        }
        List<String> clear = sup.isEmpty() ? opp : sup;
        Stance clearStance = sup.isEmpty() ? Stance.OPPOSES : Stance.SUPPORTS;
        if (clear.size() >= 2) {
            consensus.add(agreement(row, clearStance, clear, mixed, usableCount, claimsPerSource));
        } else if (clear.size() == 1) {
            singles.add(single(row, clearStance, clear.getFirst(), mixed, claimsPerSource));
        } else if (mixed.size() >= 2) {
            consensus.add(agreement(row, Stance.MIXED, mixed, List.of(), usableCount, claimsPerSource));
        } else {
            singles.add(single(row, Stance.MIXED, mixed.getFirst(), List.of(), claimsPerSource));
        }
    }

    // ---- the three row outcomes ----------------------------------------------------------------

    /** Consensus: number of agreeing sources, then strongest evidence, then prevalence (all descending). */
    private Ranked<ConsensusClaim> agreement(Row row, Stance stance, List<String> agreeing, List<String> mixed,
                                             int usableCount, Map<String, Long> claimsPerSource) {
        double prevalence = row.prevalence(agreeing, claimsPerSource);
        Evidence evidence = row.strongestEvidence(agreeing, stance);
        String reason = String.format(Locale.ROOT, "%d/%d usable sources %s; strongest evidence %s; on average %.0f%% of each one's claims",
                agreeing.size(), usableCount, verb(stance), name(evidence), prevalence * 100);
        if (!mixed.isEmpty()) reason += "; mixed view from " + String.join(", ", mixed);
        reason += caveats(row, agreeing, stance);
        ConsensusClaim claim = new ConsensusClaim(row.statementId, row.text, stance, agreeing, mixed, usableCount,
                row.citations(agreeing, stance), reason);
        return new Ranked<>(claim, row.text, agreeing.size(), strength(evidence), prevalence);
    }

    /** Contradiction: balance (size of the smaller side), then sources involved, then strongest evidence. */
    private Ranked<ContradictedClaim> contradiction(Row row, List<String> sup, List<String> opp, List<String> mixed) {
        List<ContradictedClaim.Side> sides = new ArrayList<>();
        sides.add(new ContradictedClaim.Side(Stance.SUPPORTS, sup, row.citations(sup, Stance.SUPPORTS)));
        sides.add(new ContradictedClaim.Side(Stance.OPPOSES, opp, row.citations(opp, Stance.OPPOSES)));
        if (!mixed.isEmpty()) sides.add(new ContradictedClaim.Side(Stance.MIXED, mixed, row.citations(mixed, Stance.MIXED)));

        List<String> all = new ArrayList<>(sup);
        all.addAll(opp);
        all.addAll(mixed);
        Evidence evidence = row.strongestEvidence(all, Stance.MIXED);
        String reason = String.format(Locale.ROOT, "%s support vs %s oppose%s; strongest evidence %s",
                String.join(", ", sup), String.join(", ", opp),
                mixed.isEmpty() ? "" : " (" + String.join(", ", mixed) + " mixed)", name(evidence));
        reason += caveats(row, sup, Stance.SUPPORTS) + caveats(row, opp, Stance.OPPOSES);
        return new Ranked<>(new ContradictedClaim(row.statementId, row.text, sides, reason),
                row.text, Math.min(sup.size(), opp.size()), all.size(), strength(evidence));
    }

    /** Single source: strongest evidence first, then claims tied to a topic statement, then prevalence. */
    private Ranked<SingleSourceClaim> single(Row row, Stance stance, String sourceId, List<String> mixed,
                                             Map<String, Long> claimsPerSource) {
        Evidence evidence = row.strongestEvidence(List.of(sourceId), stance);
        double prevalence = row.prevalence(List.of(sourceId), claimsPerSource);
        List<String> notes = new ArrayList<>();
        if (row.statementId == null) notes.add("Fits none of the topic statements");
        if (!mixed.isEmpty()) notes.add("Also discussed by " + String.join(", ", mixed) + ", arguing both sides, which does not confirm it");
        String reason = String.format(Locale.ROOT, "only %s; evidence %s; %.0f%% of %s's claims",
                sourceId, name(evidence), prevalence * 100, sourceId) + caveats(row, List.of(sourceId), stance);
        SingleSourceClaim claim = new SingleSourceClaim(row.statementId, row.text, stance, sourceId,
                row.citations(List.of(sourceId), stance), evidence, notes.isEmpty() ? null : String.join(". ", notes), reason);
        return new Ranked<>(claim, row.text, strength(evidence), row.statementId == null ? 0 : 1, prevalence);
    }

    // ---- matrix, limitations, ranking ----------------------------------------------------------

    private List<MatrixRow> matrix(Iterable<Row> rows, List<Source> sources, Set<String> usable) {
        List<MatrixRow> out = new ArrayList<>();
        for (Row row : rows) {
            Map<String, String> cells = new LinkedHashMap<>();
            for (Source s : sources) {
                cells.put(s.id(), !usable.contains(s.id()) ? "⛔" : cell(row.stanceOf(s.id())));
            }
            out.add(new MatrixRow(row.statementId, row.text, cells));
        }
        return out;
    }

    private static String cell(Stance stance) {
        if (stance == null) return "·";
        return switch (stance) {
            case SUPPORTS -> "+";
            case OPPOSES -> "−";
            case MIXED -> "±";
        };
    }

    private List<String> limitations(List<Source> sources, int usableCount, boolean hasOther) {
        List<String> out = new ArrayList<>();
        for (Source s : sources) {
            if (!s.status().usable()) {
                out.add(s.id() + " excluded (" + s.status() + "): " + s.reason());
            } else if (s.status() == SourceStatus.PARTIAL) {
                out.add(s.id() + " may be incomplete: " + s.reason());
            }
        }
        if (usableCount < 2) {
            out.add("Fewer than 2 usable sources: nothing can be confirmed or contradicted, so every claim is single-source.");
        }
        out.add("Topic statements were written from the topic alone, before any source was read; a source that "
                + "does not take a position on a statement counts neither for nor against it.");
        if (hasOther) {
            out.add("Claims that fit none of the statements are listed as single-source and were not compared across sources.");
        }
        return out;
    }

    /** "; S1 also makes 1 point the other way" — so a 2:1 majority never hides the minority from the reader. */
    private static String caveats(Row row, List<String> sources, Stance stance) {
        List<String> parts = new ArrayList<>();
        for (String id : sources) {
            int n = row.dissenting(id, stance);
            if (n > 0) parts.add(id + " also makes " + n + (n == 1 ? " point" : " points") + " the other way");
        }
        return parts.isEmpty() ? "" : "; " + String.join(", ", parts);
    }

    private static String verb(Stance stance) {
        return switch (stance) {
            case SUPPORTS -> "support it";
            case OPPOSES -> "oppose it";
            case MIXED -> "argue both sides";
        };
    }

    private static String name(Evidence e) {
        return e == null ? "unknown" : e.name().toLowerCase(Locale.ROOT);
    }

    private static int strength(Evidence e) {
        return e == null ? 0 : e.strength();
    }

    /** An item plus its sort keys (all descending), with its text as a final, stable tie-break. */
    private record Ranked<T>(T item, String label, double k1, double k2, double k3) {
    }

    private static <T> List<T> sorted(List<Ranked<T>> items) {
        return items.stream()
                .sorted(Comparator.<Ranked<T>>comparingDouble(r -> -r.k1())
                        .thenComparingDouble(r -> -r.k2())
                        .thenComparingDouble(r -> -r.k3())
                        .thenComparing(Ranked::label))
                .map(Ranked::item)
                .toList();
    }

    static String key(String label) {
        return label.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
    }

    // ---- one row -------------------------------------------------------------------------------

    private static final class Row {
        final String statementId; // null for an "other" row
        final String text;
        final Map<String, List<Claim>> bySource;

        Row(String statementId, String text, Comparator<String> bySourceOrder) {
            this.statementId = statementId;
            this.text = text;
            this.bySource = new TreeMap<>(bySourceOrder);
        }

        void add(Claim c) {
            bySource.computeIfAbsent(c.sourceId(), k -> new ArrayList<>()).add(c);
        }

        boolean isEmpty() {
            return bySource.isEmpty();
        }

        /**
         * Rule 1: one stance per source per row, by a 2:1 majority of its claims (a MIXED claim counts half
         * for each side). A source with five supporting points and one caveat SUPPORTS; 3 vs 2 is MIXED.
         * Null means the source is silent.
         */
        Stance stanceOf(String sourceId) {
            List<Claim> claims = bySource.get(sourceId);
            if (claims == null) return null;
            double sup = 0;
            double opp = 0;
            for (Claim c : claims) {
                switch (c.stance()) {
                    case SUPPORTS -> sup += 1;
                    case OPPOSES -> opp += 1;
                    case MIXED -> {
                        sup += 0.5;
                        opp += 0.5;
                    }
                }
            }
            if (sup >= 2 * opp && sup > opp) return Stance.SUPPORTS;
            if (opp >= 2 * sup && opp > sup) return Stance.OPPOSES;
            return Stance.MIXED;
        }

        /** Claims from these sources that point the given way (all of them for a MIXED side). */
        private List<Claim> claimsOn(List<String> sourceIds, Stance side) {
            return sourceIds.stream()
                    .flatMap(id -> bySource.getOrDefault(id, List.of()).stream())
                    .filter(c -> side == Stance.MIXED || c.stance() == side)
                    .toList();
        }

        /** How many of a source's claims on this row went against its overall stance. */
        int dissenting(String sourceId, Stance stance) {
            if (stance == Stance.MIXED) return 0;
            return (int) bySource.getOrDefault(sourceId, List.of()).stream()
                    .filter(c -> c.stance() != stance).count();
        }

        List<String> sourcesWith(Stance stance) {
            return bySource.keySet().stream().filter(id -> stanceOf(id) == stance).toList();
        }

        /**
         * Citations from the given sources that back the given side, strongest evidence first per source.
         * A source's minority claims are left out of its side, so a SUPPORTS side never quotes a caveat.
         */
        List<Citation> citations(List<String> sourceIds, Stance side) {
            List<Citation> out = new ArrayList<>();
            for (String id : sourceIds) {
                claimsOn(List.of(id), side).stream()
                        .map(Claim::citation)
                        .filter(c -> c != null)
                        .distinct()
                        .sorted(Comparator.comparingInt((Citation c) -> -strength(c.evidence())))
                        .forEach(out::add);
            }
            return out;
        }

        Evidence strongestEvidence(List<String> sourceIds, Stance side) {
            return claimsOn(sourceIds, side).stream()
                    .map(Claim::evidence)
                    .filter(e -> e != null)
                    .max(Comparator.comparingInt(Evidence::strength))
                    .orElse(null);
        }

        /** Average, over the given sources, of the share of each source's claims that are on this row. */
        double prevalence(List<String> sourceIds, Map<String, Long> claimsPerSource) {
            return sourceIds.stream()
                    .mapToDouble(id -> (double) bySource.getOrDefault(id, List.of()).size()
                            / Math.max(1, claimsPerSource.getOrDefault(id, 1L)))
                    .average().orElse(0);
        }
    }
}
