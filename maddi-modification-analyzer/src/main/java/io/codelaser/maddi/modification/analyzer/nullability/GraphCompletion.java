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

import io.codelaser.maddi.cst.api.expression.Assignment;
import io.codelaser.maddi.cst.api.expression.ConstructorCall;
import io.codelaser.maddi.cst.api.expression.Expression;
import io.codelaser.maddi.cst.api.expression.Lambda;
import io.codelaser.maddi.cst.api.expression.MethodCall;
import io.codelaser.maddi.cst.api.expression.NullConstant;
import io.codelaser.maddi.cst.api.expression.VariableExpression;
import io.codelaser.maddi.cst.api.info.FieldInfo;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.ParameterInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.api.statement.Block;
import io.codelaser.maddi.cst.api.statement.ExplicitConstructorInvocation;
import io.codelaser.maddi.cst.api.statement.ReturnStatement;
import io.codelaser.maddi.cst.api.type.ParameterizedType;
import io.codelaser.maddi.cst.api.variable.FieldReference;
import io.codelaser.maddi.modification.prepwork.variable.VariableData;
import io.codelaser.maddi.modification.prepwork.variable.impl.VariableDataImpl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.codelaser.maddi.modification.analyzer.nullability.Types.*;

/**
 * Phase 5 of {@link NullabilityPass}: the edges and seeds added once every method's body was walked: the keyed maps'
 * edges, the fields' default values and initializers, the generic holders' slots, the nulls in field initializers,
 * the parameters stored into generic fields, enum valueOf's contract, the parameters a filter clears of nulls.
 */
final class GraphCompletion {
    private final Policy policy;
    private final Index index;
    private final Seeds seeds;
    private final Contracts contracts;
    private final NullGraph graph;
    private final Nodes nodes;
    private final CallResults callResults;
    private final OutsideCallers outsideCallers;

    GraphCompletion(Policy policy, Index index, Seeds seeds, Contracts contracts, NullGraph graph, Nodes nodes,
                    CallResults callResults, OutsideCallers outsideCallers) {
        this.policy = policy;
        this.index = index;
        this.seeds = seeds;
        this.contracts = contracts;
        this.graph = graph;
        this.nodes = nodes;
        this.callResults = callResults;
        this.outsideCallers = outsideCallers;
    }

    /*
     A null literal given to a call in a field initializer ('private final RecAbilityContext recAbilityContext = new
     RecAbilityContext(null)'): the initializers are not method bodies, so the call sites never saw it (nacos
     GrpcClient.java:91, 2026-10-19). Gate NOFIELDINITNULLS.
     */
    void fieldInitializerNullArguments(List<FieldInfo> fields) {
        if (FIELD_INIT_NULLS_OFF.get()) return;
        for (FieldInfo fi : fields) {
            if (fi.initializer() == null || fi.initializer().isEmpty()) continue;
            fi.initializer().visit(e -> {
                if (e instanceof Lambda) return false;
                // an anonymous class's creation passes its arguments to the superclass's constructor
                // ('static final CalendarField MINUTE = new CalendarField("minute", …, null) { … }', jenkins
                // CronTab.kt:216, 2026-10-21); its body is not the initializer's. Gate NOANONYMOUSFIELDINIT.
                boolean anonymous = e instanceof ConstructorCall cc && cc.anonymousClass() != null;
                if (anonymous && ANONYMOUS_FIELD_INIT_OFF.get()) return false;
                MethodInfo callee = switch (e) {
                    case MethodCall mc -> mc.methodInfo();
                    case ConstructorCall cc when anonymous -> index.anonymousSuperConstructor(cc);
                    case ConstructorCall cc -> cc.constructor();
                    default -> null;
                };
                if (anonymous && callee == null) return false;
                if (callee == null || !index.analysed.contains(callee)) return true;
                List<Expression> args = e instanceof MethodCall mc ? mc.parameterExpressions()
                        : ((ConstructorCall) e).parameterExpressions();
                for (int i = 0; i < args.size() && i < callee.parameters().size(); i++) {
                    ParameterInfo pi = callee.parameters().get(i);
                    // not a type-variable parameter: the null instantiates the variable for that call, as at any call
                    // site ('static final ImmediateFuture<?> NULL = new ImmediateFuture<>(null)', guava)
                    if (!pi.isVarArgs() && !isTypeVariable(pi.parameterizedType())
                        && NonNullFacts.unwrap(args.get(i)) instanceof NullConstant) {
                        seeds.seed(pi, Seeds.NULL_ARGUMENT + fi.fullyQualifiedName(), fi.owner());
                    }
                }
                return !anonymous;
            });
        }
    }

