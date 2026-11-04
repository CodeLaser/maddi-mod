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

import static io.codelaser.maddi.modification.analyzer.nullability.Types.*;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.IntStream;

import io.codelaser.maddi.cst.api.expression.BinaryOperator;
import io.codelaser.maddi.cst.api.expression.Expression;
import io.codelaser.maddi.cst.api.expression.MethodCall;
import io.codelaser.maddi.cst.api.expression.NullConstant;
import io.codelaser.maddi.cst.api.expression.VariableExpression;
import io.codelaser.maddi.cst.api.info.FieldInfo;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.ParameterInfo;
import io.codelaser.maddi.cst.api.type.NullableState;
import io.codelaser.maddi.cst.api.type.ParameterizedType;
import io.codelaser.maddi.cst.api.variable.LocalVariable;
import io.codelaser.maddi.cst.api.variable.Variable;
import io.codelaser.maddi.cst.impl.analysis.NullAnnotations;

/**
 * CodeLaser/maddi-mod#68: a parameter callable from outside whose body tolerates null on every path is nullable,
 * whatever the analysed callers pass ({@link #tolerantParameters}).
 */
final class Tolerance {
    private final Trace trace;
    private final Index index;
    private final Seeds seeds;
    private final Contracts contracts;
    private final NullGraph graph;
    private final OutsideCallers outsideCallers;
    private final NonNullFacts facts;

    Tolerance(Trace trace, Index index, Seeds seeds, Contracts contracts, NullGraph graph,
              OutsideCallers outsideCallers, NonNullFacts facts) {
        this.trace = trace;
        this.index = index;
        this.seeds = seeds;
        this.contracts = contracts;
        this.graph = graph;
        this.outsideCallers = outsideCallers;
        this.facts = facts;
    }

    // ------------------------------------------------------------------ tolerated nulls (CodeLaser/maddi-mod#68)

    // gate NOTOLERANT: a visible parameter whose body tolerates null is decided by the flow alone, as before #68
    static final Switches.Gate TOLERANT_OFF = Switches.gate("NOTOLERANT");
    // the nodes one tolerance search visits at most; beyond that the parameter is not tolerant
    private static final int MAX_TOLERANCE_SEARCH = 2000;

    /*
     CodeLaser/maddi-mod#68: a parameter callable from outside whose body tolerates null on every path is nullable,
     whatever the analysed callers pass. guava marks Preconditions' message arguments, the exception constructors'
     message and cause, and Synchronized's mutex @Nullable because they accept null; no analysed call passes one, so
     the closed world said non-null, and a Kotlin translation would throw at a Java caller guava's API permits.
     Not a type variable (parametric), not a primitive, not a declared non-null contract, not an override of a
     library method declaring the parameter non-null. Not a parameter an overridden method never reads: an empty
     hook (guava's Service.Listener.failed) is no evidence. Not a parameter annotated non-null either, also where the policy
     does not hold the annotation as a contract (withoutContracts: a diagnosis checking the annotation): the author has
     declared it, and a defensive null check under it is no null reaching it (diagnostics' TestNullIntoNonNull).
     Not a parameter an overload shares the position and type of (overloadedAt), nor one of an override of an
     analysed method (overridesAnalysed).
     */
    void tolerantParameters(List<MethodInfo> methods) {
        this.methods = methods;
        overrideTolerates.clear();
        // what null reaches without these seeds: a field or a return that is nullable anyway may take the parameter
        Set<Object> before = graph.closure(new HashMap<>());
        for (MethodInfo mi : methods) {
            if (!hasBody(mi) || !outsideCallers.externallyCallable(mi)) continue;
            for (ParameterInfo pi : mi.parameters()) {
                ParameterizedType type = pi.parameterizedType();
                if (primitive(type) || isTypeVariable(type) || before.contains(pi) || contracts.nonNullContracts.contains(pi)
                    || contracts.explicitState(pi) == NullableState.NONNULL || contracts.libraryNonNullParameter(mi, pi.index())
                    || hook(mi, pi) || overloadedAt(mi, pi) || overridesAnalysed(mi) || nullMarked(mi)) continue;
                if (tolerates(pi, before)) {
                    seeds.seedIndirect(pi, mi.fullyQualifiedName() + " tolerates null in " + pi.name()
                                           + ", callable from outside");
                }
            }
        }
    }

