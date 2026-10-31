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

package io.codelaser.maddi.modification.analyzer.nullability;

import io.codelaser.maddi.cst.api.analysis.Property;
import io.codelaser.maddi.cst.api.element.Element;
import io.codelaser.maddi.cst.api.expression.Expression;
import io.codelaser.maddi.cst.api.expression.MethodCall;
import io.codelaser.maddi.cst.api.expression.MethodReference;
import io.codelaser.maddi.cst.api.info.FieldInfo;
import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.ParameterInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.api.analysis.Value;
import io.codelaser.maddi.cst.api.type.NullableState;
import io.codelaser.maddi.cst.api.type.ParameterizedType;
import io.codelaser.maddi.cst.api.variable.LocalVariable;
import io.codelaser.maddi.cst.api.variable.Variable;
import io.codelaser.maddi.cst.impl.analysis.NullAnnotations;
import io.codelaser.maddi.cst.impl.analysis.PropertyImpl;
import io.codelaser.maddi.cst.impl.analysis.ValueImpl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.codelaser.maddi.modification.analyzer.nullability.Types.*;

/**
 * Declaration nullability as reachability over the converged link facts (docs/design/nullability.md §4.1, M3).
 * Modelled on {@code ShadowModificationPass}: a one-shot pass after the iterating analyzer has converged, so no
 * optimistic value is frozen before its evidence arrives.
 * <p>
 * <b>Nodes</b>: fields, parameters, methods standing for their return value, and local variables. A local is
 * keyed by its DECLARATION ({@link Local}), resolved through the block scopes, so same-named locals of sibling
 * scopes are distinct nodes (a {@link LocalVariable} is equal by name). <b>Seeds</b> (may be null): a null constant flowing into a node, as a link to a null-constant marker
 * or as a {@code null} argument; a field that can still hold Java's default value (not final, no initializer, not
 * assigned in every constructor); a local or field assigned the literal {@code null} (read from the code, so also in
 * a degraded method); under {@link Policy#nullTests()}, a parameter or field compared with null; library null
 * contracts (a library method's {@code NULLABILITY_*} property, from the analysis hints, says NULLABLE):
 * overriding a method that may return null or accepts null, and using such a method's result directly; under
 * {@link Policy#contracts()}, a source declaration annotated nullable. A source declaration annotated NON-NULL is
 * a contract too: null does not travel through it (passing null there is the caller's error, an M5 finding), and
 * its verdict is the annotation's.
 * <b>Edges</b>, in the direction null travels (source to recipient): {@code ←}/{@code ≡}/{@code →} links between
 * nodes in each method's variable data; an argument's nodes to the callee's parameter (the per-call argument
 * links); an implementation's return to the return it overrides; and a parameter to the parameter at the same
 * index along the override chain, both ways (Kotlin's rule: one nullability per chain).
 * <b>Use sites</b> ({@link NonNullFacts}, M4): an edge from a link or an argument is not made where the source is a
 * local or parameter known non-null at that statement (after {@code if (v == null) return;}, inside
 * {@code if (v != null)}, after a dereference, in the {@code ?:} branch that excludes null).
 * <p>
 * <b>Verdict</b>: reached is {@link NullableState#NULLABLE}; not reached is {@link Policy#unreached()}; a primitive
 * is {@link NullableState#NONNULL}; a type-variable type and the outputs of a degraded method (no links) are
 * {@link NullableState#UNSPECIFIED}, as are the unreached locals of a degraded method. Locals get a verdict too ({@link Report#local}).
 * <p>
 * <b>Content</b>: an array's elements ({@link Content}) and each type argument ({@link Arg}) are nodes of their own,
 * from the link engine's element and hidden-content variables ({@code a[i]}, {@code list.§$s},
 * {@code map.§$$s[-2]}, {@code box.t}), from writes into a generic receiver ({@code list.add(x)}), and from
 * invariance: wherever an array or a generic value flows, both ends' slots are tied both ways.
 * <p>
 * Requires the analyzer to have run with {@code Configuration.nullability()}: without
 * {@code LinkComputer.Options.nullConstantReturns} a null does not cross a call, and without
 * {@code trackObjectCreations} there are no argument links.
 * <p>
 * <b>Parts</b>: this class runs the rounds ({@link #go}) and, per round, the phases in their order (once). Each phase
 * is a part of its own, built once per round and handed what it reads: {@link Index} and {@link TypeUses} (the
 * indexes), {@link Seeds} (where null enters), {@link Contracts} (where it stops), {@link NullGraph} (nodes, edges,
 * closure), {@link Nodes}, {@link MethodWalker} and {@link CallResults} (the walk that builds the graph),
 * {@link GraphCompletion}, {@link Tolerance}, {@link Preconditions}, {@link OutsideCallers}, {@link ReadNarrowing}
 * and {@link Verdicts}; {@link Trace} prints for all of them.
 */
