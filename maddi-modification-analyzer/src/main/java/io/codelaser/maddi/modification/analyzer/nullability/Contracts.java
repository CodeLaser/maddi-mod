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
import io.codelaser.maddi.cst.api.expression.ClassExpression;
import io.codelaser.maddi.cst.api.expression.ConstructorCall;
import io.codelaser.maddi.cst.api.expression.Expression;
import io.codelaser.maddi.cst.api.expression.Lambda;
import io.codelaser.maddi.cst.api.expression.MethodCall;
import io.codelaser.maddi.cst.api.expression.NullConstant;
import io.codelaser.maddi.cst.api.expression.StringConstant;
import io.codelaser.maddi.cst.api.expression.VariableExpression;
import io.codelaser.maddi.cst.api.info.FieldInfo;
import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.ParameterInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.api.statement.ReturnStatement;
import io.codelaser.maddi.cst.api.type.NullableState;
import io.codelaser.maddi.cst.api.type.ParameterizedType;
import io.codelaser.maddi.cst.api.variable.FieldReference;
import io.codelaser.maddi.cst.api.variable.This;
import io.codelaser.maddi.cst.impl.analysis.NullAnnotations;
import io.codelaser.maddi.cst.impl.analysis.PropertyImpl;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.codelaser.maddi.modification.analyzer.nullability.Types.*;

/**
 * Declared and inferred non-null contracts: null stops at a node that has one. A source declaration's null
 * annotations (Policy.contracts), the library's (the analysis hints, the class files' annotations, a null-marked
 * scope), and Kotlin's rules for overrides. Also what the use-site facts ({@link NonNullFacts}) ask about callees
 * ({@link #parameterContract}, {@link #returnContract}, {@link #ownReturnsNonNull}), and whether a library call's
 * result may be null ({@link #libraryNullableCall}).
 */
final class Contracts {
    private final Policy policy;
    private final Trace trace;
    private final Index index;
    private final Seeds seeds;
    // analysed methods whose return an earlier round found unreached by null (see NullabilityPass.go)
    private final Set<MethodInfo> trustedReturns;
    // analysed parameters an earlier round found non-null on every normal exit of their method: passing null throws
    private final Set<ParameterInfo> preconditions;
    // CodeLaser/maddi-mod#48 B6: the lookups an earlier round's report knew the key of present
    private final Set<Expression> presentLookups;
    // the facts of the walk, built once the contracts they read exist (NullabilityPass.once)
    NonNullFacts facts;

    Contracts(Policy policy, Trace trace, Index index, Seeds seeds, Set<MethodInfo> trustedReturns,
              Set<ParameterInfo> preconditions, Set<Expression> presentLookups) {
        this.policy = policy;
        this.trace = trace;
        this.index = index;
        this.seeds = seeds;
        this.trustedReturns = trustedReturns;
        this.preconditions = preconditions;
        this.presentLookups = presentLookups;
    }

    final Set<MethodCall> lookups = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    final Set<MethodInfo> degraded = new LinkedHashSet<>();
    // source declarations annotated non-null (Policy.contracts): null stops there
    final Set<Object> nonNullContracts = new HashSet<>();

    // a non-null contract on a node; NULLWHY logs the code that put it
    boolean add(Object o) {
        boolean added = nonNullContracts.add(o);
        if (added && trace.why(o)) trace.log("contract", o, null);
        return added;
    }

    void addAll(Collection<?> nodes) {
        for (Object o : nodes) add(o);
    }

    /*
     Kotlin: an override of a method returning a class type variable with an explicit bound ('R execute(P)' in
     'GuardrailExecutor<..., R extends GuardrailResult<R>, ...>') returns the type argument its class passes for R,
     which the bound keeps non-null: Kotlin refuses 'execute(...): OutputGuardrailResult?' as an override of 'R'
     (langchain4j OutputGuardrailExecutor, whose 'return result' after the retry loop is reached only by the
     'result = null' declaration, the loop running at least once). Its return is non-null; a null value is asserted.
     */
    /*
     Kotlin: a member of its own collection interfaces (Iterable, Iterator, Collection, List, Set, Map, Map.Entry, ...)
     that returns its type variable returns it non-null ('Iterator<E>.next(): E'), unless Kotlin declares it nullable
     (Map.get, put, remove: the hints say so). An override may not widen it: guava's LocalCache.HashIterator<T>.next(),
     and EntryIterator.next() below it.
     */
    boolean overridesKotlinNonNullReturn(MethodInfo mi) {
        if (mi.isConstructor() || mi.returnType().isVoid()) return false;
        for (MethodInfo overridden : overrides(mi)) {
            if (!index.analysed.contains(overridden) && isTypeVariable(overridden.returnType())
                && KOTLIN_COLLECTIONS.contains(overridden.typeInfo().fullyQualifiedName())
                && stateOf(overridden, PropertyImpl.NULLABILITY_METHOD) != NullableState.NULLABLE) {
                return true;
            }
        }
        return false;
    }

    static final Set<String> KOTLIN_COLLECTIONS = Set.of("java.lang.Iterable", "java.util.Iterator",
            "java.util.ListIterator", "java.util.Collection", "java.util.List", "java.util.Set", "java.util.Map",
            "java.util.Map.Entry");

    /*
     Kotlin: the analysed overrides of a return the pass holds non-null (nonNullContracts: an annotation, the rules
     above) are non-null too, a null value asserted: an override may not widen its overridden member's return.
     */
    void nonNullOverrides(List<MethodInfo> methods) {
        java.util.ArrayDeque<MethodInfo> work = new java.util.ArrayDeque<>();
        for (MethodInfo mi : methods) if (nonNullContracts.contains(mi)) work.add(mi);
        while (!work.isEmpty()) {
            MethodInfo m = work.poll();
            for (MethodInfo implementation : index.implementations.getOrDefault(m, List.of())) {
                if (!implementation.returnType().isVoid() && add(implementation)) work.add(implementation);
            }
        }
    }