    private static final Switches.Gate FIELD_INIT_NULLS_OFF = Switches.gate("NOFIELDINITNULLS");
    private static final Switches.Gate ANONYMOUS_FIELD_INIT_OFF = Switches.gate("NOANONYMOUSFIELDINIT");

    /*
     Kotlin: 'this.f = p' of a generic type makes p's slots f's (invariant), also where the link engine does not say so:
     'old = instanceMetadataMap; instanceMetadataMap = snapshot; old.clear();' links the field to 'old' only, and the
     field's 'String?' keys never reached the parameter's (nacos NamingMetadataManager.java:207, 2026-10-19). A flow
     only: coupleContent ties the slots; the value itself is the links' to decide. Gate NOASSIGNEDSLOTS.
     */
    void assignedParameterSlots(List<MethodInfo> methods) {
        if (ASSIGNED_SLOTS_OFF.get() || !policy.callResults()) return;
        for (MethodInfo mi : methods) {
            if (mi.methodBody() == null) continue;
            mi.methodBody().visit(e -> {
                if (e instanceof Lambda || e instanceof ConstructorCall cc && cc.anonymousClass() != null) return false;
                if (e instanceof Assignment a && a.assignmentOperator() == null
                    && a.variableTarget() instanceof FieldReference fr && (fr.scopeIsThis() || fr.fieldInfo().isStatic())
                    && NonNullFacts.unwrap(a.value()) instanceof VariableExpression ve
                    && ve.variable() instanceof ParameterInfo pi && !nodes.reassigned.contains(pi)
                    && pi.methodInfo() == mi && !pi.parameterizedType().parameters().isEmpty()
                    && pi.parameterizedType().arrays() == 0) {
                    graph.flows.add(List.of(pi, fr.fieldInfo()));
                }
                return true;
            });
        }
    }

    private static final Switches.Gate ASSIGNED_SLOTS_OFF = Switches.gate("NOASSIGNEDSLOTS");

    /*
     Kotlin: an enum's 'valueOf(String)' is Kotlin's own, which takes a 'String': its parameter is a non-null contract,
     so a nullable argument is asserted at the call instead of turning the generated method's parameter nullable
     (nacos ServerStatusManager.java:64, 'ServerStatus.valueOf(switchDomain.getOverriddenServerStatus())',
     2026-10-19). Gate NOENUMVALUEOF.
     */
    void enumValueOfContracts(List<MethodInfo> methods) {
        if (ENUM_VALUE_OF_OFF.get() || !policy.callResults()) return;
        for (MethodInfo mi : methods) {
            if (mi.isStatic() && "valueOf".equals(mi.name()) && mi.typeInfo().typeNature().isEnum()
                && mi.parameters().size() == 1 && mi.parameters().getFirst().parameterizedType().isJavaLangString()) {
                contracts.add(mi.parameters().getFirst());
            }
        }
    }

    private static final Switches.Gate ENUM_VALUE_OF_OFF = Switches.gate("NOENUMVALUEOF");