public final class NullabilityPass {

    private final Policy policy;
    // NULLWHY: every print of the pass goes through it
    private final Trace trace;
    // analysed parameters an earlier round found non-null on every normal exit of their method: passing null throws
    private final Set<ParameterInfo> preconditions;
    // what an earlier round found about final fields per runtime class: a read narrowed by 'instanceof' (gap 3)
    private final NarrowedReads narrowed;
    // CodeLaser/maddi-mod#48 A5: what an earlier round found non-null at every call of a private instance method, per
    // method the fields of this; and this round's, for the next (fieldsAtEntry)
    private final Map<MethodInfo, Set<Variable>> fieldsAtEntry;
    private Map<MethodInfo, Set<Variable>> nextFieldsAtEntry = Map.of();
    // CodeLaser/maddi-mod#48 B6: the lookups this round's report knows the key of present (useSites), for the next
    // round, whose graph's walk cannot know it itself: its put's value is non-null by a library call only the closure
    // clears (Contracts.presentLookups)
    private Set<Expression> nextPresentLookups = Set.of();
    // gate NOROUNDKEYS: no key presence from an earlier round (A/B for #48 B6)
    private static final Switches.Gate ROUND_KEYS_OFF = Switches.gate("NOROUNDKEYS");
    // gate NOENTRYFIELDS: no field of this is non-null at a private method's entry by its calls (A/B for #48 A5)
    private static final Switches.Gate ENTRY_FIELDS_OFF = Switches.gate("NOENTRYFIELDS");
    // the round's results: what null reaches, after each closure; the locals asserted at their declaration
    // (Policy.assertAtDeclaration); not reached by null, but by a value of an outside caller (World.OPEN*): UNSPECIFIED
    private Set<Object> reached = Set.of();
    private final Set<Local> asserted = new HashSet<>();
    private Set<Object> external = Set.of();
    // Culprits: the statement the walk is at (WalkSite)
    private final WalkSite site = new WalkSite();
    // the parts of this round, in the order they are built (see once)
    private final Index index;
    private final TypeUses typeUses;
    private final Seeds seeds;
    private final Contracts contracts;
    // the nodes and edges of this round
    private final NullGraph graph;
    private final Nodes nodes;
    // the narrowed reads: this round's, and the next round's from what null reaches in it
    private final ReadNarrowing narrowing;
    // the facts the walk reads, and the preconditions found with them: built in once
    private NonNullFacts facts;
    private Preconditions preconditionChecks;
    private static final int CULPRIT_STEPS = 12;

    public NullabilityPass(Policy policy) {
        this(policy, Trace.NULL_WHY, Set.of(), Set.of(), NarrowedReads.NONE, Map.of(), Set.of());
    }