    /*
     A @NullMarked scope (the method, its types, package-info, module) declares an unannotated parameter non-null, as
     an explicit annotation does, and the author's declaration wins over a defensive null check, as it does for
     '@NonNull String s'. timefold's Joiners.equal(leftMapping, rightMapping): the mappings are dereferenced through an
     element of the joiner's mapping array, which the walk does not follow, and 23 nullable warnings appeared in the
     C# translation (maddi-csharp, 2026-11-04). A type variable is excluded before this. Gate NOTOLERANTNULLMARKED.
     */
    private static boolean nullMarked(MethodInfo mi) {
        return !TOLERANT_NULL_MARKED_OFF.get() && NullAnnotations.inNullMarkedScope(mi);
    }

    private static final Switches.Gate TOLERANT_NULL_MARKED_OFF = Switches.gate("NOTOLERANTNULLMARKED");

    /*
     An overridden method that ignores a parameter is a hook (guava's Service.Listener.failed(from, failure) { }): its
     analysed overrides decide what the parameter needs, and Kotlin wants one nullability along the family ("'failed'
     overrides nothing"). A method without an analysed override that ignores a parameter tolerates null, as #68 has
     it: an overridable one too (guava's StringCatcher.methodWithoutAnnotation(string)).
     */
    private boolean hook(MethodInfo mi, ParameterInfo pi) {
        return index.implementations.containsKey(mi) && !read(mi, pi);
    }

    /*
     An override of an analysed method takes its parameters' nullability from the family, which Kotlin wants as one: its
     own tolerance would raise the declaration's (guava's ServiceManager listener logs 'failure', which Logger.log
     accepts null for, and Service.Listener.failed's empty hook became 'failure: Throwable?'). The declaration's own
     body decides. An override of a library method only (a Comparator's compare) is #68's case.
     */
    private boolean overridesAnalysed(MethodInfo mi) {
        return mi.overrides().stream().anyMatch(index.analysed::contains);
    }

    /*
     An overload of the method (same name, a method or a constructor as it is) has a parameter of the same type at the
     same position. Java picks between them by arity or varargs; Kotlin first by the most specific parameter types,
     where String is more specific than String?: langchain4j's ensureNotNull(object, String name) widened to
     'name: String?', kotlinc resolves ensureNotNull(text, "text") to ensureNotNull(object, String format, Object...
     args), and the message is "text", not "text cannot be null". Only an overload that takes the same arguments
     counts: the same parameter types followed by one varargs parameter. Overloads of one arity that differ elsewhere
     (Preconditions' checkArgument(boolean,String,Object,char) and (boolean,String,char,Object)) or in arity
     (an exception's (String) and (String,Throwable)) are told apart by those arguments in Kotlin too.
     */
    private static boolean overloadedAt(MethodInfo mi, ParameterInfo pi) {
        List<ParameterInfo> parameters = mi.parameters();
        int n = parameters.size();
        if (pi.isVarArgs()) return false;
        return mi.typeInfo().constructorAndMethodStream()
                .filter(m -> m != mi && m.isConstructor() == mi.isConstructor() && m.name().equals(mi.name())
                             && m.parameters().size() == n + 1 && m.parameters().get(n).isVarArgs())
                .anyMatch(m -> IntStream.range(0, n).allMatch(i -> sameErasure(
                        parameters.get(i).parameterizedType(), m.parameters().get(i).parameterizedType())));
    }

    /*
     Each overload has its own type parameters: ensureNotNull's <T> T(T object, String name) and <T> T(T object, String
     format, Object... args) agree at position 0 by their erasure, Object, not by T == T.
     */
    private static boolean sameErasure(ParameterizedType a, ParameterizedType b) {
        if (a.arrays() != b.arrays()) return false;
        String ea = erasure(a);
        return ea != null && ea.equals(erasure(b));
    }

