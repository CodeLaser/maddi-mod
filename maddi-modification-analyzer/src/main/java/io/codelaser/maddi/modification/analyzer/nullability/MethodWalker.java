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

import io.codelaser.maddi.cst.api.element.Element;
import io.codelaser.maddi.cst.api.expression.And;
import io.codelaser.maddi.cst.api.expression.ArrayInitializer;
import io.codelaser.maddi.cst.api.expression.Assignment;
import io.codelaser.maddi.cst.api.expression.BinaryOperator;
import io.codelaser.maddi.cst.api.expression.ConstantExpression;
import io.codelaser.maddi.cst.api.expression.ConstructorCall;
import io.codelaser.maddi.cst.api.expression.Expression;
import io.codelaser.maddi.cst.api.expression.InlineConditional;
import io.codelaser.maddi.cst.api.expression.Lambda;
import io.codelaser.maddi.cst.api.expression.MethodCall;
import io.codelaser.maddi.cst.api.expression.MethodReference;
import io.codelaser.maddi.cst.api.expression.NullConstant;
import io.codelaser.maddi.cst.api.expression.Or;
import io.codelaser.maddi.cst.api.expression.SwitchExpression;
import io.codelaser.maddi.cst.api.expression.VariableExpression;
import io.codelaser.maddi.cst.api.info.FieldInfo;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.ParameterInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.api.statement.Block;
import io.codelaser.maddi.cst.api.statement.DoStatement;
import io.codelaser.maddi.cst.api.statement.ExplicitConstructorInvocation;
import io.codelaser.maddi.cst.api.statement.ExpressionAsStatement;
import io.codelaser.maddi.cst.api.statement.ForEachStatement;
import io.codelaser.maddi.cst.api.statement.ForStatement;
import io.codelaser.maddi.cst.api.statement.LocalVariableCreation;
import io.codelaser.maddi.cst.api.statement.ReturnStatement;
import io.codelaser.maddi.cst.api.statement.Statement;
import io.codelaser.maddi.cst.api.statement.TryStatement;
import io.codelaser.maddi.cst.api.type.NullableState;
import io.codelaser.maddi.cst.api.type.ParameterizedType;
import io.codelaser.maddi.cst.api.variable.DependentVariable;
import io.codelaser.maddi.cst.api.variable.FieldReference;
import io.codelaser.maddi.cst.api.variable.LocalVariable;
import io.codelaser.maddi.cst.api.variable.This;
import io.codelaser.maddi.cst.api.variable.Variable;
import io.codelaser.maddi.cst.impl.analysis.PropertyImpl;
import io.codelaser.maddi.cst.impl.analysis.ValueImpl;
import io.codelaser.maddi.modification.link.LinkComputer;
import io.codelaser.maddi.modification.link.impl.LinkComputerImpl;
import io.codelaser.maddi.modification.link.impl.LinkNatureImpl;
import io.codelaser.maddi.modification.prepwork.variable.Link;
import io.codelaser.maddi.modification.prepwork.variable.LinkNature;
import io.codelaser.maddi.modification.prepwork.variable.Links;
import io.codelaser.maddi.modification.prepwork.variable.ReturnVariable;
import io.codelaser.maddi.modification.prepwork.variable.VariableData;
import io.codelaser.maddi.modification.prepwork.variable.VariableInfo;
import io.codelaser.maddi.modification.prepwork.variable.VariableInfoContainer;
import io.codelaser.maddi.modification.prepwork.variable.impl.VariableDataImpl;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.codelaser.maddi.modification.analyzer.nullability.Types.*;

/**
 * Phase 4 of {@link NullabilityPass}: builds the graph for one method at a time. It walks the statements in their
 * block scopes, reads each statement's links into edges ({@link #linksOf}), counts the guarded ones, and at each
 * call site passes the arguments to the parameters, the receiver's slots and the call's own argument nodes (gap 9).
 * One walker serves the round: what it remembers about guarded null markers spans the methods, as it did in the pass.
 */
final class MethodWalker {
    private final Policy policy;
    private final Trace trace;
    private final WalkSite site;
    private final Index index;
    private final Seeds seeds;
    private final Contracts contracts;
    private final NullGraph graph;
    private final Nodes nodes;
    private final CallResults callResults;
    private final Preconditions preconditionChecks;
    private final NonNullFacts facts;
    private final Set<ParameterInfo> preconditions;

    MethodWalker(Policy policy, Trace trace, WalkSite site, Index index, Seeds seeds, Contracts contracts,
                 NullGraph graph, Nodes nodes, CallResults callResults, Preconditions preconditionChecks,
                 NonNullFacts facts, Set<ParameterInfo> preconditions) {
        this.policy = policy;
        this.trace = trace;
        this.site = site;
        this.index = index;
        this.seeds = seeds;
        this.contracts = contracts;
        this.graph = graph;
        this.nodes = nodes;
        this.callResults = callResults;
        this.preconditionChecks = preconditionChecks;
        this.facts = facts;
        this.preconditions = preconditions;
    }

    // locals their block dereferences unconditionally further on (Policy.assertAtDeclaration), and those null reaches
    final Set<Local> assertCandidates = new HashSet<>();
    // of the candidates, those the links prove unobserved between declaration and dereference
    final Set<Local> unobservedCandidates = new HashSet<>();

    // (method, null marker): a return statement guarded the marker's link into the method's return (linksOf)
    private final Set<List<Object>> guardedReturnMarkers = new HashSet<>();
    // the same for a local: (method, marker, local) whose every assignment carrying the marker was guarded
    private final Set<List<Object>> guardedLocalMarkers = new HashSet<>();
    // (method, null marker): a statement carried the marker's link into the method's return (linksOf)
    private final Set<List<Object>> returnMarkersSeen = new HashSet<>();
    // NODIRECT: read the closure's products (Link.derived()) as before, for an A/B against the direct links only
    private static final Switches.Gate DERIVED_LINKS = Switches.gate("NODIRECT");
    // see the element-slot identity rule in the link walk
    private static final Switches.Gate SLOT_IDENTITY = Switches.gate("NOSLOTIDENTITY");

    /*
     Kotlin: type arguments are invariant, so the type-argument slots of an override's return or parameter equal the
     analysed overridden member's, where both declare the same class ('List<String>' over 'List<E>'): edges both ways
     (guava: AbstractMapBasedMultimap's remove, MutableCollection<V?>? over MutableCollection<V>?).
     */
    private void invariantSlots(ParameterizedType ours, Object node, ParameterizedType theirs, Object theirNode,
                                boolean upward) {
        if (ours == null || theirs == null || ours.arrays() != 0 || theirs.arrays() != 0 || ours.typeInfo() == null
            || ours.typeInfo() != theirs.typeInfo() || ours.parameters().size() != theirs.parameters().size()) return;
        for (int j = 0; j < ours.parameters().size(); j++) {
            if (upward) graph.addEdge(new Arg(node, j), new Arg(theirNode, j));
            graph.addEdge(new Arg(theirNode, j), new Arg(node, j));
        }
    }
    private static final Switches.Gate REJECTED_FLOWS_OFF = Switches.gate("NOREJECTEDFLOWS");

    private static final Switches.Gate VARARG_COPIES_OFF = Switches.gate("NOVARARGCOPIES");

    /*
     A null marker reaches a node. With LinkComputer.Options.nullConstantReturns a callee's 'return null' crosses the
     call as the callee's own marker: then the null is the callee's return, an edge (the cause chain names the
     callee, 'return relay <- return find <- null in find'), not a null written here.
     */
    private void nullMarker(MethodInfo mi, Variable marker, Object target, String origin) {
        // Policy.withoutTestNulls: a test's null literal written into a production node ('new Decider(null)')
        if (!policy.testNulls() && testCode(mi.typeInfo()) && !testCode(target)) return;
        MethodInfo owner = seeds.nullOwner(((LocalVariable) marker).assignmentExpression());
        if (owner != null && !owner.equals(mi) && index.analysed.contains(owner) && !owner.returnType().isVoid()) {
            graph.addEdge(owner, target);
        } else {
            seeds.seed(target, origin, writer(mi));
        }
    }

    // a face ('x.f.list.§s') on a field of an object reached through another field: the field holding the slot is
    // not read from this, a local or a parameter
    private static boolean deepFace(Variable face) {
        if (!(face instanceof FieldReference virtual) || !(NonNullFacts.unwrap(virtual.scope()) instanceof VariableExpression ve)
            || !(ve.variable() instanceof FieldReference holder)) return false;
        Expression base = NonNullFacts.unwrap(holder.scope());
        return !(base instanceof VariableExpression bv && (bv.variable() instanceof This
                                                           || bv.variable() instanceof LocalVariable
                                                           || bv.variable() instanceof ParameterInfo));
    }

    // a constant marker ('$_ceN', the link module's MarkerVariable, seen through the API) holding the null constant
    private static boolean isNullMarker(Variable v) {
        return v instanceof LocalVariable lv && lv.simpleName().startsWith("$_ce")
               && lv.assignmentExpression() instanceof NullConstant;
    }