    /*
     Kotlin, a test's override of a main member (guava's tests, worktree-maddi-all 2026-10-16: ForwardingDequeTest's
     anonymous Function, DiscreteDomainTest.MyIntegerDomain.next): its parameters, type arguments included, take
     main's states exactly, as an override of a library member does; what the tests pass is asserted at their calls.
     Its return is not clamped: a null it returns widens main's return through the upward edge (buildForMethod).
     */
    void clampTestOverrides(List<MethodInfo> methods, Map<Info, ParameterizedType> verdicts) {
        for (MethodInfo mi : methods) {
            if (mi.isConstructor() || !testCode(mi)) continue;
            for (MethodInfo overridden : overrides(mi)) {
                if (!index.analysed.contains(overridden) || testCode(overridden)) continue;
                int n = Math.min(mi.parameters().size(), overridden.parameters().size());
                for (int i = 0; i < n; i++) {
                    ParameterInfo ours = mi.parameters().get(i);
                    ParameterizedType theirs = verdicts.get(overridden.parameters().get(i));
                    if (verdicts.containsKey(ours) && theirs != null) {
                        verdicts.put(ours, clamped(verdicts.get(ours), theirs));
                    }
                }
            }
        }
    }

    /*
     Kotlin: an override's parameter has the overridden parameter's type, which for a type variable is the
     instantiation's: 'apply(input: F)' over java.util.function.Function's 'apply(p0: F)', 'compareTo(that: Cut<C>)'
     over Comparable's 'compareTo(other: T)'. A null passed to the override belongs to that instantiation, not to the
     override's own parameter, or kotlinc finds it overrides nothing (guava, worktree-maddi-all 2026-10-17: 14
     errors). Not where the override says @Nullable itself ('Equivalence.test(@Nullable T t, ...)' over
     BiPredicate<@Nullable T, @Nullable T>): the supertype's argument carries that. Overridden analysed parameters
     first, so a chain settles (a few passes).
     */
    void clampTypeVariableOverrides(List<MethodInfo> methods, Map<Info, ParameterizedType> verdicts) {
        if (TYPE_VARIABLE_OVERRIDES_OFF.get()) return;
        for (int pass = 0; pass < 3; pass++) {
            boolean changed = false;
            for (MethodInfo mi : methods) {
                if (mi.isConstructor()) continue;
                for (MethodInfo overridden : mi.overrides()) {
                    int n = Math.min(mi.parameters().size(), overridden.parameters().size());
                    for (int i = 0; i < n; i++) {
                        ParameterInfo ours = mi.parameters().get(i);
                        ParameterInfo their = overridden.parameters().get(i);
                        if (!isTypeVariable(their.parameterizedType()) || !verdicts.containsKey(ours)
                            || explicitState(ours) == NullableState.NULLABLE) {
                            continue;
                        }
                        ParameterizedType theirs;
                        if (index.analysed.contains(overridden)) {
                            theirs = verdicts.get(their);
                        } else {
                            NullableState state = libraryParameterState(their);
                            if (state == NullableState.NULLABLE) continue;
                            theirs = their.parameterizedType().withNullable(state);
                        }
                        if (theirs == null) continue;
                        ParameterizedType before = verdicts.get(ours);
                        ParameterizedType after = clamped(before, theirs);
                        if (!after.equals(before)) {
                            verdicts.put(ours, after);
                            changed = true;
                        }
                    }
                }
            }
            if (!changed) return;
        }
    }

    // gate NOTYPEVAROVERRIDES: an override keeps its own parameter state over a type-variable parameter (A/B)
    private static final Switches.Gate TYPE_VARIABLE_OVERRIDES_OFF = Switches.gate("NOTYPEVAROVERRIDES");

    // 'ours' with the overridden type's states, type arguments included
    static ParameterizedType clamped(ParameterizedType ours, ParameterizedType theirs) {
        if (ours.isPrimitiveExcludingVoid() && ours.arrays() == 0) return ours;
        NullableState top = theirs.nullable();
        // a type variable's own state stands for the instantiation's type, non-null as written ('F' as 'Deque<*>')
        if (top == NullableState.UNSPECIFIED && !isTypeVariable(ours)) top = NullableState.NONNULL;
        ParameterizedType result = ours.withNullable(top);
        if (ours.arrays() == 0 && theirs.arrays() == 0 && ours.typeInfo() != null && ours.typeInfo() == theirs.typeInfo()
            && ours.parameters().size() == theirs.parameters().size() && !ours.parameters().isEmpty()) {
            List<ParameterizedType> arguments = new ArrayList<>();
            for (int j = 0; j < ours.parameters().size(); j++) {
                arguments.add(clamped(ours.parameters().get(j), theirs.parameters().get(j)));
            }
            result = result.withParameters(arguments);
        }
        return result;
    }

    /*
     Not where the override declares its return @Nullable (guava MapMakerInternalMap's Helper.copy and
     Segment.castForTesting over 'E copy(...)', 'abstract E castForTesting(...)', E bounded by InternalEntry<K, V, E>):
     'E?' is a valid return for a bounded E, so the overridden member takes the null through the upward edge, as for
     any analysed type-variable return (MapMakerInternalMap.kt, 7 "not a subtype of the overridden member").
     Gate NOEXPLICITBOUNDED.
     */
    private static final Switches.Gate RETURNS_NULL_BOUNDED_OFF = Switches.gate("NORETURNSNULLBOUNDED");

    private static boolean returnsNullLiteral(MethodInfo mi) {
        if (mi.methodBody() == null) return false;
        boolean[] found = {false};
        mi.methodBody().visit(e -> {
            if (e instanceof Lambda || e instanceof ConstructorCall cc && cc.anonymousClass() != null) return false;
            if (e instanceof ReturnStatement rs && rs.expression() != null
                && NonNullFacts.unwrap(rs.expression()) instanceof NullConstant) found[0] = true;
            return !found[0];
        });
        return found[0];
    }

