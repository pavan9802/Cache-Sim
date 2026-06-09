Compare JMH benchmark results to detect regressions and verify phase improvement floors.

Usage:
  /benchmark-check           — compare the two most recent result files in results/
  /benchmark-check 1         — compare phase-0 results vs phase-1 results
  /benchmark-check 1 2       — compare phase-1 results vs phase-2 results explicitly

---

## Step 1 — Locate Result Files

Search `results/` for JSON files matching the requested phases.
Files follow the pattern: `phase-<N>-*.json` or `phase-<N>-baseline.json`.

If files for the requested phases are missing, report:
```
MISSING: No result file found for phase <N>.
Run the JMH benchmark suite and save output to results/ before proceeding.
```

Report which files are being compared (full paths and timestamps).

## Step 2 — Parse Results

Extract from each JMH JSON result file:
- Benchmark name
- Mode (throughput / average time)
- Score (mean) and score error (±)
- Thread count parameter if present
- Hit rate if present in secondary metrics

## Step 3 — Regression Check

For each benchmark present in both result sets, compare mean throughput:

| Result | Classification |
|---|---|
| Newer >= older - 5% | PASS (within noise) |
| Newer is 0–5% slower | WARNING (possible noise — note it) |
| Newer is > 5% slower | REGRESSION — FAIL |

A regression on any benchmark is a gate failure. Do not proceed to the next phase.

## Step 4 — Improvement Floor Check

Based on which phases are being compared, apply the floor from CLAUDE.md:

| Transition | Minimum improvement required |
|---|---|
| Phase 0 → Phase 1 | N/A (Phase 1 adds correctness overhead; baseline is a stub) |
| Phase 1 → Phase 2 StripedCache | >= 2x Phase 1 throughput on ReadHeavyBenchmark at 8 threads |
| Phase 1 → Phase 2 StampedCache | >= 1.5x Phase 1 on ReadHeavyBenchmark (95/5 workload) |
| Phase 2 → Phase 3 TinyLFUCache | >= 1.5x best Phase 2 throughput |

If the floor is not met: report FAIL with the actual ratio (e.g., "achieved 1.3x, required 2x").
If the transition is not listed above: report the raw ratio and flag as INFORMATIONAL.

## Step 5 — Hit Rate Sanity Check

If hit rate data is present in the results:

| Implementation | Expected range under Zipfian |
|---|---|
| LRU | 65–75% |
| LFU / TinyLFU | 80–90% |
| Caffeine W-TinyLFU | 85–92% |

A hit rate outside the expected range likely indicates a correctness bug, not a performance issue.
Flag as WARNING if within 5% of the boundary; flag as INVESTIGATE if outside the range entirely.

## Step 6 — Summary Table

```
BENCHMARK COMPARISON: Phase <N-1> vs Phase <N>
Files: results/phase-<N-1>-*.json → results/phase-<N>-*.json
════════════════════════════════════════════════════════════════════
 Benchmark              Phase N-1      Phase N     Change   Status
────────────────────────────────────────────────────────────────────
 ReadHeavy (8T)         X.XXM ops/s   X.XXM ops/s  +XX%    PASS
 WriteHeavy (8T)        X.XXM ops/s   X.XXM ops/s  +XX%    PASS
 Zipfian (8T)           X.XXM ops/s   X.XXM ops/s  +XX%    PASS
 p99 latency            XXXns          XXXns        -XX%    PASS
 Hit rate (Zipfian)     XX%            XX%          +X%     PASS
════════════════════════════════════════════════════════════════════

Improvement floor:  PASS (achieved Nx, required Nx)
Regressions:        NONE / list any regressions

OVERALL: PASS / FAIL
```

If FAIL: state exactly which check failed and what is required to pass.
