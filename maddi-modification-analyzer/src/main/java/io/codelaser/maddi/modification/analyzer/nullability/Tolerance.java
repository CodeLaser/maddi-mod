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

import io.codelaser.maddi.cst.api.expression.VariableExpression;
import io.codelaser.maddi.cst.api.info.FieldInfo;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.ParameterInfo;
import io.codelaser.maddi.cst.api.type.NullableState;
import io.codelaser.maddi.cst.api.type.ParameterizedType;
import io.codelaser.maddi.cst.api.variable.LocalVariable;
import io.codelaser.maddi.cst.api.variable.Variable;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

import static io.codelaser.maddi.modification.analyzer.nullability.Types.*;

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
        // what null reaches without these seeds: a field or a return that is nullable anyway may take the parameter
        Set<Object> before = graph.closure(new HashMap<>());
        for (MethodInfo mi : methods) {
            if (!hasBody(mi) || !outsideCallers.externallyCallable(mi)) continue;
            for (ParameterInfo pi : mi.parameters()) {
                ParameterizedType type = pi.parameterizedType();
                if (primitive(type) || isTypeVariable(type) || before.contains(pi) || contracts.nonNullContracts.contains(pi)
                    || contracts.explicitState(pi) == NullableState.NONNULL || contracts.libraryNonNullParameter(mi, pi.index())
                    || hook(mi, pi) || overloadedAt(mi, pi) || overridesAnalysed(mi)) continue;
                if (tolerates(pi, before)) {
                    seeds.seedIndirect(pi, mi.fullyQualifiedName() + " tolerates null in " + pi.name()
                                     + ", callable from outside");
                }
            }
        }
    }

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
                    if (!hasBody(m) || demanded(m, v -> pi.equals(v))) return trace.tolerance(from, n, "demanded");
                }
                case Local local -> {
                    // by name, as NonNullFacts compares locals; a reassigned parameter's shadow is the parameter
                    if (demanded(local.methodInfo(), v -> (v instanceof LocalVariable || v instanceof ParameterInfo)
                                                          && v.simpleName().equals(local.name()))) {
                        return trace.tolerance(from, n, "demanded");
                    }
                }
                case FieldInfo _, MethodInfo _ -> {
                    if (!before.contains(n)) return trace.tolerance(from, n, "non-null sink");
                    trace.tolerance(from, n, "nullable anyway");
                    continue; // nullable anyway: what it flows into is decided without this parameter
                }
                case Content _, Arg _ when declaredSlot(n) && !TOLERANT_SLOTS_OFF.get() -> {
                    if (!before.contains(n)) return trace.tolerance(from, n, "non-null slot");
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
        return holder instanceof FieldInfo || holder instanceof MethodInfo || holder instanceof AnonymousThis;
    }

    private static final Switches.Gate TOLERANT_SLOTS_OFF = Switches.gate("NOTOLERANTSLOTS");

    /*
     a demand in m's body, or in the body of the method that encloses m's lambda or local class; or in a method of an
     anonymous or local class m encloses, which captures m's variables: langchain4j LambdaStreamingResponseHandler
     hands 'onPartialResponse' to an anonymous handler whose onPartialResponse(s) calls 'onPartialResponse.accept(s)'
     (#68: 5 C# warnings, test-manager 2026-10-30). A lambda's body is m's own (NonNullFacts.demands). Gate
     NOENCLOSEDDEMANDS.
     */
    private boolean demanded(MethodInfo m, java.util.function.Predicate<Variable> variable) {
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