    /*
     A field typed by its class's type variable ('T t' in 'Box<T>') holds, in each box, a value of that box's type
     argument: null in the field (its default value, 'this.t = null', a 'set(null)') reaches the argument slot of
     every node typed 'Box<…>'.
     */
    void holderFields(List<MethodInfo> methods, List<FieldInfo> fields) {
        Map<TypeInfo, List<FieldInfo>> byOwner = new HashMap<>();
        for (FieldInfo fi : fields) {
            if (classTypeVariable(fi) >= 0) byOwner.computeIfAbsent(fi.owner(), _ -> new ArrayList<>()).add(fi);
        }
        if (byOwner.isEmpty()) return;
        List<Object> nodes = new ArrayList<>(fields);
        for (MethodInfo mi : methods) {
            nodes.addAll(mi.parameters());
            if (!mi.isConstructor() && !mi.returnType().isVoid()) nodes.add(mi);
        }
        nodes.addAll(graph.declaredLocals.keySet());
        for (Object n : nodes) {
            ParameterizedType type = graph.typeOf(n);
            if (type == null || type.arrays() > 0 || type.typeInfo() == null) continue;
            for (FieldInfo fi : byOwner.getOrDefault(type.typeInfo(), List.of())) {
                int index = classTypeVariable(fi);
                if (index >= type.parameters().size()) continue;
                if (policy.assertContentWrites()) {
                    graph.pendingHolders.computeIfAbsent(fi, _ -> new ArrayList<>()).add(new Arg(n, index));
                } else {
                    graph.addEdge(fi, new Arg(n, index));
                }
            }
        }
        if (!graph.pendingHolders.isEmpty()) holdersReachedFromOutside();
    }

    /*
     Policy.assertContentWrites (Kotlin): a null the field's own class writes ('this(store, attributes, embedding,
     null)' into 'Embedded embedded', a default value, 'this.t = null') says the field is 'T?', not that every
     instance is a 'Box<X?>': Kotlin writes 'val embedded: Embedded?' and leaves the type argument alone. Only a null
     written outside the class, through its parameters ('new Box<>(null)', a setter), is the instantiation's. Without
     this, langchain4j's EmbeddingStoreRequestContext.Add made every request context an Add<X?>, and through them
     ListeningEmbeddingStore's 'delegate' an EmbeddingStore<Embedded?> that 'this' is not (13 type errors).
     A field is the instances' when a closure from the seeds written outside its class (or by no code at all)
     reaches it. Not iterated: a holder reached only through another holder's instance slot stays the field's.
     */
    private void holdersReachedFromOutside() {
        Map<TypeInfo, List<FieldInfo>> byOwner = new LinkedHashMap<>();
        graph.pendingHolders.keySet().forEach(fi -> byOwner.computeIfAbsent(fi.owner(), _ -> new ArrayList<>()).add(fi));
        byOwner.forEach((holder, holders) -> {
            Set<Object> outside = new LinkedHashSet<>();
            seeds.seedOrigin.keySet().forEach(seed -> {
                List<TypeInfo> writers = seeds.seedWriters.getOrDefault(seed, List.of());
                if (writers.isEmpty() || writers.stream().anyMatch(w -> !within(w, holder))) outside.add(seed);
            });
            Set<Object> reachedFromOutside = graph.closure(new HashMap<>(), outside, null);
            for (FieldInfo fi : holders) {
                if (reachedFromOutside.contains(fi)) graph.pendingHolders.get(fi).forEach(arg -> graph.addEdge(fi, arg));
            }
        });
    }

    // the code of 'type' is the code of 'holder': the class itself or a class nested in it (a lambda's, an
    // anonymous class's); null (no writer) is not
    private static boolean within(TypeInfo type, TypeInfo holder) {
        for (TypeInfo t = type; t != null; t = t.compilationUnitOrEnclosingType().isRight()
                ? t.compilationUnitOrEnclosingType().getRight() : null) {
            if (t.equals(holder)) return true;
        }
        return false;
    }

    /*
     After all writes are seen: the values under a key not known reach every key; every key's values are the field's
     values too (and their own slots are tied to the field's, coupleContent). In an open world an outside caller of a
     keyed putter writes under any key.
     */
    void keyedEdges(List<MethodInfo> methods) {
        graph.keysUsed.forEach((slot, keys) -> {
            Keyed unknown = new Keyed(slot, null);
            for (Object key : keys) {
                if (key == NullGraph.NO_KEY) continue;
                Keyed k = new Keyed(slot, key);
                graph.addEdge(unknown, k);
                graph.addEdge(k, slot);
            }
        });
        if (policy.world() == World.CLOSED) return;
        index.keyedPutters.forEach((mi, putter) -> {
            if (outsideCallers.externallyCallable(mi)) {
                graph.addEdge(mi.parameters().get(putter.valueIndex()), new Keyed(index.keyedSlots.get(putter.field()), null));
                for (Object key : graph.keysUsed.getOrDefault(index.keyedSlots.get(putter.field()), Set.of())) {
                    if (key != NullGraph.NO_KEY) graph.addEdge(new Keyed(index.keyedSlots.get(putter.field()), null),
                            new Keyed(index.keyedSlots.get(putter.field()), key));
                }
            }
        });
    }

