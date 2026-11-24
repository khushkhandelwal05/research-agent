package com.researchagent;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.researchagent.analyze.Classifier;
import com.researchagent.extract.ClaimExtractor;
import com.researchagent.extract.ClaimJudge;
import com.researchagent.extract.StatementGenerator;
import com.researchagent.fetch.SourceLoader;
import com.researchagent.llm.LlmClient;
import com.researchagent.llm.LlmConfig;
import com.researchagent.model.Brief;
import com.researchagent.model.Claim;
import com.researchagent.model.ExtractedClaim;
import com.researchagent.model.Source;
import com.researchagent.model.Statement;
import com.researchagent.render.BriefRenderer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Entry point. Stages:
 *   1 fetch + clean            -> 01-fetch.json
 *   2 topic statements         -> 02-statements.json
 *   3a extract claims (LLM)    -> 03-claims.json   (neutral claims, verified quotes)
 *   3b judge each claim (LLM)  -> 03-claims.json   (statement + SUPPORTS/OPPOSES/MIXED)
 *   4 classify (plain code)    -> 04-brief.json
 *   5 render (plain code)      -> 05-brief.md      (re-render alone with --render runs/<id>/04-brief.json)
 *
 * Usage:
 *   ./gradlew run --args="--urls links.txt --topic 'AI and jobs'"
 *   ./gradlew run --args="--from runs/<id>/01-fetch.json --topic 'AI and jobs' --extract S1,S3,S5"
 * Model options (also settable in agent.properties; flags win):
 *   --provider ollama|anthropic  --model NAME  --base-url URL  --api-key-env VAR  --effort LEVEL  --config FILE
 * Other options: --out runs  --statements file.txt  --extract all|S1,S3
 */
