package io.codelaser.maddi.modification.link.impl.graph;

import java.util.concurrent.atomic.AtomicLong;

/*
CodeLaser/maddi-mod#92 diagnostic (env OVLSTATS=1): where the implicit '∩' costs time. Totals printed at JVM exit.
 */
public final class OverlapStats {
    public static final boolean ON = System.getenv("OVLSTATS") != null;

    private static final AtomicLong builds = new AtomicLong();
    private static final AtomicLong buildNanos = new AtomicLong();
    private static final AtomicLong buildVertices = new AtomicLong();
    private static final AtomicLong maxBuildNanos = new AtomicLong();
    private static final AtomicLong freezes = new AtomicLong();
    private static final AtomicLong freezeNanos = new AtomicLong();
    private static final AtomicLong reads = new AtomicLong();
    private static final AtomicLong cachedReads = new AtomicLong();

    // per thread: the nanos spent in builds and freezes, for the per-method line (LinkComputerImpl.doMethod)
    private static final ThreadLocal<long[]> threadNanos = ThreadLocal.withInitial(() -> new long[1]);

    public static long threadNanos() {
        return threadNanos.get()[0];
    }

    private static final AtomicLong freezeStale = new AtomicLong();
    private static final AtomicLong freezeAffected = new AtomicLong();
    private static final AtomicLong freezeInFrozen = new AtomicLong();
    private static final AtomicLong freezeSeeds = new AtomicLong();
    private static final AtomicLong freezeNewGroups = new AtomicLong();
    private static final AtomicLong growthPasses = new AtomicLong();
    private static final AtomicLong creationNanos = new AtomicLong();
    private static final AtomicLong growthNanos = new AtomicLong();
    private static final AtomicLong methods = new AtomicLong();
    private static final AtomicLong statements = new AtomicLong();
    private static final AtomicLong dirtyAll = new AtomicLong();
    private static final AtomicLong reused = new AtomicLong();
    private static final AtomicLong extracted = new AtomicLong();
    private static final AtomicLong extractedLinks = new AtomicLong();

    // WriteLinksAndModification: per statement, whether reuse was off (dirty-all); per variable, reused or extracted
    public static void statement(boolean all) {
        statements.incrementAndGet();
        threadShape.get()[0]++;
        if (all) dirtyAll.incrementAndGet();
    }

    public static void extraction(boolean reuse, int links) {
        long[] shape = threadShape.get();
        if (reuse) {
            reused.incrementAndGet();
            shape[1]++;
        } else {
            extracted.incrementAndGet();
            extractedLinks.addAndGet(links);
            shape[2]++;
            shape[3] += links;
        }
    }

    // per thread, for the per-method line: statements, reused, extracted, links, most vertices, largest class
    private static final ThreadLocal<long[]> threadShape = ThreadLocal.withInitial(() -> new long[6]);

    // links extracted on this thread since the top-level method started (callees computed inside it included)
    public static long threadLinks() {
        return threadShape.get()[3];
    }

    public static String takeShape() {
        long[] s = threadShape.get();
        String r = "statements=" + s[0] + " reused=" + s[1] + " extracted=" + s[2] + " links=" + s[3]
                   + " maxVertices=" + s[4] + " maxClass=" + s[5];
        java.util.Arrays.fill(s, 0);
        return r;
    }

    static void shape(int vertices, int largestClass) {
        long[] s = threadShape.get();
        s[4] = Math.max(s[4], vertices);
        s[5] = Math.max(s[5], largestClass);
    }
    private static final AtomicLong methodNanos = new AtomicLong();
    private static final AtomicLong joins = new AtomicLong();
    private static final AtomicLong crossJoins = new AtomicLong();
    private static final AtomicLong crossPairs = new AtomicLong();
    private static final ThreadLocal<long[]> threadCross = ThreadLocal.withInitial(() -> new long[1]);

    public static long threadCross() {
        return threadCross.get()[0];
    }

    private static final AtomicLong staleReads = new AtomicLong();
    private static final AtomicLong staleDiffer = new AtomicLong();
    private static final java.util.concurrent.ConcurrentHashMap<String, AtomicLong> staleSites
            = new java.util.concurrent.ConcurrentHashMap<>();