    void buildForMethod(MethodInfo mi) {
        // overrides: an implementation's null return makes the overridden return nullable; a parameter has one
        // nullability along the chain. DOWNWARD always: a null passed to the overridden method can be dispatched to
        // any implementation. UPWARD (implementation to overridden) not through a position whose overridden type is
        // a TYPE VARIABLE: there the nullability belongs to each instantiation, and joining them made every
        // implementation of a generic interface one hub (guava: Function.apply's 'F input' tied
        // ToStringFunction.apply to a Map.remove key).
        if (!mi.isConstructor()) {
            for (MethodInfo overridden : overrides(mi)) {
                // Kotlin: a test's override of a main member takes main's parameter types as they are, as for a
                // library's (clampTestOverrides): no null of the tests widens main's parameters. Its return does
                // widen main's: a test implementation returning null (langchain4j's DocumentTransformerTest
                // 'return null') makes the interface's result nullable, as the printer cannot assert a null literal
                boolean upward = !(policy.callResults() && testCode(mi) && !testCode(overridden));
                // Kotlin: an override's return may not be wider than its overridden member's, so a null returned
                // by an implementation makes an analysed type-variable return nullable too ('Holder<T>.get(): T?')
                if (!isTypeVariable(overridden.returnType())
                    || policy.callResults() && this.index.analysed.contains(overridden) && !Contracts.nullableInstantiation(mi, overridden)) {
                    graph.addEdge(mi, overridden);
                }
                if (policy.callResults() && this.index.analysed.contains(overridden)) {
                    invariantSlots(mi.returnType(), mi, overridden.returnType(), overridden, true);
                    int m = Math.min(mi.parameters().size(), overridden.parameters().size());
                    for (int i = 0; i < m; i++) {
                        invariantSlots(mi.parameters().get(i).parameterizedType(), mi.parameters().get(i),
                                overridden.parameters().get(i).parameterizedType(), overridden.parameters().get(i),
                                upward);
                    }
                }
                int n = Math.min(mi.parameters().size(), overridden.parameters().size());
                for (int i = 0; i < n; i++) {
                    // DOWNWARD not from a type-variable parameter into an implementation that instantiates it with a
                    // concrete type other than Object: a null passed through the generic 'Comparator<T>.compare'
                    // reaches 'compare(boolean[] ...)' only where T is boolean[], which nothing here tells. Measured
                    // on guava (2026-10-07): -142 noise; keeping Object implementations ('IdentityFunction.apply')
                    // keeps the unsafe count. A heuristic: a null passed to a Comparator<String> does reach a String
                    // implementation. Not for Kotlin (Policy.callResults), where an override's parameter must be
                    // nullable when the overridden one is ('doForward(s: String?)' over 'doForward(a: A?)', guava's
                    // Converter, CharMatcher over Predicate.apply).
                    ParameterizedType implementationType = mi.parameters().get(i).parameterizedType();
                    if (policy.callResults() || !isTypeVariable(overridden.parameters().get(i).parameterizedType())
                        || isTypeVariable(implementationType)
                        || implementationType.typeInfo() != null && implementationType.arrays() == 0
                           && implementationType.typeInfo().isJavaLangObject()) {
                        graph.addEdge(overridden.parameters().get(i), mi.parameters().get(i));
                    }
                    if (upward && !isTypeVariable(overridden.parameters().get(i).parameterizedType())) {
                        graph.addEdge(mi.parameters().get(i), overridden.parameters().get(i));
                    }
                }
            }
        }
        if (!mi.isConstructor()) {
            String lib = contracts.libraryNullableReturn(mi);
            if (lib != null && !mi.returnType().isVoid()) seeds.seed(mi, "overrides " + lib);
            contracts.libraryNullableContent(mi, mi.returnType(), m -> m.analysis().getOrDefault(PropertyImpl.NULLABILITY_METHOD,
                    ValueImpl.NullabilityImpl.UNSPECIFIED), MethodInfo::returnType);
            for (ParameterInfo pi : mi.parameters()) {
                int index = pi.index();
                callResults.starSlots(pi);
                contracts.libraryNullableContent(pi, pi.parameterizedType(), m -> index < m.parameters().size()
                        ? m.parameters().get(index).analysis().getOrDefault(PropertyImpl.NULLABILITY_PARAMETER,
                        ValueImpl.NullabilityImpl.UNSPECIFIED) : ValueImpl.NullabilityImpl.UNSPECIFIED,
                        m -> index < m.parameters().size() ? m.parameters().get(index).parameterizedType() : null);
                String libParam = contracts.libraryNullableParameter(mi, pi.index());
                if (libParam != null) {
                    seeds.seed(pi, "overrides " + libParam);
                } else if (policy.libraryCallbacks()) {
                    String unhinted = contracts.libraryUnhintedParameter(mi, pi.index());
                    if (unhinted != null) seeds.seed(pi, "overrides " + unhinted + ", which does not declare it non-null");
                }
            }
            // JUnit injects null into every parameter of a parameterized test with a null source, a caller the
            // analysis does not see: langchain4j's DocumentTest.constructor_should_fail_on_empty_text hands it to
            // Document.from(text), whose ensureNotBlank(text) must throw the IllegalArgumentException the test
            // expects, not Kotlin's entry check
            if (junitNullSource(mi)) {
                for (ParameterInfo pi : mi.parameters()) {
                    ParameterizedType type = pi.parameterizedType();
                    if (type.isPrimitiveExcludingVoid() && type.arrays() == 0) continue;
                    seeds.seed(pi, "JUnit null source of " + mi.fullyQualifiedName());
                }
            }
        }
        // Policy.rejectionsNullable (#28): a Java caller's null should reach the method's own check; constructors too
        if (policy.rejectionsNullable()) {
            for (ParameterInfo pi : mi.parameters()) {
                if (preconditionChecks.rejects(pi)) seeds.seed(pi, mi.fullyQualifiedName() + " rejects null with its own exception");
            }
        }
        if (mi.analysis().getOrDefault(PropertyImpl.DEGRADED_ANALYSIS_METHOD,
                ValueImpl.BoolImpl.FALSE).isTrue()) {
            contracts.degraded.add(mi);
        }
        // the body first: it declares the locals; the method's own variable data is the state at the end of the
        // body, in the body's scope
        facts.walk(mi);
        nodes.shadowed.clear();
        nodes.reassigned.addAll(Nodes.parametersAssignedIn(mi.methodBody(), mi));
        for (ParameterInfo pi : mi.parameters()) if (nodes.reassigned.contains(pi)) nodes.shadow(pi);
        Scope body = mi.methodBody() == null ? new Scope(null) : handleBlock(mi, mi.methodBody(), null);
        VariableData vd = VariableDataImpl.of(mi);
        if (vd != null) {
            vd.variableInfoStream().forEach(vi -> linksOf(mi, body, vi, null));
        } else if (!mi.isConstructor() && !mi.returnType().isVoid() && mi.parameters().isEmpty()) {
            // a getter the links do not cover: a record accessor is synthesised without source ('return field;'),
            // and its statement carries no variable data, so nothing tied the component to the accessor's return.
            // Fernflower SwitchPatternHelper: 'initializer.instance' read as non-null while 'Initializer.instance'
            // was nullable (SwitchPatternHelper.kt:109). The getter/setter association says what it returns.
            io.codelaser.maddi.cst.api.analysis.Value.FieldValue getter = mi.getSetField();
            if (getter != null && getter.field() != null && !getter.setter() && !getter.hasIndex()) {
                graph.addEdge(getter.field(), mi);
            }
        }
    }

