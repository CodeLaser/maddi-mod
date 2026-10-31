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

import io.codelaser.maddi.cst.api.analysis.Value;
import io.codelaser.maddi.cst.api.expression.Assignment;
import io.codelaser.maddi.cst.api.expression.BinaryOperator;
import io.codelaser.maddi.cst.api.expression.Cast;
import io.codelaser.maddi.cst.api.expression.ConstructorCall;
import io.codelaser.maddi.cst.api.expression.Expression;
import io.codelaser.maddi.cst.api.expression.Lambda;
import io.codelaser.maddi.cst.api.expression.MethodCall;
import io.codelaser.maddi.cst.api.expression.MethodReference;
import io.codelaser.maddi.cst.api.expression.NullConstant;
import io.codelaser.maddi.cst.api.expression.VariableExpression;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.ParameterInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.api.statement.Block;
import io.codelaser.maddi.cst.api.statement.ExpressionAsStatement;
import io.codelaser.maddi.cst.api.statement.ForEachStatement;
import io.codelaser.maddi.cst.api.statement.LocalVariableCreation;
import io.codelaser.maddi.cst.api.statement.ReturnStatement;
import io.codelaser.maddi.cst.api.statement.Statement;
import io.codelaser.maddi.cst.api.statement.ThrowStatement;
import io.codelaser.maddi.cst.api.type.NullableState;
import io.codelaser.maddi.cst.api.type.ParameterizedType;
import io.codelaser.maddi.cst.api.variable.LocalVariable;
import io.codelaser.maddi.cst.api.variable.This;
import io.codelaser.maddi.cst.api.variable.Variable;
import io.codelaser.maddi.cst.impl.analysis.PropertyImpl;
import io.codelaser.maddi.cst.impl.analysis.ValueImpl;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.codelaser.maddi.modification.analyzer.nullability.Types.*;

/**
 * What a call's result stands for, as the walk reads a statement's values (syntacticSeeds): an analysed callee's
 * return, a library call that may return null, a factory's or a stream's elements, a library view of its receiver,
 * the results and parameters of lambdas and method references, the content copied by a library constructor, and
 * the instantiation of a generic callee's type variables (decided once the graph is built:
 * resolveTypeVariableArguments).
 */
final class CallResults {
    private final Policy policy;
    private final Index index;
    private final Seeds seeds;
    private final Contracts contracts;
    private final NullGraph graph;
    private final Nodes nodes;
    private final NonNullFacts facts;

    CallResults(Policy policy, Index index, Seeds seeds, Contracts contracts, NullGraph graph, Nodes nodes,
                NonNullFacts facts) {
        this.policy = policy;
        this.index = index;
        this.seeds = seeds;
        this.contracts = contracts;
        this.graph = graph;
        this.nodes = nodes;
        this.facts = facts;
    }

    private static final Switches.Gate VIEW_LOOPS_OFF = Switches.gate("NOVIEWLOOPS");

    private static final Switches.Gate VIEW_LOOP_WRITES_OFF = Switches.gate("NOVIEWLOOPWRITES");

    /*
     Kotlin: a parameter's '?' argument prints as '*', whose values read as Any?: 'for (key in keys)' over
     'getAllPresent(keys: MutableIterable<*>)' hands an Any? to 'getIfPresent(key)' (guava AbstractCache.kt:17, once
     3fd57564 cut the hub that had made that parameter nullable by accident). Nothing flows back out of the slot
     (coupleContent), so the seed reaches only what is read from it.
     */
    void starSlots(ParameterInfo pi) {
        if (!policy.callResults() || STAR_SLOTS_OFF.get()) return;
        ParameterizedType pt = pi.parameterizedType();
        if (pt.arrays() != 0) return;
        for (int i = 0; i < pt.parameters().size(); i++) {
            ParameterizedType a = pt.parameters().get(i);
            if (a.wildcard() != null && (a.wildcard().isUnbound() || a.wildcard().isExtends()
                                          && a.typeParameter() == null && a.typeInfo() != null
                                          && a.typeInfo().isJavaLangObject())) {
                seeds.seed(new Arg(pi, i), "read from '*' in " + pi.methodInfo().fullyQualifiedName());
            }
        }
    }

    // gate NOSTARRESULTS: a generic call given a '?' argument does not read its result from that slot (A/B)
    private static final Switches.Gate STAR_RESULTS_OFF = Switches.gate("NOSTARRESULTS");

    // gate NOSTARSLOTS: a '?' argument's values are not read as nullable (A/B)
    private static final Switches.Gate STAR_SLOTS_OFF = Switches.gate("NOSTARSLOTS");

    /*
     Kotlin: a call on this object to a method of its own class instantiates the class's type variables as the
     caller has them, so the result's slots are the target's ('table = newEntryArray(n)' in guava's
     LocalCache.Segment: the null elements of AtomicReferenceArray<ReferenceEntry<K, V>?> table belong to
     newEntryArray's result too; Striped.SmallLazyStriped likewise). Not with a method type variable in the result,
     which each call instantiates anew.
     */
    private static boolean sameInstantiation(MethodCall mc, MethodInfo caller) {
        MethodInfo callee = mc.methodInfo();
        if (SAME_INSTANTIATION_OFF.get() || callee.isStatic() || callee.typeInfo() != caller.typeInfo()
            || caller.isStatic() || hasMethodTypeVariable(callee.returnType())) {
            return false;
        }
        Expression object = mc.object() == null ? null : NonNullFacts.unwrap(mc.object());
        return object == null || object.isEmpty()
               || object instanceof VariableExpression ve && ve.variable() instanceof This;
    }

    private static boolean hasMethodTypeVariable(ParameterizedType pt) {
        if (isMethodTypeVariable(pt)) return true;
        if (pt.arrays() > 0 && pt.componentType() != null && hasMethodTypeVariable(pt.componentType())) return true;
        for (ParameterizedType a : pt.parameters()) if (hasMethodTypeVariable(a)) return true;
        return false;
    }

    // gate NOSAMEINSTANTIATION: a call on this does not tie its result's slots to the target's (A/B)
    private static final Switches.Gate SAME_INSTANTIATION_OFF = Switches.gate("NOSAMEINSTANTIATION");

    /*
     Kotlin: guava's '@ParametricNullness T get()' returns T as each instantiation has it, which the printer keeps
     ('Supplier<T>.get(): T'). An implementation's own null ('return uncheckedCastNullableTToT(value)' from a
     '@Nullable T value' cache, Suppliers.ExpiringMemoizingSupplier) belongs to a nullable instantiation only: it does
     not reach the result of 'factory.get()' on a Supplier<Map<C, V>> (StandardTable.backingMap's and
     AbstractMapBasedMultimap.map's values, guava 2026-10-17).
     */
    private static boolean parametricNullness(MethodInfo mi) {
        return !PARAMETRIC_OFF.get() && mi.annotations().stream().anyMatch(a -> a.typeInfo() != null
                && a.typeInfo().simpleName().equals("ParametricNullness"));
    }

    // gate NOPARAMETRIC: a '@ParametricNullness' type-variable return's own null reaches every call (A/B)
    private static final Switches.Gate PARAMETRIC_OFF = Switches.gate("NOPARAMETRIC");

    // gate NONESTEDASSIGNMENTS: a link into a variable assigned inside an expression is not counted (A/B)
    private static final Switches.Gate NESTED_ASSIGNMENTS_OFF = Switches.gate("NONESTEDASSIGNMENTS");

    // read from the code, not from the links: the literal null assigned to a local or field (so also in a degraded
    // method, which has no links), and a library call whose result may be null USED DIRECTLY: returned, assigned,
    // initializing a local (its value reaches the link graph only as an opaque '$_v')
    // the method and scope of the statement syntacticSeeds is reading, for callResult
    private record ReadScope(MethodInfo mi, Scope scope) {
    }

    private ReadScope readScope;
    private Statement seedStatement;

    /*
     Kotlin: 'x = lib(); if (x == null || ...) throw ...;' the library's null never leaves the assignment: the very
     next statement exits on it. Its seed on the local would reach every use after an if/else merge that loses the
     fact (guava TreeRangeMap.SubRangeMap.span(): 'lowerBound = entriesByLowerBound.ceilingKey(...)' in one branch,
     'subRange.lowerBound' in the other, made Range.lowerBound and ImmutableRangeSet's 'Cut<C>?' nullable,
     2026-10-17). Only an if without else whose block ends in a throw or return.
     */
    final Set<Statement> deadNullAssignments = java.util.Collections.newSetFromMap(new IdentityHashMap<>());

    void deadNullAssignments(Block block) {
        List<Statement> statements = block.statements();
        for (int i = 0; i < statements.size(); i++) {
            Statement statement = statements.get(i);
            for (Block sub : statement.subBlocks()) deadNullAssignments(sub);
            if (i + 1 >= statements.size()
                || !(statements.get(i + 1) instanceof io.codelaser.maddi.cst.api.statement.IfElseStatement ifs)
                || ifs.elseBlock() != null && !ifs.elseBlock().isEmpty() || !exits(ifs.block())) {
                continue;
            }
            Variable local = assignedLocal(statement);
            if (local != null && facts.whenFalse(ifs.expression()).contains(local)) deadNullAssignments.add(statement);
        }
    }

    private static boolean exits(Block block) {
        if (block == null || block.statements().isEmpty()) return false;
        Statement last = block.statements().getLast();
        return last instanceof ThrowStatement || last instanceof ReturnStatement;
    }

    private static Variable assignedLocal(Statement statement) {
        if (statement instanceof LocalVariableCreation lvc && lvc.localVariableStream().count() == 1) {
            LocalVariable lv = lvc.localVariableStream().findFirst().orElseThrow();
            return lv.assignmentExpression() == null ? null : lv;
        }
        if (statement instanceof ExpressionAsStatement && statement.expression() instanceof Assignment a
            && a.assignmentOperator() == null && a.variableTarget() instanceof LocalVariable lv) {
            return lv;
        }
        return null;
    }

