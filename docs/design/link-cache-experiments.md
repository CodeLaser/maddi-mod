# Link cache: experiments tried and rejected

The link cache (`SingleIterationAnalyzerImpl.linkCacheHit` / `linkCacheStore`, recorder
`AnalysisAccessRecorder` in maddi) skips a method's link computation when every slot the previous
computation read still holds the value it read then. A recording cannot be used when the computation
read everything (`readAll`), was abandoned, or is **reproduced**: it read a slot and later wrote another
value into that same slot, i.e. it overwrote its own previous output. The method then runs again in the next
pass even when nothing else changed. On the private closed-core corpus (~640K elements per full pass), these
re-runs and the "new" recordings that follow them took about 15 of the remaining 23 h of slow-element thread
time (elements taking at least 5 s, gate `MADDI_SLOW_ELEMENT_MS`).

This note records two attempts to recover that time, both rejected, so they are not tried again in the same form.

## 1. Fresh owners (2026-11-23, reverted)

Hypothesis: many reproduced slots sit on objects the computation itself creates (a fresh `VariableInfoImpl`
or analysis map), so a write onto them is not a write over an earlier output. The recorder registered created
owners (`created(Owner)`, gate `NOFRESHOWNERS` to switch it off) and ignored reads and writes on them.

Result: no measurable change in the reproduced counts or in time on langchain4j and fernflower. Reverted
(maddi de0894cf5, maddi-mod 894fdb7c).

## 2. REPRODUCEDOK: accept a reproduced slot as output (2026-11-23, removed)

Gate `REPRODUCEDOK`: a slot that was read and then overwritten with another value was recorded as an output,
with its final value, instead of making the recording unusable. The next pass then skips the method while the
slot still holds what this computation wrote.

What it assumes: running the method again with its own previous output as input gives that same output back,
`f(inputs, old = b) = b`. Nothing checks this at the write sites.

| Run | Time | Results |
|---|---|---|
| langchain4j | 75 s → 70 s | FPDUMP identical |
| fernflower | 220 s → 183 s | FPDUMP identical |
| closed-core | 402 / 410 min (two runs without the gate) → 467 min | outside the run-to-run noise |

On closed-core, the time per pass did not change. All three runs were pass for pass the same until about
minute 330, when the gated run needed one extra MODREACH re-derivation cycle (~75 min). Its results differ
from both ungated runs beyond the 86-value run-to-run noise:
- 3 methods of a generated web-service stub became degraded, with knock-on changes in the defaults,
  independence and assumed-defaults fields of that class;
- ~8 parameters flipped between unmodified and modified.

The small corpora did not catch this. Removed (maddi f00b65849; it was added in 783fcfa48).

## What stays

- `REPRODUCEDTRACE` (diagnostic): counts reproduced slots per property, owner class and write site.
- `LINKCACHECHECK` classification (maddi eb1ac390b, 4dd7ede45; maddi-mod 08222f2a, 3d91a8e0). The check
  recomputes each hit and compares, and now separates three outcomes:
  - **moved input:** another thread rewrote an input during the check. Parallel passes read callee
    links while other threads rewrite them;
  - **fresh owner:** same reads, but the output lands on an object the computation creates;
  - **real failures:** logged as warnings.

  With the cache as it is, both corpora show 0 real failures.

## If this comes back

To skip reproduced recordings safely, the assumption `f(inputs, old = b) = b` needs to hold, or be checked,
at the write sites, starting with the ones `REPRODUCEDTRACE` lists most often. A bigger gain may come from
making parallel passes deterministic: today threads read callee links that other threads are rewriting in the
same pass. That is the source of the run-to-run noise, and it is a design change to how passes snapshot their
inputs.