    private void linksOf(MethodInfo mi, Scope scope, VariableInfo vi, Statement statement) {
        Variable v = vi.variable();
        Object recipient = nodes.node(mi, scope, v);
        // the statement assigns a reassigned parameter: the assignment's links are the shadow's, the reads' the
        // parameter's ('value = decode(value.trim())')
        Object assignedTo = v instanceof ParameterInfo pi && nodes.reassigned.contains(pi) && statement != null
                            && assignsHere(statement, pi) ? nodes.shadow(pi) : recipient;
        Links links = vi.linkedVariables();
        if (recipient == null || links == null) return;
        // 'x = ctx.get(KEY)' on a keyed map: the value read is the key's, not every key's (gap 2)
        Keyed keyed = statement == null ? null : keyedRead(statement, v);
        for (Link link : links) {
            // a product of the link graph's closure says that two variables are related, not where: the pass
            // composes the statements' own links itself, over its locals (Link.derived()). A null marker's link
            // is still read from the closure: the reconstruction of an assignment group does not spell the hop
            // through a local's face ('return b.get()' on a 'Box<String> b = new Box<>(null)' has no 'read ← b.t'),
            // and the marker rules below decide per statement anyway.
            if (link.derived() && !DERIVED_LINKS.get() && !isNullMarker(link.to()) && !updaterLink(statement, link)) continue;
            if (updaterLink(statement, link) && !link.from().equals(v)
                && link.linkNature().isIdenticalToOrAssignedFromTo()) {
                // 'child.parent → node', stored with child: a face of v that the updater assigns to the loop variable
                Object source = nodes.node(mi, scope, link.from());
                Object loopVariable = nodes.node(mi, scope, link.to());
                if (source != null && loopVariable instanceof Local) {
                    linkEdge(source, loopVariable, link.from(), link.to(), statement);
                }
                continue;
            }
            // links about a face of v ('this.f.g') are not about v; an element of v ('r[1] <- null', an initializer)
            // is v's Content, its hidden content ('l.§$s', 'm.§$$s[-2]', 'b.t') a type argument's slot
            Object from;
            if (link.from().equals(v)) {
                from = link.linkNature().isIdenticalTo() || link.linkNature().isAssignedFrom() ? assignedTo : recipient;
            } else {
                Object face = nodes.node(mi, scope, link.from());
                from = face instanceof Content || face instanceof Arg ? face : null;
            }
            if (from == null) continue;
            Variable fromVar = link.from();
            LinkNature nature = link.linkNature();
            if (nature == LinkNatureImpl.CONTAINS_AS_MEMBER || nature == LinkNatureImpl.IS_ELEMENT_OF) {
                membership(mi, scope, from, fromVar, nature == LinkNatureImpl.CONTAINS_AS_MEMBER, link.to(),
                        statement);
                continue;
            }
            if (nature == LinkNatureImpl.IS_SUBSET_OF || nature == LinkNatureImpl.IS_SUPERSET_OF) {
                // content copied: 'copy.§$s ⊆ in.§$s', the elements of 'in' flow into those of 'copy'
                Object other = nodes.node(mi, scope, link.to());
                if ((other instanceof Arg || other instanceof Content) && (from instanceof Arg || from instanceof Content)) {
                    if (nature == LinkNatureImpl.IS_SUBSET_OF) graph.addEdge(other, from);
                    else graph.addEdge(from, other);
                }
                continue;
            }
            // a type argument's slot is written by an assignment ('b.t ← null') or at a call site, never by identity:
            // a '≡' between a local and a slot's value runs both ways and would carry the local's other sources in
            if (from instanceof Arg && link.linkNature().isIdenticalTo()) continue;
            // the same for an element slot facing another: 'hashTable[b] ≡ entryArray[i]' after both were assigned
            // 'newEntry' is one slot each, not the arrays' elements at large -- the pass keeps one Content per array,
            // so the '≡' carried hashTable's null slots into entryArray (guava RegularImmutableMultiset.create, once
            // CodeLaser/maddi-mod#77 put the stored slot in its value's group). The assignments carry the value.
            // Gate NOSLOTIDENTITY.
            if (from instanceof Content && link.linkNature().isIdenticalTo() && !SLOT_IDENTITY.get()
                && nodes.node(mi, scope, link.to()) instanceof Content) {
                continue;
            }
            if (link.linkNature().isIdenticalTo() || link.linkNature().isAssignedFrom()) {
                if (isNullMarker(link.to())) {
                    // a return whose value is known non-null returns no null, whatever '≡' links it to: in 'var d =
                    // find(c); if (d == null) throw ...; return d;' the return is linked to find's 'return null'
                    // through d (timefold SolutionDescriptor.findEntityDescriptorOrFail, the diagnose session). The
                    // method's own summary repeats the link (no statement): skipped once a return guarded it.
                    if (trace.why(mi)) {
                        trace.log("marker", mi, "from " + from + " to " + link.to() + " statement "
                                                + (statement == null ? "-" : statement.toString().strip()));
                    }
                    if (fromVar instanceof ReturnVariable) {
                        List<Object> key = List.of(mi, link.to());
                        if (statement == null) {
                            if (guardedReturnMarkers.contains(key)) continue;
                        } else {
                            // the graph is persistent: every later statement repeats the return's link to the
                            // marker, so the first statement carrying it (in walk order, the statement that
                            // introduced it) decides. In 'var v = pick(); if (v != null) return v; ...; return
                            // other();' the second return's data still says 'return ← pick's null', though it
                            // returns something else (timefold ValueRangeState.getReachableValues)
                            Expression returned = assignedValue(statement, fromVar);
                            // a 'return null' itself is never guarded: the link engine can give its null the marker
                            // of an earlier call's ('Node n = getNode(name); if (n instanceof Slave) return (Slave)
                            // n; return null;'), whose first, guarded return then hid it (jenkins Hudson.kt:52,
                            // PluginWrapper.kt:478, 2026-10-21). Gate NORETURNNULLLITERALS.
                            boolean literal = !RETURN_NULL_LITERALS_OFF.get() && returned != null
                                              && NonNullFacts.unwrap(returned) instanceof NullConstant;
                            if (!returnMarkersSeen.add(key) && !literal) continue;
                            if (!literal && returned != null && facts.nonNull(returned, facts.before(statement))) {
                                guardedReturnMarkers.add(key);
                                continue;
                            }
                        }
                    }
                    // a variable becomes null only where it is assigned: '≡' is transitive in the links, so in
                    // 'checked = check(key)' with 'check' returning its argument or null, 'key ≡ return ≡ null'
                    // reaches 'key' (nacos SystemEnvPropertySource, reported by the diagnose session). A slot
                    // (Content, Arg) is written by calls too.
                    boolean slot = from instanceof Content || from instanceof Arg;
                    // a callee's 'return null' does not become an element of a collection reached through another
                    // object's fields: 'cfg.selector.solutionDescriptor.genuineEntityDescriptorList.§s ← $_ce3', the
                    // links' transitive closure through calls, in a method that never writes that list (timefold
                    // DefaultConstructionHeuristicPhaseFactory.getBuilder, CodeLaser/maddi-mod#27 a). A null this
                    // method writes is kept, and so are the call-site writes ('list.add(x)', receiverSlots).
                    if (slot && deepFace(link.from()) && !mi.equals(seeds.nullOwner(((LocalVariable) link.to()).assignmentExpression()))) {
                        continue;
                    }
                    // a local assigned a value known non-null takes no null, whatever the closure links it to: in
                    // 'Object o = null; for (..) o = b.call(o); Integer c = o instanceof Integer ? (Integer) o : 0;'
                    // 'c ← o ← null' holds in the graph, but neither branch can be null (jenkins
                    // CLIRegisterer.main, once CodeLaser/maddi-mod#72 4a let the zero-iteration null reach 'o').
                    // As for a return above. Gate NOASSIGNEDNONNULL.
                    // The method's own data repeats the link without a statement: skipped once an assignment guarded
                    // it, as an unguarded one has carried the null in at its own statement.
                    if (!slot && !(fromVar instanceof ReturnVariable) && !ASSIGNED_NON_NULL_OFF.get()) {
                        List<Object> key = List.of(mi, link.to(), fromVar);
                        if (statement == null) {
                            if (guardedLocalMarkers.contains(key)) continue;
                        } else {
                            Expression assigned = assignedValue(statement, fromVar);
                            if (assigned != null && !(NonNullFacts.unwrap(assigned) instanceof NullConstant)
                                && facts.nonNull(assigned, facts.before(statement))) {
                                guardedLocalMarkers.add(key);
                                continue;
                            }
                        }
                    }
                    if (slot || statement == null || fromVar instanceof ReturnVariable
                        || assignsHere(statement, fromVar)) {
                        nullMarker(mi, link.to(), from, "null in " + mi.fullyQualifiedName());
                    }
                } else {
                    linkEdge(keyedSource(keyed, nodes.node(mi, scope, link.to())), from, link.to(), fromVar, statement);
                }
            } else if (link.linkNature().isIdenticalToOrAssignedFromTo()) {
                // '→': v is assigned to link.to()
                Keyed keyedTo = statement == null ? null : keyedRead(statement, link.to());
                linkEdge(keyedSource(keyedTo, from), nodes.node(mi, scope, link.to()), fromVar, link.to(), statement);
            }
        }
    }

    /*
     'container ∋ member' / 'member ∈ container', where the container side is a slot (Arg or Content). The link
     engine writes membership both ways for both a write ('list.add(p)') and a read ('x = list.get(0)'), and derives
     more by transitivity (the null that 'Maps.safeGet' returns besides 'map.get(k)' comes out as a null element of
     the map). So membership is read only as a READ: into a return value, and into a local or field in the statement
     that assigns it (a statement's data carries the variable's earlier links too). Writes come from the call site
     (receiverSlots: 'add(E)', 'put(K, V)', a helper's parameter through invariance).
     */
    private void membership(MethodInfo mi, Scope scope, Object side, Variable sideVar, boolean sideIsContainer,
                            Variable otherVar, Statement statement) {
        Object other = nodes.node(mi, scope, otherVar);
        Variable memberVar = sideIsContainer ? otherVar : sideVar;
        // the member read from a keyed map by this statement ('ids = ctx.get(IDS)'): the key's values (gap 2)
        Keyed keyed = statement == null ? null : keyedRead(statement, memberVar);
        Object container = keyedSource(keyed, sideIsContainer ? side : other);
        Object member = sideIsContainer ? other : side;
        if (!(container instanceof Arg || container instanceof Content || container instanceof Keyed)) return;
        if (member == null || member instanceof Arg || member instanceof Content || member instanceof Keyed) return;
        switch (memberVar) {
            case ParameterInfo _ -> {
            }
            case ReturnVariable _ -> graph.addEdge(container, member);
            default -> {
                if (statement != null && assignsHere(statement, memberVar)) graph.addEdge(container, member);
            }
        }
    }

    // does the statement give 'variable' its value: a declaration with initializer, an assignment, a for-each variable
    private static boolean assignsHere(Statement statement, Variable variable) {
        if (statement instanceof ForEachStatement fe && fe.initializer() != null) {
            return fe.initializer().localVariableStream().anyMatch(lv -> lv.equals(variable));
        }
        return assignedValue(statement, variable) != null;
    }

    // an edge from a link: sourceVar's value flows into recipientVar. Counted per statement that assigns the
    // recipient (decided in go): where every position in which the source can BE the assigned value is one where it
    // is known non-null, the statement carries no null from it (M4)
    private void linkEdge(Object from, Object to, Variable sourceVar, Variable recipientVar, Statement statement) {
        if (from == null || to == null || from.equals(to)) return;
        int[] count = graph.linkEdges.computeIfAbsent(List.of(from, to), _ -> new int[4]);
        if (statement == null) {
            count[3]++; // a link of the method's summary, not of one statement
            return;
        }
        Expression value = assignedValue(statement, recipientVar);
        Guard guard;
        if (value == null && statement instanceof io.codelaser.maddi.cst.api.statement.ForStatement fs) {
            // 'for (ClassNode c = child; c != null; c = c.parent)': the loop assigns its variable in its init clause
            // and in its updaters, statements of their own that the walk does not visit. fernflower
            // NestedClassProcessor.insertLocalVars printed 'classNode = classNode.parent!!' once the pass read direct
            // links only (23f8bf65): the closure's composed null had covered it.
            List<Expression> values = forAssignedValues(fs, recipientVar);
            value = values.isEmpty() ? null : values.getFirst();
            guard = Guard.ABSENT;
            for (Expression v : values) guard = combine(guard, guard(v, sourceVar, facts.before(statement)));
            if (values.isEmpty()) guard = argumentGuard(statement, sourceVar, recipientVar);
        } else {
            guard = value == null ? argumentGuard(statement, sourceVar, recipientVar)
                    : guard(value, sourceVar, facts.before(statement));
        }
        if (guard == Guard.GUARDED) count[0]++;
        else if (guard == Guard.UNGUARDED) count[1]++;
        else if (guard == Guard.OPAQUE) count[2]++; // a value (or part) not seen through
        // a call that is not given the source can still return it through an alias ('table = this.table; return
        // table.get(i)', guava LocalCache.Segment.getFirst): assigned here by a value not seen through
        else if (value != null && NonNullFacts.unwrap(value) instanceof MethodCall) count[2]++;
    }

    /*
     A recipient the statement does not assign itself: the source reaches it through a call it is passed to
     ('converter = new IdentifierConverter(..., interceptor)' links the local to the new object's field). GUARDED when
     the source is known non-null at every call that takes it as an argument, as argument() decides for the parameter.
     */
    private Guard argumentGuard(Statement statement, Variable source, Variable recipient) {
        if (statement.expression() == null) return Guard.ABSENT;
        // only a call that can reach the recipient: a field of an object the statement assigns ('converter = new
        // X(..., interceptor)') or hands to the call; never a local, a parameter or a return. In fernflower's
        // 'mapSimpleNames.put(outerShortName, ...)' the link engine's closure linked 'outerShortName' to an
        // unrelated 'node.parent.classStruct.qualifiedName'.
        if (!(recipient instanceof FieldReference fr)) return Guard.ABSENT;
        Variable base = fr;
        while (base instanceof FieldReference f && f.scopeVariable() != null) base = f.scopeVariable();
        Variable recipientBase = base;
        boolean assignsBase = assignsHere(statement, recipientBase);
        Guard[] guard = {Guard.ABSENT};
        statement.expression().visit(e -> {
            if (e instanceof Lambda) return false;
            List<Expression> arguments = switch (e) {
                case MethodCall mc -> mc.parameterExpressions();
                case ConstructorCall cc -> cc.parameterExpressions();
                default -> List.of();
            };
            boolean reaches = assignsBase || e instanceof MethodCall mc && mc.object() != null && mentions(mc.object(), recipientBase)
                              || arguments.stream().anyMatch(a -> mentions(a, recipientBase));
            for (Expression argument : arguments) {
                if (!reaches) break;
                if (NonNullFacts.unwrap(argument) instanceof VariableExpression ve && ve.variable().equals(source)) {
                    guard[0] = combine(guard[0], facts.nonNullWhenCalled((Expression) e, source)
                                                 || facts.nonNullAt(statement, source) ? Guard.GUARDED : Guard.UNGUARDED);
                }
            }
            return true;
        });
        return guard[0];
    }