    // OVLSNAPSHOT: a read after the closure changed since the refresh; 'differs' when the current classes answer
    // differently. The differing reads are counted per calling site outside the engine and Graph.
    static void staleRead(boolean differs) {
        staleReads.incrementAndGet();
        if (differs) {
            staleDiffer.incrementAndGet();
            String site = StackWalker.getInstance().walk(frames -> frames
                    .filter(f -> !f.getClassName().endsWith("IncrementalFixpointEngine")
                                 && !f.getClassName().endsWith("OverlapStats")
                                 && !(f.getClassName().endsWith("linkgraph.Graph") && f.getMethodName().startsWith("closure")))
                    .filter(f -> !f.getMethodName().startsWith("lambda$") && !f.getClassName().startsWith("java."))
                    .limit(3).map(f -> f.getClassName().substring(f.getClassName().lastIndexOf('.') + 1)
                                       + "." + f.getMethodName() + ":" + f.getLineNumber())
                    .collect(java.util.stream.Collectors.joining("<")));
            staleSites.computeIfAbsent(site, _ -> new AtomicLong()).incrementAndGet();
        }
    }

    private static final java.util.concurrent.ConcurrentHashMap<String, AtomicLong> links
            = new java.util.concurrent.ConcurrentHashMap<>();

    // FollowGraph: one extracted link, by kind
    public static void link(String kind) {
        links.computeIfAbsent(kind, _ -> new AtomicLong()).incrementAndGet();
    }

    // one union of two alternatives: the implicit pairs that neither alternative has (both directions counted)
    static void join(int pairs) {
        joins.incrementAndGet();
        if (pairs > 0) {
            crossJoins.incrementAndGet();
            crossPairs.addAndGet(pairs);
            threadCross.get()[0] += pairs;
        }
    }

    // one top-level LinkComputerImpl.doMethod: all link computation, with or without the implicit overlap
    public static void method(long nanos) {
        methods.incrementAndGet();
        methodNanos.addAndGet(nanos);
        if (methods.get() % 500 == 0) print();
    }

    static void freezeShape(boolean stale, boolean affected, boolean inFrozen, int seeds) {
        if (stale) freezeStale.incrementAndGet();
        if (affected) freezeAffected.incrementAndGet();
        if (inFrozen) freezeInFrozen.incrementAndGet();
        freezeSeeds.addAndGet(seeds);
    }

    static void freezeNewGroups() {
        freezeNewGroups.incrementAndGet();
    }

    static void buildPhases(long creation, long growth, int passes) {
        creationNanos.addAndGet(creation);
        growthNanos.addAndGet(growth);
        growthPasses.addAndGet(passes);
    }

    private static void print() {
        System.out.println("OVLSTATS builds=" + builds + " buildMs=" + buildNanos.get() / 1_000_000
                           + " avgVertices=" + (builds.get() == 0 ? 0 : buildVertices.get() / builds.get())
                           + " maxBuildMs=" + maxBuildNanos.get() / 1_000_000
                           + " freezes=" + freezes + " freezeMs=" + freezeNanos.get() / 1_000_000
                           + " reads=" + reads + " cachedReads=" + cachedReads
                           + " freezeStale=" + freezeStale + " freezeAffected=" + freezeAffected
                           + " freezeInFrozen=" + freezeInFrozen + " freezeSeeds=" + freezeSeeds
                           + " freezeNewGroups=" + freezeNewGroups + " creationMs=" + creationNanos.get() / 1_000_000
                           + " growthMs=" + growthNanos.get() / 1_000_000 + " growthPasses=" + growthPasses
                           + " methods=" + methods + " methodMs=" + methodNanos.get() / 1_000_000
                           + " statements=" + statements + " dirtyAll=" + dirtyAll + " reused=" + reused
                           + " extracted=" + extracted + " extractedLinks=" + extractedLinks
                           + " joins=" + joins + " crossJoins=" + crossJoins + " crossPairs=" + crossPairs
                           + " staleReads=" + staleReads + " staleDiffer=" + staleDiffer
                           + " staleSites=" + new java.util.TreeMap<>(staleSites).toString().replace(' ', '_')
                           + " links=" + new java.util.TreeMap<>(links).toString().replace(' ', '_'));
    }

    private OverlapStats() {
    }

    static void build(long nanos, int vertices) {
        builds.incrementAndGet();
        buildNanos.addAndGet(nanos);
        threadNanos.get()[0] += nanos;
        buildVertices.addAndGet(vertices);
        maxBuildNanos.accumulateAndGet(nanos, Math::max);
        if (nanos > 200_000_000L) System.out.println("OVLSLOW build " + nanos / 1_000_000 + "ms vertices=" + vertices);
        if (builds.get() % 20_000 == 0) print();
    }

    static void freeze(long nanos) {
        freezes.incrementAndGet();
        freezeNanos.addAndGet(nanos);
        threadNanos.get()[0] += nanos;
        if (nanos > 200_000_000L) System.out.println("OVLSLOW freeze " + nanos / 1_000_000 + "ms");
        if (freezes.get() % 20_000 == 0) print();
    }

    static void read(boolean cached) {
        reads.incrementAndGet();
        if (cached) cachedReads.incrementAndGet();
    }
}
