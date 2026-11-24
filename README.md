# Research brief agent

Give it a topic and 3-5 URLs. It fetches the pages, pulls out the claims each source makes, and writes a brief that shows where sources agree, where they contradict each other, what only one source says, and what none of them address. Every claim comes with a quote that was checked against the page.

## Setup

You need:
- **JDK 21 or newer** (`java -version`). Gradle comes with the project (`./gradlew`), nothing else to install.
- **Ollama** with a model pulled. Default is `qwen3:14b` (9.3 GB, needs ~16 GB RAM):
  ```bash
  ollama pull qwen3:14b
  ```
  Ollama must be running (`ollama serve`, or the Ollama app).

  Smaller machine? Use `ollama pull qwen2.5:7b` and add `--model qwen2.5:7b` to the commands below. It is faster but less accurate.

## Run

```bash
./gradlew run --args="--urls samples/ai-and-jobs.txt --topic 'Impact of AI on jobs and the labor market'"
```

The first run takes a while on a laptop (about 25 min for 5 sources with qwen3:14b). Every model answer is cached in `.cache/llm/`, so running it again is instant.

Output goes to `runs/<timestamp>/`:

| File | What it is |
|---|---|
| `05-brief.md` | **The brief.** Open this one. |
| `04-brief.json` | Same brief as JSON |
| `03-claims.json` | Every claim, its quote, and how it was judged |
| `02-statements.json` | The topic statements sources were compared on |
| `01-fetch.json`, `raw/` | Fetch status and cleaned text per source, plus the raw HTML |

The URL file has one URL (or local `.html` file) per line; lines starting with `#` are ignored.

**Don't want to wait?** A finished run on the 5 sample links (qwen3:14b) is in [`examples/ai-and-jobs/05-brief.md`](examples/ai-and-jobs/05-brief.md). The fetched page text (`01-fetch.json`, `raw/`) is left out because it is full copies of the articles.

## Broken pages

To see how failures are handled (404, unknown host, bad URL, JS-only page, paywall, bot check, URL with junk at the end):

```bash
./gradlew run --args="--urls fixtures/broken-links.txt --out runs/fixtures"
```

This only runs the fetch stage (no `--topic`), so it is quick.

## Using a different model

Any Ollama model:
```bash
./gradlew run --args="--urls samples/ai-and-jobs.txt --topic 'Impact of AI on jobs and the labor market' --model llama3.1:8b"
```

Claude via the Anthropic API:
```bash
export ANTHROPIC_API_KEY=your-key
```
```bash
./gradlew run --args="--urls samples/ai-and-jobs.txt --topic 'Impact of AI on jobs and the labor market' --provider anthropic"
```
Default is `claude-opus-5-5` at `--effort medium`. Note: I could not test this path end to end (no key), only that the request is built correctly.

To save these settings instead of typing flags, copy `agent.properties.example` to `agent.properties`. Flags override the file. Keys are only read from environment variables, never from the file.

## Other options

| Flag | What it does |
|---|---|
| `--urls FILE` / `--url URL` | Input sources |
| `--topic TEXT` | Topic. Without it only the fetch stage runs |
| `--statements FILE` | Use your own topic statements (one per line) instead of generated ones |
| `--from runs/<id>/01-fetch.json` | Reuse an earlier fetch instead of downloading again |
| `--extract S1,S3` | Only analyse some sources (default: all) |
| `--render runs/<id>/04-brief.json` | Rebuild `05-brief.md` from the JSON, no model calls |
| `--provider`, `--model`, `--base-url`, `--api-key-env`, `--effort` | Model settings |
| `--out DIR` | Where runs are written (default `runs/`) |

## Tests

```bash
./gradlew test
```

Covers the classifier (consensus / contradiction / single-source / unaddressed rules), the quote checker, the config, the Claude request and the markdown renderer. These run without a model or network.

## How it works

```
fetch + clean -> topic statements -> extract claims -> judge each claim -> classify -> render
   (jsoup)          (LLM)              (LLM)            (LLM)             (Java)    (Java)
```

1. **Fetch** (`fetch/`): downloads each page, strips menus and footers, and gives each source a status. Broken pages are reported, not fatal.
2. **Statements** (`extract/StatementGenerator`): from the topic alone, the model writes 6-10 statements sources could agree or disagree with.
3. **Extract** (`extract/ClaimExtractor`): per chunk of text, the model lists claims with exact quotes. Quotes that don't match the page are dropped.
4. **Judge** (`extract/ClaimJudge`): one small call per claim: which statement does it bear on, and does it support or oppose it.
5. **Classify** (`analyze/Classifier`): plain Java builds a statement x source matrix and sorts each statement into consensus, contradicted, single-source or unaddressed.
6. **Render** (`render/BriefRenderer`): plain Java writes the markdown brief, with each quote linked back to its page.

See `NOTES.md` for the design choices, what didn't work and known limits.