    private NullabilityPass(Policy policy, Trace trace, Set<MethodInfo> trustedReturns,
                            Set<ParameterInfo> preconditions, NarrowedReads narrowed,
                            Map<MethodInfo, Set<Variable>> fieldsAtEntry, Set<Expression> presentLookups) {
        this.policy = policy;
        this.trace = trace;
        this.index = new Index(policy);
        this.typeUses = new TypeUses(policy, index);
        this.seeds = new Seeds(trace, index, site);
        this.contracts = new Contracts(policy, trace, index, seeds, trustedReturns, preconditions, presentLookups);
        this.graph = new NullGraph(policy, trace, site, index, typeUses, seeds, contracts);
        this.nodes = new Nodes(policy, index, graph);
        this.narrowing = new ReadNarrowing(policy, index, contracts, narrowed);
        this.preconditions = preconditions;
        this.narrowed = narrowed;
        this.fieldsAtEntry = fieldsAtEntry;
    }

    private static final int MAX_ROUNDS = 5;

    /**
     * Runs the pass in rounds. Round 1 trusts no analysed method's return. Each later round takes the analysed
     * methods whose return the previous round found unreached by null (sound: that round's reachability
     * over-approximates) as known non-null values: {@code field = create()} then carries no null. Dropping edges
     * only shrinks what null reaches, so the trusted set grows until it is stable. Likewise the parameters an earlier
     * round found to be preconditions ({@link NonNullFacts#nonNullAtExit}): an argument passed to one is non-null
     * after the call, which may make the caller's own parameter a precondition. And the fields of this non-null at
     * every call of a private method ({@link NonNullFacts#fieldsAtCalls}): non-null when its body starts.
     */
    public Report go(List<Info> analysisOrder) {
        Set<MethodInfo> trusted = Contracts.lazyCacheCalls(policy, analysisOrder);
        Set<ParameterInfo> nonNullAtExit = Set.of();
        NarrowedReads narrowedReads = NarrowedReads.NONE;
        Map<MethodInfo, Set<Variable>> atEntry = Map.of();
        Set<Expression> present = Set.of();
        for (int round = 1; ; round++) {
            NullabilityPass pass = new NullabilityPass(policy, trace, trusted, nonNullAtExit, narrowedReads, atEntry, present);
            Report report = pass.once(analysisOrder);
            Set<MethodInfo> next = pass.unreachedReturns();
            Set<ParameterInfo> nextPreconditions = pass.preconditionChecks.preconditions();
            NarrowedReads nextNarrowed = pass.narrowing.next(pass.reached, pass.external, pass.facts);
            Map<MethodInfo, Set<Variable>> nextAtEntry = pass.nextFieldsAtEntry;
            Set<Expression> nextPresent = pass.nextPresentLookups;
            if (next.equals(trusted) && nextPreconditions.equals(nonNullAtExit) && nextNarrowed.equals(narrowedReads)
                && nextAtEntry.equals(atEntry) && nextPresent.equals(present) || round == MAX_ROUNDS) {
                if (io.codelaser.maddi.modification.common.util.Culprits.ENABLED) pass.writeCulprits(report);
                return report;
            }
            trusted = next;
            nonNullAtExit = nextPreconditions;
            narrowedReads = nextNarrowed;
            atEntry = nextAtEntry;
            present = nextPresent;
        }
    }

