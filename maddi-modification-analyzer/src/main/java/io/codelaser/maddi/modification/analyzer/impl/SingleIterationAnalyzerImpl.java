/*
 * maddi: a modification analyzer for duplication detection and immutability.
 * Copyright 2020-2026, CodeLaser BV, https://github.com/CodeLaser/maddi
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.codelaser.maddi.modification.analyzer.impl;

import static io.codelaser.maddi.modification.link.impl.MethodLinkedVariablesImpl.METHOD_LINKS;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.ObjIntConsumer;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.codelaser.maddi.cst.api.analysis.Message;
import io.codelaser.maddi.cst.api.analysis.PropertyValueMap;
import io.codelaser.maddi.cst.api.element.Element;
import io.codelaser.maddi.cst.api.info.FieldInfo;
import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.ParameterInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.api.runtime.Runtime;
import io.codelaser.maddi.cst.api.statement.Statement;
import io.codelaser.maddi.cst.impl.analysis.AnalysisAccessRecorder;
import io.codelaser.maddi.cst.impl.analysis.ConsumptionEdgeRecorder;
import io.codelaser.maddi.cst.impl.analysis.MessageImpl;
import io.codelaser.maddi.cst.impl.analysis.PropertyImpl;
import io.codelaser.maddi.cst.impl.analysis.ValueImpl;
import io.codelaser.maddi.inspection.api.integration.JavaInspector;
import io.codelaser.maddi.modification.analyzer.*;
import io.codelaser.maddi.modification.common.defaults.ShallowTypeAnalyzer;
import io.codelaser.maddi.modification.common.util.TolerantWrite;
import io.codelaser.maddi.modification.link.LinkComputer;
import io.codelaser.maddi.modification.link.impl.Gate;
import io.codelaser.maddi.modification.link.impl.LinkComputerImpl;
import io.codelaser.maddi.modification.link.impl.MethodLinkedVariablesImpl;
import io.codelaser.maddi.modification.link.impl.ReceiverLevel;
import io.codelaser.maddi.modification.prepwork.PrepAnalyzer;
import io.codelaser.maddi.modification.prepwork.Util;
import io.codelaser.maddi.modification.prepwork.variable.MethodLinkedVariables;
import io.codelaser.maddi.modification.prepwork.variable.VariableData;
import io.codelaser.maddi.modification.prepwork.variable.impl.VariableDataImpl;

public class SingleIterationAnalyzerImpl implements SingleIterationAnalyzer, ModAnalyzerForTesting {
    private static final Logger LOGGER = LoggerFactory.getLogger(SingleIterationAnalyzerImpl.class);

    private final LinkComputer linkComputer;
    private final FieldAnalyzer fieldAnalyzer;
    private final TypeModIndyAnalyzer typeModIndyAnalyzer;
    private final TypeImmutableAnalyzer typeImmutableAnalyzer;
    private final TypeIndependentAnalyzer typeIndependentAnalyzer;
    private final ShallowTypeAnalyzer shallowTypeAnalyzer;
    private final TypeContainerAnalyzer typeContainerAnalyzer;
    private final TypeEventualAnalyzer typeEventualAnalyzer;
    private final StaticSideEffectAnalyzerImpl staticSideEffectAnalyzer;
    private final SourceContractMaterializer sourceContractMaterializer;
    private final ContractResolution contractResolution;
    private final DynamicImmutabilityInference dynamicImmutabilityInference;
    private final AbstractMethodAnalyzer abstractMethodAnalyzer;
    // EXPERIMENTAL greatest-fixpoint oracle (EVENTUALCLUSTER), exposed so IteratingAnalyzerImpl's post-convergence
    // contraction phase can read the assumption ledger it accumulated
    private final EventualCluster eventualCluster;
    private final AtomicInteger propertiesChanged;
    private final List<Message> messages;
    private final boolean faultTolerant;
    private final Set<Info> failed = ConcurrentHashMap.newKeySet();
    // VariableData flatten-snapshot (Configuration.flattenVariableData; DESIGN-vardata-flatten.md):
    // runtime rebuilds a method's VD via prepwork on regeneration; the set tracks methods whose
    // intermediate-statement VD has been dropped, so a re-link regenerates it first.
    private final Runtime runtime;
    private final boolean flattenVariableData;
    private final Set<MethodInfo> flattenedMethods = ConcurrentHashMap.newKeySet();

    // Parallel per-element loop: iterations 2+ on a fixed pool, iteration 1 via call-graph strata waves.
    // DEFAULT ON since the 3-corpus proof (2026-07-17: certified + verdict-exact at 8 threads): min(8,
    // cores-2) threads. Override with PARALLEL=<n>; PARALLEL=1 = sequential. Small runs (the suites) stay
    // sequential via MIN_ELEMENTS_FOR_PARALLEL — no pool overhead, no order sensitivity in pinned counts.
    static final int PARALLEL_THREADS = parallelThreads(); // package: IteratingAnalyzerImpl gates wave computation
    static final int MIN_ELEMENTS_FOR_PARALLEL = 256;
    // EXPERIMENT (#82, #91): once cycle breaking is active, run every pass sequentially. Cycle breaking decides
    // the still-undecided (an independence, an immutability) at the moment an element is reached; in a parallel
    // pass that moment depends on thread timing, and so does the verdict (guava, 8 threads: 6-10 FPDUMP lines
    // differ between runs, 0 sequentially). Opt-in while measuring.
    static final boolean SEQUENTIAL_CYCLE_BREAKING = Gate.isSet("SEQCYCLEBREAKING");
    // CBSWEEP (#82): the deterministic alternative to SEQCYCLEBREAKING that keeps the loop parallel; see goInternal.
    // The default since it measured identical outputs on the oracles and corpora, and converges jenkins; NOCBSWEEP
    // turns it off.
    static final boolean CYCLE_BREAKING_SWEEP = !Gate.isSet("NOCBSWEEP");

    /*
    SCCJOIN (CodeLaser/maddi-mod#23): a method in a recursion cycle (it reads, through other summaries, its own) whose
    freshly computed summary is one it already had in an earlier pass, but not the stored one, is oscillating: on
    elasticsearch, ShardRouting.<init> and initializeTargetRelocatingShard each compute from the other's previous
    summary and go round a loop of two states for ever, and every caller up to DesiredBalanceComputer.compute
    inherits the swing. From that moment on, the method's new summary is joined with the stored one instead of
    replacing it: the summaries can only grow, so the cycle settles. The latest-wins slot write stays in force for
    every other method, and for this one until it oscillates, so the early pessimism a summary sheds as the analysis
    improves is shed as before.
     */
    static final boolean SCC_JOIN = Gate.isSet("SCCJOIN");
    private static final int SCC_JOIN_HISTORY = 4;
    // per method: the content hashes of its last SCC_JOIN_HISTORY computed summaries (ring buffer, slot 0 = count)
    private final java.util.Map<MethodInfo, int[]> linkHistory = new java.util.concurrent.ConcurrentHashMap<>();
    private final Set<MethodInfo> joiningMethods = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private MethodLinkedVariables joinInRecursionCycle(MethodInfo methodInfo, MethodLinkedVariablesImpl computed) {
        MethodLinkedVariablesImpl stored = methodInfo.analysis().getOrNull(METHOD_LINKS, MethodLinkedVariablesImpl.class);
        if (stored == null) return computed;
        if (!joiningMethods.contains(methodInfo)) {
            int h = computed.contentHash();
            int[] history = linkHistory.computeIfAbsent(methodInfo, _ -> new int[SCC_JOIN_HISTORY + 1]);
            boolean revisit;
            synchronized (history) {
                int n = Math.min(history[0], SCC_JOIN_HISTORY);
                boolean seen = false;
                for (int i = 1; i <= n; i++) if (history[i] == h) seen = true;
                revisit = seen && stored.contentHash() != h;
                history[1 + history[0] % SCC_JOIN_HISTORY] = h;
                history[0]++;
            }
            if (!revisit || !inRecursionCycle(methodInfo)) return computed;
            joiningMethods.add(methodInfo);
            linkHistory.remove(methodInfo);
            LOGGER.info("SCCJOIN: summary of {} oscillates in a recursion cycle; joining from now on", methodInfo);
        }
        return computed.joinWith(stored);
    }

    // the method reads its own summary through others: a path back to itself over the summary-consumption edges
    private boolean inRecursionCycle(MethodInfo methodInfo) {
        java.util.Map<MethodInfo, Set<MethodInfo>> consumed = linkComputer.consumedSummaries();
        java.util.ArrayDeque<MethodInfo> todo = new java.util.ArrayDeque<>(consumed.getOrDefault(methodInfo, Set.of()));
        Set<MethodInfo> seen = new HashSet<>(todo);
        while (!todo.isEmpty()) {
            MethodInfo m = todo.poll();
            if (m == methodInfo) return true;
            for (MethodInfo next : consumed.getOrDefault(m, Set.of())) {
                if (seen.add(next)) todo.add(next);
            }
        }
        return false;
    }
    private static final int MAX_SWEEP_ROUNDS = 25;

    // DIAGNOSTIC (#91): PARSHUFFLE=<seed> submits each parallel pass's elements (and each first-iteration wave's
    // units) in a seeded random order. The order is arbitrary under PARALLEL anyway; this moves the thread timing
    // on purpose, to expose verdicts that depend on it without needing machine load.
    private static final Long PARALLEL_SHUFFLE_SEED = parseShuffleSeed();

    private static Long parseShuffleSeed() {
        String s = System.getenv("PARSHUFFLE");
        if (s == null || s.isBlank()) return null;
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return (long) s.trim().hashCode();
        }
    }

    private int shufflePass;

    private <T> List<T> submissionOrder(List<T> list) {
        if (PARALLEL_SHUFFLE_SEED == null) return list;
        List<T> copy = new ArrayList<>(list);
        Collections.shuffle(copy, new Random(PARALLEL_SHUFFLE_SEED * 31 + shufflePass++));
        return copy;
    }

    private static int parallelThreads() {
        String s = System.getenv("PARALLEL");
        if (s == null) {
            // fully qualified: the CST Runtime is imported in this file
            return Math.max(1, Math.min(8, java.lang.Runtime.getRuntime().availableProcessors() - 2));
        }
        try {
            return Math.max(1, Integer.parseInt(s.trim()));
        } catch (NumberFormatException e) {
            LOGGER.warn("Cannot parse PARALLEL={}, running sequentially", s);
            return 1;
        }
    }
    // worklist support: elements whose analysis changed in the most recent go() (see SingleIterationAnalyzer)
    private final Set<Info> changedInfos = Collections.synchronizedSet(new HashSet<>());
    private final Set<Info> summaryChangedInfos = Collections.synchronizedSet(new HashSet<>());

    // wave-barrier hook (AnalysisValueFeed.waveCompleted): fired on the coordinator thread after each
    // strata wave's join, with that wave's element set. Set by IteratingAnalyzerImpl when a feed exists.
    private ObjIntConsumer<Collection<Info>> waveCompletedCallback;

    public void setWaveCompletedCallback(ObjIntConsumer<Collection<Info>> callback) {
        this.waveCompletedCallback = callback;
    }

    // per-element hook (AnalysisValueFeed.elementCompleted): fired once as each element's processElement
    // finishes, on the (parallel) worker thread — must be cheap + thread-safe. Gives intra-wave progress
    // when a giant SCC is one wave. Set by IteratingAnalyzerImpl when a feed exists.
    private Runnable elementCompletedCallback;

    public void setElementCompletedCallback(Runnable callback) {
        this.elementCompletedCallback = callback;
    }

    // G43: the run's wall-clock budget, checked before every element (the workers included); null = unlimited.
    // Set by IteratingAnalyzerImpl, which names each pass on it before calling go().
    private AnalysisBudget budget;
    private final AtomicInteger elementsDoneInPass = new AtomicInteger();

    /*
    CodeLaser/maddi-mod#76: a method's links are a deterministic function of the analysis values its computation
    reads. Each computation is recorded (AnalysisAccessRecorder); a later pass skips it when every value read is
    unchanged and every value written is still in place: recomputing would write the same values again. Most of a
    re-derivation pass after MODREACH's clear, and of every late fixpoint pass, is exactly that. NOLINKCACHE
    computes every time; LINKCACHECHECK computes anyway on a hit and reports a hit whose result would differ.
     */
    private static final boolean LINK_CACHE = !Gate.isSet("NOLINKCACHE");
    private static final boolean LINK_CACHE_CHECK = Gate.isSet("LINKCACHECHECK");
    private final Map<MethodInfo, AnalysisAccessRecorder.Recording> linkRecordings =
            new ConcurrentHashMap<>();
    private final AtomicInteger linkCacheHits = new AtomicInteger();
    private final Map<String, AtomicInteger> linkCacheMisses = new ConcurrentHashMap<>();
    private final AtomicInteger linkCacheCheckFailures = new AtomicInteger();
    // LINKCACHECHECK: hits that are recomputed, to compare with the recording they would have replaced
    private final Set<MethodInfo> linkCheckPending = ConcurrentHashMap.newKeySet();

    void setBudget(AnalysisBudget budget) {
        this.budget = budget;
    }

    public static final String ANALYZER_CRASH = "analyzer-crash";
    public static final String LINK_CRASH = "link-crash";

    public SingleIterationAnalyzerImpl(JavaInspector javaInspector, IteratingAnalyzer.Configuration configuration) {
        this.propertiesChanged = new AtomicInteger();
        this.messages = Collections.synchronizedList(new ArrayList<>());
        this.faultTolerant = configuration.faultTolerant();
        // sv-integration gave LinkComputerImpl a TestVisitor parameter; production passes null
        linkComputer = new LinkComputerImpl(javaInspector, configuration.linkComputerOptions(), propertiesChanged,
                null);
        if (LINK_CACHE) AnalysisAccessRecorder.arm();
        Runtime runtime = javaInspector.runtime();
        fieldAnalyzer = new FieldAnalyzerImpl(runtime, configuration, propertiesChanged, messages);
        typeModIndyAnalyzer = new TypeModIndyAnalyzerImpl(configuration, propertiesChanged, messages);
        // EXPERIMENTAL greatest-fixpoint oracle for the eventual cluster (gated on EVENTUALCLUSTER), shared so the
        // immutable analyzer's supertype step, the independence analyzer's exposure excusal and the eventual
        // analyzer's cross-reference step agree on membership
        this.eventualCluster = new EventualCluster();
        // the independent analyzer is built first: the immutable analyzer asks it for after-mark independence,
        // because the dependence cap in computeImmutableType would otherwise fire before the AfterMark relaxation
        typeIndependentAnalyzer = new TypeIndependentAnalyzerImpl(runtime, configuration, propertiesChanged, messages,
                eventualCluster);
        // ONE resolution, shared: the materializer seeds from it and the abstract-method folds skip on it, so a
        // disagreement between the two would mean a contract seeded and then folded over (or the reverse). The
        // immutable analyzer's interface walk honours it too: a contracted abstract method is not re-folded.
        ContractResolution contractResolution = new ContractResolution(runtime);
        this.contractResolution = contractResolution;
        typeImmutableAnalyzer = new TypeImmutableAnalyzerImpl(typeIndependentAnalyzer, configuration,
                propertiesChanged, messages, eventualCluster, contractResolution);
        shallowTypeAnalyzer = new ShallowTypeAnalyzer(runtime, Element::annotations, false, false);
        typeContainerAnalyzer = new TypeContainerAnalyzerImpl(configuration, propertiesChanged, messages);
        typeEventualAnalyzer = new TypeEventualAnalyzerImpl(runtime, typeImmutableAnalyzer, configuration, propertiesChanged, messages, eventualCluster);
        staticSideEffectAnalyzer = new StaticSideEffectAnalyzerImpl(propertiesChanged, runtime.objectTypeInfo());
        sourceContractMaterializer = new SourceContractMaterializer(runtime, propertiesChanged, contractResolution);
        dynamicImmutabilityInference = new DynamicImmutabilityInference(propertiesChanged);
        abstractMethodAnalyzer = new AbstractMethodAnalyzerImpl(configuration, propertiesChanged, messages,
                eventualCluster, contractResolution);
        this.runtime = runtime;
        this.flattenVariableData = configuration.flattenVariableData();
    }

    @Override
    public int propertiesChanged() {
        return propertiesChanged.get();
    }

    /** The greatest-fixpoint oracle (EVENTUALCLUSTER), for the post-convergence contraction phase. */
    public EventualCluster eventualCluster() {
        return eventualCluster;
    }

    /**
     * The shared contract adjudication, for the MODREACH cutover writer: the pass must not downgrade a property
     * the author has DECLARED on a bodiless method. See {@code ShadowModificationPass.writeVerdicts}.
     */
    public ContractResolution contractResolution() {
        return contractResolution;
    }

    @Override
    public Map<MethodInfo, Set<MethodInfo>> consumedSummaries() {
        return linkComputer.consumedSummaries();
    }

    @Override
    public Set<Info> changedInfos() {
        return Set.copyOf(changedInfos);
    }

    @Override
    public Set<Info> summaryChangedInfos() {
        return Set.copyOf(summaryChangedInfos);
    }

    @Override
    public List<Message> messages() {
        // the iteration's own analyzers, plus the shallow analyzer used for abstract types
        return Stream.concat(messages.stream(), shallowTypeAnalyzer.messages().stream()).toList();
    }

    @Override
    public void go(List<Info> analysisOrder) {
        go(analysisOrder, false, true);
    }

    @Override
    public void go(List<Info> analysisOrder, boolean activateCycleBreaking, boolean firstIteration) {
        go(analysisOrder, activateCycleBreaking, firstIteration, null);
    }

    @Override
    public void go(List<Info> analysisOrder, boolean activateCycleBreaking, boolean firstIteration,
                   List<List<List<Info>>> firstIterationWaves) {
        try {
            goInternal(analysisOrder, activateCycleBreaking, firstIteration, firstIterationWaves);
        } finally {
            firstVisits = Set.of();
        }
    }

    // whether the next pass covers the whole order (or, incremental, the whole scope): only such a pass may sweep
    private boolean fullPass = true;

    public void setFullPass(boolean fullPass) {
        this.fullPass = fullPass;
    }

    // see SingleIterationAnalyzer.setFirstVisits; read by processElement, possibly from worker threads
    private volatile Set<Info> firstVisits = Set.of();

    /*
     The shallow analysis of an abstract type's field reads the
     field type's immutability and independence as they stand, and writes only upwards. Once (the first iteration, or
     a seeded run's first visit), a cold run reads its field types before they are decided, and MODREACH's
     clearDerivedFamily later removes what it wrote with no pass to write it again; a seeded run reads the carried,
     final values. The two disagree (fernflower's Statement.cancellationManager, endpoints: immutableField only
     warm), and the early cutoff falls back. Every visit, both read the values the field types settle on.
     NOSHALLOWFIELDEVERY restores the once-only behaviour.
     */
    private static final boolean SHALLOW_FIELD_EVERY_VISIT = System.getenv("NOSHALLOWFIELDEVERY") == null;

    @Override
    public void setFirstVisits(Set<Info> firstVisits) {
        this.firstVisits = firstVisits == null ? Set.of() : Set.copyOf(firstVisits);
    }

    private void goInternal(List<Info> analysisOrder, boolean activateCycleBreaking, boolean firstIteration,
                            List<List<List<Info>>> firstIterationWaves) {
        linkComputer.reset();
        elementsDoneInPass.set(0);
        changedInfos.clear();
        summaryChangedInfos.clear();
        TolerantWrite.resetChangedTargets();
        // flatten-snapshot: before this pass re-links any method whose intermediate VD was dropped,
        // regenerate its full per-statement VD from the body (quiescent: before the parallel loop).
        if (flattenVariableData && !flattenedMethods.isEmpty()) regenerateFlattened(analysisOrder);
        // first iteration only; concurrent for the strata-parallel path
        Set<TypeInfo> abstractTypes = ConcurrentHashMap.newKeySet();

        // CBSWEEP (#82): under cycle breaking, the per-element loop runs WITHOUT it, and the defaults are applied
        // afterwards in a sequential sweep over the fields and types, in analysis order, and only on a full pass
        // whose loop changed nothing. That state is the fixpoint without cycle breaking, the same whatever the
        // thread timing, and a sequential sweep over it decides the same defaults in every run.
        boolean sweepMode = activateCycleBreaking && CYCLE_BREAKING_SWEEP;
        boolean loopCycleBreaking = activateCycleBreaking && !sweepMode;
        // and the type analyzers leave the loop for the sequential second type pass: the eventual family is
        // write-once, and its assumption ledger records whether a candidate already had its verdict when a member
        // leaned on it. In a parallel loop that depends on thread timing (guava: CaseFormat.$0/$1 retracted by
        // cascade at 8 threads, kept sequentially).
        typesInSecondPassOnly = sweepMode;
        long startLoop = System.currentTimeMillis();
        if (PARALLEL_THREADS > 1 && firstIteration && firstIterationWaves != null
            && analysisOrder.size() >= MIN_ELEMENTS_FOR_PARALLEL) {
            int units = firstIterationWaves.stream().mapToInt(List::size).sum();
            int elements = firstIterationWaves.stream().flatMap(List::stream).mapToInt(List::size).sum();
            if (elements != analysisOrder.size()) {
                // defensive: the waves must cover exactly the analysis order's element set
                LOGGER.warn("Wave element count {} != analysis order size {}; falling back to sequential",
                        elements, analysisOrder.size());
                for (Info info : analysisOrder) {
                    processElement(info, loopCycleBreaking, true, abstractTypes);
                }
            } else {
                LOGGER.info("Strata-parallel FIRST iteration: {} threads, {} waves, {} units, {} elements",
                        PARALLEL_THREADS, firstIterationWaves.size(), units, elements);
                linkComputer.setLockComputeDisabled(true);
                try (ExecutorService pool =
                             Executors.newFixedThreadPool(PARALLEL_THREADS)) {
                    int waveIndex = 0;
                    for (List<List<Info>> wave : firstIterationWaves) {
                        waveIndex++;
                        List<Future<?>> futures = new ArrayList<>(wave.size());
                        for (List<Info> unit : submissionOrder(wave)) {
                            futures.add(pool.submit(() -> {
                                for (Info info : unit) {
                                    processElement(info, loopCycleBreaking, true, abstractTypes);
                                }
                            }));
                        }
                        joinAll(futures); // barrier per wave: the next wave's callees are complete
                        if (waveCompletedCallback != null) {
                            // workers quiescent at the barrier; the callback (guarded by the iterating
                            // analyzer's feed wrapper) must not slow the pass beyond its own throttle
                            List<Info> waveElements = wave.stream().flatMap(List::stream).toList();
                            waveCompletedCallback.accept(waveElements, waveIndex);
                        }
                        // flatten happens per-method in processElement (Phase 5), not at the barrier:
                        // a giant SCC is one wave, so a barrier fires only at its end, after the peak.
                    }
                } finally {
                    linkComputer.setLockComputeDisabled(false);
                }
            }
        } else if (PARALLEL_THREADS > 1 && !firstIteration
                   && analysisOrder.size() >= MIN_ELEMENTS_FOR_PARALLEL
                   && !(activateCycleBreaking && SEQUENTIAL_CYCLE_BREAKING)) {
            LOGGER.info("Parallel iteration: {} threads over {} elements", PARALLEL_THREADS, analysisOrder.size());
            List<Future<?>> futures = new ArrayList<>(analysisOrder.size());
            Map<Info, Long> elementMillis = new ConcurrentHashMap<>();
            // A seeded (incremental) analysis clears the links of its seed (ClearDerived) and then runs this
            // pass with callees still unlinked: computing a callee's links under its analysis monitor
            // (getOrCreate) nests monitors in call order, and two workers entering a call cycle from different
            // ends deadlock. Same lock-free recursion as the first-iteration waves; a no-op when every callee
            // already has its links (the cold case).
            linkComputer.setLockComputeDisabled(true);
            try (ExecutorService pool =
                         Executors.newFixedThreadPool(PARALLEL_THREADS)) {
                for (Info info : submissionOrder(analysisOrder)) {
                    futures.add(pool.submit(() -> {
                        long t0 = System.nanoTime();
                        try {
                            processElement(info, loopCycleBreaking, false, abstractTypes);
                        } finally {
                            elementMillis.put(info, (System.nanoTime() - t0) / 1_000_000);
                        }
                    }));
                }
            } finally { // close() awaits completion of all submitted tasks
                linkComputer.setLockComputeDisabled(false);
            }
            joinAll(futures);
            String slowest = elementMillis.entrySet().stream()
                    .sorted(Map.Entry.<Info, Long>comparingByValue().reversed())
                    .limit(10)
                    .map(e -> e.getKey().fullyQualifiedName() + "=" + e.getValue() + "ms")
                    .reduce((a, b) -> a + ", " + b).orElse("-");
            LOGGER.info("Slowest elements: {}", slowest);
        } else {
            // the same "slowest elements" line the parallel branch prints: the sequential first iteration is
            // the one every corpus probe runs, and it had no per-element figure at all
            Map<Info, Long> elementMillis = LOGGER.isInfoEnabled() ? new HashMap<>() : null;
            for (Info info : analysisOrder) {
                long t0 = System.nanoTime();
                processElement(info, loopCycleBreaking, firstIteration, abstractTypes);
                if (elementMillis != null) elementMillis.put(info, (System.nanoTime() - t0) / 1_000_000);
            }
            if (elementMillis != null) {
                String slowest = elementMillis.entrySet().stream()
                        .sorted(Map.Entry.<Info, Long>comparingByValue().reversed())
                        .limit(10)
                        .map(e -> e.getKey().fullyQualifiedName() + "=" + e.getValue() + "ms")
                        .reduce((a, b) -> a + ", " + b).orElse("-");
                LOGGER.info("Slowest elements (sequential, {} elements): {}", analysisOrder.size(), slowest);
            }
        }
        long endLoop = System.currentTimeMillis();
        // derived rather than collected during the loop: same content as before, in deterministic
        // analysisOrder order, independent of parallel completion order
        List<MethodInfo> abstractMethods = analysisOrder.stream()
                .filter(info -> info instanceof MethodInfo mi && !failed.contains(info)
                                && Util.unionOverImplementations(mi))
                .map(info -> (MethodInfo) info)
                .toList();
        List<TypeInfo> typesInOrder = analysisOrder.stream()
                .filter(info -> info instanceof TypeInfo && !failed.contains(info))
                .map(info -> (TypeInfo) info)
                .toList();

        long startAbstract = System.currentTimeMillis();
        int changesBeforeAbstract = propertiesChanged.get();
        try {
            Set<Info> visits = firstVisits;
            if (firstIteration || visits.isEmpty()) {
                abstractMethodAnalyzer.go(firstIteration, abstractMethods);
            } else {
                // a seeded run: an abstract method visited for the first time gets the first-iteration treatment
                abstractMethodAnalyzer.go(false, abstractMethods.stream().filter(mi -> !visits.contains(mi)).toList());
                abstractMethodAnalyzer.go(true, abstractMethods.stream().filter(visits::contains).toList());
            }
        } catch (RuntimeException | AssertionError | StackOverflowError e) {
            LOGGER.error("Caught exception in the abstract-method analyzer", e);
            if (!faultTolerant) throw e;
            // batch step — attribute to the first abstract method so the finding is at least locatable
            if (!abstractMethods.isEmpty()) messages.add(crashFinding(abstractMethods.getFirst(), e));
        } finally {
            // batch step: coarse attribution, any change dirties all abstract methods of this round
            if (propertiesChanged.get() > changesBeforeAbstract) changedInfos.addAll(abstractMethods);
        }

        long startSecondPass = System.currentTimeMillis();
        boolean sweep = sweepMode && fullPass && propertiesChanged.get() == 0;
        boolean typeCycleBreaking = sweepMode ? sweep : activateCycleBreaking;
        // CBSWEEP: a sweep repeats in place until it changes nothing. Type-level conclusions feed each other (an
        // implementor's verdict gives its interface the mark labels); one round per pass made every link of such a
        // chain cost a worklist drain plus a full pass, and the dogfood ran out of iterations (120) mid-epoch.
        int sweepRound = 0;
        boolean again;
        do {
            int changesBeforeRound = propertiesChanged.get();
            if (sweep) {
                if (sweepRound == 0) LOGGER.info("Cycle-breaking sweep over {} elements", analysisOrder.size());
                for (Info info : analysisOrder) {
                    if (!(info instanceof FieldInfo fieldInfo) || faultTolerant && failed.contains(info)) continue;
                    int changesBefore = propertiesChanged.get();
                    try {
                        fieldAnalyzer.go(fieldInfo, true);
                    } catch (RuntimeException | AssertionError | StackOverflowError e) {
                        LOGGER.error("Caught exception (cycle-breaking sweep) on {}", fieldInfo, e);
                        if (!faultTolerant) throw e;
                        failed.add(fieldInfo);
                        messages.add(crashFinding(fieldInfo, e));
                    } finally {
                        if (propertiesChanged.get() > changesBefore) changedInfos.add(fieldInfo);
                    }
                }
            }
            /*
            run once more, because the abstract method analyzer may have resolved independence and modification values
            for abstract methods.
             */
            for (TypeInfo typeInfo : typesInOrder) {
                if (faultTolerant && failed.contains(typeInfo)) continue;
                int changesBefore2 = propertiesChanged.get();
                try {
                    runTypeAnalyzers(typeCycleBreaking, typeInfo);
                } catch (RuntimeException | AssertionError | StackOverflowError e) {
                    LOGGER.error("Caught exception (2nd type pass) on {}", typeInfo, e);
                    if (!faultTolerant) throw e;
                    failed.add(typeInfo);
                    messages.add(crashFinding(typeInfo, e));
                } finally {
                    if (propertiesChanged.get() > changesBefore2) changedInfos.add(typeInfo);
                }
            }
            again = sweep && propertiesChanged.get() > changesBeforeRound && ++sweepRound < MAX_SWEEP_ROUNDS;
        } while (again);
        if (sweepRound > 0) LOGGER.info("Cycle-breaking sweep: {} extra round(s)", sweepRound);
        long end = System.currentTimeMillis();
        LOGGER.info("Phase timing: main loop {} ms, abstract batch {} ms, 2nd type pass {} ms",
                endLoop - startLoop, startSecondPass - startAbstract, end - startSecondPass);
        if (linkCacheEnabled()) {
            Map<String, Integer> misses = new TreeMap<>();
            linkCacheMisses.forEach((k, v) -> misses.put(k, v.get()));
            LOGGER.info("Link cache: {} hit(s), {} check failure(s), misses by first changed property {}",
                    linkCacheHits.getAndSet(0), linkCacheCheckFailures.getAndSet(0), misses);
            linkCacheMisses.clear();
        }
        unionInWriteTargets();
    }

    // ---- VariableData flatten-snapshot helpers (DESIGN-vardata-flatten.md) --------------------------
    // Both regenerate and flatten run only at pass boundaries in go(), where the workers are quiescent,
    // so neither races the parallel FieldAnalyzer reads of another method's last-statement VariableData.

    private void regenerateFlattened(List<Info> subset) {
        PrepAnalyzer prep = new PrepAnalyzer(runtime);
        for (Info info : subset) {
            if (info instanceof MethodInfo mi && flattenedMethods.remove(mi)) {
                clearAllVariableData(mi);
                try {
                    prep.doMethod(mi);
                } catch (RuntimeException | AssertionError | StackOverflowError e) {
                    LOGGER.error("VariableData regeneration failed for {}", mi, e);
                }
            }
        }
    }

    /** Called concurrently from processElement, once per method, right after its link is written. */
    private void flattenMethod(MethodInfo mi) {
        if (failed.contains(mi) || !flattenedMethods.add(mi)) return;
        try {
            if (!flattenOneMethod(mi)) flattenedMethods.remove(mi); // nothing flattened: don't track
        } catch (RuntimeException | AssertionError | StackOverflowError e) {
            LOGGER.error("VariableData flatten failed for {}; keeping full VD", mi, e);
            flattenedMethods.remove(mi);
        }
    }

    /** @return true iff the method's VariableData was flattened (false = abstract/empty, do not track). */
    private boolean flattenOneMethod(MethodInfo mi) {
        if (mi.methodBody() == null) return false;
        List<Statement> statements = mi.methodBody().statements();
        if (statements.isEmpty()) return false;
        Statement last = statements.getLast(); // its VD is the method's consumed (last-block) VariableData
        VariableData consumed = VariableDataImpl.of(last);
        if (!(consumed instanceof VariableDataImpl vdi)) return false;
        VariableDataImpl flat = vdi.flattened();
        setVariableData(last.analysis(), flat);
        if (VariableDataImpl.of(mi) != null) setVariableData(mi.analysis(), flat); // same anchor, method level
        // consumers read only the last statement; drop the rest (recursing into sub-blocks)
        walkStatements(statements, s -> {
            if (s != last) clearVariableData(s.analysis());
        });
        return true;
    }

    private void clearAllVariableData(MethodInfo mi) {
        clearVariableData(mi.analysis());
        if (mi.methodBody() != null) {
            walkStatements(mi.methodBody().statements(), s -> clearVariableData(s.analysis()));
        }
    }

    private static void walkStatements(List<Statement> statements, Consumer<Statement> action) {
        for (Statement s : statements) {
            action.accept(s);
            s.subBlockStream().forEach(b -> walkStatements(b.statements(), action));
        }
    }

    private static void setVariableData(PropertyValueMap analysis, VariableDataImpl flat) {
        // ATOMIC single put (PropertyValueMapImpl.overwrite is synchronized): a parallel FieldAnalyzer
        // reading this statement's VD via the equally-synchronized getOrNull sees the old chained VD or
        // the new flattened one — never a transient null, as a removeIf-then-set would leave.
        analysis.overwrite(VariableDataImpl.VARIABLE_DATA, flat);
    }

    private static void clearVariableData(PropertyValueMap analysis) {
        analysis.removeIf(p -> p == VariableDataImpl.VARIABLE_DATA);
    }

    private static void joinAll(List<Future<?>> futures) {
        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (ExecutionException e) {
                // only reachable when !faultTolerant (processElement rethrows); preserve that contract
                if (e.getCause() instanceof RuntimeException re) throw re;
                if (e.getCause() instanceof Error error) throw error;
                throw new RuntimeException(e.getCause());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }
    }

    private boolean linkCacheEnabled() {
        return LINK_CACHE && !flattenVariableData;
    }

    /**
     * @return true when the method's previous link computation can stand for this one (see LINK_CACHE). Under
     * LINKCACHECHECK, a hit is recomputed, and its recording compared afterwards in linkCacheStore.
     */
    private boolean linkCacheHit(MethodInfo methodInfo, boolean firstIteration) {
        if (!linkCacheEnabled() || firstIteration) return false;
        AnalysisAccessRecorder.Recording previous = linkRecordings.get(methodInfo);
        String change = previous == null ? "new" : previous.firstChange(linkCacheContext());
        if (change != null) {
            linkCacheMisses.computeIfAbsent(change, _ -> new AtomicInteger()).incrementAndGet();
            return false;
        }
        linkCacheHits.incrementAndGet();
        if (LINK_CACHE_CHECK) {
            linkCheckPending.add(methodInfo);
            return false;
        }
        return true;
    }

    /*
    the state outside the analysis maps that a link computation depends on: a frozen modification property refuses
    the computation's write without reading the map; ReceiverLevel is a gate that tests switch at runtime. The
    recursion-prevention stack is not part of it: a hit replaces a top-level computation, which always starts from
    an empty stack, and nested on-demand computations recorded inside it see the same stack each time.
     */
    private static Object linkCacheContext() {
        return List.of(TolerantWrite.modificationPropertiesFrozen(),
                ReceiverLevel.enabled());
    }

    private void linkCacheStore(MethodInfo methodInfo, AnalysisAccessRecorder.Recording recording, boolean completed) {
        if (!completed) {
            AnalysisAccessRecorder.abandon(recording);
            linkRecordings.remove(methodInfo);
            return;
        }
        AnalysisAccessRecorder.stop(recording);
        recording.setContext(linkCacheContext());
        if (recording.unusable() != null) {
            linkCacheMisses.computeIfAbsent("not stored: " + recording.unusable(), _ -> new AtomicInteger())
                    .incrementAndGet();
        }
        AnalysisAccessRecorder.Recording previous = recording.unusable() == null
                ? linkRecordings.put(methodInfo, recording) : linkRecordings.remove(methodInfo);
        if (linkCheckPending.remove(methodInfo) && previous != null) {
            // variablesLinkedToObject lands on a call expression the computation itself creates (LinkComputerImpl:
            // "write-once on a fresh expression"), so every computation writes a slot no other one can see
            String difference = previous.differenceFrom(recording,
                    Set.of(LinkComputerImpl.VARIABLES_LINKED_TO_OBJECT.key()));
            if (difference != null) {
                linkCacheCheckFailures.incrementAndGet();
                LOGGER.warn("LINKCACHECHECK: a cache hit on {} would differ from its computation: {}", methodInfo,
                        difference);
            }
        }
    }

    // CBSWEEP: set per pass in goInternal, before the loop starts; read by processElement on the workers
    private volatile boolean typesInSecondPassOnly;

    private void processElement(Info info, boolean activateCycleBreaking, boolean firstIteration,
                                Set<TypeInfo> abstractTypes) {
        // G43: OUTSIDE the fault-tolerant try below — a spent budget is not this element's crash, and must not be
        // recorded as a finding and swallowed. On a pool worker it reaches the caller through joinAll.
        AnalysisBudget b = budget;
        if (b != null) b.check(elementsDoneInPass.get());
        if (faultTolerant && failed.contains(info)) return; // an earlier iteration already crashed on this one
        int changesBefore = propertiesChanged.get();
        // task #35 Phase A: attribute all analysis() touches during this element to it (CONSEDGES gate)
        ConsumptionEdgeRecorder.setCurrent(info);
        try {
            if (info instanceof MethodInfo methodInfo) {
                sourceContractMaterializer.materialize(methodInfo);
                if ((firstIteration || firstVisits.contains(info)) && methodInfo.isAbstract()
                    && abstractTypes.add(info.typeInfo())) {
                    shallowTypeAnalyzer.analyze(info.typeInfo());
                }
                if ((!firstIteration || !methodInfo.analysis().haveAnalyzedValueFor(METHOD_LINKS))
                    && !linkCacheHit(methodInfo, firstIteration)) {
                    AnalysisAccessRecorder.Recording recording = linkCacheEnabled() ? AnalysisAccessRecorder.start() : null;
                    boolean completed = false;
                    try {
                        MethodLinkedVariables mlv = linkComputer.doMethod(methodInfo);
                        // methodLinks IS the method's summary: pass the target so the change reaches
                        // summaryChangedInfos and dirties dependents (the 3-arg overload's "?" context did not,
                        // leaving the worklist 0-dirty after a verification pass found methodLinks changes)
                        // This is the method's analysis-order SLOT: for a source method the latest computation wins
                        // over an equal-keyed value with other content (TolerantWrite.setLatestWins). A source
                        // abstract method's links are a pure function of its CURRENT independence (the union over
                        // implementations): the DEPENDENT default before the fold decides -- first iteration, and
                        // again after MODREACH's clearDerivedFamily -- gives 'it.§m ≡ this.§m' + content, the
                        // decided 'except' gives the sparser 'it.§m ☷{remove} this.§m'; and every caller's first
                        // computation carries that pessimism as extra links. "Equal primary, richer wins" kept the
                        // early value for good (EC O4, booleanIterator; dogfood: 29 methods stuck @Dependent).
                        // A library method's shallow links are a function of its annotations: ordinary retention.
                        boolean source = !methodInfo.typeInfo().compilationUnit().externalLibrary();
                        if (SCC_JOIN && source && mlv instanceof MethodLinkedVariablesImpl computed) {
                            mlv = joinInRecursionCycle(methodInfo, computed);
                        }
                        boolean written = source
                                ? TolerantWrite.setLatestWins(methodInfo.analysis(), METHOD_LINKS, mlv, methodInfo)
                                : TolerantWrite.setAllowControlledOverwrite(methodInfo.analysis(), METHOD_LINKS, mlv,
                                methodInfo);
                        if (written) propertiesChanged.incrementAndGet();
                        // flatten-snapshot Phase 5: this method is fully linked (summary written), so flatten
                        // its last-statement VD and drop the intermediates RIGHT HERE — concurrently, inside
                        // the giant SCC wave, which is the only place the peak can actually be bounded. Safe
                        // because the last-statement replacement is an atomic overwrite (a parallel
                        // FieldAnalyzer read sees the old chained or new flattened VD, both valid) and nothing
                        // cross-method reads this method's intermediate statements.
                        if (flattenVariableData) flattenMethod(methodInfo);
                        completed = true;
                    } finally {
                        if (recording != null) linkCacheStore(methodInfo, recording, completed);
                    }
                }
            } else if (info instanceof FieldInfo fieldInfo) {
                sourceContractMaterializer.materialize(fieldInfo);
                // after materialization (a contract wins over inference) and before fieldAnalyzer.go, which is
                // the first consumer of IMMUTABLE_FIELD via DynamicImmutability
                dynamicImmutabilityInference.infer(fieldInfo);
                if (fieldInfo.owner().isAbstract()
                    && (SHALLOW_FIELD_EVERY_VISIT || firstIteration || firstVisits.contains(info))) {
                    shallowTypeAnalyzer.analyzeField(fieldInfo);
                }
                fieldAnalyzer.go(fieldInfo, activateCycleBreaking);
            } else if (info instanceof TypeInfo typeInfo) {
                if (!typesInSecondPassOnly) runTypeAnalyzers(activateCycleBreaking, typeInfo);
            }
        } catch (RuntimeException | AssertionError | StackOverflowError e) {
            LOGGER.error("Caught exception processing {}", info, e);
            if (!faultTolerant) throw e;
            failed.add(info);
            // degradation marker (task #36): consumers relying on per-call data must go pessimistic here
            if (info instanceof MethodInfo mi) {
                // atomically: a linker thread may be marking it at the same time
                mi.analysis().getOrCreate(PropertyImpl.DEGRADED_ANALYSIS_METHOD,
                        () -> ValueImpl.BoolImpl.TRUE);
            }
            messages.add(crashFinding(info, e));
        } finally {
            ConsumptionEdgeRecorder.clearCurrent();
            // under PARALLEL the delta can over-attribute (another thread's change lands in the window);
            // a superset of changed elements is safe for the worklist
            if (propertiesChanged.get() > changesBefore) changedInfos.add(info);
            elementsDoneInPass.incrementAndGet();
            // intra-wave progress tick (in finally: a fault-tolerant crash still counts as processed)
            Runnable ec = elementCompletedCallback;
            if (ec != null) ec.run();
        }
    }

    private void unionInWriteTargets() {
        // union in the write-target attribution: writes that landed on elements other than the one being
        // processed (the link computer's on-demand recursion writing a callee's METHOD_LINKS)
        for (Object target : TolerantWrite.changedTargets()) {
            // ParameterInfo extends Info but is not an analysis-order element: attribute to its method
            Info info = target instanceof ParameterInfo pi ? pi.methodInfo()
                    : target instanceof Info i ? i : null;
            if (info != null) {
                changedInfos.add(info);
                summaryChangedInfos.add(info); // targets carry only summary-level properties (see TolerantWrite)
            }
        }
    }

    /**
     * Turn an analysis/link crash on one {@code Info} into a located ERROR finding, so a single bad element is
     * isolated (not fatal) and surfaces through the normal {@code messages()} / {@code ErrorReport} channel.
     * The category distinguishes a failure originating in the link module from one in the analyzer proper.
     */
    private static Message crashFinding(Info info, Throwable t) {
        String category = isLinkCrash(t) ? LINK_CRASH : ANALYZER_CRASH;
        String detail = t.getClass().getSimpleName() + (t.getMessage() == null ? "" : ": " + t.getMessage());
        return MessageImpl.error(info, category,
                "analysis crashed on " + info.fullyQualifiedName() + " — " + detail
                + " (isolated; this element was not fully analyzed)");
    }

    private static boolean isLinkCrash(Throwable t) {
        return Arrays.stream(t.getStackTrace())
                .anyMatch(ste -> ste.getClassName().startsWith("io.codelaser.maddi.modification.link."));
    }

    private void runTypeAnalyzers(boolean activateCycleBreaking, TypeInfo typeInfo) {
        typeModIndyAnalyzer.go(typeInfo, activateCycleBreaking);
        typeIndependentAnalyzer.go(typeInfo, activateCycleBreaking);
        typeImmutableAnalyzer.go(typeInfo, activateCycleBreaking);
        typeContainerAnalyzer.go(typeInfo);
        typeEventualAnalyzer.go(typeInfo, activateCycleBreaking);
        staticSideEffectAnalyzer.go(typeInfo, activateCycleBreaking); // additive, writes only its own
    }
}