    private static String erasure(ParameterizedType t) {
        if (t.typeParameter() != null) {
            List<ParameterizedType> bounds = t.typeParameter().typeBounds();
            return bounds.isEmpty() ? "java.lang.Object" : erasure(bounds.getFirst());
        }
        return t.typeInfo() == null ? null : t.typeInfo().fullyQualifiedName();
    }

    // the body reads the parameter
    private static boolean read(MethodInfo mi, ParameterInfo pi) {
        boolean[] found = {false};
        mi.methodBody().visit(e -> {
            if (e instanceof VariableExpression ve && pi.equals(ve.variable())) found[0] = true;
            return !found[0];
        });
        return found[0];
    }

    /*
     No path requires the parameter non-null (NonNullFacts.demands), nor any local or parameter it flows into: along
     the null edges, into the analysed callees it is passed to (a callee's tolerant parameter tolerates; checkArgument
     hands its message arguments to lenientFormat), and both ways along the override chain. A field or return it flows
     into must be nullable anyway; a non-null contract, or a method without a body, ends the tolerance.
     */
    private boolean tolerates(ParameterInfo start, Set<Object> before) {
        // NULLWHY: the node the walk reached each node from, to print the paths of a traced parameter
        Map<Object, Object> from = trace.why(start) ? new HashMap<>() : null;
        Set<Object> seen = new HashSet<>();
        Deque<Object> queue = new ArrayDeque<>();
        seen.add(start);
        queue.add(start);
        while (!queue.isEmpty()) {
            Object n = queue.removeFirst();
            if (seen.size() > MAX_TOLERANCE_SEARCH || contracts.nonNullContracts.contains(n)) {
                return trace.tolerance(from, n, seen.size() > MAX_TOLERANCE_SEARCH ? "search too large" : "contract");
            }
            switch (n) {
                case ParameterInfo pi -> {
                    MethodInfo m = pi.methodInfo();
                    if (!hasBody(m)) return trace.tolerance(from, n, "demanded");
                    if (demanded(m, v -> pi.equals(v))) {
                        // the declaring body throws on null; the walk goes on in the overrides that take it
                        List<ParameterInfo> tolerating = toleratingOverrides(m, pi, before);
                        if (tolerating.isEmpty()) return trace.tolerance(from, n, "demanded");
                        trace.tolerance(from, n, "demanded, tolerated by an override");
                        if (OVERRIDE_CYCLES_OFF.get()) {
                            for (ParameterInfo op : tolerating) {
                                if (seen.add(op)) {
                                    queue.addLast(op);
                                    if (from != null) from.put(op, n);
                                }
                            }
                        }
                        continue;
                    }
                    // passed on to a parameter its @NullMarked scope declares non-null: a demand, as a contract is
                    // (timefold's Joiners.equal(l, r) hands both to DefaultBiJoiner's @NullMarked constructor)
                    if (pi != start && !isTypeVariable(pi.parameterizedType()) && nullMarked(m)
                        && contracts.explicitState(pi) != NullableState.NULLABLE) {
                        return trace.tolerance(from, n, "@NullMarked");
                    }
                }
                case Local local -> {
                    // by name, as NonNullFacts compares locals; a reassigned parameter's shadow is the parameter
                    if (demanded(local.methodInfo(), v -> (v instanceof LocalVariable || v instanceof ParameterInfo)
                                                          && v.simpleName().equals(local.name()))) {
                        return trace.tolerance(from, n, "demanded");
                    }
                }
                case FieldInfo _ -> {
                    if (!before.contains(n)) return trace.tolerance(from, n, "non-null sink");
                    trace.tolerance(from, n, "nullable anyway");
                    continue; // nullable anyway: what it flows into is decided without this parameter
                }
                case MethodInfo m -> {
                    if (before.contains(m)) {
                        trace.tolerance(from, n, "nullable anyway");
                        continue; // as a field
                    }
                    // the return of an override of a library method is no sink: the value travels through the
                    // library to whoever reads it there, which this walk cannot follow. guava's Joiner.join(first,
                    // second, rest) hands them to an AbstractList's get(), whose iterator the appendTo loop reads;
                    // first and second are @Nullable (useForNull), and the walk failed at get's return. Not the
                    // parameter's own method: 's -> s' hands the library back what the library gave it, which says
                    // nothing about null (guava's identity lambdas). Gate NOLIBRARYRETURN.
                    // #85: an anonymous class's override handing out its object's element: follow it into the slot
                    // (GraphCompletion.overrideReturnsIntoSlots), and on to who reads the object's elements
                    if (graph.successors.getOrDefault(m, Set.of()).stream()
                            .anyMatch(s -> s instanceof Arg a && a.of() instanceof AnonymousThis)) {
                        trace.tolerance(from, n, "into the object's slot");
                    } else if (!LIBRARY_RETURN_OFF.get() && !m.equals(start.methodInfo()) && overridesLibraryOnly(m)) {
                        trace.tolerance(from, n, "library return");
                        continue;
                    } else {
                        return trace.tolerance(from, n, "non-null sink");
                    }
                }
                case Content _, Arg _ when declaredSlot(n) && !TOLERANT_SLOTS_OFF.get() -> {
                    if (!before.contains(n)) return trace.tolerance(from, n, "non-null slot");
                    if (dereferencedRead(n)) return trace.tolerance(from, n, "nullable anyway, dereferenced when read");
                    trace.tolerance(from, n, "nullable anyway");
                    continue;
                }
                default -> {
                }
            }
            for (Object s : graph.successors.getOrDefault(n, Set.of())) {
                if (seen.add(s)) {
                    queue.addLast(s);
                    if (from != null) from.put(s, n);
                }
            }
        }
        if (from != null) trace.print("NULLWHY tolerates " + Trace.name(start) + " visited=" + seen.size());
        return true;
    }