    /*
     Kotlin: the local of such a dead library null is still declared nullable, though its null flows nowhere: the
     platform value checked into a non-null local throws on the very null the next statement handles. fernflower's
     ExprProcessor.writeNestedTypeAnnotations 'path = helper.getPaths().peek(); if (path == null) {...; return false;}'
     printed 'val path = ...peek()!!' (ExprProcessor.kt:932: SingleClassesTest and JavaTypeAnnotationsDecompilerTest,
     6 tests, worktree-maddi-all 2026-10-23). After the exit Kotlin smart-casts it, also across an if/else merge whose
     other branch assigns a non-null value. Gate NOGUARDEDLOCALS.
     */
    final Set<Object> guardedLocals = new HashSet<>();

    private static final Switches.Gate GUARDED_LOCALS_OFF = Switches.gate("NOGUARDEDLOCALS");

    // gate NODEADNULLS: a library null assigned to a local seeds it even when the next statement exits on it (A/B)
    static final Switches.Gate DEAD_NULLS_OFF = Switches.gate("NODEADNULLS");

    void syntacticSeeds(MethodInfo mi, Scope scope, Statement statement) {
        readScope = new ReadScope(mi, scope);
        seedStatement = statement;
        try {
            syntacticSeedsIn(mi, scope, statement);
        } finally {
            readScope = null;
        }
    }

    private void syntacticSeedsIn(MethodInfo mi, Scope scope, Statement statement) {
        nestedAssignments(mi, scope, statement);
        if (statement instanceof ReturnStatement rs && !mi.isConstructor() && !mi.returnType().isVoid()) {
            callResult(mi, rs.expression());
            seeds.seedCreated(mi, rs.expression(), mi);
            constructorCopies(mi, scope, mi, rs.expression());
        } else if (statement instanceof LocalVariableCreation lvc) {
            lvc.localVariableStream().forEach(lv -> {
                Object local = nodes.local(mi, scope, lv.simpleName());
                if (lv.assignmentExpression() instanceof NullConstant) {
                    seeds.seed(local, "initialized null in " + mi.fullyQualifiedName(), mi.typeInfo());
                }
                callResult(local, lv.assignmentExpression());
                seeds.seedCreated(local, lv.assignmentExpression(), mi);
                constructorCopies(mi, scope, local, lv.assignmentExpression());
            });
        } else if (statement.expression() instanceof Assignment a && a.variableTarget() != null) {
            Object target = nodes.assignmentTarget(mi, scope, a.variableTarget());
            if (a.value() instanceof NullConstant) seeds.seed(target, "assigned null in " + mi.fullyQualifiedName(), mi.typeInfo());
            callResult(target, a.value());
            seeds.seedCreated(target, a.value(), mi);
            constructorCopies(mi, scope, target, a.value());
        } else if (statement instanceof ForEachStatement fe && fe.initializer() != null) {
            // for (T x : array): the elements flow into x
            Expression iterated = NonNullFacts.unwrap(fe.expression());
            Object array = iterated instanceof VariableExpression ve ? nodes.node(mi, scope, ve.variable())
                    : iterated instanceof MethodCall mc && mc.methodInfo() != null && index.analysed.contains(mc.methodInfo())
                    ? mc.methodInfo() : null;
            // Kotlin: a library view ('for (Map.Entry<K, V> e : data.entrySet())') holds the receiver's data: the loop
            // variable's slots are the map's, so 'e.setValue(null)' writes it (jenkins DiskSpaceMonitorDescriptor.kt:38,
            // 2026-10-22). Gate NOVIEWLOOPS.
            if (array == null && policy.callResults() && !VIEW_LOOPS_OFF.get()) array = viewArgument(mi, scope, iterated);
            ParameterizedType type = array == null ? null : graph.typeOf(array);
            if (type != null && (type.arrays() > 0 || type.parameters().size() == 1)) {
                // over an array its elements, over an Iterable<T> its one type argument; the statement's own scope
                // declares the loop variable
                Object elements = type.arrays() > 0 ? new Content(array) : new Arg(array, 0);
                boolean view = !(iterated instanceof VariableExpression) && !(array instanceof MethodInfo);
                fe.initializer().localVariableStream().forEach(lv -> {
                    Object local = nodes.local(mi, scope, lv.simpleName());
                    graph.addEdge(elements, local);
                    if (view && !VIEW_LOOP_WRITES_OFF.get()) graph.viewLoopReads.add(List.of(elements, local));
                });
            }
        }
    }

    // an assignment inside an expression: 'if ((res = isHead(h)) != null)', 'while ((x = next()) != null)'
    private void nestedAssignments(MethodInfo mi, Scope scope, Statement statement) {
        Expression top = statement.expression();
        if (top == null) return;
        top.visit(e -> {
            if (e instanceof Lambda) return false;
            if (e instanceof Assignment a && a != top && a.variableTarget() != null && a.assignmentOperator() == null) {
                Object target = nodes.assignmentTarget(mi, scope, a.variableTarget());
                if (a.value() instanceof NullConstant) seeds.seed(target, "assigned null in " + mi.fullyQualifiedName(), mi.typeInfo());
                callResult(target, a.value());
                seeds.seedCreated(target, a.value(), mi);
                nestedCopy(mi, scope, statement, target, a.value());
            }
            return true;
        });
    }

    /*
     Kotlin: a variable copied by an assignment inside an expression ('while ((queuedTask = nextTask) != null)', guava
     ExecutionSequencer) takes the value as it is; the null test comes after. The links leave that copy out, and the
     local printed 'Runnable' (worktree-maddi-all 2026-10-17).
     */
    private void nestedCopy(MethodInfo mi, Scope scope, Statement statement, Object target, Expression value) {
        if (!policy.callResults() || NESTED_ASSIGNMENTS_OFF.get() || target == null
            || !(NonNullFacts.unwrap(value) instanceof VariableExpression ve)) return;
        Object source = nodes.node(mi, scope, ve.variable());
        if (source == null) return;
        if (facts.nonNull(value, facts.before(statement))) graph.flows.add(List.of(source, target));
        else graph.addEdge(source, target);
    }

    /*
     'new ArrayList<>(c)', 'new HashMap<>(m)': a library constructor copying the content of its argument into the
     new object, which is the target's ('l = new ArrayList<>(c)'): the argument's slots flow into the target's.
     */
    private void constructorCopies(MethodInfo mi, Scope scope, Object target, Expression value) {
        if (target == null || !(NonNullFacts.unwrap(value) instanceof ConstructorCall cc) || cc.constructor() == null
            || cc.parameterizedType() == null || cc.parameterizedType().arrays() > 0) return;
        contentCopies(mi, scope, target, cc.constructor(), cc.parameterExpressions());
        // an analysed generic class: an argument to a parameter typed with the class's type variables inside a
        // generic type ('Factory(Collection<E> set)') instantiates them with the new object's, i.e. the target's,
        // slots (instantiatedSlots)
        MethodInfo constructor = cc.constructor();
        ParameterizedType targetType = graph.typeOf(target);
        if (!index.analysed.contains(constructor) || targetType == null || constructor.typeInfo().typeParameters().isEmpty()) {
            return;
        }
        List<ParameterInfo> parameters = constructor.parameters();
        List<Expression> arguments = cc.parameterExpressions();
        for (int i = 0; i < arguments.size() && i < parameters.size(); i++) {
            ParameterizedType formal = parameters.get(i).parameterizedType();
            if (formal.arrays() > 0 || formal.parameters().isEmpty()) continue;
            Object argument = nodes.argumentNode(mi, scope, arguments.get(i));
            if (argument == null) argument = viewArgument(mi, scope, arguments.get(i));
            if (argument == null) continue;
            ParameterizedType type = graph.typeOf(argument);
            instantiatedSlots(argument, type != null ? type : arguments.get(i).parameterizedType(), parameters.get(i),
                    formal, target, targetType, constructor.typeInfo(), true);
        }
    }

    /*
     Kotlin: a library view passed as the argument ('new ProviderChain<>(installStateFilterChain.iterator())'): a node of
     its own (CallResult), its slots tied to the receiver's (libraryView). The list's 'Function<…, InstallState?>' then
     instantiates the chain's T (jenkins InstallUtil.kt:62, 2026-10-20). Gate NOVIEWARGUMENTS.
     */
    Object viewArgument(MethodInfo mi, Scope scope, Expression argument) {
        if (VIEW_ARGUMENTS_OFF.get() || !(NonNullFacts.unwrap(argument) instanceof MethodCall mc) || mc.methodInfo() == null
            || index.analysed.contains(mc.methodInfo()) || mc.methodInfo().isStatic() || mc.concreteReturnType() == null) {
            return null;
        }
        CallResult result = new CallResult(mc);
        ReadScope outer = readScope;
        if (outer == null) readScope = new ReadScope(mi, scope); // a call site's argument (callSite has none)
        try {
            libraryView(result, mc, 0);
        } finally {
            readScope = outer;
        }
        return result;
    }

    private static final Switches.Gate VIEW_ARGUMENTS_OFF = Switches.gate("NOVIEWARGUMENTS");
    static final Switches.Gate VIEW_METHOD_ARGUMENTS_OFF = Switches.gate("NOVIEWMETHODARGUMENTS");

    /*
     A parameter whose type arguments are the declaring class's type variables ('addAll(Collection<? extends E>)',
     'putAll(Map<? extends K, ? extends V>)', 'ArrayList(Collection<? extends E>)'): the argument's slot j flows into
     the receiver's slot of that variable. Slot to slot: the content of one object copied into another.
     */
    void contentCopies(MethodInfo mi, Scope scope, Object receiver, MethodInfo callee,
                       List<Expression> arguments) {
        ParameterizedType type = graph.typeOf(receiver);
        int arity = callee.typeInfo().typeParameters().size();
        if (type == null || type.arrays() > 0 || arity == 0 || type.parameters().size() != arity) return;
        List<ParameterInfo> parameters = callee.parameters();
        for (int i = 0; i < arguments.size() && i < parameters.size(); i++) {
            ParameterizedType pt = parameters.get(i).parameterizedType();
            if (pt.arrays() > 0 || pt.parameters().isEmpty()) continue;
            for (int j = 0; j < pt.parameters().size(); j++) {
                ParameterizedType a = pt.parameters().get(j);
                if (a.typeParameter() == null || a.typeParameter().isMethodTypeParameter()
                    || a.typeParameter().getOwner().getLeft() != callee.typeInfo()) continue;
                int index = a.typeParameter().getIndex();
                if (index >= arity) continue;
                for (Object source : slotSources(mi, scope, arguments.get(i), j)) {
                    graph.addEdge(source, new Arg(receiver, index));
                }
            }
        }
    }