    /*
     A for loop's updater 'node = node.parent' reaches the links only through the loop's reconstruction, as the derived
     'child.parent → node' (the loop variable's first value is child): the engine emits no direct link for it. Read
     it as the updater's own assignment. fernflower NestedClassProcessor.insertLocalVars printed 'classNode =
     classNode.parent!!' once the pass read direct links only, and the null-constant composition no longer put the
     parent's null on the loop variable itself (23f8bf65).
     */
    private static boolean updaterLink(Statement statement, Link link) {
        if (!(statement instanceof io.codelaser.maddi.cst.api.statement.ForStatement fs)) return false;
        for (Expression updater : fs.updaters()) {
            if (NonNullFacts.unwrap(updater) instanceof Assignment a && a.assignmentOperator() == null
                && (a.variableTarget().equals(link.to()) || a.variableTarget().equals(link.from()))) {
                return true;
            }
        }
        return false;
    }

    // the values a for loop's init clause and updaters assign to 'recipient'
    private static List<Expression> forAssignedValues(io.codelaser.maddi.cst.api.statement.ForStatement fs,
                                                      Variable recipient) {
        List<Expression> values = new ArrayList<>();
        for (Element init : fs.initializers()) {
            if (init instanceof LocalVariableCreation lvc) {
                lvc.localVariableStream().filter(lv -> lv.equals(recipient))
                        .map(LocalVariable::assignmentExpression).filter(java.util.Objects::nonNull).forEach(values::add);
            } else if (init instanceof Assignment a && recipient.equals(a.variableTarget()) && a.assignmentOperator() == null) {
                values.add(a.value());
            }
        }
        for (Expression updater : fs.updaters()) {
            if (NonNullFacts.unwrap(updater) instanceof Assignment a && recipient.equals(a.variableTarget())
                && a.assignmentOperator() == null) {
                values.add(a.value());
            }
        }
        return values;
    }

    // the value 'recipient' gets in this statement: 'recipient = v;', 'T recipient = v;', 'return v;'; else null
    private static Expression assignedValue(Statement statement, Variable recipient) {
        return switch (statement) {
            case LocalVariableCreation lvc -> lvc.localVariableStream().filter(lv -> lv.equals(recipient))
                    .map(LocalVariable::assignmentExpression).findFirst().orElse(null);
            case ReturnStatement rs -> recipient instanceof ReturnVariable ? rs.expression() : null;
            default -> statement.expression() instanceof Assignment a && recipient.equals(a.variableTarget())
                       && a.assignmentOperator() == null ? a.value() : null;
        };
    }

    /**
     * How {@code source} can become the value of an assigned expression. ABSENT: it does not occur as a value
     * (it may still flow in through an alias, so this decides nothing); GUARDED: every value position it occurs in
     * is one where it is known non-null, or it is assigned a non-null value right there
     * ({@code return result == null ? field = new X() : result}); UNGUARDED: somewhere it may be null; OPAQUE: a
     * part is not seen through (a call, another object's field).
     */
    private enum Guard {ABSENT, GUARDED, UNGUARDED, OPAQUE}

    private static final Switches.Gate KNOWN_ARGUMENTS_OFF = Switches.gate("NOKNOWNARGUMENTS");

    private Guard guard(Expression value, Variable source, Set<Variable> known) {
        // a value known non-null as a whole carries no null, whatever flows into it ('return requireNonNull(f)')
        if (facts.nonNull(value, known)) return Guard.GUARDED;
        Expression e = NonNullFacts.unwrap(value);
        switch (e) {
            case VariableExpression ve -> {
                if (sameVariable(ve.variable(), source)) {
                    return known.contains(source) ? Guard.GUARDED : Guard.UNGUARDED;
                }
                return ve.variable() instanceof io.codelaser.maddi.cst.api.variable.FieldReference fr
                       && !fr.scopeIsRecursivelyThis() ? Guard.OPAQUE : Guard.ABSENT;
            }
            case InlineConditional ic -> {
                Set<Variable> whenTrue = new java.util.HashSet<>(known);
                whenTrue.addAll(facts.whenTrue(ic.condition()));
                Set<Variable> whenFalse = new java.util.HashSet<>(known);
                whenFalse.addAll(facts.whenFalse(ic.condition()));
                return combine(guard(ic.ifTrue(), source, whenTrue), guard(ic.ifFalse(), source, whenFalse));
            }
            case Assignment a -> {
                if (a.assignmentOperator() != null) return Guard.OPAQUE;
                if (source.equals(a.variableTarget())) {
                    return facts.nonNull(a.value(), known) ? Guard.GUARDED : Guard.UNGUARDED;
                }
                return guard(a.value(), source, known);
            }
            case NullConstant _, ConstructorCall _, Lambda _, MethodReference _, ConstantExpression<?> _ -> {
                return Guard.ABSENT;
            }
            case MethodCall mc when !mentions(mc, source) -> {
                // a call that is not given the source cannot return it ('a == null ? Collections.emptyList() : a')
                return Guard.ABSENT;
            }
            case MethodCall _ when policy.callResults() && !KNOWN_ARGUMENTS_OFF.get() && known.contains(source) -> {
                // Kotlin: a call given the source where it is known non-null returns no null of it. guava
                // TypeResolver.TypeTable.resolveInternal: 'type = map.get(key); if (type == null) { ...; return ...; }
                // return new TypeResolver(...).resolveType(type)' carried ImmutableMap.get's null into the return,
                // TypeToken.runtimeType and MutableClassToInstanceMap.getInstance's parameter
                // (MutableClassToInstanceMap.kt:57, 2026-10-18). Gate NOKNOWNARGUMENTS.
                return Guard.GUARDED;
            }
            default -> {
                return Guard.OPAQUE;
            }
        }
    }

    private static Guard combine(Guard g1, Guard g2) {
        if (g1 == Guard.OPAQUE || g2 == Guard.OPAQUE) return Guard.OPAQUE;
        if (g1 == Guard.UNGUARDED || g2 == Guard.UNGUARDED) return Guard.UNGUARDED;
        if (g1 == Guard.GUARDED || g2 == Guard.GUARDED) return Guard.GUARDED;
        return Guard.ABSENT;
    }

    // returns the block's scope at its end
    private Scope handleBlock(MethodInfo mi, Block block, Scope parent) {
        Scope scope = new Scope(parent);
        List<Statement> statements = block.statements();
        for (int index = 0; index < statements.size(); index++) {
            Statement statement = statements.get(index);
            if (policy.assertAtDeclaration() && statement instanceof LocalVariableCreation lvc) {
                assertCandidates(mi, lvc, statements, index);
            }
            // a loop that assigns a parameter: every read inside it may see the assigned value (a later iteration)
            if (statement instanceof io.codelaser.maddi.cst.api.statement.LoopStatement) {
                nodes.shadowed.addAll(Nodes.parametersAssignedIn(statement, mi));
            }
            // the statement's own expression is evaluated before its sub-blocks: what they assign does not shadow it
            // ('if (args == null) { args = …; }' tests the caller's value)
            Set<ParameterInfo> shadowedBefore = Set.copyOf(nodes.shadowed);
            // a statement's own declarations are visible in the statement only; a local variable creation's in the
            // rest of the block
            Scope own = new Scope(scope);
            Map<Block, TryStatement.CatchClause> catchBlocks = new IdentityHashMap<>();
            switch (statement) {
                case LocalVariableCreation lvc -> lvc.localVariableStream().forEach(lv -> nodes.declare(scope, mi, lvc, lv));
                case ForEachStatement fe when fe.initializer() != null -> fe.initializer().localVariableStream()
                        .forEach(lv -> nodes.declare(own, mi, fe.initializer(), lv));
                case ForStatement fs -> fs.initializers().forEach(e -> {
                    if (e instanceof LocalVariableCreation lvc) {
                        lvc.localVariableStream().forEach(lv -> nodes.declare(own, mi, lvc, lv));
                    }
                });
                case TryStatement ts -> {
                    ts.resources().forEach(r -> {
                        if (r instanceof LocalVariableCreation lvc) {
                            lvc.localVariableStream().forEach(lv -> nodes.declare(own, mi, lvc, lv));
                        }
                    });
                    ts.catchClauses().forEach(cc -> catchBlocks.put(cc.block(), cc));
                }
                default -> {
                }
            }
            statement.subBlockStream().forEach(sb -> {
                TryStatement.CatchClause cc = catchBlocks.get(sb);
                Scope blockParent = own;
                if (cc != null && cc.catchVariable() != null) {
                    blockParent = new Scope(own);
                    nodes.declare(blockParent, mi, cc, cc.catchVariable());
                }
                handleBlock(mi, sb, blockParent);
            });
            Set<ParameterInfo> shadowedBySubBlocks = new HashSet<>(nodes.shadowed);
            nodes.shadowed.retainAll(shadowedBefore);
            site.element = statement;
            site.method = mi;
            VariableData vd = VariableDataImpl.of(statement);
            if (vd != null) vd.variableInfoStream().forEach(vi -> linksOf(mi, own, vi, statement));
            if (statement instanceof TryStatement ts) {
                // 'try (In in = open(name))': each resource is a statement with its own variable data, a declaration
                // like any other (fernflower ContextUnit.reload passed a nullable resource on unnoticed)
                for (Statement resource : ts.resources()) {
                    VariableData rvd = VariableDataImpl.of(resource);
                    if (rvd != null) rvd.variableInfoStream().forEach(vi -> linksOf(mi, own, vi, resource));
                    callResults.syntacticSeeds(mi, own, resource);
                }
            }
            statement.visit(e -> {
                if (e instanceof Lambda lambda) {
                    if (lambda.methodBody() != null) handleBlock(lambda.methodInfo(), lambda.methodBody(), own);
                    return false;
                }
                // a switch expression's block arms are statements inside an expression: no sub-block of the
                // statement (fernflower StructTypeAnnotationAttribute.parse declared and filled an array there)
                if (e instanceof SwitchExpression se) {
                    se.entries().forEach(entry -> {
                        if (entry.statement() instanceof Block arm) handleBlock(mi, arm, own);
                    });
                }
                if (e instanceof Block) return false; // nested statements are handled with their own vd
                if (policy.nullTests() && e instanceof BinaryOperator bo) nullTest(mi, own, bo);
                if (e instanceof MethodCall || e instanceof ConstructorCall) {
                    site.element = e;
                    site.method = mi;
                }
                if (e instanceof MethodCall mc && mc.methodInfo() != null) {
                    callSite(mi, own, statement, mc, mc.methodInfo(), mc.analysis(), mc.parameterExpressions());
                } else if (e instanceof ConstructorCall cc && cc.constructor() != null) {
                    callSite(mi, own, statement, cc, cc.constructor(), cc.analysis(), cc.parameterExpressions());
                }
                if (e instanceof ConstructorCall cc) {
                    anonymousSuperArguments(mi, own, statement, cc);
                    anonymousThisTarget(mi, own, statement, cc);
                }
                return true;
            });
            site.element = statement;
            site.method = mi;
            callResults.syntacticSeeds(mi, own, statement);
            if (statement instanceof ExplicitConstructorInvocation eci && eci.methodInfo() != null) {
                callSite(mi, own, statement, null, eci.methodInfo(), eci.analysis(), eci.parameterExpressions());
            }
            // from here on a read of a parameter the statement assigned is a read of the shadow
            nodes.shadowed.addAll(shadowedBySubBlocks);
            nodes.shadowed.addAll(Nodes.parametersAssignedIn(statement, mi));
            site.element = null;
        }
        return scope;
    }