    /*
     CodeLaser/maddi-mod#85: the call may be virtual. guava's Joiner.appendTo calls toString(part), which demands part
     (requireNonNull); the useForNull override reads it and demands nothing ('part == null ? nullText : …'). A dispatch
     target that accepts null makes the parameter nullable for the family, which Kotlin wants as one. Not an override
     that ignores the parameter (an empty hook is no evidence), nor one without a body. Gate NOOVERRIDETOLERATES.
     */
    private List<ParameterInfo> toleratingOverrides(MethodInfo m, ParameterInfo pi, Set<Object> before) {
        if (OVERRIDE_TOLERATES_OFF.get()) return List.of();
        List<ParameterInfo> out = new ArrayList<>();
        for (MethodInfo o : index.implementations.getOrDefault(m, List.of())) {
            if (!hasBody(o) || o.parameters().size() <= pi.index()) continue;
            ParameterInfo op = o.parameters().get(pi.index());
            if (read(o, op) && !demanded(o, v -> op.equals(v))
                && (OVERRIDE_CYCLES_OFF.get() || comparedWithNull(o, op) || overrideTolerates(op, before))) {
                out.add(op);
            }
        }
        return out;
    }

    /*
     An override overrules a demand only when it tolerates null by itself: its own walk ends tolerant, without counting
     on the overrule being decided. guava's ReverseOrdering.min(a, b, c, rest) hands rest to forwardOrder.max, whose
     demand ReverseOrdering.max would overrule by handing rest back to min: a cycle of delegating overrides, every one
     of them "tolerating", none of them reading null. The walk took the cycle for tolerance (62 noise rows in the guava
     oracle, ReverseOrdering.kt and ReverseNaturalOrdering.kt out of the J2K bytecode). An override that compares the
     parameter with null tolerates it by its own word, whatever it hands on behind the check: useForNull's
     'part == null ? nullText : Joiner.this.toString(part)' calls back into the demand, but only with a part that is not
     null, which the walk cannot see. Otherwise the override's own walk decides. An override whose overrule is
     being decided counts as demanding (the least fixpoint). A result is kept for the rest of the run unless it is a
     "no" that rested on such an assumption: the outcome must not depend on which parameter was asked about first.
     Gate NOOVERRIDECYCLES.
     */
    private boolean overrideTolerates(ParameterInfo op, Set<Object> before) {
        Boolean known = overrideTolerates.get(op);
        if (known != null) return known;
        if (!overrulesInProgress.add(op)) {
            assumedDemands++;
            return false;
        }
        int assumedBefore = assumedDemands;
        boolean t;
        try {
            t = tolerates(op, before);
        } finally {
            overrulesInProgress.remove(op);
        }
        if (t || assumedDemands == assumedBefore) overrideTolerates.put(op, t);
        return t;
    }