    static boolean boundedTypeVariableOverride(MethodInfo mi) {
        if (mi.isConstructor() || mi.returnType().isVoid() || mi.returnType().typeParameter() != null) return false;
        if (!EXPLICIT_BOUNDED_OFF.get() && NullAnnotations.explicitState(mi) == NullableState.NULLABLE) return false;
        // nor where the override returns a null literal itself ('else { return null; }' in nacos
        // NacosDelayTaskExecuteEngine.removeTask over 'T removeTask(Object)', T bounded by NacosTask: Kotlin had
        // 'return null' in a non-null function, NacosDelayTaskExecuteEngine.java:90, 2026-10-19). Gate NORETURNSNULLBOUNDED.
        if (!RETURNS_NULL_BOUNDED_OFF.get() && returnsNullLiteral(mi)) return false;
        for (MethodInfo overridden : mi.overrides()) {
            ParameterizedType rt = overridden.returnType();
            if (isClassTypeVariable(rt) && !rt.typeParameter().typeBounds().isEmpty()
                && rt.typeParameter().typeBounds().stream().noneMatch(b -> b.typeInfo() != null
                        && "java.lang.Object".equals(b.typeInfo().fullyQualifiedName()))) {
                return true;
            }
        }
        return false;
    }

    /*
     Kotlin: what a declaration's null annotations say about the declared value itself. The front end puts a type-use
     annotation written in declaration position on an array's ELEMENT type (JLS 9.7.4: '@Nullable Entry<K, V>[]
     entries' has nullable elements, a non-null array), but NullAnnotations also reads it from the declaration's own
     list, as about the array. guava's ImmutableMap.Builder.entries then made the array nullable too, and with it
     RegularImmutableMap's and RegularImmutableBiMap's entries parameters, 'Array<Entry<K, V>?>?' (RegularImmutableMap.kt:63,
     RegularImmutableBiMap.kt:113, 2026-10-17). On an array, an annotation the element carries does not count for the
     array; one written on the array type ('X @Nullable []') and a declaration-only one (JSR-305) still do.
     Gate NOELEMENTANNOTATIONS.
     */
    NullableState explicitState(Info info) {
        NullableState state = NullAnnotations.explicitState(info);
        if (state == null || !policy.callResults() || ELEMENT_ANNOTATIONS_OFF.get()) return state;
        ParameterizedType type = switch (info) {
            case FieldInfo fi -> fi.type();
            case ParameterInfo pi -> pi.parameterizedType();
            case MethodInfo mi -> mi.returnType();
            default -> null;
        };
        if (type == null || type.arrays() == 0) return state;
        ParameterizedType element = type;
        while (element.arrays() > 0 && element.componentType() != null) element = element.componentType();
        Set<String> onElement = new HashSet<>();
        for (var ae : element.annotations()) if (ae.typeInfo() != null) onElement.add(ae.typeInfo().fullyQualifiedName());
        boolean nonNull = false;
        // the array type's own annotations ('X @Nullable []', guava's RegularImmutableMap.table) are about the array;
        // the declaration's list holds a parameter's type-use annotation, which the front end also moved to the
        // element, and a field's, which it did not: there the annotation type's @Target decides
        List<io.codelaser.maddi.cst.api.expression.AnnotationExpression> own = new ArrayList<>(type.annotations());
        for (var ae : info.annotations()) {
            if (ae.typeInfo() != null && !onElement.contains(ae.typeInfo().fullyQualifiedName())
                && !typeUseOnly(ae.typeInfo())) {
                own.add(ae);
            }
        }
        for (var ae : own) {
            if (ae.typeInfo() == null) continue;
            if ("io.codelaser.maddi.annotation".equals(ae.typeInfo().packageName()) && ae.extractBoolean("absent")) continue;
            String name = ae.typeInfo().simpleName();
            if (NullAnnotations.NULLABLE.contains(name)) return NullableState.NULLABLE;
            if (NullAnnotations.NON_NULL.contains(name)) nonNull = true;
        }
        return nonNull ? NullableState.NONNULL : null;
    }

    // Kotlin: a type-use @Nullable in a field's declaration position, which the front end leaves off the element
    // type: it is about the elements (explicitState)
    private boolean nullableElementsByDeclaration(Info info) {
        if (!policy.callResults() || ELEMENT_ANNOTATIONS_OFF.get() || !(info instanceof FieldInfo fi)) return false;
        ParameterizedType type = fi.type();
        // one dimension only: in '@Nullable V[][] values' (guava DenseImmutableTable, ArrayTable) the innermost V is
        // nullable, not the rows, and the pass has no node for an element's element
        if (type.arrays() != 1 || type.componentType() == null) return false;
        if (type.componentType().annotations().stream().anyMatch(NullAnnotations::isNullnessAnnotation)) return false;
        return info.annotations().stream().anyMatch(ae -> ae.typeInfo() != null && typeUseOnly(ae.typeInfo())
                && NullAnnotations.NULLABLE.contains(ae.typeInfo().simpleName()));
    }

    // an annotation type whose @Target is TYPE_USE (or TYPE_PARAMETER) only: JSpecify's, Checker's; not JSR-305's
    private static boolean typeUseOnly(TypeInfo annotationType) {
        for (var target : annotationType.annotations()) {
            if (target.typeInfo() == null
                || !"java.lang.annotation.Target".equals(target.typeInfo().fullyQualifiedName())) continue;
            String targets = target.keyValuePairs().stream().map(kv -> String.valueOf(kv.value()))
                    .collect(java.util.stream.Collectors.joining(","));
            return targets.contains("TYPE_USE") && !targets.contains("FIELD") && !targets.contains("PARAMETER")
                   && !targets.contains("METHOD");
        }
        return false;
    }

    // gate NOELEMENTANNOTATIONS: an array's element annotation in declaration position counts for the array (A/B)
    private static final Switches.Gate ELEMENT_ANNOTATIONS_OFF = Switches.gate("NOELEMENTANNOTATIONS");

    // a source declaration's null annotation, under Policy.contracts: nullable seeds, non-null stops null
    void contract(Info info, Object node) {
        NullableState state = explicitState(info);
        if (state == NullableState.NULLABLE) seeds.seed(node, "annotated nullable");
        else if (state == NullableState.NONNULL) add(node);
        if (nullableElementsByDeclaration(info)) seeds.seed(new Content(node), "annotated nullable elements");
    }