    /*
     Kotlin: a method that returns its parameter filtered of nulls ('<T> Iterator<T> removeNull(Iterator<T> itr) {
     return Iterators.filter(itr, Objects::nonNull); }') takes nullable elements and returns non-null ones:
     'removeNull(itr: Iterator<T?>): Iterator<T>', from which Kotlin infers a non-null T for an Iterator<Int?>
     argument (jenkins BuildReferenceMapAdapter.kt:111, 2026-10-21). Gate NONULLFILTEREDPARAMETERS.
     */
    void nullFilteredParameters(List<MethodInfo> methods) {
        if (NULL_FILTERED_PARAMETERS_OFF.get() || !policy.callResults()) return;
        for (MethodInfo mi : methods) {
            if (mi.methodBody() == null || mi.methodBody().statements().size() != 1
                || !(mi.methodBody().statements().getFirst() instanceof ReturnStatement rs)
                || !(NonNullFacts.unwrap(rs.expression()) instanceof MethodCall mc) || mc.methodInfo() == null
                || !"filter".equals(mc.methodInfo().name()) || mc.parameterExpressions().size() != 2
                || !callResults.nullFilter(mc.parameterExpressions().get(1))
                || !(NonNullFacts.unwrap(mc.parameterExpressions().getFirst()) instanceof VariableExpression ve)
                || !(ve.variable() instanceof ParameterInfo pi) || !mi.equals(pi.methodInfo())) continue;
            ParameterizedType type = pi.parameterizedType();
            // only a covariant container ('Iterator<out T>', 'Iterable<out T>'): every caller's argument fits T?
            String container = type.typeInfo() == null ? "" : type.typeInfo().fullyQualifiedName();
            if (type.arrays() == 0 && type.parameters().size() == 1 && isTypeVariable(type.parameters().getFirst())
                && ("java.util.Iterator".equals(container) || "java.lang.Iterable".equals(container))) {
                Arg slot = new Arg(pi, 0);
                graph.typeVariableElementSlots.add(slot); // a T? slot, not held at T (boundedSlot)
                seeds.seed(slot, "filtered of nulls in " + mi.fullyQualifiedName(), mi.typeInfo());
            }
        }
    }

    private static final Switches.Gate NULL_FILTERED_PARAMETERS_OFF = Switches.gate("NONULLFILTEREDPARAMETERS");

    // Java's default value: a non-final reference field without an initializer, which some constructor leaves alone
    void seedDefaultValue(FieldInfo fi) {
        if (fi.type().isPrimitiveExcludingVoid() && fi.type().arrays() == 0) return;
        Expression initializer = fi.initializer();
        if (initializer != null) {
            seeds.seedCreated(fi, initializer, null);
            callResults.factorySlots(fi, initializer, null);
            initializerContent(fi, initializer);
        }
        if (initializer instanceof NullConstant) {
            seeds.seed(fi, "initializer null", fi.owner());
            return;
        }
        if (fi.isFinal() || initializer != null && !initializer.isEmpty()) return;
        List<MethodInfo> constructors = fi.owner().constructors();
        if (constructors.isEmpty()) {
            seeds.seedIndirect(fi, "default value: no constructor assigns it", fi.owner());
            return;
        }
        for (MethodInfo constructor : constructors) {
            if (!assigns(constructor, fi)) {
                seeds.seedIndirect(fi, "default value: not assigned in " + constructor.fullyQualifiedName(), fi.owner());
                return;
            }
        }
    }