    /*
     Substitution. A call on a receiver of generic class C instantiates C's type variables with the receiver's type
     arguments, so a position typed by one of them denotes the receiver's slot for it. 'tmpSet = factory.spawnEmptySet()'
     with 'FastFixedSet<E> spawnEmptySet()' in Factory<E>: tmpSet's argument 0 is the factory's argument 0 (fernflower
     DomHelper). 'tmpSet.union(set)' with 'union(FastFixedSet<E> set)': the argument's argument 0 is the receiver's.
     'new Factory<>(lstStats)' with 'Factory(Collection<E> set)': lstStats' argument 0 is the new object's, which is the
     target's. Before, coupleContent tied such a slot to the declaration's own type-variable slot, which made 'E'
     nullable for the whole class (FastFixedSetFactory.kt:19/33, 'FastFixedSet<E?>').

     Directions: the receiver's slot flows into a result's, and an argument's slot flows into the receiver's (content
     copied in): real flows, always. The opposite directions are Kotlin's invariance (Policy.assertContentWrites, as
     for arrays): 'tmpSet.add(x)' with a nullable x needs 'FastFixedSetFactory<Statement?>'. Not into a '? extends'
     position, which only receives; and not where the class's own null makes the position 'E?' ('List<E> all() {
     ...; l.add(null); return l; }'): a 'List<Node?>' then matches a Factory<Node> already. That is decided after the
     graph is built (invariantTies, in once). A position typed by another type variable (a method's, another class's)
     is skipped; a concrete generic position ('Map<K, List<V>>') recurses.
     */
    private void instantiatedSlots(Object concrete, ParameterizedType concreteType, Object formalNode,
                                   ParameterizedType formal, Object receiver, ParameterizedType receiverType,
                                   TypeInfo owner, boolean intoReceiver) {
        if (concrete == null || concreteType == null || formal == null || concreteType.arrays() > 0
            || formal.arrays() > 0 || concreteType.parameters().size() != formal.parameters().size()) return;
        for (int i = 0; i < formal.parameters().size(); i++) {
            ParameterizedType f = formal.parameters().get(i);
            ParameterizedType c = concreteType.parameters().get(i);
            Arg slot = new Arg(concrete, i);
            Arg formalSlot = new Arg(formalNode, i);
            if (f.typeParameter() != null) {
                if (f.arrays() > 0 || f.typeParameter().isMethodTypeParameter()
                    || !f.typeParameter().getOwner().isLeft() || f.typeParameter().getOwner().getLeft() != owner) {
                    continue;
                }
                int j = slotIndex(receiverType, owner, f.typeParameter().getIndex());
                if (j < 0) continue;
                Arg receiverSlot = new Arg(receiver, j);
                // Kotlin: a position declared nullable ('Predicate<@Nullable T> equivalentTo(...)') is 'T?' for any
                // instantiation: it ties nothing back into the receiver's slot. EquivalenceTest's 'equalTo1.apply(null)'
                // on a Predicate<@Nullable Object> had made Equivalence.equals() an Equivalence<Any?>, which
                // Equals (an Equivalence<Object>) is not (Equivalence.kt:98/99, 2026-10-18)
                boolean declaredNullable = policy.callResults() && !NullGraph.NULLABLE_PARAMETER_SLOTS_OFF.get()
                                           && explicit(f.annotations()) == NullableState.NULLABLE;
                boolean receivesOnly = isExtendsWildcard(f) || declaredNullable;
                boolean consumes = f.wildcard() != null && f.wildcard().isSuper(); // 'sort(Comparator<? super E>)'
                // Kotlin (Policy.callResults): the class's own null in that position ('List<E> all() { ...;
                // l.add(null); return l; }') reaches this instantiation, and the receiver's slot flows into a result
                // ('multimap.keySet()' holds the multimap's keys) or into a consumer argument. Real flows, but for
                // Java annotations they carried guava's flow-insensitive '@Nullable Object key' (AsMap.get) through
                // the ImmutableSet<E> override hub into the EMPTY singletons: +246 noise (2026-10-09). The Java
                // policies keep the ties the links give them, as before.
                if (policy.callResults()) graph.addEdge(formalSlot, slot);
                if (intoReceiver) {
                    if (consumes) {
                        if (policy.callResults()) graph.addEdge(receiverSlot, slot);
                    } else {
                        graph.addEdge(slot, receiverSlot);
                        if (policy.assertContentWrites() && !receivesOnly) {
                            graph.invariantTies.add(new Object[]{receiverSlot, slot, formalSlot});
                        }
                    }
                } else {
                    if (policy.callResults()) graph.addEdge(receiverSlot, slot);
                    if (policy.assertContentWrites() && !receivesOnly) {
                        graph.invariantTies.add(new Object[]{slot, receiverSlot, formalSlot});
                    }
                }
            } else if (!f.parameters().isEmpty() && c.typeParameter() == null && c.arrays() == 0) {
                instantiatedSlots(slot, c, formalSlot, f, receiver, receiverType, owner, intoReceiver);
            }
        }
    }

    // the arguments of a call whose parameters are typed with the callee's class type variables inside a generic type
    void instantiatedArguments(MethodInfo mi, Scope scope, Expression call, MethodInfo callee,
                               List<Expression> arguments) {
        if (!(call instanceof MethodCall mc) || callee.typeInfo().typeParameters().isEmpty()) return;
        Object receiver = nodes.receiverNode(mi, scope, mc);
        ParameterizedType receiverType = receiver == null ? null : graph.typeOf(receiver);
        if (receiverType == null) return;
        List<ParameterInfo> parameters = callee.parameters();
        for (int i = 0; i < arguments.size() && i < parameters.size(); i++) {
            ParameterizedType formal = parameters.get(i).parameterizedType();
            if (formal.arrays() > 0 || formal.parameters().isEmpty()) continue;
            Object argument = nodes.argumentNode(mi, scope, arguments.get(i));
            if (argument == null) continue;
            ParameterizedType type = graph.typeOf(argument);
            instantiatedSlots(argument, type != null ? type : arguments.get(i).parameterizedType(), parameters.get(i),
                    formal, receiver, receiverType, callee.typeInfo(), true);
        }
    }

    /*
     Streams. The pass has no node for a stream; it follows the pipeline to the nodes whose values are its elements:
     'c.stream()' (the collection's slot), 'Arrays.stream(a)', 'Stream.of(x, y)'; through filter, sorted, distinct, …;
     through 'map(t -> t.f)' to the lambda body's node (a field, a call result, a slot read) or a method reference's
     method. A terminal 'toList()', 'collect(Collectors.toList() / toSet() / toCollection(…) / toMap(k, v))' or
     'findFirst()' then makes those nodes values of the result's slot. Fernflower's SwitchPatternHelper carries
     'List<@Nullable Exprent>' elements through two such pipelines and two records into 'FullCase.exprents'.
     */
    private static final Set<String> STREAM_PASS_THROUGH = Set.of("filter", "sorted", "distinct", "limit", "skip",
            "peek", "parallel", "sequential", "unordered", "onClose", "boxed", "takeWhile", "dropWhile");

    private static boolean isStreamType(MethodInfo mi) {
        return mi.typeInfo().fullyQualifiedName().startsWith("java.util.stream.");
    }

    // the nodes whose values are the elements of a stream expression; empty when the pass does not model it
    private List<Object> streamElements(MethodInfo mi, Scope scope, Expression expression) {
        if (expression == null) return List.of();
        Expression e = NonNullFacts.unwrap(expression);
        if (!(e instanceof MethodCall mc) || mc.methodInfo() == null) return List.of();
        MethodInfo callee = mc.methodInfo();
        String name = callee.name();
        List<Expression> args = mc.parameterExpressions();
        if (isStreamType(callee)) {
            // Kotlin: 'filter(Objects::nonNull)', 'filter(x -> x != null)' lets no null through (jenkins CspBuilder.kt:52,
            // Jenkins.kt:619, 2026-10-21). Gate NONULLFILTERS.
            if ("filter".equals(name) && args.size() == 1 && nullFilter(args.getFirst())) return List.of();
            if (STREAM_PASS_THROUGH.contains(name)) return streamElements(mi, scope, mc.object());
            if (("map".equals(name) || "mapToObj".equals(name)) && args.size() == 1) {
                return mapped(mi, scope, mc.object(), args.getFirst());
            }
            if ("of".equals(name) && callee.isStatic()) {
                List<Object> nodes = new ArrayList<>();
                for (Expression a : args) {
                    Object n = this.nodes.argumentNode(mi, scope, a);
                    if (n != null) nodes.add(n);
                }
                return nodes;
            }
            if ("concat".equals(name) && callee.isStatic() && args.size() == 2) {
                List<Object> nodes = new ArrayList<>(streamElements(mi, scope, args.get(0)));
                nodes.addAll(streamElements(mi, scope, args.get(1)));
                return nodes;
            }
            return List.of();
        }
        if (("stream".equals(name) || "parallelStream".equals(name)) && callee.parameters().isEmpty()
            && mc.object() != null && !callee.isStatic()) {
            Object slot = this.nodes.slotOf(mi, scope, mc.object(), 0);
            return slot == null ? List.of() : List.of(slot);
        }
        if ("java.util.Arrays".equals(callee.typeInfo().fullyQualifiedName()) && "stream".equals(name)
            && !args.isEmpty()) {
            Object array = this.nodes.argumentNode(mi, scope, args.getFirst());
            return array == null ? List.of() : List.of(new Content(array));
        }
        return List.of();
    }