    ParameterizedType contracted(Info info, ParameterizedType verdict) {
        if (!policy.contracts() || verdict.isPrimitiveExcludingVoid() && verdict.arrays() == 0) return verdict;
        ParameterizedType declared = switch (info) {
            case FieldInfo fi -> fi.type();
            case ParameterInfo pi -> pi.parameterizedType();
            case MethodInfo mi -> mi.returnType();
            default -> verdict;
        };
        ParameterizedType v = nestedContracted(declared, verdict);
        if (nullableElementsByDeclaration(info) && v.arrays() == 1 && v.componentType() != null) {
            v = v.withComponentType(v.componentType().withNullable(NullableState.NULLABLE));
        }
        NullableState state = explicitState(info);
        return state == null ? v : v.withNullable(state);
    }

    // the annotations written on an array's elements and on type arguments, over the inferred states
    private static ParameterizedType nestedContracted(ParameterizedType declared, ParameterizedType verdict) {
        if (declared.arrays() > 0 && verdict.arrays() == declared.arrays()) {
            ParameterizedType component = nestedContracted(declared.componentType(), verdict.componentType());
            NullableState element = explicitElementState(declared);
            if (element != null && !(component.isPrimitiveExcludingVoid() && component.arrays() == 0)) {
                component = component.withNullable(element);
            }
            return verdict.withComponentType(component);
        }
        if (declared.parameters().isEmpty() || declared.parameters().size() != verdict.parameters().size()) {
            return verdict;
        }
        List<ParameterizedType> args = new ArrayList<>();
        for (int i = 0; i < declared.parameters().size(); i++) {
            ParameterizedType d = declared.parameters().get(i);
            ParameterizedType a = nestedContracted(d, verdict.parameters().get(i));
            NullableState state = explicit(d.annotations());
            args.add(state == null ? a : a.withNullable(state));
        }
        ParameterizedType withArgs = verdict.withParameters(args);
        return withArgs;
    }

    // the null annotation on an array's elements, as the front end placed it (ParameterizedType.componentType)
    private static NullableState explicitElementState(ParameterizedType declared) {
        return explicit(declared.componentType().annotations());
    }

    // Policy.contracts, for an array's elements and the type arguments ('List<@Nullable String>'), recursively: a
    // nullable one is a seed, a non-null one stops null
    void elementContract(ParameterizedType declared, Object node) {
        if (declared.arrays() > 0) {
            Content content = new Content(node);
            nestedContract(explicitElementState(declared), content);
            elementContract(declared.componentType(), content);
            return;
        }
        for (int i = 0; i < declared.parameters().size(); i++) {
            ParameterizedType argument = declared.parameters().get(i);
            Arg arg = new Arg(node, i);
            nestedContract(explicit(argument.annotations()), arg);
            elementContract(argument, arg);
        }
    }

    private void nestedContract(NullableState state, Object slot) {
        if (state == NullableState.NULLABLE) seeds.seed(slot, "annotated nullable");
        else if (state == NullableState.NONNULL) add(slot);
    }

    // what a callee promises, for the use-site facts: a library method's hints; an analysed one's annotation
    NullableState parameterContract(ParameterInfo pi) {
        if (!index.analysed.contains(pi.methodInfo())) return stateOf(pi, PropertyImpl.NULLABILITY_PARAMETER);
        if (preconditions.contains(pi)) return NullableState.NONNULL;
        return policy.contracts() ? explicitState(pi) : null;
    }

    /*
     Kotlin: a call through 'super' runs the method's own body, whatever its overrides return. Non-null when every
     return of that body is non-null by itself ('return new NullsFirstOrdering<S>(this)'), with no facts. guava's
     NaturalOrdering caches 'result = nullsFirst = super.<Comparable<?>>nullsFirst()' in an @Nullable field: the
     override family tied Ordering.nullsFirst()'s return to NaturalOrdering's, so the super call was nullable, the
     cached 'result' too, and so were nullsFirst(), nullsLast() and reverse() across Ordering's whole family
     (NaturalOrdering.kt:21, NullsFirstOrdering.kt:14, Ordering.kt:23; 2026-10-17).
     */
    boolean ownReturnsNonNull(MethodInfo mi) {
        if (!index.analysed.contains(mi) || mi.methodBody() == null || mi.isAbstract()) return false;
        Boolean known = ownReturnsNonNull.get(mi);
        if (known != null) return known;
        ownReturnsNonNull.put(mi, false); // a cycle (not through super) is not proof
        boolean[] any = {false};
        boolean[] all = {true};
        mi.methodBody().visit(e -> {
            if (e instanceof Lambda || e instanceof ConstructorCall cc && cc.anonymousClass() != null) return false;
            if (e instanceof ReturnStatement rs) {
                any[0] = true;
                if (rs.expression() == null || !facts.nonNull(rs.expression(), Set.of())) all[0] = false;
            }
            return all[0];
        });
        boolean result = any[0] && all[0];
        ownReturnsNonNull.put(mi, result);
        return result;
    }

    final Map<MethodInfo, Boolean> ownReturnsNonNull = new HashMap<>();
    // gate NOSUPERCALLS: a call through super returns what the method's override family does (A/B)
    static final Switches.Gate SUPER_CALLS_OFF = Switches.gate("NOSUPERCALLS");

    NullableState returnContract(MethodInfo mi) {
        if (!index.analysed.contains(mi)) {
            NullableState hint = stateOf(mi, PropertyImpl.NULLABILITY_METHOD);
            if (hint == NullableState.UNSPECIFIED && policy.callResults() && collectionsFactory(mi)) {
                return NullableState.NONNULL;
            }
            return hint;
        }
        if (trustedReturns.contains(mi)) return NullableState.NONNULL;
        return policy.contracts() ? explicitState(mi) : null;
    }

    /*
     Kotlin: java.util.Collections' static methods return a new or wrapping object ('unmodifiableMap', 'emptyList',
     'synchronizedSet'), never null, unless they return an element ('max(Collection<T>)'). Without it guava's lazy
     caches, 'result = map; if (result == null) { result = map = Collections.unmodifiableMap(...); } return result',
     made Multimap.asMap() and values() nullable across their override families (2026-10-17).
     */
    private static boolean collectionsFactory(MethodInfo mi) {
        return !COLLECTIONS_FACTORIES_OFF.get() && mi.isStatic() && !isTypeVariable(mi.returnType())
               && !mi.returnType().isPrimitiveExcludingVoid() && !mi.returnType().isVoid()
               && "java.util.Collections".equals(mi.typeInfo().fullyQualifiedName());
    }