    /*
     Kotlin: a field initialized with another field or an analysed method's result is that object ('_LITERAL_NAMES =
     makeLiteralNames()', 'countryCodeTLDsPlus = EMPTY_STRING_ARRAY'): their content slots are tied both ways, as a
     local's assignment is (nestedCopy, callResult). Arrays are invariant: an Array<String?> initializer needs an
     Array<String?> field (jenkins CrontabLexer.kt:45, DomainValidator.kt:1714, 2026-10-20). Gate NOFIELDINITCONTENT.
     */
    private void initializerContent(FieldInfo fi, Expression initializer) {
        if (FIELD_INIT_CONTENT_OFF.get() || !policy.callResults() || fi.type().arrays() == 0 && fi.type().parameters().isEmpty()) {
            return;
        }
        Expression e = NonNullFacts.unwrap(initializer);
        Object source = null;
        if (e instanceof VariableExpression ve && ve.variable() instanceof FieldReference fr) {
            source = fr.fieldInfo();
        } else if (e instanceof MethodCall mc && mc.methodInfo() != null && index.analysed.contains(mc.methodInfo())
                   && !mc.methodInfo().returnType().isVoid()) {
            source = mc.methodInfo();
        }
        if (source != null && source != fi) graph.flows.add(List.of(source, fi));
    }

    private static final Switches.Gate FIELD_INIT_CONTENT_OFF = Switches.gate("NOFIELDINITCONTENT");

    private static boolean assigns(MethodInfo constructor, FieldInfo fi) {
        Block body = constructor.methodBody();
        if (body != null && !body.isEmpty() && body.statements().getFirst() instanceof ExplicitConstructorInvocation eci
            && !eci.isSuper()) {
            return true; // this(...): the delegate's assignments count; it is checked as a constructor itself
        }
        VariableData vd = VariableDataImpl.of(constructor);
        if (vd == null) return false;
        return vd.variableInfoStream().anyMatch(vi -> vi.variable() instanceof FieldReference fr
                                                      && fr.fieldInfo() == fi && fr.scopeIsRecursivelyThis()
                                                      && !vi.assignments().isEmpty());
    }

    // phase 5: every method's body was walked; the edges and seeds that need all of them, in this order
    void complete(List<MethodInfo> methods, List<FieldInfo> fields) {
        keyedEdges(methods);
        for (FieldInfo fi : fields) seedDefaultValue(fi);
        linkEdges();
        holderFields(methods, fields);
        fieldInitializerNullArguments(fields);
        assignedParameterSlots(methods);
        enumValueOfContracts(methods);
        nullFilteredParameters(methods);
        callResults.resolveTypeVariableArguments();
        graph.coupleContent();
        invariantTies();
    }

    // the link edges counted by the walk become edges, or flows only
    private void linkEdges() {
        graph.linkEdges.forEach((edge, count) -> {
            // [0] assigned (or passed) where the source is known non-null, [1] where it is not, [2] assigned by a
            // value not seen through. Never observed at all: only the links' transitive closure. In
            // 'imp.getNestedName(root ? node.classStruct.qualifiedName : node.simpleName)' both alternatives are '≡'
            // the argument, and so each other: fernflower's ClassNode.simpleName (compared with null) reached
            // StructClass.qualifiedName and from there most of the program. A slot is written by calls, without
            // an assignment the statement shows: kept.
            // [3] a link of a method's summary (no statement): kept as before, unless guarded somewhere
            boolean slot = edge.get(1) instanceof Arg || edge.get(1) instanceof Content;
            if (policy.callResults() && !NullGraph.DIFFERENT_SHAPES_OFF.get() && graph.differentShapes(edge.get(0), edge.get(1))) return;
            if (graph.liftedIntoNarrower(edge.get(0), edge.get(1))) return;
            if (count[1] > 0 || count[2] > 0 || count[0] == 0 && slot) {
                graph.addEdge(edge.get(0), edge.get(1));
            } else {
                graph.flows.add(List.of(edge.get(0), edge.get(1))); // the value is not null there; its elements may be
            }
        });
    }

    // Kotlin's invariance ties (instantiatedSlots), once the class type variables' own slots are known
    private void invariantTies() {
        if (!graph.invariantTies.isEmpty()) {
            // a class type variable's slot is reached only by the class's own nulls (coupleContent never ties it
            // to an instantiation's slot), so that is known before the invariance edges are added
            Set<Object> own = graph.closure(new HashMap<>());
            boolean added = false;
            for (Object[] tie : graph.invariantTies) {
                if (!own.contains(tie[2])) {
                    graph.addEdge(tie[0], tie[1]);
                    added = true;
                }
            }
            if (added) graph.coupleContent(); // the nested slots of the new flows
        }
    }
}