    private static final Switches.Gate NULL_FILTERS_OFF = Switches.gate("NONULLFILTERS");

    // a predicate that holds only for a non-null element: 'Objects::nonNull', 'x -> x != null', 'x -> null != x'
    boolean nullFilter(Expression predicate) {
        if (NULL_FILTERS_OFF.get() || !policy.callResults()) return false;
        Expression p = NonNullFacts.unwrap(predicate);
        if (p instanceof MethodReference mr && mr.methodInfo() != null) {
            return "nonNull".equals(mr.methodInfo().name())
                   && "java.util.Objects".equals(mr.methodInfo().typeInfo().fullyQualifiedName());
        }
        if (!(p instanceof Lambda lambda) || lambda.methodInfo() == null || lambda.methodInfo().parameters().size() != 1) {
            return false;
        }
        if (lambda.singleExpression() == null) return false;
        Expression body = NonNullFacts.unwrap(lambda.singleExpression());
        if (!(body instanceof BinaryOperator bo) || !NonNullFacts.isOperator(bo, "!=")) return false;
        ParameterInfo element = lambda.methodInfo().parameters().getFirst();
        Expression l = NonNullFacts.unwrap(bo.lhs());
        Expression r = NonNullFacts.unwrap(bo.rhs());
        return l instanceof NullConstant && r instanceof VariableExpression rv && element.equals(rv.variable())
               || r instanceof NullConstant && l instanceof VariableExpression lv && element.equals(lv.variable());
    }

    // the nodes a mapping function ('map(t -> t.f)', 'map(Foo::bar)') produces from the elements of 'source'
    private List<Object> mapped(MethodInfo mi, Scope scope, Expression source, Expression function) {
        Expression f = NonNullFacts.unwrap(function);
        if (f instanceof MethodReference mr && mr.methodInfo() != null) {
            MethodInfo m = mr.methodInfo();
            return !m.isConstructor() && !m.returnType().isVoid() && index.analysed.contains(m) ? List.of(m) : List.of();
        }
        if (!(f instanceof Lambda lambda) || lambda.methodInfo() == null) return List.of();
        Expression body = lambda.singleExpression();
        if (body == null && lambda.methodBody() != null && lambda.methodBody().statements().size() == 1
            && lambda.methodBody().statements().getFirst() instanceof ReturnStatement rs) {
            body = rs.expression();
        }
        if (body == null) return List.of();
        Expression value = NonNullFacts.unwrap(body);
        // the element itself ('map(t -> t)'): the source's elements
        if (value instanceof VariableExpression ve && ve.variable() instanceof ParameterInfo pi
            && lambda.methodInfo().equals(pi.methodInfo())) {
            return streamElements(mi, scope, source);
        }
        Object node = nodes.argumentNode(lambda.methodInfo(), scope, body);
        return node == null ? List.of() : List.of(node);
    }

    // a terminal operation: slot index of its result -> the nodes whose values it collects there; empty otherwise
    Map<Integer, List<Object>> streamTerminal(MethodInfo mi, Scope scope, Expression expression) {
        Expression e = NonNullFacts.unwrap(expression);
        if (!(e instanceof MethodCall mc) || mc.methodInfo() == null || mc.object() == null
            || !isStreamType(mc.methodInfo())) return Map.of();
        String name = mc.methodInfo().name();
        List<Expression> args = mc.parameterExpressions();
        List<Object> elements;
        switch (name) {
            case "toList", "findFirst", "findAny", "min", "max" -> elements = streamElements(mi, scope, mc.object());
            case "collect" -> {
                if (args.size() != 1 || !(NonNullFacts.unwrap(args.getFirst()) instanceof MethodCall collector)
                    || collector.methodInfo() == null
                    || !"java.util.stream.Collectors".equals(collector.methodInfo().typeInfo().fullyQualifiedName())) {
                    return Map.of();
                }
                List<Expression> cargs = collector.parameterExpressions();
                switch (collector.methodInfo().name()) {
                    case "toList", "toSet", "toUnmodifiableList", "toUnmodifiableSet", "toCollection" ->
                            elements = streamElements(mi, scope, mc.object());
                    case "toMap", "toUnmodifiableMap" -> {
                        if (cargs.size() < 2) return Map.of();
                        Map<Integer, List<Object>> slots = new LinkedHashMap<>();
                        List<Object> keys = mapped(mi, scope, mc.object(), cargs.get(0));
                        List<Object> values = mapped(mi, scope, mc.object(), cargs.get(1));
                        if (!keys.isEmpty()) slots.put(0, keys);
                        if (!values.isEmpty()) slots.put(1, values);
                        return slots;
                    }
                    default -> {
                        return Map.of();
                    }
                }
            }
            default -> {
                return Map.of();
            }
        }
        return elements.isEmpty() ? Map.of() : Map.of(0, elements);
    }

    // the nodes whose values are slot j of what an expression denotes: slotOf, or a stream terminal's elements
    private List<Object> slotSources(MethodInfo mi, Scope scope, Expression expression, int j) {
        Object slot = nodes.slotOf(mi, scope, expression, j);
        if (slot != null) return List.of(slot);
        return streamTerminal(mi, scope, expression).getOrDefault(j, List.of());
    }

    // a call's result used directly: a library method that may return null, or (Policy.contracts) an analysed one
    // annotated nullable, seeds. Neither leaves a null marker in the link summary. NOT a general edge from every
    // analysed callee's return: on guava that carried the flow-insensitivity of 'v = get(k); if (v == null) ...'
    // into the callers, +947 noise for -27 unsafe (2026-10-06); to be revisited with the use-site pass (M4).
    private void callResult(Object target, Expression value) {
        for (Expression alternative : alternatives(value)) callResultOf(target, alternative);
    }