    // gate NOCOLLECTIONSFACTORIES: a Collections factory's return is unknown, as any unhinted library return (A/B)
    private static final Switches.Gate COLLECTIONS_FACTORIES_OFF = Switches.gate("NOCOLLECTIONSFACTORIES");

    /** A library method, {@code mi} or one it overrides, whose return may be null; null when there is none. */
    String libraryNullableReturn(MethodInfo mi) {
        List<MethodInfo> nearest = nearestLibraryDeclarations(mi);
        if (nearest.stream().anyMatch(m -> libraryReturnState(m) == NullableState.NONNULL)) return null;
        return nearest.stream().filter(m -> libraryReturnState(m) == NullableState.NULLABLE)
                .map(MethodInfo::fullyQualifiedName).findFirst().orElse(null);
    }

    /*
     The library declarations of mi's return that say something, the nearest ones: Spring Security 7's
     UsernamePasswordAuthenticationFilter.attemptAuthentication (@NullMarked, non-null) over
     AbstractAuthenticationProcessingFilter's '@Nullable Authentication attemptAuthentication' (jenkins
     AuthenticationProcessingFilter2.kt:36, 2026-10-20). Gate NONEARESTLIBRARYRETURN: any one that says nullable.
     */
    private List<MethodInfo> nearestLibraryDeclarations(MethodInfo mi) {
        List<MethodInfo> declared = java.util.stream.Stream.concat(java.util.stream.Stream.of(mi), overrides(mi).stream())
                .filter(m -> !index.analysed.contains(m))
                .filter(m -> libraryReturnState(m) != NullableState.UNSPECIFIED)
                .toList();
        if (NEAREST_LIBRARY_RETURN_OFF.get() || declared.size() < 2) return declared;
        return declared.stream()
                .filter(m -> declared.stream().noneMatch(o -> o != m && o.typeInfo() != m.typeInfo()
                        && o.typeInfo().superTypesExcludingJavaLangObject().contains(m.typeInfo())))
                .toList();
    }

    private static final Switches.Gate NEAREST_LIBRARY_RETURN_OFF = Switches.gate("NONEARESTLIBRARYRETURN");

    /*
     Kotlin: an override of a library method whose return is declared non-null may not widen it ('override fun
     attemptAuthentication(...): Authentication?' over Spring 7's non-null one): its return is non-null, a null value
     asserted. Gate NONEARESTLIBRARYRETURN.
     */
    boolean overridesLibraryNonNullReturn(MethodInfo mi) {
        if (NEAREST_LIBRARY_RETURN_OFF.get() || mi.isConstructor() || mi.returnType().isVoid()
            || mi.returnType().isPrimitiveExcludingVoid() && mi.returnType().arrays() == 0
            || NullAnnotations.explicitState(mi) == NullableState.NULLABLE) return false;
        return overrides(mi).stream().anyMatch(m -> !index.analysed.contains(m)
                                                    && libraryReturnState(m) == NullableState.NONNULL);
    }

    /*
     What a library method's return declares: the hints first, then the class file's own annotations, as for a
     library parameter (libraryParameterState). Without the second, only the JDK hints seeded: io.kubernetes.client's
     model getters, '@jakarta.annotation.Nullable V1EndpointSubset getSubsets()', never did (nacos K8sSyncServer,
     21 SpotBugs results we missed, 2026-10-10). A @NullMarked scope makes an unannotated return non-null: it seeds
     nothing, but it is the nearest declaration (nearestLibraryDeclarations), and an override may not widen it.
     */
    private static NullableState libraryReturnState(MethodInfo mi) {
        NullableState hint = stateOf(mi, PropertyImpl.NULLABILITY_METHOD);
        if (hint != NullableState.UNSPECIFIED) return hint;
        NullableState explicit = NullAnnotations.explicitState(mi);
        if (explicit != null) return explicit;
        // a @NullMarked scope's unannotated return that is not a type variable (Spring Security 7)
        if (!NEAREST_LIBRARY_RETURN_OFF.get() && !mi.isConstructor() && !mi.returnType().isVoid()
            && !isTypeVariable(mi.returnType()) && NullAnnotations.inNullMarkedScope(mi)) {
            return NullableState.NONNULL;
        }
        return NullableState.UNSPECIFIED;
    }

    /*
     The content an overridden library method declares nullable, one level down (the hints' nullableArguments, from
     JSpecify): the elements of 'Object[] toArray()' (guava's overrides of Collection.toArray()), the elements of
     InvocationHandler.invoke's 'args'. Seeded on the override's own node, the return or the parameter, where the
     override declares the same shape (the same class or array of it), so that the positions correspond.
     */
    void libraryNullableContent(Object node, ParameterizedType declared,
                                java.util.function.Function<MethodInfo, Value.Nullability> nullabilityOf,
                                java.util.function.Function<MethodInfo, ParameterizedType> typeOf) {
        MethodInfo mi = node instanceof ParameterInfo pi ? pi.methodInfo() : (MethodInfo) node;
        if (declared == null || declared.isVoid() || declared.isPrimitiveExcludingVoid() && declared.arrays() == 0) return;
        for (MethodInfo m : overrides(mi)) {
            if (index.analysed.contains(m)) continue;
            ParameterizedType theirs = typeOf.apply(m);
            if (theirs == null || theirs.arrays() != declared.arrays() || theirs.typeInfo() != declared.typeInfo()
                || theirs.parameters().size() != declared.parameters().size()) continue;
            List<? extends Value.Nullability> arguments = nullabilityOf.apply(m).arguments();
            for (int j = 0; j < arguments.size(); j++) {
                if (arguments.get(j).state() != NullableState.NULLABLE) continue;
                Object content = declared.arrays() > 0 ? new Content(node) : new Arg(node, j);
                seeds.seed(content, "overrides " + m.fullyQualifiedName() + ", whose "
                                    + (declared.arrays() > 0 ? "elements" : "type argument " + j) + " may be null");
            }
        }
    }