    /*
     CodeLaser/maddi-mod#48 A5, nacos CredentialWatcher: 'loadPropertyPath(init); InputStream is =
     loadPropertyPathToStream();', the first leaving 'propertyPath' non-null, the second dereferencing it. Per private
     instance method, the fields of this the walk found non-null at every call, provided it walked every use of the
     method: a call in a field's initializer runs before any method, and is not walked. A private method has no
     callers outside its class's nest, in an open world too.
     */
    private static Map<MethodInfo, Set<Variable>> fieldsAtEntry(NonNullFacts walked, List<MethodInfo> methods,
                                                                List<FieldInfo> fields) {
        if (ENTRY_FIELDS_OFF.get()) return Map.of();
        Set<MethodInfo> unwalked = new HashSet<>();
        java.util.function.Predicate<Element> uses = e -> {
            MethodInfo callee = e instanceof MethodCall mc ? mc.methodInfo()
                    : e instanceof MethodReference mr ? mr.methodInfo() : null;
            if (callee != null && NonNullFacts.privateInstanceMethod(callee) && !walked.walked((Expression) e)) {
                unwalked.add(callee);
            }
            return true;
        };
        for (MethodInfo mi : methods) if (mi.methodBody() != null) mi.methodBody().visit(uses);
        for (FieldInfo fi : fields) if (fi.initializer() != null) fi.initializer().visit(uses);
        Map<MethodInfo, Set<Variable>> map = new HashMap<>();
        walked.fieldsAtCalls().forEach((method, set) -> {
            if (!set.isEmpty() && !unwalked.contains(method)) map.put(method, Set.copyOf(set));
        });
        return Map.copyOf(map);
    }

    // analysed methods with a reference return no null reached, not degraded. A type-variable return counts too:
    // its verdict stays parametric, but no null of this program reaches it, so its calls here are non-null values
    // (guava's own 'checkNotNull(T)')
    private Set<MethodInfo> unreachedReturns() {
        Set<MethodInfo> set = new HashSet<>();
        for (MethodInfo mi : index.analysed) {
            ParameterizedType rt = mi.returnType();
            if (mi.isConstructor() || rt.isVoid() || rt.isPrimitiveExcludingVoid() && rt.arrays() == 0
                || contracts.degraded.contains(mi) || reached.contains(mi) || external.contains(mi)) continue;
            set.add(mi);
        }
        return Set.copyOf(set);
    }