public final class Main {

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    public static void main(String[] args) throws Exception {
        List<String> inputs = new ArrayList<>();
        Path outRoot = Path.of("runs");
        Path from = null;
        String topic = null;
        String extract = null;
        Path statementsFile = null;
        Path configFile = Path.of("agent.properties");
        Map<String, String> llmOverrides = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--urls" -> inputs.addAll(readInputs(Path.of(args[++i])));
                case "--url" -> inputs.add(args[++i]);
                case "--out" -> outRoot = Path.of(args[++i]);
                case "--from" -> from = Path.of(args[++i]);
                case "--topic" -> topic = args[++i];
                case "--extract" -> extract = args[++i];
                case "--statements" -> statementsFile = Path.of(args[++i]);
                case "--config" -> configFile = Path.of(args[++i]);
                case "--provider" -> llmOverrides.put("provider", args[++i]);
                case "--model" -> llmOverrides.put("model", args[++i]);
                case "--base-url", "--ollama" -> llmOverrides.put("base_url", args[++i]);
                case "--api-key-env" -> llmOverrides.put("api_key_env", args[++i]);
                case "--effort" -> llmOverrides.put("effort", args[++i]);
                case "--render" -> {
                    renderOnly(Path.of(args[++i]));
                    return;
                }
                default -> usage("Unknown argument: " + args[i]);
            }
        }

        // Stage 1: fetch (or reuse a previous run's fetch output).
        Path runDir;
        List<Source> sources;
        if (from != null) {
            runDir = from.toAbsolutePath().getParent();
            sources = JSON.readValue(from.toFile(), new TypeReference<>() {});
            System.out.println("Reusing " + sources.size() + " fetched sources from " + from);
        } else {
            if (inputs.isEmpty()) usage("No inputs given.");
            if (inputs.size() > 5) System.out.println("Note: " + inputs.size() + " inputs given; the agent is designed for 3-5.");
            runDir = outRoot.resolve(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));
            Files.createDirectories(runDir.resolve("raw"));
            sources = fetchAll(inputs, runDir);
            JSON.writeValue(runDir.resolve("01-fetch.json").toFile(), sources);
            printFetchSummary(sources);
            System.out.println("\nWrote " + runDir.resolve("01-fetch.json"));
        }

        if (extract == null && topic == null) return;
        if (topic == null) usage("--extract needs --topic");
        Set<String> wanted = extract == null || extract.equalsIgnoreCase("all") ? null : Set.of(extract.split(","));
        LlmConfig config = LlmConfig.load(configFile, llmOverrides);
        LlmClient llm = config.create(Path.of(".cache", "llm"));
        System.out.println("\nModel: " + config);

        // Stage 2: topic statements, written before reading any source (or supplied by an analyst).
        List<Statement> statements = statementsFile != null
                ? StatementGenerator.fromFile(statementsFile)
                : new StatementGenerator(llm).generate(topic);
        JSON.writeValue(runDir.resolve("02-statements.json").toFile(), statements);
        System.out.println("\nTopic statements" + (statementsFile != null ? " (from " + statementsFile + ")" : "") + ":");
        statements.forEach(s -> System.out.println("  " + s.id() + "  " + s.text()));
        System.out.println();

        // Stage 3: (a) extract neutral claims, (b) judge each claim against the statements.
        ClaimExtractor extractor = new ClaimExtractor(llm);
        ClaimJudge judge = new ClaimJudge(llm);
        List<ExtractedClaim> extracted = new ArrayList<>();
        List<Claim> claims = new ArrayList<>();
        List<Object> rejected = new ArrayList<>();
        List<Object> stats = new ArrayList<>();
        for (Source s : sources) {
            if (wanted != null && !wanted.contains(s.id())) continue;
            if (!s.status().usable()) {
                System.out.println(s.id() + ": skipped, " + s.status());
                continue;
            }
            System.out.print(s.id() + ": extracting ... ");
            ClaimExtractor.Result r = extractor.extract(s, topic);
            System.out.printf("%d claims kept of %d proposed (%d chunks, %.0fs); judging ... ",
                    r.claims().size(), r.stats().proposed(), r.stats().chunks(), r.stats().millis() / 1000.0);
            long judgeStart = System.currentTimeMillis();
            List<Claim> judged = judge.judge(s, r.claims(), topic, statements);
            long matched = judged.stream().filter(Claim::matched).map(c -> c.id().split("/")[0]).distinct().count();
            System.out.printf("%d of %d tied to a statement (%.0fs)%n", matched, r.claims().size(),
                    (System.currentTimeMillis() - judgeStart) / 1000.0);

            extracted.addAll(r.claims());
            claims.addAll(judged);
            rejected.addAll(r.rejected().stream().map(x -> Map.of("sourceId", s.id(), "rejected", x)).toList());
            stats.add(r.stats());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("topic", topic);
        out.put("model", llm.describe());
        out.put("stats", stats);
        out.put("judgedClaims", claims);
        out.put("extractedClaims", extracted);
        out.put("rejected", rejected);
        Path claimsFile = runDir.resolve("03-claims.json");
        JSON.writeValue(claimsFile.toFile(), out);
        System.out.println("\nWrote " + claimsFile);

        // Stage 4: cross-source classification, plain code.
        List<Source> analyzed = sources.stream().filter(s -> wanted == null || wanted.contains(s.id())).toList();
        Brief brief = new Classifier().classify(topic, analyzed, statements, claims);
        Path briefFile = runDir.resolve("04-brief.json");
        JSON.writeValue(briefFile.toFile(), brief);
        printBriefSummary(brief);
        System.out.println("\nWrote " + briefFile);
        System.out.println("Wrote " + writeMarkdown(brief, llm.describe(), runDir));
    }

    /** Stage 5: the readable brief, rendered from the JSON by plain code. */
    private static Path writeMarkdown(Brief brief, String model, Path runDir) throws IOException {
        String when = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        Path md = runDir.resolve("05-brief.md");
        Files.writeString(md, new BriefRenderer().render(brief, model, when), StandardCharsets.UTF_8);
        return md;
    }

    /** --render runs/&lt;id&gt;/04-brief.json: re-renders an existing brief without calling any model. */
    private static void renderOnly(Path briefJson) throws IOException {
        Brief brief = JSON.readValue(briefJson.toFile(), Brief.class);
        Path claims = briefJson.toAbsolutePath().getParent().resolve("03-claims.json");
        String model = Files.exists(claims) ? JSON.readTree(claims.toFile()).path("model").asText("unknown") : "unknown";
        System.out.println("Wrote " + writeMarkdown(brief, model, briefJson.toAbsolutePath().getParent()));
    }

    private static void printBriefSummary(Brief brief) {
        System.out.println("\nMATRIX (" + brief.sourcesUsable() + "/" + brief.sourcesTotal() + " usable; + supports, − opposes, ± mixed, · silent)");
        for (Brief.MatrixRow row : brief.matrix()) {
            System.out.printf("  %-4s %-62s %s%n", row.statementId(), truncate(row.statement(), 62),
                    String.join("  ", row.cells().entrySet().stream().map(e -> e.getKey() + ":" + e.getValue()).toList()));
        }
        System.out.println();
        brief.consensusClaims().forEach(c -> System.out.println("CONSENSUS     " + c.statementId() + " " + c.stance() + " " + c.agreeingSources() + "  " + c.rankReason()));
        brief.contradictedClaims().forEach(c -> System.out.println("CONTRADICTED  " + c.statementId() + "  " + c.rankReason()));
        brief.singleSourceClaims().forEach(c -> System.out.println("SINGLE        " + (c.statementId() == null ? "other: " + c.statement() : c.statementId())
                + " " + c.stance() + " " + c.sourceId() + " (" + c.strongestEvidence() + ")"));
        brief.unaddressedClaims().forEach(c -> System.out.println("UNADDRESSED   " + c.statementId() + "  " + c.statement()));
    }

    /** Fetches all inputs in parallel (one virtual thread each), keeping input order. */
    private static List<Source> fetchAll(List<String> inputs, Path runDir) throws Exception {
        SourceLoader loader = new SourceLoader();
        List<Future<SourceLoader.Result>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < inputs.size(); i++) {
                String id = "S" + (i + 1);
                String input = inputs.get(i);
                futures.add(pool.submit(() -> loader.load(id, input)));
            }
        }
        List<Source> sources = new ArrayList<>();
        for (Future<SourceLoader.Result> f : futures) {
            SourceLoader.Result r = f.get();
            sources.add(r.source());
            if (r.rawHtml() != null) {
                Files.writeString(runDir.resolve("raw").resolve(r.source().id() + ".html"), r.rawHtml(), StandardCharsets.UTF_8);
            }
        }
        return sources;
    }

    /** One input per line; blank lines and lines starting with # are ignored. */
    private static List<String> readInputs(Path file) throws IOException {
        return Files.readAllLines(file, StandardCharsets.UTF_8).stream()
                .map(String::strip)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .toList();
    }

    private static void printFetchSummary(List<Source> sources) {
        System.out.printf("%n%-3s %-13s %-5s %6s %-14s %s%n", "ID", "STATUS", "HTTP", "WORDS", "METHOD", "TITLE / REASON");
        for (Source s : sources) {
            String detail = s.status().usable() ? truncate(s.title(), 60) + "  [" + s.reason() + "]" : s.reason();
            System.out.printf("%-3s %-13s %-5s %6d %-14s %s%n",
                    s.id(), s.status(), s.httpStatus() == null ? "-" : s.httpStatus(), s.wordCount(),
                    s.extractionMethod() == null ? "-" : s.extractionMethod(), detail);
        }
        long usable = sources.stream().filter(s -> s.status().usable()).count();
        System.out.printf("%n%d of %d sources usable.%n", usable, sources.size());
        if (usable < 2) {
            System.out.println("Warning: fewer than 2 usable sources, so no cross-source comparison is possible.");
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "(no title)";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static void usage(String message) {
        System.err.println(message);
        System.err.println("Usage: ./gradlew run --args=\"--urls links.txt --topic 'AI and jobs'\"");
        System.err.println("       ./gradlew run --args=\"--from runs/<id>/01-fetch.json --topic 'AI and jobs' --extract S1,S3,S5\"");
        System.err.println("Model: --provider ollama|anthropic --model NAME --base-url URL --api-key-env VAR --effort LEVEL --config FILE");
        System.exit(2);
    }
}
