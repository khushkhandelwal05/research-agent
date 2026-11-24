# NOTES

## Approach
A fixed pipeline in Java, not one big prompt: fetch + clean (jsoup) -> topic statements -> extract claims -> judge each claim -> classify -> markdown brief.
- Before reading any source the model writes 6-10 statements about the topic. Every claim is judged against them (supports / opposes / mixed), so all sources are compared on the same rows.
- The model only does small jobs. Consensus, contradictions, single-source and unaddressed claims are worked out in plain Java, so that part is tested and gives the same answer every run.
- Every quote is checked against the page text. If the model changed the wording, the claim is dropped.
- Broken pages (404, paywall, bot check, JS-only, timeout) don't stop the run. The brief says which sources were left out and why.

## What didn't work
Extracting and judging in one call: qwen2.5:7b got about half the claims wrong, qwen3:14b about 40%. Two steps brought it to roughly 22-34%. After that, more prompt fixes just moved the errors around.

## Tradeoffs (2 hours)
- Local qwen3:14b because I had no API key. Slow (about 25 min first run, cached after) and about 1 in 3 judgments wrong. Claude can be switched on in the config but I couldn't test it.
- No JS rendering, tested on one topic only, graded by hand.

## In production I would
- Use a stronger model for judging, or add a check step that drops judgments the quote doesn't back.
- Separate what a source argues from what it only quotes.
- Build a small labelled set to measure accuracy, and flag vendor blogs vs research.

## Assumptions
The user gives the topic. 2 sources on the same side = consensus. A source needs a 2:1 majority of its claims to count on a side. A source that is silent on a statement counts neither way.