    /** A library method, {@code mi} or one it overrides, whose parameter {@code index} accepts null. */
    String libraryNullableParameter(MethodInfo mi, int index) {
        return java.util.stream.Stream.concat(java.util.stream.Stream.of(mi), overrides(mi).stream())
                .filter(m -> !this.index.analysed.contains(m) && index < m.parameters().size())
                .filter(m -> libraryParameterState(m.parameters().get(index)) == NullableState.NULLABLE)
                .map(MethodInfo::fullyQualifiedName).findFirst().orElse(null);
    }

    /*
     What a library parameter declares: the hints first, then the class file's own annotations (JSpecify's
     @Nullable on a type use), then its @NullMarked scope -- method, types, package-info, module -- where an
     unannotated parameter that is not a type variable is non-null (JUnit 6's ArgumentsProvider.provideArguments:
     a nullable override parameter does not override it in Kotlin). UNSPECIFIED when nothing says.
     */
    private static NullableState libraryParameterState(ParameterInfo pi) {
        NullableState hint = stateOf(pi, PropertyImpl.NULLABILITY_PARAMETER);
        if (hint != NullableState.UNSPECIFIED) return hint;
        if (containerCallback(pi.methodInfo())) return NullableState.NONNULL;
        NullableState explicit = NullAnnotations.explicitState(pi);
        if (explicit != null) return explicit;
        if (!isTypeVariable(pi.parameterizedType()) && NullAnnotations.inNullMarkedScope(pi.methodInfo())) {
            return NullableState.NONNULL;
        }
        return NullableState.UNSPECIFIED;
    }

    /*
     The servlet container's callbacks: it never passes a null request, response, chain or configuration, though the
     servlet API declares nothing (jenkins: a 'Filter.doFilter' override's 'response' had reached Spring Security's
     @NullMarked RememberMeServices.loginFail, BasicHeaderProcessor.kt:94, HttpSessionContextIntegrationFilter2.kt:34,
     2026-10-20). Gate NOCONTAINERCALLBACKS.
     */
    private static final List<String> CONTAINER_CALLBACKS = List.of("jakarta.servlet.Filter.", "javax.servlet.Filter.",
            "jakarta.servlet.Servlet.", "javax.servlet.Servlet.", "jakarta.servlet.FilterChain.doFilter(",
            "javax.servlet.FilterChain.doFilter(", "jakarta.servlet.http.HttpServlet.", "javax.servlet.http.HttpServlet.",
            "jakarta.servlet.GenericServlet.", "javax.servlet.GenericServlet.");

    private static final Switches.Gate CONTAINER_CALLBACKS_OFF = Switches.gate("NOCONTAINERCALLBACKS");

    private static boolean containerCallback(MethodInfo mi) {
        if (CONTAINER_CALLBACKS_OFF.get() || mi.isConstructor() || mi.isStatic()) return false;
        String fqn = mi.fullyQualifiedName();
        return CONTAINER_CALLBACKS.stream().anyMatch(fqn::startsWith);
    }

    /*
     Kotlin: a library parameter declared non-null (@NullMarked, a hint) is a contract: a caller's null is asserted
     at the call, and does not flow on through it into the analysed implementations (Spring Security 7's
     RememberMeServices.loginFail(request, response) had carried a null into RememberMeServicesProxy's override,
     jenkins RememberMeServicesProxy.kt:20, 2026-10-20). Gate NOLIBRARYCONTRACTS.
     */
    void libraryParameterContracts(Map<Object, Set<Object>> successors) {
        if (LIBRARY_CONTRACTS_OFF.get() || !policy.callResults()) return;
        Set<ParameterInfo> seen = new HashSet<>();
        for (Set<Object> targets : successors.values()) {
            for (Object t : targets) {
                if (t instanceof ParameterInfo pi && !index.analysed.contains(pi.methodInfo()) && seen.add(pi)
                    && libraryParameterState(pi) == NullableState.NONNULL) {
                    add(pi);
                }
            }
        }
    }

    private static final Switches.Gate LIBRARY_CONTRACTS_OFF = Switches.gate("NOLIBRARYCONTRACTS");

    /*
     A library method mi overrides (a real override, not a lambda's functional method) whose parameter 'index'
     nothing declares (libraryParameterState): the library may call the override back with null (fernflower's
     SimpleFileVisitor.postVisitDirectory before its hint). Not a type-variable parameter (the instantiation decides),
     a primitive, or one any overridden library method declares non-null.
     */
    String libraryUnhintedParameter(MethodInfo mi, int index) {
        if (libraryNonNullParameter(mi, index)) return null;
        return mi.overrides().stream()
                .filter(m -> !this.index.analysed.contains(m) && index < m.parameters().size())
                .filter(m -> {
                    ParameterizedType type = m.parameters().get(index).parameterizedType();
                    return !isTypeVariable(type) && !(type.isPrimitiveExcludingVoid() && type.arrays() == 0)
                           && libraryParameterState(m.parameters().get(index)) == NullableState.UNSPECIFIED;
                })
                .map(MethodInfo::fullyQualifiedName).findFirst().orElse(null);
    }

    /** A library method {@code mi} overrides declares parameter {@code index} non-null: its callers honour that. */
    boolean libraryNonNullParameter(MethodInfo mi, int index) {
        return overrides(mi).stream()
                .filter(m -> !this.index.analysed.contains(m) && index < m.parameters().size())
                .anyMatch(m -> libraryParameterState(m.parameters().get(index)) == NullableState.NONNULL);
    }

    /*
     Kotlin: an implementation whose class passes a nullable type argument for the overridden return's type variable
     ('new Tables.AbstractCell<R, C, @Nullable V>()' in guava's ArrayTable.getCell, over Table.Cell's
     '@ParametricNullness V getValue()'): its null belongs to that instantiation, which the printer writes as
     'AbstractCell<R, C, V?>', so 'getValue(): V?' overrides 'getValue(): V' as it is. Without this, the upward edge
     made Cell.getValue V? and AbstractTable's callers failed (worktree-maddi-all, 2026-10-27). A direct supertype
     only. Gate NONULLABLEINSTANTIATION.
     */
    static boolean nullableInstantiation(MethodInfo mi, MethodInfo overridden) {
        if (NULLABLE_INSTANTIATION_OFF.get()) return false;
        ParameterizedType argument = instantiation(mi, overridden);
        return argument != null && (argument.nullable() == NullableState.NULLABLE || argument.annotations().stream()
                .anyMatch(ae -> ae.typeInfo() != null && NullAnnotations.NULLABLE.contains(ae.typeInfo().simpleName())));
    }

