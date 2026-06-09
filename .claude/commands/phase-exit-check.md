Run the full exit gate check for a phase before marking it complete.

Usage: /phase-exit-check <phase-number>  (e.g., /phase-exit-check 1)

If no phase number is given, detect the current phase from the most recently modified
implementation files under `cache-core/src/` and `cache-simulator/src/`.

---

## Step 1 — Identify In-Scope Files

Based on the phase number, list the files that belong to this phase.
Reference `docs/phase-<N>-*.md` for the authoritative file list.
Report which files you found vs which are expected but missing.

## Step 2 — Correctness Gate

Check each item and mark PASS / FAIL / MISSING:

**Tests exist**
- [ ] Unit test class exists for every implementation class in this phase
- [ ] Every public method has at least one test covering its contract
- [ ] Edge cases covered: null keys, zero/negative TTL, cache at exactly maxSize

**Concurrency tests (if phase involves concurrency)**
- [ ] `@RepeatedTest` annotation present on all concurrency tests
- [ ] `@RepeatedTest` count is >= 50 for standard tests, >= 100 for critical paths
  (critical paths: write-lock correctness, thundering herd, StampedLock validation)
- [ ] Deadlock tests have a timeout (test completes within a defined number of seconds)
- [ ] jcstress tests exist in `cache-stress/src/main/java/com/hpcache/stress/` for any memory-model-sensitive operations
- [ ] Build and run: `mvn package -pl cache-stress -am -q && java -jar cache-stress/target/cache-stress.jar -t <TestName>`
      Open `jcstress-results/index.html` — zero FORBIDDEN outcomes required

**Static analysis**
- Run `/concurrency-review` on all phase files and include its output
- Run `/java-best-practices` on all phase files and include its output
- PASS only if both report zero MUST FIX findings

## Step 3 — Performance Gate

- [ ] Result file exists: `results/phase-<N>-*.json`
- [ ] Run `/benchmark-check <N-1> <N>` and include its output
- [ ] No regression vs previous phase (ReadHeavyBenchmark, same thread count)
- [ ] Improvement floor met — check `docs/phase-<N>-*.md` for the target ratio

## Step 4 — Abstraction Gate

- Run `/abstraction-check` on all phase files
- [ ] Zero module boundary violations
- [ ] Zero MUST FIX abstraction findings

## Step 5 — Verdict

Print a gate summary table:

```
PHASE <N> EXIT GATE RESULTS
═══════════════════════════════════════════════════
 Gate                          Status    Notes
───────────────────────────────────────────────────
 Unit tests exist              PASS/FAIL
 @RepeatedTest counts adequate PASS/FAIL
 jcstress tests exist          PASS/FAIL/N/A
 /concurrency-review clean     PASS/FAIL
 /java-best-practices clean    PASS/FAIL
 Benchmark baseline recorded   PASS/FAIL
 No regression vs phase N-1    PASS/FAIL
 Improvement floor met         PASS/FAIL
 No abstraction violations     PASS/FAIL
═══════════════════════════════════════════════════
 OVERALL                       PASS / FAIL
```

If OVERALL is FAIL: list exactly what must be fixed before proceeding.
If OVERALL is PASS: state that it is safe to begin Phase <N+1>.