    /*
     A local declared with an initializer that the rest of its block dereferences before anything may leave the block,
     and that is never assigned again: in Java a null there throws at the dereference anyway (Policy
     .assertAtDeclaration). 'BasicBlock block = blocks.getWithKey(j); protectedRange.add(block);
     block.addSuccessorException(handle);' (fernflower ControlFlowGraph.setExceptionEdges).
     */
    void assertCandidates(MethodInfo mi, LocalVariableCreation lvc, List<Statement> statements, int index) {
        lvc.localVariableStream().forEach(lv -> {
            Expression init = lv.assignmentExpression();
            if (init == null || init.isEmpty() || NonNullFacts.unwrap(init) instanceof NullConstant
                || lv.parameterizedType().isPrimitiveExcludingVoid()) return;
            List<Statement> rest = statements.subList(index + 1, statements.size());
            if (rest.stream().anyMatch(st -> assigns(st, lv))) return;
            for (int j = 0; j < rest.size(); j++) {
                Statement st = rest.get(j);
                if (dereferencesFirst(st, lv)) {
                    Local local = new Local(mi, lvc, lv.simpleName());
                    assertCandidates.add(local);
                    if (unobserved(lvc, rest.subList(0, j), lv)) unobservedCandidates.add(local);
                    return;
                }
                if (mayLeave(st)) return;
            }
        });
    }

    /*
     The linking engine's proof that nothing between the declaration and the dereference observes the local (then the
     printer reports it as INFO, not as a behaviour change): no statement in between refers to it, and no variable
     such a statement uses is an alias of it ('≡'/'←' links; also of a variable built on it: 'block.f'). Without the
     links of each statement (a degraded method) there is no proof.
     */
    private static boolean unobserved(LocalVariableCreation declaration, List<Statement> between, LocalVariable lv) {
        if (VariableDataImpl.of(declaration) == null) return false;
        for (Statement st : between) {
            VariableData vd = VariableDataImpl.of(st);
            if (vd == null) return false;
            Set<Variable> used = new HashSet<>();
            boolean[] direct = {false};
            st.visit(e -> {
                if (e instanceof VariableExpression ve) {
                    if (refersTo(ve.variable(), lv)) direct[0] = true;
                    used.add(ve.variable());
                }
                return !direct[0];
            });
            if (direct[0]) return false;
            for (Variable v : used) {
                VariableInfoContainer vic = vd.variableInfoContainerOrNull(v.fullyQualifiedName());
                if (vic == null) continue; // not a variable of this statement's data (a constant, a type)
                Links links = vic.best().linkedVariables();
                if (links == null) return false;
                for (Link link : links) {
                    // only the identity family of the link algebra ('≡', '←', '→') makes a variable the local's
                    // value; the others leave them possibly unrelated: 'from ∈ instrBlocks' and 'to ∈ instrBlocks'
                    // say nothing of 'from' against 'to'
                    if (link.linkNature().isIdenticalToOrAssignedFromTo()
                        && (refersTo(link.from(), lv) || refersTo(link.to(), lv))) return false;
                }
            }
        }
        return true;
    }

    private static boolean refersTo(Variable v, LocalVariable lv) {
        return v != null && v.variableStreamDescendIntoScope().anyMatch(lv::equals);
    }

    private static boolean assigns(Statement statement, LocalVariable lv) {
        boolean[] found = {false};
        statement.visit(e -> {
            if (e instanceof Assignment a && lv.equals(a.variableTarget())) found[0] = true;
            return !found[0];
        });
        return found[0];
    }

    // the statement's first evaluated expressions dereference lv, whatever their values
    private static boolean dereferencesFirst(Statement statement, LocalVariable lv) {
        return switch (statement) {
            case LocalVariableCreation lvc -> lvc.localVariableStream()
                    .anyMatch(v -> v.assignmentExpression() != null && dereferences(v.assignmentExpression(), lv));
            case ForEachStatement fe -> isVariable(fe.expression(), lv) || dereferences(fe.expression(), lv);
            // the init clause, then the condition, run before any iteration: 'for (int i = arr.length - 1; ...)'
            // (fernflower SFormsFastMapDirect.removeAllFields: 'val arr = elements[2]' then 'arr!!.size', 'arr!![i]')
            case ForStatement fs -> fs.initializers().stream().anyMatch(init -> switch (init) {
                case LocalVariableCreation lvc -> lvc.localVariableStream()
                        .anyMatch(v -> v.assignmentExpression() != null && dereferences(v.assignmentExpression(), lv));
                case Expression e -> dereferences(e, lv);
                default -> false;
            }) || fs.expression() != null && dereferences(fs.expression(), lv);
            case DoStatement _, TryStatement _, Block _ -> false;
            default -> statement.expression() != null && dereferences(statement.expression(), lv);
        };
    }

    // evaluating e certainly dereferences lv: not in a conditionally evaluated part, a lambda, or a switch arm
    private static boolean dereferences(Expression e, LocalVariable lv) {
        boolean[] found = {false};
        e.visit(x -> {
            if (found[0]) return false;
            switch (x) {
                case Lambda _ -> {
                    return false;
                }
                case InlineConditional ic -> {
                    found[0] = dereferences(ic.condition(), lv);
                    return false;
                }
                case BinaryOperator bo when NonNullFacts.isOperator(bo, "&&") || NonNullFacts.isOperator(bo, "||") -> {
                    found[0] = dereferences(bo.lhs(), lv);
                    return false;
                }
                case And and -> {
                    found[0] = !and.expressions().isEmpty() && dereferences(and.expressions().getFirst(), lv);
                    return false;
                }
                case Or or -> {
                    found[0] = !or.expressions().isEmpty() && dereferences(or.expressions().getFirst(), lv);
                    return false;
                }
                case SwitchExpression se -> {
                    found[0] = dereferences(se.selector(), lv);
                    return false;
                }
                case ConstructorCall cc when cc.anonymousClass() != null -> {
                    found[0] = cc.parameterExpressions().stream().anyMatch(a -> dereferences(a, lv));
                    return false;
                }
                case MethodCall mc when mc.methodInfo() != null && !mc.methodInfo().isStatic()
                                        && mc.object() != null && isVariable(mc.object(), lv) -> found[0] = true;
                case VariableExpression ve when ve.variable() instanceof FieldReference fr && !fr.fieldInfo().isStatic()
                                                && fr.scope() != null && isVariable(fr.scope(), lv) -> found[0] = true;
                case VariableExpression ve when ve.variable() instanceof DependentVariable dv
                                                && isVariable(dv.arrayExpression(), lv) -> found[0] = true;
                case io.codelaser.maddi.cst.api.expression.ArrayLength al when isVariable(al.scope(), lv) ->
                        found[0] = true;
                default -> {
                }
            }
            return !found[0];
        });
        return found[0];
    }

    // in Java only == and != take a null operand
    private void nullTest(MethodInfo mi, Scope scope, BinaryOperator bo) {
        // NOT excluded: 'if (p == null) throw ...'. Guava annotates such checking parameters @Nullable (the method's
        // job is to accept null and throw); treating the test as a non-null precondition cost 28 agreements, +3
        // unsafe (2026-10-06)
        Expression other = bo.lhs() instanceof NullConstant ? bo.rhs() : bo.rhs() instanceof NullConstant ? bo.lhs() : null;
        if (other instanceof VariableExpression ve) {
            Object node = nodes.node(mi, scope, ve.variable());
            if (node instanceof ParameterInfo || node instanceof FieldInfo) {
                seeds.seedIndirect(node, "compared with null in " + mi.fullyQualifiedName(), mi.typeInfo());
            }
        }
    }