    // the type argument a direct supertype of mi's class passes for the overridden return's class type variable
    private static ParameterizedType instantiation(MethodInfo mi, MethodInfo overridden) {
        ParameterizedType rt = overridden.returnType();
        if (!isClassTypeVariable(rt) || rt.typeParameter().getOwner().getLeft() != overridden.typeInfo()) return null;
        int index = rt.typeParameter().getIndex();
        List<ParameterizedType> supertypes = new ArrayList<>();
        if (mi.typeInfo().parentClass() != null) supertypes.add(mi.typeInfo().parentClass());
        supertypes.addAll(mi.typeInfo().interfacesImplemented());
        for (ParameterizedType st : supertypes) {
            if (st.typeInfo() == null) continue;
            ParameterizedType formal = st.typeInfo().asParameterizedType();
            ParameterizedType view = st.typeInfo() == overridden.typeInfo() ? formal
                    : formal.concreteSuperType(overridden.typeInfo().asParameterizedType());
            if (view == null || index >= view.parameters().size()) continue;
            ParameterizedType a = view.parameters().get(index);
            if (a.arrays() > 0 || a.typeParameter() == null || a.typeParameter().isMethodTypeParameter()
                || a.typeParameter().getOwner().getLeft() != st.typeInfo()) continue;
            int j = a.typeParameter().getIndex();
            if (j < st.parameters().size()) return st.parameters().get(j);
        }
        return null;
    }

    /*
     Kotlin: a test's override of a main member's type-variable return, in a class that instantiates the variable with
     a concrete type ('new Tables.AbstractCell<Character, Integer, String>() { String getValue() { return
     holder.string; } }' in guava's ImmutableTableTest, the field @Nullable): its result is that instantiation's
     String, non-null, its null value asserted. Without this, the test's null made main's Table.Cell.getValue V? and
     AbstractTable failed (worktree-maddi-all, 2026-10-27). Not where the test returns a null literal, which the printer
     cannot assert (the upward edge of buildForMethod keeps that one). Gate NOTESTINSTANTIATION.
     */
    boolean testOverrideOfInstantiatedReturn(MethodInfo mi) {
        if (TEST_INSTANTIATION_OFF.get() || mi.isConstructor() || mi.returnType().isVoid() || !testCode(mi)
            || returnsNullLiteral(mi)) return false;
        for (MethodInfo overridden : overrides(mi)) {
            if (!index.analysed.contains(overridden) || testCode(overridden)) continue;
            ParameterizedType argument = instantiation(mi, overridden);
            if (argument != null && argument.typeParameter() == null && argument.wildcard() == null
                && argument.nullable() != NullableState.NULLABLE && argument.annotations().stream().noneMatch(ae ->
                    ae.typeInfo() != null && NullAnnotations.NULLABLE.contains(ae.typeInfo().simpleName()))) {
                return true;
            }
        }
        return false;
    }

    // gate NOTESTINSTANTIATION: a test override's null reaches main's type-variable return (A/B)
    private static final Switches.Gate TEST_INSTANTIATION_OFF = Switches.gate("NOTESTINSTANTIATION");

    // gate NONULLABLEINSTANTIATION: an implementation's null reaches the overridden type-variable return (A/B)
    private static final Switches.Gate NULLABLE_INSTANTIATION_OFF = Switches.gate("NONULLABLEINSTANTIATION");

    String nullableLibraryCall(Expression e) {
        Expression unwrapped = e instanceof Cast c ? c.expression() : e;
        if (unwrapped instanceof MethodCall mc && mc.methodInfo() != null) return libraryNullableCall(mc);
        return null;
    }

    /*
     A library call that may return null, at this call: not 'map.get(k)' with k known present (NonNullFacts,
     CodeLaser/maddi-mod#22 gap 1), not 'x.m()' after its own null test (gap 8: 'if (ioe.getMessage() != null) {
     String errMsg = ioe.getMessage(); ...'), not 'computeIfAbsent(k, X::new)', not 'TL.get()' on a
     'ThreadLocal.withInitial(X::new)' (gap 1's residue), not 'getClassLoader()' on the class of a type of this analysis, which the
     bootstrap loader never loads ('getClass().getClassLoader()', 'X.class.getClassLoader()'; nacos 2026-10-10).
     */
    String libraryNullableCall(MethodCall mc) {
        if (mc.methodInfo() != null && !index.analysed.contains(mc.methodInfo())) lookups.add(mc);
        if (mc.methodInfo() == null || facts.keyPresentWhenCalled(mc) || presentLookups.contains(mc)
            || facts.pureCallNonNullWhenCalled(mc)
            || NonNullFacts.computedNonNull(mc) || NonNullFacts.initialThreadLocal(mc)
            || applicationClassLoader(mc) || standardSystemProperty(mc)) return null;
        return libraryNullableReturn(mc.methodInfo());
    }

    /*
     The keys System.getProperties() guarantees (its javadoc's table): 'System.getProperty("os.name")' is never null
     (nacos, CodeLaser/maddi-mod#22, after gap 9). A key known only at run time stays the library's null.
     */
    private static final Set<String> STANDARD_PROPERTIES = Set.of("java.version", "java.version.date", "java.vendor",
            "java.vendor.url", "java.home", "java.vm.specification.version", "java.vm.specification.vendor",
            "java.vm.specification.name", "java.vm.version", "java.vm.vendor", "java.vm.name",
            "java.specification.version", "java.specification.vendor", "java.specification.name",
            "java.class.version", "java.class.path", "java.library.path", "java.io.tmpdir", "os.name", "os.arch",
            "os.version", "file.separator", "path.separator", "line.separator", "user.name", "user.home", "user.dir",
            "native.encoding", "file.encoding");