    private void callResultOf(Object target, Expression value) {
        Expression unwrapped = value instanceof Cast c ? c.expression() : value;
        if (target == null || !(unwrapped instanceof MethodCall mc) || mc.methodInfo() == null) return;
        MethodInfo callee = mc.methodInfo();
        if (readScope != null) factorySlots(target, value, readScope.mi());
        if (policy.callResults() && readScope != null) {
            for (Expression source : passedThrough(mc)) {
                Object node = this.nodes.argumentNode(readScope.mi(), readScope.scope(), source);
                if (node != null) graph.flows.add(List.of(node, target)); // Kotlin infers one type: content slots tied
            }
            if (readScope != null) {
                callTargets.computeIfAbsent(mc, _ -> new ArrayList<>())
                        .add(new CallTarget(target, readScope.mi(), readScope.scope()));
            }
            typeArgumentsPassedThrough(mc).forEach((source, k) -> {
                // Kotlin: a lambda in a functional position whose result is T ('<T> T ifOverridden(Supplier<T>, …)')
                // returns the call's result (jenkins CrumbIssuer.kt:60, 2026-10-21). Gate NOLAMBDATYPEVARRESULTS.
                if (!LAMBDA_TYPE_VARIABLE_RESULTS_OFF.get() && NonNullFacts.unwrap(source) instanceof Lambda lambda
                    && lambda.methodInfo() != null && lambdaResultsPosition(lambda, k)) {
                    graph.addEdge(lambda.methodInfo(), target);
                    return;
                }
                Object node = this.nodes.argumentNode(readScope.mi(), readScope.scope(), source);
                if (node != null) graph.flows.add(List.of(new Arg(node, k), target));
                // Kotlin: from a '?' argument the result is the star projection's value, Any? ('Object v =
                // getUninterruptibly(future)' on a ListenableFuture<?>: guava AbstractFuture.getFutureValue then tests
                // 'v == null', AbstractFuture.kt:346, 2026-10-18). Gate NOSTARRESULTS.
                ParameterizedType st = node == null ? null : graph.typeOf(node);
                if (!STAR_RESULTS_OFF.get() && st != null && k < st.parameters().size()
                    && st.parameters().get(k).wildcard() != null) {
                    graph.addEdge(new Arg(node, k), target);
                }
            });
            arrayResultElements(target, mc);
            libraryView(target, mc, 0);
            if (!ARRAY_ELEMENTS_PASSED_OFF.get()) {
                List<Expression> calls = new ArrayList<>();
                calls.add(mc);
                for (Expression source : passedThrough(mc)) {
                    if (NonNullFacts.unwrap(source) instanceof MethodCall inner) calls.add(inner);
                }
                for (Expression c : calls) {
                    MethodCall arrayCall = (MethodCall) NonNullFacts.unwrap(c);
                    // a library method only: an analysed one's parameter elements are tied to every caller's
                    // array, and its body decides its result ('ImmutableList.copyOf(E[])' had carried the tests'
                    // nulls into Types.TypeVariableImpl.bounds, Files.FileByteSink.modes; guava 2026-10-17)
                    if (index.analysed.contains(arrayCall.methodInfo())) continue;
                    arrayElementsPassedThrough(arrayCall).forEach((source, k) -> {
                        Object node = this.nodes.argumentNode(readScope.mi(), readScope.scope(), source);
                        if (node == null) return;
                        Arg slot = new Arg(target, k);
                        graph.addEdge(new Content(node), slot);
                        // a local array of the method's own type variable: its null is the slot's, not an
                        // instantiation's. Not a parameter's array, whose elements are each caller's: through
                        // 'ImmutableSet.copyOf(E[])' and 'Iterators.forArray(T...)' the tests' nulls had made
                        // their results ImmutableSet<E?> and UnmodifiableIterator<T?> (guava 2026-10-17)
                        ParameterizedType type = graph.typeOf(node);
                        if (node instanceof Local && type != null && type.arrays() == 1
                            && isTypeVariable(type.copyWithOneFewerArrays())) {
                            graph.typeVariableElementSlots.add(slot);
                        }
                    });
                }
            }
        }
        if (index.analysed.contains(callee) && !callee.isConstructor() && !callee.returnType().isVoid()) {
            ParameterizedType rt = callee.returnType();
            // the same object: its content slots are one (coupleContent); not where the callee's type arguments are
            // type variables, which each call instantiates anew. An array's elements are the callee's whatever its
            // component's type arguments ('Node<K, V>[] createTable(int n) { return new Node[n]; }': guava
            // HashBiMap.hashTableVToK, ImmutableMapEntry.createEntryArray), unless the component is the callee's own
            // type variable ('<T> T[] toArray(T[] a)'), which would tie the arrays of all its callers
            if (concreteArguments(rt) || rt.arrays() > 0 && !isMethodTypeVariable(rt.componentType())
                || policy.callResults() && readScope != null && sameInstantiation(mc, readScope.mi())) {
                graph.flows.add(List.of(callee, target));
            } else if (policy.callResults() && !GENERIC_RESULT_SLOTS_OFF.get() && rt.arrays() == 0) {
                // Kotlin: a slot whose argument is not a type variable has its own nullability whatever the
                // instantiation: 'Map<Descriptor<T>, T> toMap(…)' with a nullable key makes 'return toMap(properties)'
                // a Map<Descriptor<UserProperty>?, UserProperty> (jenkins User.kt:143, 2026-10-21). Gate
                // NOGENERICRESULTSLOTS.
                ParameterizedType tt = graph.typeOf(target);
                for (int j = 0; j < rt.parameters().size(); j++) {
                    ParameterizedType a = rt.parameters().get(j);
                    if (a.typeParameter() == null && a.wildcard() == null && tt != null && tt.arrays() == 0
                        && j < tt.parameters().size()) {
                        graph.addEdge(new Arg(callee, j), new Arg(target, j));
                    }
                }
            }
            // a class type variable's own null ('E getWithKey(K k) { ... return null; }', E?) holds for every
            // instantiation; so does a method type variable's, unless a parameter of that very type can carry the
            // caller's null back out ('<X> X id(X x)'): '<T> T getAttribute(Key<T> key)' returns a map's lookup
            boolean ownNull = rt.arrays() == 0 && rt.typeParameter() != null
                              && (!rt.typeParameter().isMethodTypeParameter()
                                  || callee.parameters().stream().noneMatch(pi ->
                                         rt.typeParameter().equals(pi.parameterizedType().typeParameter())));
            if (policy.callResults() && (!isTypeVariable(rt) || ownNull && !parametricNullness(callee))) {
                if (NullGraph.GAP9_OFF.get() || callee.parameters().isEmpty()) graph.addEdge(callee, target);
                else graph.gatedSites.add(new GatedSite(callee, target, graph.callId(mc, callee)));
            }
            // a result typed with the callee's class type variables inside a generic type ('FastFixedSet<E>
            // spawnEmptySet()'): those positions are the receiver's slots (instantiatedSlots)
            if (readScope != null && !callee.isStatic() && rt.arrays() == 0 && !rt.parameters().isEmpty()
                && !concreteArguments(rt)) {
                Object receiver = this.nodes.receiverNode(readScope.mi(), readScope.scope(), mc);
                ParameterizedType receiverType = receiver == null ? null : graph.typeOf(receiver);
                if (receiverType != null) {
                    instantiatedSlots(target, graph.typeOf(target), callee, rt, receiver, receiverType, callee.typeInfo(),
                            false);
                }
            }
        }
        if (readScope != null) {
            // a stream's terminal operation: its elements are values of the result's slot
            streamTerminal(readScope.mi(), readScope.scope(), unwrapped)
                    .forEach((j, nodes) -> nodes.forEach(n -> graph.addEdge(n, new Arg(target, j))));
        }
        // Kotlin: 'collect(LinkedHashMap::new, (map, plugin) -> map.put(plugin, url), Map::putAll)' returns the
        // container its accumulator fills: the lambda's first parameter is the result, slots and all (jenkins
        // PluginManager.kt:1532, 2026-10-21). Gate NOCOLLECTCONTAINERS.
        if (policy.callResults() && !COLLECT_CONTAINERS_OFF.get() && isStreamType(callee) && "collect".equals(callee.name())
            && mc.parameterExpressions().size() == 3
            && NonNullFacts.unwrap(mc.parameterExpressions().get(1)) instanceof Lambda accumulator
            && accumulator.methodInfo() != null && !accumulator.methodInfo().parameters().isEmpty()) {
            graph.flows.add(List.of(accumulator.methodInfo().parameters().getFirst(), target));
        }
        if (readScope != null && this.nodes.argumentNode(readScope.mi(), readScope.scope(), unwrapped) instanceof Arg slot) {
            if (!index.analysed.contains(callee)) {
                graph.flows.add(List.of(slot, target)); // 'x = map.get(k)': x is the map's value, its slots are the value's
            }
            // Kotlin: the value read is the receiver's slot ('block = blocks.getWithKey(j)', 'st = stats.get(0)' on a
            // VBStyleCollection<Statement?, ...>): a nullable slot gives a nullable value
            if (policy.callResults()) graph.addEdge(slot, target);
        }
        // 'x = map.get(k)' with k known present (NonNullFacts, CodeLaser/maddi-mod#22 gap 1): not the absent key's null; what
        // the map holds for k still flows through its value slot above
        String lib = contracts.libraryNullableCall(mc);
        if (lib != null) {
            // not a member Kotlin itself types nullable ('Map.get(k): V?'): the local must then be declared so
            if (!(target instanceof Local && seedStatement != null && deadNullAssignments.contains(seedStatement)
                  && java.util.stream.Stream.concat(java.util.stream.Stream.of(callee), overrides(callee).stream())
                          .noneMatch(m -> Contracts.KOTLIN_COLLECTIONS.contains(m.typeInfo().fullyQualifiedName())))) {
                seeds.seedIndirect(target, "assigned " + lib);
            } else if (!GUARDED_LOCALS_OFF.get()) {
                guardedLocals.add(target);
            }
        } else if (policy.contracts() && index.analysed.contains(callee)
                   && contracts.explicitState(callee) == NullableState.NULLABLE) {
            seeds.seed(target, "assigned " + callee.fullyQualifiedName() + ", annotated nullable");
        }
    }

    /*
     Kotlin: an argument to a method's own type variable ('<E> Entry<E> immutableEntry(E e, int n)'). Kotlin's
     declaration decides what a null argument does. A parameter the method passes on unchecked, into its result or
     into another class's type variable ('new ImmutableEntry<>(e)'), is declared 'e: E': a null argument instantiates
     E for that call, so the call's result (or the result's slot E) is nullable, not the parameter. Through the
     parameter, MultimapKeysTester's 'immutableEntry(null, 3)' had made ImmutableEntry.element, getElement() and
     Multiset.Entry.getElement() 'E?' (guava Multisets.kt:547, 2026-10-17). A parameter the method checks
     ('ensureNotNull(T object, ...)', 'getOrDefault(T value, T defaultValue)'s value) is declared 'T?' and takes the
     argument as before. Decided once the graph is built, from the edges that leave the parameter.
     Gate NOMETHODTYPEVARARGS.
     */
    record TypeVariableArgument(ParameterInfo parameter, Expression call, int index, MethodInfo mi,
                                Scope scope, Runnable asBefore) {
    }

    private record CallTarget(Object target, MethodInfo mi, Scope scope) {
    }

    final List<TypeVariableArgument> typeVariableArguments = new ArrayList<>();
    private final Map<Expression, List<CallTarget>> callTargets = new IdentityHashMap<>();
    private final Map<ParameterInfo, Boolean> passedOnUnchecked = new HashMap<>();

    void resolveTypeVariableArguments() {
        for (TypeVariableArgument tva : typeVariableArguments) {
            if (!passedOnUnchecked.computeIfAbsent(tva.parameter(), this::passedOnUnchecked)
                && !parametricParameter(tva.parameter())) {
                tva.asBefore().run();
                continue;
            }
            MethodCall mc = (MethodCall) tva.call();
            ParameterizedType rt = mc.methodInfo().returnType();
            var typeParameter = tva.parameter().parameterizedType().typeParameter();
            for (CallTarget ct : callTargets.getOrDefault(mc, List.of())) {
                if (rt.arrays() == 0 && typeParameter.equals(rt.typeParameter())) {
                    inferredNull(ct.target(), ct, mc, tva.index());
                } else if (rt.arrays() == 0) {
                    for (int k = 0; k < rt.parameters().size(); k++) {
                        ParameterizedType slot = rt.parameters().get(k);
                        if (slot.wildcard() == null && typeParameter.equals(slot.typeParameter())) {
                            inferredNull(new Arg(ct.target(), k), ct, mc, tva.index());
                        }
                    }
                }
            }
        }
        typeVariableArguments.clear();
    }

    /*
     Kotlin: a '@ParametricNullness V' parameter is a plain 'V': a caller's null instantiates V, as for a parameter passed
     on unchecked. guava's TrustedListenableFutureTask.create(Runnable, @ParametricNullness V result), given null by
     Futures.submit(Runnable, Executor), printed 'result: V?', and 'Executors.callable(runnable, result)' then inferred
     a Callable<V?> (TrustedListenableFutureTask.kt:68, 2026-10-18). Gate NOPARAMETRICPARAMETERS.
     */
    private static boolean parametricParameter(ParameterInfo pi) {
        return !PARAMETRIC_PARAMETERS_OFF.get() && pi.annotations().stream().anyMatch(a -> a.typeInfo() != null
                && a.typeInfo().simpleName().equals("ParametricNullness"));
    }

    private static final Switches.Gate PARAMETRIC_PARAMETERS_OFF = Switches.gate("NOPARAMETRICPARAMETERS");

    // does the parameter's value leave the method unchecked: into its return, or into another class's type variable
    private boolean passedOnUnchecked(ParameterInfo pi) {
        MethodInfo method = pi.methodInfo();
        Set<Object> seen = new HashSet<>();
        Deque<Object> todo = new ArrayDeque<>(graph.successors.getOrDefault(pi, Set.of()));
        while (!todo.isEmpty() && seen.size() < 2000) {
            Object n = todo.removeFirst();
            if (!seen.add(n)) continue;
            if (n.equals(method)) return true;
            ParameterizedType type = graph.typeOf(n);
            if (type != null && isClassTypeVariable(type)) return true;
            if (n instanceof Local || n instanceof ParameterInfo p && p.methodInfo() == method) {
                todo.addAll(graph.successors.getOrDefault(n, Set.of()));
            }
        }
        return false;
    }