    private Report once(List<Info> analysisOrder) {
        List<MethodInfo> methods = analysisOrder.stream()
                .filter(i -> i instanceof MethodInfo).map(i -> (MethodInfo) i).toList();
        List<FieldInfo> fields = analysisOrder.stream()
                .filter(i -> i instanceof FieldInfo).map(i -> (FieldInfo) i).toList();
        // 1. the indexes
        index.analysed.addAll(methods);
        index.analysedFields = fields;
        typeUses.nonNullBounds(analysisOrder, methods, fields);
        methods.forEach(seeds::indexNullLiterals);
        for (MethodInfo mi : methods) if (mi.methodBody() != null) seeds.indexFills(mi.methodBody());
        for (MethodInfo mi : methods) {
            for (MethodInfo overridden : overrides(mi)) {
                index.implementations.computeIfAbsent(overridden, _ -> new java.util.ArrayList<>()).add(mi);
            }
        }
        index.indexFunctionals(methods);
        index.indexKeyedMaps(methods, fields);
        NullPredicates predicates = NullPredicates.infer(methods);
        java.util.function.BiPredicate<Object, TypeInfo> narrowedReads = narrowed.isEmpty() ? null : narrowed::nonNull;
        // 2. the facts the walk reads, and the parts that read them
        facts = new NonNullFacts(contracts::parameterContract, contracts::returnContract)
                .withPredicates(predicates)
                .withNarrowedReads(narrowedReads)
                .withNarrowedInMethod(narrowedReads == null ? null : narrowing::narrowedInMethod)
                .withFieldsAtEntry(fieldsAtEntry)
                .withSuperCalls(policy.callResults() && !Contracts.SUPER_CALLS_OFF.get() ? contracts::ownReturnsNonNull : null);
        contracts.facts = facts;
        CallResults callResults = new CallResults(policy, index, seeds, contracts, graph, nodes, facts);
        preconditionChecks = new Preconditions(policy, index, nodes, facts, preconditions);
        MethodWalker walker = new MethodWalker(policy, trace, site, index, seeds, contracts, graph, nodes, callResults,
                preconditionChecks, facts, preconditions);
        OutsideCallers outsideCallers = new OutsideCallers(policy, index, contracts, graph, facts);
        Tolerance tolerance = new Tolerance(trace, index, seeds, contracts, graph, outsideCallers, facts);
        GraphCompletion completion = new GraphCompletion(policy, index, seeds, contracts, graph, nodes, callResults,
                outsideCallers);
        // 3. the contracts known before the walk
        contracts.annotations(methods, fields);
        contracts.overrideContracts(methods);
        callResults.deadNullAssignments(methods);
        // 4. the graph, a method at a time
        for (MethodInfo mi : methods) {
            nodes.owner = mi;
            walker.buildForMethod(mi);
        }
        // 5. what needs every body walked
        completion.complete(methods, fields);
        // 6. the rules that need the complete graph
        contracts.libraryParameterContracts(graph.successors);
        if (!Tolerance.TOLERANT_OFF.get()) tolerance.tolerantParameters(methods);
        // 7. the closure, again after each rule that adds contracts from it
        Map<Object, Object> cause = new LinkedHashMap<>();
        reached = graph.closure(cause);
        List<NullArgument> nullArguments = preconditionChecks.nullArguments(reached);
        Set<ParameterInfo> preconditionsNonNull = Set.of();
        if (policy.preconditionsNonNull()) {
            Set<ParameterInfo> declared = preconditionChecks.declaredPreconditions();
            Set<Object> reachedBefore = reached;
            // the printers report only these: elsewhere the parameter is non-null without the policy
            preconditionsNonNull = declared.stream().filter(reachedBefore::contains)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            if (!declared.isEmpty()) {
                contracts.addAll(declared);
                cause.clear();
                reached = graph.closure(cause);
            }
        }
        if (policy.assertAtDeclaration()) {
            // only where null arrives: elsewhere the assertion would be a '!!' on a non-null value
            walker.assertCandidates.forEach(local -> {
                if (reached.contains(local)) asserted.add(local);
            });
            if (!asserted.isEmpty()) {
                contracts.addAll(asserted);
                cause.clear();
                reached = graph.closure(cause);
            }
        }
        if (policy.world() != World.CLOSED) external = outsideCallers.externalClosure(methods, reached);
        if (policy.callResults()) reached = Verdicts.storedOnlyIntoNullableFields(index, methods, reached, cause);
        // 8. the verdicts
        Verdicts verdictRules = new Verdicts(policy, trace, index, seeds, contracts, graph, reached, external);
        Map<Local, ParameterizedType> locals = verdictRules.locals(callResults.guardedLocals);
        Map<Info, ParameterizedType> verdicts = verdictRules.declarations(methods, fields, cause);
        // 9. the use sites, as the printers ask them
        NonNullFacts smartCasts = facts.kotlinSmartCasts();
        methods.forEach(smartCasts::walk);
        Set<Expression> nonNullCallResults = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        Map<MethodInfo, Boolean> intrinsic = new HashMap<>();
        Set<Object> finalReached = reached;
        graph.callIds.forEach((call, id) -> {
            MethodInfo callee = graph.callees.get(id);
            if (finalReached.contains(callee)
                && !graph.callResultNullable(callee, id, finalReached, finalReached::contains, intrinsic)) {
                nonNullCallResults.add(call);
            }
        });
        // gap 7: the facts handed out know what the closure decided; the walk that built the graph could not
        NonNullFacts useSites = new NonNullFacts(contracts::parameterContract, contracts::returnContract)
                .withPredicates(predicates)
                .withNarrowedReads(narrowedReads)
                .withNarrowedInMethod(narrowedReads == null ? null : narrowing::narrowedInMethod)
                .withNotNullable(e -> verdictRules.notNullable(e, nonNullCallResults))
                .withFieldsAtEntry(fieldsAtEntry);
        methods.forEach(useSites::walk);
        nextFieldsAtEntry = fieldsAtEntry(useSites, methods, fields);
        if (!ROUND_KEYS_OFF.get()) {
            Set<Expression> present = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
            for (MethodCall lookup : contracts.lookups) if (useSites.keyPresentWhenCalled(lookup)) present.add(lookup);
            nextPresentLookups = present;
        }
        return new Report(verdicts, locals, cause, seeds.seedOrigin, useSites, smartCasts, Set.copyOf(asserted),
                Set.copyOf(walker.unobservedCandidates), nonNullCallResults, preconditions, nullArguments,
                preconditionsNonNull, preconditionChecks.rejectsNull(), graph.writtenReach,
                Set.copyOf(typeUses.nonNullTypeParameters), verdictRules.parentClassVerdicts(methods));
    }