    private static boolean standardSystemProperty(MethodCall mc) {
        return "java.lang.System.getProperty(String)".equals(mc.methodInfo().fullyQualifiedName())
               && NonNullFacts.unwrap(mc.parameterExpressions().getFirst()) instanceof StringConstant sc
               && STANDARD_PROPERTIES.contains(sc.constant());
    }

    private boolean applicationClassLoader(MethodCall mc) {
        if (!"java.lang.Class.getClassLoader()".equals(mc.methodInfo().fullyQualifiedName())) return false;
        Expression receiver = mc.object() == null ? null : NonNullFacts.unwrap(mc.object());
        ParameterizedType classOf = null;
        if (receiver instanceof ClassExpression ce) {
            classOf = ce.type();
            if (classOf != null && classOf.typeInfo() != null && "java.lang.Class".equals(classOf.typeInfo()
                    .fullyQualifiedName()) && !classOf.parameters().isEmpty()) {
                classOf = classOf.parameters().getFirst();
            }
        } else if (receiver instanceof MethodCall getClass && getClass.methodInfo() != null
                   && "getClass".equals(getClass.methodInfo().name()) && getClass.parameterExpressions().isEmpty()) {
            Expression of = getClass.object() == null ? null : NonNullFacts.unwrap(getClass.object());
            if (of == null || of instanceof VariableExpression ve && ve.variable() instanceof This) return true;
            classOf = of.parameterizedType();
        }
        if (classOf == null || classOf.arrays() > 0 || classOf.typeInfo() == null) return false;
        TypeInfo primary = classOf.typeInfo().primaryType();
        return index.analysed.stream().anyMatch(m -> m.typeInfo().primaryType().equals(primary));
    }
    private static final Switches.Gate EXPLICIT_BOUNDED_OFF = Switches.gate("NOEXPLICITBOUNDED");

    // phase 3, Policy.contracts: the source declarations' null annotations, on the declarations and their slots
    void annotations(List<MethodInfo> methods, List<FieldInfo> fields) {
        if (policy.contracts()) {
            for (FieldInfo fi : fields) contract(fi, fi);
            for (MethodInfo mi : methods) {
                for (ParameterInfo pi : mi.parameters()) contract(pi, pi);
                if (!mi.isConstructor() && !mi.returnType().isVoid()) contract(mi, mi);
            }
            for (FieldInfo fi : fields) elementContract(fi.type(), fi);
            for (MethodInfo mi : methods) {
                for (ParameterInfo pi : mi.parameters()) elementContract(pi.parameterizedType(), pi);
                if (!mi.isConstructor() && !mi.returnType().isVoid()) elementContract(mi.returnType(), mi);
            }
        }
    }

    // phase 3, Kotlin: the analysed returns its override rules hold non-null, and their overrides
    void overrideContracts(List<MethodInfo> methods) {
        if (policy.callResults()) {
            for (MethodInfo mi : methods) {
                if (boundedTypeVariableOverride(mi) || overridesKotlinNonNullReturn(mi)
                    || overridesLibraryNonNullReturn(mi) || testOverrideOfInstantiatedReturn(mi)) add(mi);
            }
            nonNullOverrides(methods);
        }
    }

    /*
     Kotlin: the calls that fill a lazily cached field of this ('return (result == null) ? keySet = createKeySet() :
     result'), trusted in round 1. Each later round keeps only what that round found unreached, so a null that really
     reaches one still wins; what goes is the cycle that justifies itself. guava's keySet() was nullable because
     createKeySet() might be, and createKeySet() because some members return another map's keySet()
     ('ImmutableMap.this.keySet()', 'fromMultimap.keySet()'): the whole family printed 'createKeySet(): ImmutableSet<K>?'
     (ImmutableMap.kt:105/107, AbstractMultimap.kt:76, 2026-10-17). Gate NOLAZYCACHES.
     */
    static Set<MethodInfo> lazyCacheCalls(Policy policy, List<Info> analysisOrder) {
        if (!policy.callResults() || LAZY_CACHES_OFF.get()) return Set.of();
        Set<MethodInfo> out = new HashSet<>();
        for (Info info : analysisOrder) {
            if (!(info instanceof MethodInfo mi) || mi.methodBody() == null) continue;
            boolean[] nullTest = {false};
            List<MethodInfo> calls = new ArrayList<>();
            mi.methodBody().visit(e -> {
                if (e instanceof Lambda || e instanceof ConstructorCall cc && cc.anonymousClass() != null) return false;
                if (e instanceof BinaryOperator bo && (NonNullFacts.unwrap(bo.lhs()) instanceof NullConstant
                                                       || NonNullFacts.unwrap(bo.rhs()) instanceof NullConstant)) {
                    nullTest[0] = true;
                }
                if (e instanceof Assignment a && a.assignmentOperator() == null
                    && a.variableTarget() instanceof FieldReference fr && fr.scopeIsRecursivelyThis()
                    && NonNullFacts.unwrap(a.value()) instanceof MethodCall mc && mc.methodInfo() != null
                    && !mc.methodInfo().isStatic() && !mc.methodInfo().returnType().isPrimitiveExcludingVoid()
                    && (mc.object() == null || mc.object().isEmpty()
                        || NonNullFacts.unwrap(mc.object()) instanceof VariableExpression ve
                           && ve.variable() instanceof io.codelaser.maddi.cst.api.variable.This)) {
                    calls.add(mc.methodInfo());
                }
                return true;
            });
            if (nullTest[0] && !calls.isEmpty()) {
                out.addAll(calls);
                // and the cache's getter itself: 'return from.keySet()' in another member is a call the guard does
                // not see through, unless keySet() is trusted too
                if (!mi.isConstructor() && !mi.returnType().isVoid() && !mi.returnType().isPrimitiveExcludingVoid()) {
                    out.add(mi);
                }
            }
        }
        return Set.copyOf(out);
    }

    // gate NOLAZYCACHES: round 1 trusts no analysed method's return (A/B)
    private static final Switches.Gate LAZY_CACHES_OFF = Switches.gate("NOLAZYCACHES");
}