    private static boolean comparedWithNull(MethodInfo mi, ParameterInfo pi) {
        boolean[] found = {false};
        mi.methodBody().visit(e -> {
            if (e instanceof BinaryOperator bo && bo.operator() != null
                && ("==".equals(bo.operator().name()) || "!=".equals(bo.operator().name()))) {
                Expression other = bo.lhs() instanceof NullConstant ? bo.rhs()
                        : bo.rhs() instanceof NullConstant ? bo.lhs() : null;
                if (other != null && NonNullFacts.unwrap(other) instanceof VariableExpression ve && pi.equals(ve.variable())) {
                    found[0] = true;
                }
            }
            return !found[0];
        });
        return found[0];
    }

    private final Map<ParameterInfo, Boolean> overrideTolerates = new HashMap<>();
    private final Set<ParameterInfo> overrulesInProgress = new HashSet<>();
    private int assumedDemands;

    private static final Switches.Gate OVERRIDE_TOLERATES_OFF = Switches.gate("NOOVERRIDETOLERATES");
    private static final Switches.Gate OVERRIDE_CYCLES_OFF = Switches.gate("NOOVERRIDECYCLES");

    /*
     CodeLaser/maddi-mod#93: a slot that is nullable anyway takes the parameter without deciding about it, but its
     readers may still dereference what they read. timefold's Joiners.equal(l, r) stores the mappings as elements of
     the joiner's mapping arrays, which DummyMemberAccessor.getGetterFunction()'s null makes nullable anyway (through
     Joiners.lessThan); matches() calls 'getRightMapping(i).apply(b)'. A reader is a getter returning the slot whose
     call result is dereferenced directly, or a local the slot flows into that a body demands; a few steps from the
     slot, through slots, locals and returns only. Gate NODEREFERENCEDSLOTS.
     */
    private boolean dereferencedRead(Object slot) {
        if (DEREFERENCED_SLOTS_OFF.get() || methods == null) return false;
        Set<MethodInfo> dereferenced = dereferencedResults();
        Set<Object> seen = new HashSet<>();
        List<Object> frontier = List.of(slot);
        for (int depth = 0; depth < MAX_READER_DEPTH && !frontier.isEmpty(); depth++) {
            List<Object> next = new ArrayList<>();
            for (Object n : frontier) {
                for (Object s : graph.successors.getOrDefault(n, Set.of())) {
                    if (!seen.add(s)) continue;
                    switch (s) {
                        case MethodInfo m -> {
                            if (dereferenced.contains(m)) return true;
                            next.add(m);
                        }
                        case Local local -> {
                            if (demanded(local.methodInfo(), v -> (v instanceof LocalVariable || v instanceof ParameterInfo)
                                                                  && v.simpleName().equals(local.name()))) {
                                return true;
                            }
                            next.add(local);
                        }
                        case Content _, Arg _ -> next.add(s);
                        default -> {
                        }
                    }
                }
            }
            frontier = next;
        }
        return false;
    }

    private static final int MAX_READER_DEPTH = 4;
    private static final Switches.Gate DEREFERENCED_SLOTS_OFF = Switches.gate("NODEREFERENCEDSLOTS");
    private List<MethodInfo> methods;
    private Set<MethodInfo> dereferencedResults;

    // the methods whose call result some body dereferences directly: 'getRightMapping(i).apply(b)'
    private Set<MethodInfo> dereferencedResults() {
        if (dereferencedResults != null) return dereferencedResults;
        Set<MethodInfo> out = new HashSet<>();
        for (MethodInfo mi : methods) {
            if (mi.methodBody() == null) continue;
            mi.methodBody().visit(e -> {
                if (e instanceof MethodCall mc && mc.methodInfo() != null && !mc.methodInfo().isStatic()
                    && mc.object() != null && NonNullFacts.unwrap(mc.object()) instanceof MethodCall inner
                    && inner.methodInfo() != null && index.analysed.contains(inner.methodInfo())) {
                    out.add(inner.methodInfo());
                }
                return true;
            });
        }
        return dereferencedResults = out;
    }