    private void inferredNull(Object target, CallTarget ct, MethodCall mc, int i) {
        Expression argument = NonNullFacts.unwrap(mc.parameterExpressions().get(i));
        if (argument instanceof NullConstant) {
            seeds.seed(target, Seeds.NULL_ARGUMENT + ct.mi().fullyQualifiedName(), ct.mi().typeInfo());
        } else if (argument instanceof VariableExpression ve) {
            if (facts.nonNullWhenCalled(mc, ve.variable())) return;
            Object node = nodes.node(ct.mi(), ct.scope(), ve.variable());
            if (node != null) graph.addEdge(node, target);
        } else if (argument instanceof MethodCall inner && inner.methodInfo() != null) {
            Object node = nodes.argumentNode(ct.mi(), ct.scope(), inner);
            if (node != null) graph.addEdge(node, target);
            String lib = contracts.libraryNullableCall(inner);
            if (lib != null) seeds.seedIndirect(target, "assigned " + lib);
        }
    }

    /*
     Kotlin: a generic method that hands an argument's type on ('<T> T ensureNotNull(T, String)', '<T> List<T>
     copy(List<? extends T>)', '<T> T getOrDefault(T, T)') gives its result the argument's type, type arguments
     included: Kotlin infers the method's type variable from the argument, and generic types are invariant. Those
     arguments (through nested such calls) are the values whose content slots the target's must equal (langchain4j
     EmbeddingModelRequestContext: 'textSegments = copy(ensureNotNull(builder.textSegments, ...))' is a
     List<TextSegment?> where the builder's is; DefaultContent: 'metadata = copy(metadata)').
     */
    private static List<Expression> passedThrough(MethodCall mc) {
        MethodInfo callee = mc.methodInfo();
        if (callee == null) return List.of();
        ParameterizedType rt = callee.returnType();
        List<ParameterInfo> parameters = callee.parameters();
        List<Expression> arguments = mc.parameterExpressions();
        List<Expression> out = new ArrayList<>();
        for (int i = 0; i < parameters.size() && i < arguments.size(); i++) {
            if (parameters.get(i).isVarArgs()) break;
            if (!sameInstantiation(rt, parameters.get(i).parameterizedType())) continue;
            Expression argument = arguments.get(i);
            List<Expression> nested = NonNullFacts.unwrap(argument) instanceof MethodCall inner
                    ? passedThrough(inner) : List.of();
            if (nested.isEmpty()) out.add(argument);
            else out.addAll(nested);
        }
        return out;
    }

    /*
     Kotlin: a generic method whose result is its type variable T, with a parameter that carries T as a type argument
     ('<T> T readValue(String, TypeReference<T>)', '<T> T convert(Object, Class<T>)'), infers T from that argument:
     the result's type is the argument's type argument k, and generic types are invariant, so their content slots are
     one (langchain4j DefaultStructuredPromptFactory: 'return OBJECT_MAPPER.readValue(json, MAP_TYPE)' with a
     'TypeReference<Map<String, Object>> MAP_TYPE' returned a MutableMap<String, Any?>, maddi-mod#38). Per argument,
     the position k.
     */
    private static Map<Expression, Integer> typeArgumentsPassedThrough(MethodCall mc) {
        MethodInfo callee = mc.methodInfo();
        if (callee == null || !isMethodTypeVariable(callee.returnType())) return Map.of();
        var typeVariable = callee.returnType().typeParameter();
        List<ParameterInfo> parameters = callee.parameters();
        List<Expression> arguments = mc.parameterExpressions();
        Map<Expression, Integer> out = new LinkedHashMap<>();
        for (int i = 0; i < parameters.size() && i < arguments.size(); i++) {
            if (parameters.get(i).isVarArgs()) break;
            ParameterizedType formal = parameters.get(i).parameterizedType();
            if (formal.arrays() > 0) continue;
            List<ParameterizedType> typeArguments = formal.parameters();
            for (int k = 0; k < typeArguments.size(); k++) {
                ParameterizedType a = typeArguments.get(k);
                if (a.arrays() == 0 && a.wildcard() == null && typeVariable.equals(a.typeParameter())) {
                    out.put(arguments.get(i), k);
                    break;
                }
            }
        }
        return out;
    }

    /*
     Kotlin: a generic method given an array of its type variable ('<T> List<T> asList(T... a)' passed the array
     itself) infers T from the array's elements: the result's slot of T holds them. guava Iterators.partitionImpl:
     'List<@Nullable T> list = unmodifiableList(asList(array))' with nulls written into 'array' printed a
     MutableList<T> (Iterators.kt:397, 2026-10-17). Per array argument, the result's position k.
     */
    private static Map<Expression, Integer> arrayElementsPassedThrough(MethodCall mc) {
        MethodInfo callee = mc.methodInfo();
        if (callee == null) return Map.of();
        ParameterizedType rt = callee.returnType();
        if (rt.arrays() > 0 || rt.parameters().isEmpty()) return Map.of();
        List<ParameterInfo> parameters = callee.parameters();
        List<Expression> arguments = mc.parameterExpressions();
        Map<Expression, Integer> out = new LinkedHashMap<>();
        for (int i = 0; i < parameters.size() && i < arguments.size(); i++) {
            ParameterizedType formal = parameters.get(i).parameterizedType();
            if (formal.arrays() != 1 || !isMethodTypeVariable(formal)) continue;
            if (parameters.get(i).isVarArgs() && !passesTheArray(arguments, parameters, i)) continue;
            for (int k = 0; k < rt.parameters().size(); k++) {
                ParameterizedType a = rt.parameters().get(k);
                if (a.arrays() == 0 && a.wildcard() == null && formal.typeParameter().equals(a.typeParameter())) {
                    out.put(arguments.get(i), k);
                    break;
                }
            }
        }
        return out;
    }

    /*
     Kotlin: a generic method returning its type variable's array, given an array of it (guava's
     'ObjectArrays.newArray(T[] reference, int length)'), infers T from that array: the
     result's elements are the argument's. guava ObjectArrays.concat: 'T[] result = newArray(array, n)' with an
     'array: Array<out T?>' returned an Array<T> (ObjectArrays.kt:30, 2026-10-17). From the caller's own array to its
     own target only, so no two callers are tied. Gate NOARRAYRESULTS.
     */
    private void arrayResultElements(Object target, MethodCall mc) {
        if (ARRAY_RESULTS_OFF.get() || readScope == null) return;
        MethodInfo callee = mc.methodInfo();
        // an analysed callee only: through 'Arrays.copyOf(castBuffer, n)' guava's TopKSelector.topK() returned a
        // List<T?>, where 'T[] castBuffer = buffer' is guava's own @SuppressWarnings("nullness") assertion. Nor in a
        // test: OrderingTest's 'newArray(strictlyOrderedElements, 0)' reached Ordering.max's 'E... rest', shared by
        // every caller, and made the min/max family's results nullable (guava 2026-10-17)
        if (!index.analysed.contains(callee) || testCode(readScope.mi())) return;
        ParameterizedType rt = callee.returnType();
        if (rt.arrays() != 1 || !isMethodTypeVariable(rt)) return;
        List<ParameterInfo> parameters = callee.parameters();
        List<Expression> arguments = mc.parameterExpressions();
        for (int i = 0; i < parameters.size() && i < arguments.size(); i++) {
            ParameterizedType formal = parameters.get(i).parameterizedType();
            if (formal.arrays() != 1 || !rt.typeParameter().equals(formal.typeParameter())) continue;
            if (parameters.get(i).isVarArgs() && !passesTheArray(arguments, parameters, i)) continue;
            Object node = nodes.argumentNode(readScope.mi(), readScope.scope(), arguments.get(i));
            if (node != null) graph.addEdge(new Content(node), new Content(target));
        }
    }

    private static final Switches.Gate ARRAY_RESULTS_OFF = Switches.gate("NOARRAYRESULTS");

    // gate NOARRAYELEMENTSPASSED: an array argument's elements do not reach the slot of a generic call's result (A/B)
    private static final Switches.Gate ARRAY_ELEMENTS_PASSED_OFF = Switches.gate("NOARRAYELEMENTSPASSED");

    // is the return type the parameter's, in the method's own type variables: 'T' and 'T', 'List<T>' and
    // 'List<? extends T>', 'Map<K, V>' and 'Map<K, V>'
    private static boolean sameInstantiation(ParameterizedType returnType, ParameterizedType parameterType) {
        if (returnType.arrays() > 0 || parameterType.arrays() > 0) return false;
        if (isMethodTypeVariable(returnType)) {
            return returnType.typeParameter().equals(parameterType.typeParameter()) && parameterType.wildcard() == null;
        }
        if (returnType.parameters().isEmpty() || returnType.typeInfo() == null
            || !returnType.typeInfo().equals(parameterType.typeInfo())
            || returnType.parameters().size() != parameterType.parameters().size()) {
            return false;
        }
        for (int i = 0; i < returnType.parameters().size(); i++) {
            ParameterizedType r = returnType.parameters().get(i);
            ParameterizedType a = parameterType.parameters().get(i);
            if (!isMethodTypeVariable(r) || r.arrays() > 0 || a.arrays() > 0
                || !r.typeParameter().equals(a.typeParameter())) {
                return false;
            }
        }
        return true;
    }