    /*
     Culprits: each field, parameter and method return whose verdict is nullable, and which the closure reached, gets
     NULLABLE_CULPRIT, its chain back to the seed told as the statements that built each edge, the seed's own
     statement last with its origin ("null argument in ..."). An edge built outside the walk (an override, a type
     argument slot) says nothing and is left out; the chain is cut at CULPRIT_STEPS.
     */
    private void writeCulprits(Report report) {
        report.verdicts().forEach((info, pt) -> {
            if (ValueImpl.NullabilityImpl.of(pt).state() != NullableState.NULLABLE) return;
            if (!report.cause().containsKey(info) && !report.seedOrigin().containsKey(info)) return;
            List<Value.Culprit.Step> steps = new ArrayList<>();
            Object at = info;
            Set<Object> seen = new HashSet<>();
            while (report.cause().containsKey(at) && seen.add(at) && steps.size() < CULPRIT_STEPS) {
                Object from = report.cause().get(at);
                Site site = graph.edgeSites.getOrDefault(from, Map.of()).get(at);
                if (site != null) {
                    io.codelaser.maddi.modification.common.util.Culprits.add(steps,
                            io.codelaser.maddi.modification.common.util.Culprits.step(site.in(), site.element(),
                                    Trace.culpritText(site.element())));
                }
                at = from;
            }
            String origin = report.seedOrigin().get(at);
            Site seed = seeds.seedSites.get(at);
            if (seed != null) {
                io.codelaser.maddi.modification.common.util.Culprits.add(steps,
                        io.codelaser.maddi.modification.common.util.Culprits.step(seed.in(), seed.element(),
                                Trace.culpritText(seed.element()) + (origin == null ? "" : " (" + Trace.shorten(origin) + ")")));
            } else if (origin != null) {
                steps.add(new Value.Culprit.Step(null, 0, 0, Trace.shorten(origin)));
            }
            if (steps.isEmpty()) return;
            ValueImpl.CulpritImpl culprit = new ValueImpl.CulpritImpl(steps);
            if (info.analysis().haveAnalyzedValueFor(PropertyImpl.NULLABLE_CULPRIT)) {
                info.analysis().overwrite(PropertyImpl.NULLABLE_CULPRIT, culprit);
            } else {
                info.analysis().set(PropertyImpl.NULLABLE_CULPRIT, culprit);
            }
        });
    }

    /**
     * Writes the declaration verdicts as the B2 properties ({@code NULLABILITY_FIELD}, {@code _PARAMETER},
     * {@code _METHOD}), which the annotation decorator and the printers read. Locals have no property: they are
     * not {@link Info}s; a printer asks the {@link Report}. A declaration that carries a null annotation keeps the
     * value that annotation gave it: a contract wins over inference (M2).
     */
    public static void write(Report report) {
        report.verdicts().forEach((info, pt) -> {
            Property property = switch (info) {
                case FieldInfo _ -> PropertyImpl.NULLABILITY_FIELD;
                case ParameterInfo _ -> PropertyImpl.NULLABILITY_PARAMETER;
                case MethodInfo _ -> PropertyImpl.NULLABILITY_METHOD;
                default -> null;
            };
            if (property != null && !NullAnnotations.hasNullnessAnnotation(info)) {
                info.analysis().setAllowControlledOverwrite(property, ValueImpl.NullabilityImpl.of(pt));
            }
        });
    }
}