    // gate NOLIBRARYRETURN: the return of an override of a library method ends the tolerance, as any other (A/B)
    private static final Switches.Gate LIBRARY_RETURN_OFF = Switches.gate("NOLIBRARYRETURN");

    /*
     overrides a library method, and no analysed one. A method of a class only: a lambda's return is typed by its
     target, a function type declared elsewhere ('(Query) -> Double'), which a null does not fit (langchain4j
     EmbeddingStoreContentRetriever 'query -> minScore', timefold SolverJobBuilder 'id -> problem'; worktree-maddi-all
     2026-10-30). The model's overrides, not overrides(m), which adds a lambda's functional method.
     */
    private boolean overridesLibraryOnly(MethodInfo m) {
        Set<MethodInfo> overrides = m.overrides();
        return !overrides.isEmpty() && overrides.stream().noneMatch(index.analysed::contains);
    }

    /*
     An element slot of a field, of a return or of a class's own supertype is part of that declaration, as the field
     itself is: 'public void add(String item) { items.add(item); }' does not tolerate null in item when 'items' is a
     List<String> nothing writes null into; the null would make it a List<String?>. A parameter's or a local's slot
     is followed on (checkArgument's message arguments go into lenientFormat's varargs). Gate NOTOLERANTSLOTS: every
     slot followed on, as before (the maddi-java-demo's public adders and setters all came out nullable, 2026-10-27).
     */
    private static boolean declaredSlot(Object node) {
        Object holder = node;
        while (true) {
            if (holder instanceof Content c) holder = c.of();
            else if (holder instanceof Arg a) holder = a.of();
            else break;
        }
        if (!INTERNAL_SLOTS_OFF.get()) {
            // #85: an anonymous class's type argument is its creation's, a private method's return slot its class's:
            // neither is a declaration a caller sees, and the walk follows on to where the object goes
            if (holder instanceof AnonymousThis at && at.type().isAnonymous()) return false;
            if (holder instanceof MethodInfo m && m.access().isPrivate()) return false;
        }
        return holder instanceof FieldInfo || holder instanceof MethodInfo || holder instanceof AnonymousThis;
    }

    // gate NOINTERNALSLOTS: an anonymous class's type argument and a private method's return slot end the walk (A/B)
    private static final Switches.Gate INTERNAL_SLOTS_OFF = Switches.gate("NOINTERNALSLOTS");

    private static final Switches.Gate TOLERANT_SLOTS_OFF = Switches.gate("NOTOLERANTSLOTS");

    /*
     a demand in m's body, or in the body of the method that encloses m's lambda or local class; or in a method of an
     anonymous or local class m encloses, which captures m's variables: langchain4j LambdaStreamingResponseHandler
     hands 'onPartialResponse' to an anonymous handler whose onPartialResponse(s) calls 'onPartialResponse.accept(s)'
     (#68: 5 C# warnings, test-manager 2026-10-30). A lambda's body is m's own (NonNullFacts.demands). Gate
     NOENCLOSEDDEMANDS.
     */
    private boolean demanded(MethodInfo m, Predicate<Variable> variable) {
        for (MethodInfo mi = m; mi != null; mi = mi.typeInfo().enclosingMethod()) {
            if (facts.demands(mi).stream().anyMatch(variable)) return true;
        }
        if (!ENCLOSED_DEMANDS_OFF.get()) {
            for (MethodInfo inner : index.enclosedMethods().getOrDefault(m, List.of())) {
                if (facts.demands(inner).stream().anyMatch(variable)) return true;
            }
        }
        return false;
    }

    private static final Switches.Gate ENCLOSED_DEMANDS_OFF = Switches.gate("NOENCLOSEDDEMANDS");
}