    /*
     A library factory: the return type's argument j is a method type variable T ('Map.of(K, V, ...)' returns
     Map<K, V>, 'List.of(E...)', 'Arrays.asList(T...)', 'Optional.of(T)'). Each argument passed for a T (or as an
     element of a 'T...') is a value of the result's slot j, which is the target's ('Map<Integer, Integer[]> m =
     Map.of(k, new Integer[]{null})': the target's value elements are nullable). An analysed callee's result is
     tied by callResult instead.
     */
    void factorySlots(Object target, Expression value, MethodInfo mi) {
        if (target == null || !(NonNullFacts.unwrap(value) instanceof MethodCall mc) || mc.methodInfo() == null) {
            return;
        }
        MethodInfo callee = mc.methodInfo();
        if (index.analysed.contains(callee)) return;
        ParameterizedType returnType = callee.returnType();
        ParameterizedType targetType = graph.typeOf(target);
        if (returnType.arrays() > 0 || returnType.parameters().isEmpty() || targetType == null
            || targetType.arrays() > 0 || targetType.parameters().size() != returnType.parameters().size()) {
            return;
        }
        List<ParameterInfo> parameters = callee.parameters();
        List<Expression> arguments = mc.parameterExpressions();
        for (int j = 0; j < returnType.parameters().size(); j++) {
            ParameterizedType r = returnType.parameters().get(j);
            if (r.arrays() > 0 || r.typeParameter() == null || !r.typeParameter().isMethodTypeParameter()) continue;
            Arg slot = new Arg(target, j);
            for (int a = 0; a < arguments.size() && !parameters.isEmpty(); a++) {
                ParameterInfo p = parameters.get(Math.min(a, parameters.size() - 1));
                ParameterizedType pt = p.parameterizedType();
                boolean element = p.isVarArgs() && !passesTheArray(arguments, parameters, a);
                if (!r.typeParameter().equals(pt.typeParameter()) || pt.arrays() != (element ? 1 : 0)) continue;
                // a parameter the library declares nullable accepts null; that is not storing it as a T
                if (stateOf(p, PropertyImpl.NULLABILITY_PARAMETER) == NullableState.NULLABLE) continue;
                Expression argument = arguments.get(a);
                if (rejectsNull(callee)) {
                    seeds.seedCreated(slot, argument, mi); // the elements of an array stored as one ('new Integer[]{null}')
                    Object source = readScope == null ? null : nodes.argumentNode(readScope.mi(), readScope.scope(), argument);
                    if (source != null) graph.flows.add(List.of(source, slot)); // its content slots still are the slot's
                    continue;
                }
                if (NonNullFacts.unwrap(argument) instanceof NullConstant) {
                    seeds.seed(slot, "null passed to " + callee.fullyQualifiedName() + " in " + where(mi), writer(mi));
                    continue;
                }
                seeds.seedCreated(slot, argument, mi);
                Object source = readScope == null ? null : nodes.argumentNode(readScope.mi(), readScope.scope(), argument);
                if (source != null) {
                    // M4: a variable known non-null at the call carries no null into the slot ('textSegment == null
                    // ? List.of() : List.of(textSegment)', langchain4j ListeningEmbeddingModel.embed)
                    if (!(NonNullFacts.unwrap(argument) instanceof VariableExpression ve
                          && facts.nonNullAt(mc, ve.variable()))) {
                        graph.addEdge(source, slot);
                    }
                    graph.flows.add(List.of(source, slot)); // the same object: its content slots are the slot's
                }
            }
        }
    }

    /*
     An immutable collection factory throws on a null element: 'List.of(x)' holds no null, whatever x may be
     (timefold DefaultExhaustiveSearchPhaseFactory: 'return List.of(solutionDescriptor.getListVariableDescriptor())',
     whose null is the caller's NullPointerException, not an element). The JDK's 'of', 'copyOf', 'entry' and
     'ofEntries' on List, Set and Map; guava's Immutable* collections.
     */
    private static boolean rejectsNull(MethodInfo callee) {
        String type = callee.typeInfo().fullyQualifiedName();
        String name = callee.name();
        boolean factory = "of".equals(name) || "copyOf".equals(name) || "entry".equals(name) || "ofEntries".equals(name);
        return factory && callee.isStatic()
               && ("java.util.List".equals(type) || "java.util.Set".equals(type) || "java.util.Map".equals(type)
                   || type.startsWith("com.google.common.collect.Immutable"));
    }

    /*
     Kotlin: a library view of a generic receiver holds the receiver's values: 'namespaceSingletonMaps.keySet()' is a
     Set of the map's keys, 'header.entrySet().iterator()' an Iterator of its entries. The result's slots are the
     receiver's where the callee's declared result uses its class's type variables, lifted to the target's class
     ('KeySetView<K, V>' as a 'Set<K>'), nested too ('Entry<K, V>'); both ways, a view being the same data. A library
     call in between ('header.entrySet()') is a node of its own, typed by its inferred type (nacos
     ServiceManager.java:92, Header.java:85, 2026-10-19). Gate NOLIBRARYVIEWS.
     */
    private static final Switches.Gate LIBRARY_VIEWS_OFF = Switches.gate("NOLIBRARYVIEWS");

    private void libraryView(Object target, MethodCall mc, int depth) {
        if (LIBRARY_VIEWS_OFF.get() || readScope == null || depth > 3) return;
        MethodInfo callee = mc.methodInfo();
        ParameterizedType tt = graph.typeOf(target);
        if (callee == null || index.analysed.contains(callee) || callee.isStatic() || mc.object() == null || tt == null
            || tt.arrays() > 0 || tt.parameters().isEmpty() || tt.typeInfo() == null) return;
        ParameterizedType rt = callee.returnType();
        if (rt.arrays() > 0 || rt.parameters().isEmpty() || rt.typeInfo() == null) return;
        ParameterizedType lifted = rt.typeInfo() == tt.typeInfo() ? rt
                : rt.concreteSuperType(tt.typeInfo().asParameterizedType());
        if (lifted == null || lifted.parameters().size() != tt.parameters().size()) return;
        Expression object = NonNullFacts.unwrap(mc.object());
        Object receiver;
        if (object instanceof MethodCall inner && inner.methodInfo() != null && !index.analysed.contains(inner.methodInfo())
            && !inner.methodInfo().isStatic() && inner.concreteReturnType() != null) {
            receiver = new CallResult(inner);
            libraryView(receiver, inner, depth + 1);
        } else {
            receiver = nodes.receiverNode(readScope.mi(), readScope.scope(), mc);
        }
        ParameterizedType receiverType = receiver == null ? null : graph.typeOf(receiver);
        if (receiverType == null || receiverType.arrays() > 0) return;
        for (int j = 0; j < lifted.parameters().size(); j++) {
            viewSlot(new Arg(target, j), lifted.parameters().get(j), receiver, receiverType, callee.typeInfo());
        }
    }

    private void viewSlot(Arg slot, ParameterizedType formal, Object receiver, ParameterizedType receiverType,
                          TypeInfo calleeClass) {
        if (formal.arrays() > 0 || formal.wildcard() != null) return;
        var tp = formal.typeParameter();
        if (tp != null) {
            if (tp.isMethodTypeParameter() || !tp.getOwner().isLeft() || tp.getOwner().getLeft() != calleeClass) return;
            int index = slotIndex(receiverType, calleeClass, tp.getIndex());
            if (index < 0) return;
            Arg own = new Arg(receiver, index);
            graph.addEdge(own, slot);
            graph.addEdge(slot, own);
            return;
        }
        for (int m = 0; m < formal.parameters().size(); m++) {
            viewSlot(new Arg(slot, m), formal.parameters().get(m), receiver, receiverType, calleeClass);
        }
    }

    /*
     Kotlin: what a lambda returns is a value of the type argument its interface method returns, in the parameter it
     is passed to: '(query) -> maxResults' for a 'Function<Query, Integer>' makes it a 'Function<Query, Int?>' when
     maxResults may be null (langchain4j EmbeddingStoreContentRetriever, whose lambda returned an Int? where the
     constructor declared Int).
     */
    void lambdaResult(Lambda lambda, Object pi) {
        MethodInfo body = lambda.methodInfo();
        if (body == null) return;
        ParameterizedType formal;
        if (pi instanceof ParameterInfo parameter && index.analysed.contains(parameter.methodInfo())) {
            formal = parameter.parameterizedType();
        } else if (!LAMBDA_SLOTS_OFF.get() && policy.callResults() && pi instanceof Arg slot) {
            // a receiver's slot: 'chain.add(input -> states.get(current))' on a List<Function<Provider<S>, S>> makes
            // it a List<Function<Provider<S>, S?>> (jenkins InstallUtil.kt:48/60, 2026-10-20). Gate NOLAMBDASLOTS.
            formal = graph.typeOf(slot);
            if (formal == null) return;
        } else {
            return;
        }
        if (formal.arrays() > 0 || formal.typeInfo() == null || formal.parameters().isEmpty()) return;
        MethodInfo sam = formal.typeInfo().singleAbstractMethod();
        if (sam == null || !isClassTypeVariable(sam.returnType())) return;
        int j = formal.typeInfo().typeParameters().indexOf(sam.returnType().typeParameter());
        // not into a type variable ('<T> T withRetry(Callable<T> action)'): each call instantiates it anew
        if (j >= 0 && j < formal.parameters().size() && formal.parameters().get(j).typeParameter() == null) {
            graph.addEdge(body, new Arg(pi, j));
        }
    }

    private static final Switches.Gate LAMBDA_SLOTS_OFF = Switches.gate("NOLAMBDASLOTS");

    /*
     Kotlin: a method reference returns what its method returns, as a lambda its body: 'new
     JenkinsANTLRErrorListener(parser::getErrorMessage)' with a nullable getErrorMessage() makes the parameter a
     Supplier<String?> (jenkins CronTab.kt:30, 2026-10-21). Not a constructor reference, whose result is never null.
     Gate NOMETHODREFRESULTS.
     */
    void methodReferenceResult(MethodReference mr, Object pi) {
        MethodInfo method = mr.methodInfo();
        if (METHOD_REF_RESULTS_OFF.get() || method == null || method.isConstructor() || method.returnType().isVoid()
            || !index.analysed.contains(method)) return;
        ParameterizedType formal;
        if (pi instanceof ParameterInfo parameter && index.analysed.contains(parameter.methodInfo())) {
            formal = parameter.parameterizedType();
        } else if (pi instanceof Arg slot) {
            formal = graph.typeOf(slot);
            if (formal == null) return;
        } else {
            return;
        }
        if (formal.arrays() > 0 || formal.typeInfo() == null || formal.parameters().isEmpty()) return;
        MethodInfo sam = formal.typeInfo().singleAbstractMethod();
        if (sam == null || !isClassTypeVariable(sam.returnType())) return;
        int j = formal.typeInfo().typeParameters().indexOf(sam.returnType().typeParameter());
        if (j >= 0 && j < formal.parameters().size() && formal.parameters().get(j).typeParameter() == null) {
            graph.addEdge(method, new Arg(pi, j));
        }
    }

