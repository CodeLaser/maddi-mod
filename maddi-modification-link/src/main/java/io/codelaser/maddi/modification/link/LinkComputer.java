package io.codelaser.maddi.modification.link;

import java.util.List;
import java.util.Map;
import java.util.Set;

import io.codelaser.maddi.cst.api.analysis.Value;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.modification.prepwork.variable.Links;
import io.codelaser.maddi.modification.prepwork.variable.MethodLinkedVariables;

public interface LinkComputer {
    // tests only!
    void doPrimaryType(TypeInfo primaryType);

    // can be called multiple times
    MethodLinkedVariables doMethod(MethodInfo methodInfo);

    /**
     * Parallel first iteration (strata-parallel waves): while enabled, an on-demand LOCK on an ABSENT
     * METHOD_LINKS degrades to a shallow computation instead of computing under the callee's analysis
     * monitor (cross-thread monitor cycles would deadlock). The wave order makes the case rare; iteration
     * 2+ and the certification loop repair the shallow value.
     */
    default void setLockComputeDisabled(boolean disabled) {
        // no-op by default
    }

    /**
     * Worklist support: consumer read consumed's METHOD_LINKS summary while its own links were computed —
     * the ground truth of "consumer depends on consumed", including value-mediated flows (functional-
     * interface application) that have no syntactic reference edge in the call graph. Accumulated over the
     * whole run (dependencies persist across iterations); the iterating analyzer unions these into its
     * reverse adjacency so summary changes dirty their actual readers.
     */
    default void recordSummaryConsumption(MethodInfo consumer, MethodInfo consumed) {
        // no-op by default
    }

    default Map<MethodInfo, Set<MethodInfo>> consumedSummaries() {
        return Map.of();
    }

    int propertiesChanged();

    void reset();

    /**
     * Vestigial. There is one fixpoint engine, the witness-based incremental one ({@code impl.graph.IncrementalFixpointEngine},
     * the former 'sv' engine); the legacy engine was deleted when the port completed (2026-07). The option is kept
     * for source compatibility and is read by nothing.
     */
    enum Engine {LEGACY, INCREMENTAL}

    /*
     objectGraphLinks: store and propagate the coarse object-graph natures (∩ ≤ ≥) in the closure. None of
     linking's three applications (modification propagation via §m, same-type/VL2O for interface extraction,
     new-object tracking) consumes them — they exist for the full-fidelity content-containment OUTPUT the tests
     assert. Their transitive web is quadratic on deeply recursive generic structures (TestParSeqLinkBench:
     48.7s with, ~1s without), so PRODUCTION switches them off.
     */
    /*
     nullConstantReturns: a return whose links all point at markers is emptied from the summary
     (LinkComputerImpl.emptyIfOnlySomeValue), so 'return null' leaves '[] --> -' and a caller sees only '$_v'. With
     this option the links to NULL-constant markers stay ('direct←$_ce0'), which the nullability analysis needs to
     follow null across a call (maddi-mod docs/design/nullability.md §3, M1). Off by default: it changes METHOD_LINKS,
     so it waits for an FPDUMP A/B before it can be on in PRODUCTION; TestNullFlowProbe pins both settings.
     */
    record Options(boolean recurse, boolean forceShallow, boolean checkDuplicateNames, boolean trackObjectCreations,
                   boolean objectGraphLinks, Engine engine, boolean nullConstantReturns) {
        public static final Options TEST = new Options(true, false, true,
                false, true, Engine.LEGACY, false);
        public static final Options PRODUCTION = new Options(true, false, false,
                true, false, Engine.LEGACY, false);
        public static final Options FORCE_SHALLOW = new Options(true, true, true,
                false, true, Engine.LEGACY, false);

        public static class Builder {
            boolean recurse;
            boolean forceShallow;
            boolean checkDuplicateNames;
            boolean trackObjectCreations;
            boolean objectGraphLinks = true;
            Engine engine = Engine.LEGACY;
            boolean nullConstantReturns;

            public Builder setObjectGraphLinks(boolean objectGraphLinks) {
                this.objectGraphLinks = objectGraphLinks;
                return this;
            }

            public Builder setCheckDuplicateNames(boolean checkDuplicateNames) {
                this.checkDuplicateNames = checkDuplicateNames;
                return this;
            }

            public Builder setForceShallow(boolean forceShallow) {
                this.forceShallow = forceShallow;
                return this;
            }

            public Builder setRecurse(boolean recurse) {
                this.recurse = recurse;
                return this;
            }

            public Builder setTrackObjectCreations(boolean trackObjectCreations) {
                this.trackObjectCreations = trackObjectCreations;
                return this;
            }

            public Builder setNullConstantReturns(boolean nullConstantReturns) {
                this.nullConstantReturns = nullConstantReturns;
                return this;
            }

            public Builder setEngine(Engine engine) {
                this.engine = engine;
                return this;
            }

            public Options build() {
                return new Options(recurse, forceShallow, checkDuplicateNames, trackObjectCreations,
                        objectGraphLinks, engine, nullConstantReturns);
            }
        }

    }

    interface ListOfLinks extends Value {
        List<Links> list();
    }
}