    /*
     A write into a keyed map: through a keyed putter, the key its caller passes; directly ('field.put(k, v)'), the
     key k, except in a keyed putter itself, whose callers write. A key not known writes the values every key reads.
     The write into the field's whole value slot is made as before, by the parameter or the receiver's slot.
     */
    private void keyedWrite(MethodInfo mi, Scope scope, Statement statement, Expression call, MethodInfo callee,
                            LinkComputer.ListOfLinks list, List<Expression> arguments) {
        if (index.keyedSlots.isEmpty()) return;
        KeyedAccessor putter = index.keyedPutters.get(callee);
        if (putter != null && arguments.size() > Math.max(putter.keyIndex(), putter.valueIndex())) {
            Arg slot = index.keyedSlots.get(putter.field());
            argument(mi, scope, statement, call, list, arguments, putter.valueIndex(),
                    graph.keyed(slot, Index.keyOf(arguments.get(putter.keyIndex()))));
            return;
        }
        if (!(call instanceof MethodCall mc) || !"put".equals(callee.name()) || arguments.size() != 2
            || mc.object() == null || !(NonNullFacts.unwrap(mc.object()) instanceof VariableExpression ve)
            || !(ve.variable() instanceof FieldReference fr)) return;
        Arg slot = index.keyedSlots.get(fr.fieldInfo());
        if (slot == null) return;
        KeyedAccessor own = index.keyedPutters.get(mi);
        if (own != null && own.field() == fr.fieldInfo() && Index.ownParameter(mi, arguments.getFirst()) == own.keyIndex()) {
            return;
        }
        argument(mi, scope, statement, call, list, arguments, 1, graph.keyed(slot, Index.keyOf(arguments.getFirst())));
    }

    // the keyed node a statement reads: 'x = ctx.get(KEY)' through a keyed getter, or 'x = field.get(KEY)'
    private Keyed keyedRead(Statement statement, Variable recipient) {
        if (index.keyedSlots.isEmpty()) return null;
        Expression value = assignedValue(statement, recipient);
        if (!(NonNullFacts.unwrap(value) instanceof MethodCall mc) || mc.methodInfo() == null) return null;
        KeyedAccessor getter = index.keyedGetters.get(mc.methodInfo());
        if (getter != null && getter.keyIndex() < mc.parameterExpressions().size()) {
            Object key = Index.keyOf(mc.parameterExpressions().get(getter.keyIndex()));
            return key == null ? null : graph.keyed(index.keyedSlots.get(getter.field()), key);
        }
        if ("get".equals(mc.methodInfo().name()) && mc.parameterExpressions().size() == 1 && mc.object() != null
            && NonNullFacts.unwrap(mc.object()) instanceof VariableExpression ve
            && ve.variable() instanceof FieldReference fr && index.keyedSlots.containsKey(fr.fieldInfo())) {
            Object key = Index.keyOf(mc.parameterExpressions().getFirst());
            return key == null ? null : graph.keyed(index.keyedSlots.get(fr.fieldInfo()), key);
        }
        return null;
    }

    // the node a read takes its value from: the key's node where it would be the field's value slot
    private static Object keyedSource(Keyed keyed, Object node) {
        return keyed != null && keyed.slot().equals(node) ? keyed : node;
    }

    /*
     Kotlin: 'new SplittingIterator(splitter, toSplit) { ... }' passes its arguments to the superclass's constructor;
     the anonymous class's own has none to take them (guava Splitter.kt:137: a nullable 'toSplit' into
     SplittingIterator's non-null parameter, worktree-maddi-all 2026-10-17). The one superclass constructor of that
     arity, when there is exactly one.
     */
    private void anonymousSuperArguments(MethodInfo mi, Scope scope, Statement statement, ConstructorCall cc) {
        TypeInfo anonymous = cc.anonymousClass();
        if (!policy.callResults() || ANONYMOUS_SUPER_OFF.get() || anonymous == null || anonymous.parentClass() == null
            || anonymous.parentClass().typeInfo() == null) {
            return;
        }
        List<Expression> arguments = cc.parameterExpressions();
        int n = arguments.size();
        MethodInfo constructor = index.anonymousSuperConstructor(cc);
        if (constructor == null) return;
        for (int i = 0; i < n; i++) {
            ParameterInfo pi = constructor.parameters().get(i);
            Expression argument = NonNullFacts.unwrap(arguments.get(i));
            if (argument instanceof NullConstant) {
                seeds.seed(pi, Seeds.NULL_ARGUMENT + mi.fullyQualifiedName(), mi.typeInfo());
            } else if (argument instanceof VariableExpression ve) {
                Object source = nodes.node(mi, scope, ve.variable());
                if (source == null) continue;
                if (facts.nonNullWhenCalled(cc, ve.variable()) || facts.nonNullAt(statement, ve.variable())) {
                    graph.flows.add(List.of(source, pi));
                } else {
                    graph.addEdge(source, pi);
                }
            }
        }
    }

    private void anonymousThisTarget(MethodInfo mi, Scope scope, Statement statement, ConstructorCall cc) {
        TypeInfo anonymous = cc.anonymousClass();
        if (Nodes.ANONYMOUS_THIS_OFF.get() || !policy.callResults() || anonymous == null || anonymous.parentClass() == null
            || anonymous.parentClass().parameters().isEmpty()) return;
        Object target = switch (statement) {
            case LocalVariableCreation lvc -> lvc.localVariableStream()
                    .filter(lv -> lv.assignmentExpression() != null && NonNullFacts.unwrap(lv.assignmentExpression()) == cc)
                    .map(lv -> (Object) nodes.local(mi, scope, lv.simpleName())).findFirst().orElse(null);
            case ExpressionAsStatement eas when NonNullFacts.unwrap(eas.expression()) instanceof Assignment a
                                                && NonNullFacts.unwrap(a.value()) == cc -> nodes.node(mi, scope, a.variableTarget());
            case ReturnStatement rs when rs.expression() != null && NonNullFacts.unwrap(rs.expression()) == cc -> mi;
            default -> null;
        };
        if (target == null) return;
        AnonymousThis at = new AnonymousThis(anonymous);
        for (int i = 0; i < anonymous.parentClass().parameters().size(); i++) graph.addEdge(new Arg(at, i), new Arg(target, i));
    }

    // gate NOCONSUMERSLOTS: a caller's null into an abstract type-variable parameter reaches the implementations (A/B)
    private static final Switches.Gate CONSUMER_SLOTS_OFF = Switches.gate("NOCONSUMERSLOTS");
    private static final Switches.Gate METHOD_TYPE_VARIABLE_ARGUMENTS_OFF = Switches.gate("NOMETHODTYPEVARARGS");
    // gate NOANONYMOUSSUPER: an anonymous class's arguments do not reach its superclass's constructor (A/B)
    private static final Switches.Gate ANONYMOUS_SUPER_OFF = Switches.gate("NOANONYMOUSSUPER");

    private void callSite(MethodInfo mi, Scope scope, Statement statement, Expression call, MethodInfo callee,
                          io.codelaser.maddi.cst.api.analysis.PropertyValueMap analysis, List<Expression> arguments) {
        List<ParameterInfo> parameters = callee.parameters();
        if (parameters.isEmpty()) return;
        LinkComputer.ListOfLinks list = analysis.getOrNull(LinkComputerImpl.LINKED_VARIABLES_ARGUMENTS,
                LinkComputerImpl.ListOfLinksImpl.class);
        receiverSlots(mi, scope, statement, call, callee, list, arguments);
        callResults.instantiatedArguments(mi, scope, call, callee, arguments);
        if (call instanceof MethodCall && this.index.analysed.contains(callee) && !callee.returnType().isVoid()) {
            int id = graph.callId(call, callee);
            graph.argumentsSeen.add(id);
            for (int i = 0; i < arguments.size() && i < parameters.size(); i++) {
                if (parameters.get(i).isVarArgs()) break;
                siteArgument(mi, scope, statement, call, list, arguments, i, new SiteArg(id, i));
            }
        }
        for (int i = 0; i < arguments.size(); i++) {
            ParameterInfo parameter = parameters.get(Math.min(i, parameters.size() - 1)); // varargs: the last one
            if (parameter.isVarArgs() && !passesTheArray(arguments, parameters, i)) {
                // an element of the array the call creates: the array is never null, the argument is an element
                argument(mi, scope, statement, call, list, arguments, i, new Content(parameter));
                continue;
            }
            if (parameter.isVarArgs() && policy.callResults() && !VARARG_COPIES_OFF.get()) {
                // an array spread into varargs ('*array'): the callee holds a copy (varargsCopy)
                Object source = nodes.argumentNode(mi, scope, arguments.get(i));
                if (source != null) graph.varargsPasses.add(List.of(source, parameter));
            }
            if (preconditions.contains(parameter)) {
                preconditionChecks.preconditionArgument(mi, scope, statement, call, parameter, arguments.get(i));
            }
            // a library parameter typed by its class's type variable ('Map.put(K, V)') does not take the argument: the
            // write is the receiver's slot (receiverSlots), and the parameter would carry it down into every analysed
            // implementation of the library type
            // The same for an analysed generic class, where the receiver's slot takes the write: 'stack.push(null)' on a
            // 'ListStack<T>' makes the stack a 'ListStack<X?>', not 'push(T?)' (JSpecify: 'T extends @Nullable
            // Object'; Kotlin: 'push(item: T)' and a 'ListStack<X?>'). Only for a concrete method nothing overrides: an
            // abstract one is not known to store ('Function.apply(F)' is a consumer, and its null must reach the
            // implementations; guava 2026-10-07: +5 unsafe without this). A call on 'this' or a consumer method
            // still makes the parameter nullable.
            // Kotlin: an abstract one too. An implementation's parameter takes the declaration's state
            // (clampTypeVariableOverrides), so a caller's null belongs to its receiver's instantiation
            // ('predicate.apply(null)' on a 'Predicate<in Int?>'): through 'Predicate.apply(T input)' PredicatesTest's
            // null reached every implementation, AbstractNetwork's 'edge -> incidentNodes(edge)' and from there
            // Network.incidentNodes()'s return (guava AbstractNetwork.kt:21/155, 2026-10-17).
            if (parameter.parameterizedType().typeParameter() != null
                && !parameter.parameterizedType().typeParameter().isMethodTypeParameter()
                && parameter.parameterizedType().arrays() == 0
                && (!this.index.analysed.contains(callee)
                    || (policy.callResults() && !CONSUMER_SLOTS_OFF.get()
                        || !callee.isAbstract() && !this.index.implementations.containsKey(callee))
                       && slotReceiver(mi, scope, call, callee) != null)) {
                continue;
            }
            if (projectedReceiver(call, callee, parameter)) continue;
            // Kotlin: an argument to the callee's own type variable is decided once the graph is built
            // (resolveTypeVariableArguments)
            if (policy.callResults() && !METHOD_TYPE_VARIABLE_ARGUMENTS_OFF.get() && call instanceof MethodCall
                && this.index.analysed.contains(callee) && isMethodTypeVariable(parameter.parameterizedType())
                && !parameter.isVarArgs() && contracts.explicitState(parameter) != NullableState.NULLABLE) {
                int index = i;
                callResults.typeVariableArguments.add(new CallResults.TypeVariableArgument(parameter, call, index, mi, scope, () -> {
                    for (ParameterInfo pi : typedDispatch(call, callee, parameter)) {
                        argument(mi, scope, statement, call, list, arguments, index, pi);
                    }
                }));
                continue;
            }
            for (ParameterInfo pi : typedDispatch(call, callee, parameter)) {
                argument(mi, scope, statement, call, list, arguments, i, pi);
            }
        }
        keyedWrite(mi, scope, statement, call, callee, list, arguments);
    }