    private static final Switches.Gate METHOD_REF_RESULTS_OFF = Switches.gate("NOMETHODREFRESULTS");
    /*
     Kotlin: a lambda given to 'computeIfAbsent(K, Function<? super K, ? extends V>)', whose functional parameter
     returns the callee class's type variable, returns a value of the receiver's slot for it: 'cache
     .computeIfAbsent(t, unused -> computeConverter(t))' with a nullable computeConverter makes the cache's values
     nullable (jenkins XStream2.kt:224, 2026-10-21). Gate NORECEIVERLAMBDAS.
     */
    void receiverLambdaResult(MethodInfo mi, Scope scope, Expression call, Lambda lambda, Object pi) {
        if (RECEIVER_LAMBDAS_OFF.get() || lambda.methodInfo() == null || !(pi instanceof ParameterInfo parameter)
            || this.index.analysed.contains(parameter.methodInfo()) || !(call instanceof MethodCall mc)) return;
        MethodInfo callee = parameter.methodInfo();
        ParameterizedType formal = parameter.parameterizedType();
        // only ConcurrentHashMap's own 'computeIfAbsent', whose function Kotlin types '(K) -> V': Map's (and
        // ConcurrentMap's) is enhanced to 'Function<in K, out V?>', so jenkins AbstractCIBase's
        // 'computers.computeIfAbsent(n, node -> node.createComputer())' on a ConcurrentMap compiles as it is. For
        // 'compute', 'computeIfPresent' and 'merge' a null result removes the entry (guava MapsTest, nacos
        // SelectorManager: their maps' values had become nullable)
        if (!"computeIfAbsent".equals(callee.name())
            || !"java.util.concurrent.ConcurrentHashMap".equals(callee.typeInfo().fullyQualifiedName())) return;
        if (callee.isStatic() || formal.arrays() > 0 || formal.typeInfo() == null || formal.parameters().isEmpty()) return;
        MethodInfo sam = formal.typeInfo().singleAbstractMethod();
        if (sam == null || !isClassTypeVariable(sam.returnType())) return;
        int j = formal.typeInfo().typeParameters().indexOf(sam.returnType().typeParameter());
        if (j < 0 || j >= formal.parameters().size()) return;
        ParameterizedType result = formal.parameters().get(j); // '? extends V'
        var tp = result.typeParameter();
        if (tp == null || tp.isMethodTypeParameter() || !tp.getOwner().isLeft()
            || tp.getOwner().getLeft() != callee.typeInfo() || result.wildcard() != null && result.wildcard().isSuper()) {
            return;
        }
        Object receiver = nodes.receiverNode(mi, scope, mc);
        ParameterizedType receiverType = receiver == null ? null : graph.typeOf(receiver);
        if (receiverType == null || receiverType.arrays() > 0) return;
        int index = slotIndex(receiverType, callee.typeInfo(), tp.getIndex());
        if (index >= 0) graph.addEdge(lambda.methodInfo(), new Arg(receiver, index));
    }

    private static final Switches.Gate RECEIVER_LAMBDAS_OFF = Switches.gate("NORECEIVERLAMBDAS");
    private static final Switches.Gate COLLECT_CONTAINERS_OFF = Switches.gate("NOCOLLECTCONTAINERS");

    // whether a lambda's functional type returns its type argument k ('Supplier<T>': get() returns argument 0)
    private static boolean lambdaResultsPosition(Lambda lambda, int k) {
        ParameterizedType type = lambda.concreteFunctionalType();
        if (type == null || type.typeInfo() == null) return false;
        MethodInfo sam = type.typeInfo().singleAbstractMethod();
        if (sam == null || !isClassTypeVariable(sam.returnType())) return false;
        return type.typeInfo().typeParameters().indexOf(sam.returnType().typeParameter()) == k;
    }

    private static final Switches.Gate LAMBDA_TYPE_VARIABLE_RESULTS_OFF = Switches.gate("NOLAMBDATYPEVARRESULTS");
    private static final Switches.Gate GENERIC_RESULT_SLOTS_OFF = Switches.gate("NOGENERICRESULTSLOTS");

    /*
     Kotlin: a lambda's parameters are values of the type arguments its interface method takes, in the parameter it
     is passed to: 's -> Json.fromJson(s, type)' for a 'ThrowingFunction<String?, T>' has 's: String?'. Since a
     caller's null into 'parser.apply(text)' goes to the receiver's instantiation (6494045c), it reaches the lambda
     only this way (langchain4j JsonParsingUtils.kt:9, worktree-maddi-all 2026-10-17). Not from a type variable
     argument, which each call instantiates anew. Gate NOLAMBDAPARAMETERS.
     */
    void lambdaParameters(Lambda lambda, Object pi) {
        MethodInfo body = lambda.methodInfo();
        if (LAMBDA_PARAMETERS_OFF.get() || body == null) return;
        ParameterizedType formal;
        if (pi instanceof ParameterInfo parameter && index.analysed.contains(parameter.methodInfo())) {
            formal = parameter.parameterizedType();
        } else if (!LAMBDA_PARAMETER_SLOTS_OFF.get() && pi instanceof Arg slot) {
            // a receiver's slot, as in lambdaResult: 'chain.add(next -> filter.getNextInstallState(current, next))'
            // on a List<Function<Provider<S?>, S?>> makes 'next' a Provider<S?>, which it passes on (jenkins
            // InstallUtil.kt:44, 2026-10-20). Gate NOLAMBDAPARAMETERSLOTS.
            formal = graph.typeOf(slot);
            if (formal == null) return;
        } else {
            return;
        }
        if (formal.arrays() > 0 || formal.typeInfo() == null || formal.parameters().isEmpty()) return;
        MethodInfo sam = formal.typeInfo().singleAbstractMethod();
        if (sam == null || sam.typeInfo() != formal.typeInfo() || body.parameters().size() != sam.parameters().size()) {
            return;
        }
        for (ParameterInfo samParameter : sam.parameters()) {
            ParameterizedType t = samParameter.parameterizedType();
            if (!isClassTypeVariable(t) || t.wildcard() != null) continue;
            int j = formal.typeInfo().typeParameters().indexOf(t.typeParameter());
            if (j >= 0 && j < formal.parameters().size() && formal.parameters().get(j).typeParameter() == null) {
                Object lambdaParameter = body.parameters().get(samParameter.index());
                graph.addEdge(new Arg(pi, j), lambdaParameter);
                // a generic argument ('Provider<S>'): the lambda's parameter is that object, its slots tied both ways
                if (pi instanceof Arg && !formal.parameters().get(j).parameters().isEmpty()) {
                    graph.flows.add(List.of(new Arg(pi, j), lambdaParameter));
                }
            }
        }
    }

    private static final Switches.Gate LAMBDA_PARAMETER_SLOTS_OFF = Switches.gate("NOLAMBDAPARAMETERSLOTS");

    private static final Switches.Gate LAMBDA_PARAMETERS_OFF = Switches.gate("NOLAMBDAPARAMETERS");

    /*
     A lambda handed to a library parameter whose functional type has a nullable type argument (the hints'
     nullableArguments, from JSpecify): the library calls the lambda with null in each SAM parameter typed by that type
     argument. 'map.compute(k, (key, old) -> ...)' ('BiFunction<? super K, ? super @Nullable V, ? extends @Nullable
     V>'): 'old' is null for an absent key. Only where the SAM is declared by the parameter's own functional type, so
     that its type variables are the ones the positions name.
     */
    void libraryCallback(Lambda lambda, Object pi) {
        if (!(pi instanceof ParameterInfo parameter) || index.analysed.contains(parameter.methodInfo())
            || lambda.methodInfo() == null) return;
        List<? extends Value.Nullability> arguments = parameter.analysis()
                .getOrDefault(PropertyImpl.NULLABILITY_PARAMETER, ValueImpl.NullabilityImpl.UNSPECIFIED).arguments();
        ParameterizedType formal = parameter.parameterizedType();
        if (arguments.isEmpty() || formal.arrays() > 0 || formal.typeInfo() == null) return;
        MethodInfo sam = formal.typeInfo().singleAbstractMethod();
        if (sam == null || sam.typeInfo() != formal.typeInfo()) return;
        List<ParameterInfo> lambdaParameters = lambda.methodInfo().parameters();
        if (lambdaParameters.size() != sam.parameters().size()) return;
        for (int j = 0; j < arguments.size() && j < formal.typeInfo().typeParameters().size(); j++) {
            if (arguments.get(j).state() != NullableState.NULLABLE) continue;
            var typeParameter = formal.typeInfo().typeParameters().get(j);
            for (ParameterInfo samParameter : sam.parameters()) {
                ParameterizedType t = samParameter.parameterizedType();
                if (t.arrays() == 0 && t.typeParameter() == typeParameter) {
                    seeds.seed(lambdaParameters.get(samParameter.index()), "called back with null by "
                                                                           + parameter.methodInfo().fullyQualifiedName());
                }
            }
        }
    }

    // phase 3: the library nulls the next statement exits on (deadNullAssignments)
    void deadNullAssignments(List<MethodInfo> methods) {
        if (policy.callResults() && !DEAD_NULLS_OFF.get()) {
            for (MethodInfo mi : methods) if (mi.methodBody() != null) deadNullAssignments(mi.methodBody());
        }
    }
}