    /*
     A write into a generic receiver: an argument to a parameter typed by a type variable of the callee's class
     ('add(E)', 'put(K, V)', 'set(T)') flows into that type argument's slot of the receiver ('list', 'this.names').
     The receiver's type arguments are matched to the class's by position (ArrayList<E> -> List<E>), so only when the
     receiver's declared type has as many as the callee's class.
     */
    private void receiverSlots(MethodInfo mi, Scope scope, Statement statement, Expression call, MethodInfo callee,
                               LinkComputer.ListOfLinks list, List<Expression> arguments) {
        Object receiver = slotReceiver(mi, scope, call, callee);
        if (receiver == null) return;
        ParameterizedType type = graph.typeOf(receiver);
        List<ParameterInfo> parameters = callee.parameters();
        for (int i = 0; i < arguments.size() && i < parameters.size(); i++) {
            ParameterizedType pt = parameters.get(i).parameterizedType();
            if (pt.arrays() > 0 || pt.typeParameter() == null || pt.typeParameter().isMethodTypeParameter()) continue;
            // Kotlin: a parameter declared nullable takes the null itself ('convert(a: A?)'); the receiver's
            // instantiation stays. guava's tests' 'Ints.stringConverter().convert(null)' had made
            // IntConverter.INSTANCE a Converter<String?, Int>, which an IntConverter (a Converter<String, Int>) is
            // not; likewise 'Equivalence.equals().equivalentTo(null)' (Ints.kt:101, Equivalence.kt:98, 2026-10-18)
            if (policy.callResults() && !NullGraph.NULLABLE_PARAMETER_SLOTS_OFF.get()
                && contracts.explicitState(parameters.get(i)) == NullableState.NULLABLE) continue;
            int index = slotIndex(type, callee.typeInfo(), pt.typeParameter().getIndex());
            if (index >= 0) argument(mi, scope, statement, call, list, arguments, i, new Arg(receiver, index));
        }
        callResults.contentCopies(mi, scope, receiver, callee, arguments);
    }

    // the receiver whose slots a call writes: a variable of the callee's generic class, with as many type arguments;
    // or an analysed method's result, whose slots are those of what it returns ('wrapper.getInits().addWithKey(v, k)'
    // writes the field 'inits' returns: fernflower InitializerProcessor)
    /*
     Kotlin: a receiver projected at the type variable of the parameter ('Funnel<?>', 'Funnel<? extends T>') takes
     nothing in that position: Kotlin's 'Funnel<*>.funnel(from: Nothing, …)'. A null passed there belongs to no
     implementation. Once the reachability pass completed on guava (5512762c), 'Funnel.funnel' was known
     non-modifying, so slotReceiver no longer took FunnelsTest's 'funnel.funnel(null, sink)', and the null made every
     implementation's 'from' nullable (BloomFilterTest.kt:557, HashTestUtils.kt:139, 2026-10-20). Gate
     NOCONSUMERRECEIVERS.
     */
    private boolean projectedReceiver(Expression call, MethodInfo callee, ParameterInfo parameter) {
        if (CONSUMER_RECEIVERS_OFF.get() || !policy.callResults() || !(call instanceof MethodCall mc) || mc.object() == null
            || callee.isStatic()) return false;
        var tp = parameter.parameterizedType().typeParameter();
        ParameterizedType type = mc.object().parameterizedType();
        if (tp == null || type == null || type.arrays() > 0 || !tp.getOwner().isLeft()
            || tp.getOwner().getLeft() != callee.typeInfo()) return false;
        int index = slotIndex(type, callee.typeInfo(), tp.getIndex());
        if (index < 0 || index >= type.parameters().size()) return false;
        var wildcard = type.parameters().get(index).wildcard();
        return wildcard != null && !wildcard.isSuper();
    }

    private static final Switches.Gate CONSUMER_RECEIVERS_OFF = Switches.gate("NOCONSUMERRECEIVERS");

    /*
     Kotlin: an analysed consumer that overrides replace ('Ordering.compare(T, T)', abstract) takes a null into its
     class's type variable as its receiver's instantiation, as a modifying method does: the overrides' parameters
     are the declaration's T (clampTypeVariableOverrides), and Kotlin passes null only to an 'Ordering<X?>'. Seeded
     on the parameter instead, the null reached every override and flowed on from its clamped parameter: guava
     OrderingTest's 'compare(null, null)' on an Ordering<@Nullable Object> made ExplicitOrdering's 'rank(value: T?)'
     and IncomparableValueException's 'value: Any?', once Ordering.compare was known non-modifying (maddi
     afb4537bf's JDK hints; ExplicitOrdering.kt:14, 2026-10-25). A concrete consumer nothing overrides keeps its own
     nullable parameter. Gate NOFAMILYCONSUMERS.
     */
    private boolean familyConsumer(MethodInfo callee) {
        return policy.callResults() && !FAMILY_CONSUMERS_OFF.get() && index.analysed.contains(callee)
               && (callee.isAbstract() || index.implementations.containsKey(callee));
    }

    private static final Switches.Gate FAMILY_CONSUMERS_OFF = Switches.gate("NOFAMILYCONSUMERS");

    private Object slotReceiver(MethodInfo mi, Scope scope, Expression call, MethodInfo callee) {
        if (!(call instanceof MethodCall mc) || mc.object() == null || callee.isStatic()) return null;
        Expression object = NonNullFacts.unwrap(mc.object());
        Object receiver;
        if (object instanceof VariableExpression ve && nodes.anonymousThis(ve.variable()) instanceof AnonymousThis at) {
            receiver = at;
        } else if (object instanceof VariableExpression ve) {
            receiver = nodes.node(mi, scope, ve.variable());
        } else if (object instanceof MethodCall getter && getter.methodInfo() != null
                   && index.analysed.contains(getter.methodInfo()) && !getter.methodInfo().isConstructor()
                   && concreteArguments(getter.methodInfo().returnType())) {
            receiver = getter.methodInfo();
        } else if (object instanceof MethodCall lookup && !index.analysed.contains(callee)
                   && nodes.argumentNode(mi, scope, lookup) instanceof Arg slot) {
            // a library write into a generic receiver's content read ('ranges.computeIfAbsent(id, k -> new
            // ArrayList<>()).add(new String[]{…, c ? "1" : null})' writes the map's value: fernflower
            // FlattenStatementsHelper.saveEdge, a Kotlin NPE without it). Library callees only: through an analysed
            // generic class (FastFixedSet) the same receiver made fernflower's type variables disagree (32f0a688)
            receiver = slot;
        } else {
            return null;
        }
        // only a method that modifies its receiver stores what it is given: 'add', 'put', 'set'; not a consumer
        // such as 'Comparator.compare(T, T)', 'Equivalence.equivalent', 'Predicate.test'
        if (callee.analysis().getOrDefault(PropertyImpl.NON_MODIFYING_METHOD, ValueImpl.BoolImpl.FALSE).isTrue()
            && !familyConsumer(callee)) {
            return null;
        }
        ParameterizedType type = receiver == null ? null : graph.typeOf(receiver);
        if (type == null || type.arrays() > 0 || callee.typeInfo().typeParameters().isEmpty()) return null;
        boolean anySlot = false;
        for (int i = 0; i < callee.typeInfo().typeParameters().size(); i++) {
            anySlot |= slotIndex(type, callee.typeInfo(), i) >= 0;
        }
        return anySlot ? receiver : null;
    }

    /**
     * The parameter, and, where the override chain stops at a type variable (buildForMethod), the parameters of the
     * implementations the receiver's type selects: for {@code Fn<String, String> f; f.apply(x)}, every
     * {@code apply(String)} (and {@code apply(Object)}, which the chain still reaches).
     */
    private List<ParameterInfo> typedDispatch(Expression call, MethodInfo callee, ParameterInfo pi) {
        if (!isTypeVariable(pi.parameterizedType()) || pi.parameterizedType().typeParameter().isMethodTypeParameter()
            || !(call instanceof MethodCall mc) || mc.object() == null) {
            return List.of(pi);
        }
        ParameterizedType receiver = mc.object().parameterizedType();
        int index = pi.parameterizedType().typeParameter().getIndex();
        if (receiver == null || receiver.typeInfo() != callee.typeInfo() || index >= receiver.parameters().size()) {
            return List.of(pi);
        }
        ParameterizedType argumentType = receiver.parameters().get(index);
        if (argumentType.typeInfo() == null || argumentType.typeInfo().isJavaLangObject()) return List.of(pi);
        List<ParameterInfo> targets = new java.util.ArrayList<>(List.of(pi));
        for (MethodInfo implementation : this.index.implementations.getOrDefault(callee, List.of())) {
            ParameterizedType type = implementation.parameters().get(pi.index()).parameterizedType();
            if (type.typeInfo() == argumentType.typeInfo() && type.arrays() == argumentType.arrays()) {
                targets.add(implementation.parameters().get(pi.index()));
            }
        }
        return targets;
    }

    /*
     Gap 9: the null-carrying half of argument(), into this call's own SiteArg instead of the shared parameter (no
     slots, no content: only whether the argument itself may be null here).
     */
    private void siteArgument(MethodInfo mi, Scope scope, Statement statement, Expression call,
                              LinkComputer.ListOfLinks list, List<Expression> arguments, int i, SiteArg site) {
        Expression argument = arguments.get(i);
        Expression unwrapped = NonNullFacts.unwrap(argument);
        if (unwrapped instanceof NullConstant) {
            seeds.seed(site, "null argument in " + mi.fullyQualifiedName());
            return;
        }
        if (unwrapped instanceof VariableExpression ve
            && (facts.nonNullWhenCalled(call, ve.variable()) || facts.nonNullAt(statement, ve.variable()))) {
            return;
        }
        String lib = contracts.nullableLibraryCall(argument);
        if (lib != null) {
            seeds.seedIndirect(site, "argument " + lib + " in " + mi.fullyQualifiedName());
            return;
        }
        if (unwrapped instanceof MethodCall amc && amc.methodInfo() != null && index.analysed.contains(amc.methodInfo())
            && !amc.methodInfo().isConstructor() && !amc.methodInfo().returnType().isVoid()) {
            graph.addEdge(amc.methodInfo(), site); // conservative: the nested call's result, ungated
        }
        if (list == null || i >= list.list().size()) {
            if (!(unwrapped instanceof MethodCall)) graph.addEdge(nodes.argumentNode(mi, scope, argument), site);
            return;
        }
        Links links = list.list().get(i);
        Variable primary = links.primary();
        if (primary == null) return;
        if (isNullMarker(primary)) {
            nullMarker(mi, primary, site, "null argument in " + mi.fullyQualifiedName());
            return;
        }
        Object node = nodes.node(mi, scope, primary);
        if (node != null) graph.addEdge(node, site);
        sourcesOf(mi, scope, primary, links, site);
    }
    private static final Switches.Gate RETURN_NULL_LITERALS_OFF = Switches.gate("NORETURNNULLLITERALS");
    private static final Switches.Gate ASSIGNED_NON_NULL_OFF = Switches.gate("NOASSIGNEDNONNULL");

    // pi: the parameter, or the Content of a varargs parameter
    private void argument(MethodInfo mi, Scope scope, Statement statement, Expression call,
                          LinkComputer.ListOfLinks list, List<Expression> arguments, int i, Object pi) {
        // Kotlin: in 'assertThrows(NullPointerException.class, () -> copyOf(nullKey))' the arguments are the test's
        // rejected input, whether a literal null (seed) or a variable holding one: they carry nothing into the
        // callee. guava's ImmutableClassToInstanceMapTest had made Primitives.wrap(type), which starts with
        // checkNotNull(type), take a Class<T>? (worktree-maddi-all 2026-10-22). Gate NOREJECTEDFLOWS.
        if (policy.callResults() && !REJECTED_FLOWS_OFF.get() && index.nullRejectingBodies.contains(mi.fullyQualifiedName())) {
            return;
        }
        // also a cast null: '(AiMessage) null' choosing between overloads (langchain4j OutputGuardrailResult)
        if (NonNullFacts.unwrap(arguments.get(i)) instanceof NullConstant) {
            seeds.seed(pi, "null argument in " + mi.fullyQualifiedName(), mi.typeInfo());
            return;
        }
        // M4: a variable argument known non-null at the call (or when its statement starts) carries no null
        if (NonNullFacts.unwrap(arguments.get(i)) instanceof VariableExpression ve
            && (call != null && facts.nonNullWhenCalled(call, ve.variable())
                || facts.nonNullAt(statement, ve.variable()))) {
            Object node = this.nodes.node(mi, scope, ve.variable());
            if (node != null) graph.flows.add(List.of(node, pi)); // an array known non-null: its elements still flow
            return;
        }
        seeds.seedCreated(pi, arguments.get(i), mi);
        Expression unwrappedArgument = NonNullFacts.unwrap(arguments.get(i));
        if (policy.callResults() && unwrappedArgument instanceof Lambda lambda) {
            callResults.lambdaResult(lambda, pi);
            callResults.lambdaParameters(lambda, pi);
            callResults.receiverLambdaResult(mi, scope, call, lambda, pi);
        }
        if (policy.callResults() && unwrappedArgument instanceof MethodReference mr) callResults.methodReferenceResult(mr, pi);
        if (unwrappedArgument instanceof Lambda lambda) callResults.libraryCallback(lambda, pi);
        // 'getUniqueNext(graph, new HashSet<>(mapNext.values()))': a library copy constructor as the argument copies
        // its argument's content into the parameter's slots (fernflower FinallyProcessor)
        if (unwrappedArgument instanceof ConstructorCall copy && copy.constructor() != null
            && !index.analysed.contains(copy.constructor()) && copy.parameterizedType() != null
            && copy.parameterizedType().arrays() == 0) {
            callResults.contentCopies(mi, scope, pi, copy.constructor(), copy.parameterExpressions());
        }
        if (unwrappedArgument instanceof MethodCall amc && amc.methodInfo() != null) {
            Object source = this.nodes.argumentNode(mi, scope, unwrappedArgument);
            if (source instanceof Arg || source instanceof MethodInfo m && concreteArguments(m.returnType())) {
                graph.flows.add(List.of(source, pi)); // the same object: content slots tied (coupleContent)
            }
            // Kotlin: a library view ('main(args.subList(1, args.size()))') is the receiver's data: the parameter's
            // slots are the receiver's (jenkins CLIAction.kt:232, 2026-10-21). Gate NOVIEWMETHODARGUMENTS.
            if (source == null && policy.callResults() && !CallResults.VIEW_METHOD_ARGUMENTS_OFF.get() && pi instanceof ParameterInfo) {
                Object view = callResults.viewArgument(mi, scope, unwrappedArgument);
                if (view != null) graph.flows.add(List.of(view, pi));
            }
            // Kotlin: a slot read passed on ('new Pair(cv.get(i), i)') gives the parameter the slot's value, as
            // callResult does for an assignment; the Java policies leave this to the links
            if (policy.callResults() && source instanceof Arg) graph.addEdge(source, pi);
            // a stream's terminal operation as the argument: its elements are values of the parameter's slot
            callResults.streamTerminal(mi, scope, unwrappedArgument)
                    .forEach((j, nodes) -> nodes.forEach(n -> graph.addEdge(n, new Arg(pi, j))));
        }
        String lib = contracts.nullableLibraryCall(arguments.get(i));
        if (lib != null) {
            seeds.seedIndirect(pi, "argument " + lib + " in " + mi.fullyQualifiedName(), mi.typeInfo());
            return;
        }
        if (list == null || i >= list.list().size()) {
            // no argument links ('super(attributes)', 'this(...)' carry none): each value the argument may take, its
            // own node; a null branch seeds ('this(r, text == null ? null : AiMessage.from(text), …)', langchain4j
            // OutputGuardrailResult, whose translated SUCCESS threw an NPE when this was dropped)
            for (Expression alternative : alternatives(arguments.get(i))) {
                Expression unwrappedAlternative = NonNullFacts.unwrap(alternative);
                if (unwrappedAlternative instanceof NullConstant) {
                    seeds.seed(pi, "null argument in " + mi.fullyQualifiedName(), mi.typeInfo());
                } else if (!(unwrappedAlternative instanceof MethodCall)) {
                    graph.addEdge(this.nodes.argumentNode(mi, scope, alternative), pi);
                    initializerElements(mi, scope, unwrappedAlternative, pi);
                } else {
                    callResultArgument(unwrappedAlternative, pi);
                }
            }
            return;
        }
        Links links = list.list().get(i);
        Variable primary = links.primary();
        if (primary == null) {
            callResultArgument(unwrappedArgument, pi);
            return;
        }
        if (isNullMarker(primary)) {
            nullMarker(mi, primary, pi, "null argument in " + mi.fullyQualifiedName());
            return;
        }
        Object node = this.nodes.node(mi, scope, primary);
        if (node != null) graph.addEdge(node, pi);
        // an intermediate (or a local assigned in this very statement): its sources, in the argument's own links
        sourcesOf(mi, scope, primary, links, pi);
    }

    /*
     An array created in place as an argument without links ('this(new Function[] { rightMapping }, ..)'): its elements
     flow into the parameter's. Without the edge, timefold AbstractJoiner's rightMapping led nowhere, and #68's walk
     found it tolerant by absence (Joiners' rightMapping parameters, test-manager 2026-10-30). A null element is
     seedCreated's. Gate NOINITIALIZERARGUMENTS.
     */
    private void initializerElements(MethodInfo mi, Scope scope, Expression argument, Object pi) {
        if (INITIALIZER_ARGUMENTS_OFF.get()) return;
        ArrayInitializer initializer = argument instanceof ArrayInitializer ai ? ai
                : argument instanceof ConstructorCall cc ? cc.arrayInitializer() : null;
        if (initializer == null) return;
        for (Expression element : initializer.expressions()) {
            for (Expression alternative : alternatives(element)) {
                if (!(NonNullFacts.unwrap(alternative) instanceof NullConstant)) {
                    graph.addEdge(nodes.argumentNode(mi, scope, alternative), new Content(pi));
                }
            }
        }
    }

    private static final Switches.Gate INITIALIZER_ARGUMENTS_OFF = Switches.gate("NOINITIALIZERARGUMENTS");

    /*
     An analysed method's result as the argument, where the links say nothing: the link engine links no value of an
     immutable type ('String' per the JDK hints), so 'builder.description(optionalString(map, "description"))' had
     no edge, and the setter's parameter stayed non-null although optionalString returns null (langchain4j's
     JsonSchemaElementJsonUtils.fromMap; the translated tests pass null there). The same rule as callResultOf: not a
     type-variable result, unless the callee's own null; and, like it, Kotlin only (Policy.callResults): guava
     measured -13 agree / +14 noise under NULL_MARKED with it on for every policy (2026-10-09).
     */
    private void callResultArgument(Expression argument, Object pi) {
        if (!policy.callResults() || !(argument instanceof MethodCall mc) || mc.methodInfo() == null) return;
        MethodInfo callee = mc.methodInfo();
        if (!index.analysed.contains(callee) || callee.isConstructor() || callee.returnType().isVoid()) return;
        ParameterizedType rt = callee.returnType();
        boolean ownNull = rt.arrays() == 0 && rt.typeParameter() != null
                          && (!rt.typeParameter().isMethodTypeParameter()
                              || callee.parameters().stream().noneMatch(p ->
                                     rt.typeParameter().equals(p.parameterizedType().typeParameter())));
        if (!isTypeVariable(rt) || ownNull) graph.addEdge(callee, pi);
    }

    private void sourcesOf(MethodInfo mi, Scope scope, Variable primary, Links links, Object pi) {
        for (Link link : links) {
            if (!link.from().equals(primary)) continue;
            if (!(link.linkNature().isIdenticalTo() || link.linkNature().isAssignedFrom())) continue;
            if (isNullMarker(link.to())) {
                nullMarker(mi, link.to(), pi,
                        "null argument (via " + primary.simpleName() + ") in " + mi.fullyQualifiedName());
            } else {
                graph.addEdge(nodes.node(mi, scope, link.to()), pi);
            }
        }
    }
}
