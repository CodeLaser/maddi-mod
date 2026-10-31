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
import io.codelaser.maddi.cst.api.expression.Cast;
import io.codelaser.maddi.cst.api.expression.ConstructorCall;
import io.codelaser.maddi.cst.api.expression.Expression;
import io.codelaser.maddi.cst.api.expression.MethodCall;
import io.codelaser.maddi.cst.api.expression.MethodReference;
import io.codelaser.maddi.cst.api.info.FieldInfo;
import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.ParameterInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.api.statement.LocalVariableCreation;
import io.codelaser.maddi.cst.api.type.NullableState;
import io.codelaser.maddi.cst.api.type.ParameterizedType;
import io.codelaser.maddi.modification.analyzer.nullability.NullabilityPass.Policy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.codelaser.maddi.modification.analyzer.nullability.Types.*;

/**
 * Kotlin: the type parameters the null-marked code holds non-null ({@link NullabilityPass.Report#nonNullTypeParameter}),
 * from every type use of the analysed code: supertypes, declarations, bounds, and the types in the bodies, carried
 * over to the type variables used in such positions until nothing changes. Phase 1 of {@link NullabilityPass}.
 */
final class TypeUses {
    private final Policy policy;
    private final Index index;

    TypeUses(Policy policy, Index index) {
        this.policy = policy;
        this.index = index;
    }

    /*
     Kotlin: a class type parameter of a @NullMarked class without a @Nullable bound ('class LocalCache<K, V>' in
     guava) is non-null: the printer bounds it by Any (NullabilityFacts.nonNullTypeParameter), and null stops at its
     slots as at a bounded one, so no verdict instantiates it as 'X?'. Carried over to a subclass's type parameter
     passed into such a position ('X<E> extends ImmutableList<E>' in a test outside the null-marked scope), which Kotlin
     would otherwise reject, and to any type variable used in such a position. guava LocalCache.kt:1102/1020,
     CollectCollectors.kt:24 (2026-10-18). Gate NONULLMARKEDBOUNDS.
     */
    final Set<io.codelaser.maddi.cst.api.info.TypeParameter> nonNullTypeParameters = new HashSet<>();

    private static final Switches.Gate NULL_MARKED_BOUNDS_OFF = Switches.gate("NONULLMARKEDBOUNDS");
    private static final Switches.Gate MEMBERLESS_BOUNDS_OFF = Switches.gate("NOMEMBERLESSBOUNDS");
    private static final Switches.Gate BOUND_USES_OFF = Switches.gate("NOBOUNDUSES");

    void nonNullBounds(List<Info> analysisOrder, List<MethodInfo> methods, List<FieldInfo> fields) {
        if (!policy.callResults() || NULL_MARKED_BOUNDS_OFF.get()) return;
        // a source package's annotations sit on its package-info type, not on the compilation units of its classes
        // (guava's com.google.common.cache, 2026-10-18)
        for (Info info : analysisOrder) {
            if (info instanceof TypeInfo t && t.typeNature().isPackageInfo()) {
                packageInfoAnnotations.put(t.packageName(), t.annotations());
            }
        }
        Set<TypeInfo> types = new java.util.LinkedHashSet<>();
        for (MethodInfo mi : methods) types.add(mi.typeInfo());
        for (FieldInfo fi : fields) types.add(fi.owner());
        // a type without members of its own too: 'interface NullComparatorFactory<Solution_, T> extends
        // ComparatorFactory<Solution_, T>' passes its parameters into null-marked positions, a member-less
        // '@NullMarked interface UniConstraintStream<A>' has non-null ones (timefold PlanningEntity.kt:15,
        // 2026-10-22). Gate NOMEMBERLESSBOUNDS.
        if (!MEMBERLESS_BOUNDS_OFF.get()) {
            for (Info info : analysisOrder) {
                if (info instanceof TypeInfo t && !t.typeNature().isPackageInfo()) types.add(t);
            }
        }
        // every type use: supertypes, fields, parameters and results, and in the bodies the types of locals, casts,
        // calls and constructor calls ('new ReverseImmutableList<>(this)' in ImmutableList.reverse())
        List<TypeUse> uses = new ArrayList<>();
        List<Instantiation> instantiations = new ArrayList<>();
        for (TypeInfo t : types) {
            if (t.parentClass() != null) uses.add(new TypeUse(t.parentClass(), t, true));
            for (ParameterizedType i : t.interfacesImplemented()) uses.add(new TypeUse(i, t, true));
            // a type parameter's bound: 'class AbstractOriginalSwapIterator<Solution_, Move_ extends
            // Move<Solution_>, …>' passes Solution_ into Move's (timefold AbstractOriginalSwapIterator.kt:5,
            // 2026-10-22). Strict, as a supertype: Kotlin writes no 'Solution_ & Any' in a bound. Gate NOBOUNDUSES.
            if (!BOUND_USES_OFF.get()) {
                for (var tp : t.typeParameters()) for (ParameterizedType b : tp.typeBounds()) uses.add(new TypeUse(b, t, true));
            }
        }
        for (FieldInfo fi : fields) {
            uses.add(new TypeUse(fi.type(), fi, false));
            if (fi.initializer() != null) fi.initializer().visit(e -> bodyTypeUse(e, fi, uses, instantiations));
        }
        for (MethodInfo mi : methods) {
            if (!mi.isConstructor()) uses.add(new TypeUse(mi.returnType(), mi, false));
            // an override repeats its parameters exactly: the printer keeps them as declared, without '& Any'
            boolean repeated = !mi.isConstructor() && (!mi.overrides().isEmpty() || !mi.isStatic()
                    && !mi.access().isPrivate() && !mi.isFinal() && !mi.typeInfo().isFinal());
            for (ParameterInfo pi : mi.parameters()) uses.add(new TypeUse(pi.parameterizedType(), mi, repeated));
            if (!BOUND_USES_OFF.get()) {
                for (var tp : mi.typeParameters()) for (ParameterizedType b : tp.typeBounds()) uses.add(new TypeUse(b, mi, true));
            }
            if (mi.methodBody() != null) mi.methodBody().visit(e -> bodyTypeUse(e, mi, uses, instantiations));
        }
        /*
         A type variable used in a non-null position ('Response<T>' in 'interface StreamingResponseHandler<T>', or
         in '<T> Response<T> from(T content)') must be non-null too, or Kotlin rejects the use ("type argument is
         not within its bounds", langchain4j, 2026-10-18): carried over to it, class or method type variable alike.
         Where it cannot be (a @Nullable use, a library's type variable), the position is left unbounded,
         and everything is worked out again without it.
         */
        Set<io.codelaser.maddi.cst.api.info.TypeParameter> blocked = new HashSet<>();
        while (true) {
            nonNullTypeParameters.clear();
            for (TypeInfo t : types) {
                if (!nullMarked(t)) continue;
                for (var tp : t.typeParameters()) {
                    if (!nullableBound(tp) && !blocked.contains(tp)) nonNullTypeParameters.add(tp);
                }
            }
            Set<io.codelaser.maddi.cst.api.info.TypeParameter> conflicts = new HashSet<>();
            boolean changed = true;
            while (changed) {
                changed = false;
                for (TypeUse use : uses) {
                    changed |= carryNonNull(use.type(), use.where(), use.strict(), types, blocked, conflicts);
                }
                for (Instantiation in : instantiations) {
                    changed |= carryInstantiation(in.declared(), in.concrete(), in.callee(), in.where(), types,
                            blocked, conflicts);
                }
                if (!OVERRIDE_BOUNDS_OFF.get()) changed |= overrideFamilyBounds(methods, blocked, conflicts);
            }
            if (conflicts.isEmpty() || !blocked.addAll(conflicts)) {
                if (NULL_MARKED_DEBUG != null) debugNullMarked(types, blocked);
                return;
            }
        }
    }

    /*
     Kotlin: an override repeats its method type parameters' bounds exactly. 'override fun <B> ifExists(otherClass:
     Class<B>, …)' whose B goes into ConstraintFactory.forEach's '<A : Any>' must be '<B : Any>', and so must the
     method it overrides and that method's other overrides (timefold InnerUniConstraintStream.kt:32, 2026-10-22). A
     family member that cannot be bounded (not ours, blocked) leaves the position unbounded for all. Gate
     NOOVERRIDEBOUNDS.
     */
    private boolean overrideFamilyBounds(List<MethodInfo> methods,
                                         Set<io.codelaser.maddi.cst.api.info.TypeParameter> blocked,
                                         Set<io.codelaser.maddi.cst.api.info.TypeParameter> conflicts) {
        boolean changed = false;
        for (MethodInfo mi : methods) {
            int n = mi.typeParameters().size();
            if (n == 0) continue;
            for (MethodInfo om : mi.overrides()) {
                // a library method keeps its own bounds: Kotlin matches an override of a Java method against its
                // platform bounds, and a conflict there would unbound the variable for all its uses (guava
                // Parameter.getDeclaredAnnotation's A, and through it Optional's T)
                if (om.typeParameters().size() != n || !index.analysed.contains(om)) continue;
                for (int i = 0; i < n; i++) {
                    var mine = mi.typeParameters().get(i);
                    var theirs = om.typeParameters().get(i);
                    boolean a = nonNullTypeParameters.contains(mine);
                    boolean b = nonNullTypeParameters.contains(theirs);
                    if (a == b) continue;
                    var other = a ? theirs : mine;
                    var source = a ? mine : theirs;
                    if (!blocked.contains(other) && !nullableBound(other)) {
                        nonNullTypeParameters.add(other);
                        changed = true;
                    } else {
                        conflicts.add(source);
                    }
                }
            }
        }
        return changed;
    }

    private static final Switches.Gate OVERRIDE_BOUNDS_OFF = Switches.gate("NOOVERRIDEBOUNDS");

    private final Map<String, List<io.codelaser.maddi.cst.api.expression.AnnotationExpression>> packageInfoAnnotations
            = new HashMap<>();

    // strict: a supertype, or a parameter an override repeats, where the printer cannot write 'B & Any'
    private record TypeUse(ParameterizedType type, Info where, boolean strict) {
    }

    /*
     what a call instantiates its method's own type variables with, read off its declared parameter and result types
     against its arguments and result: 'CartesianList.create(lists)' instantiates E with cartesianProduct's B
     (Lists.kt:309, Sets.kt:707), 'explicit(Lists.asList(leastValue, rest))' explicit(List)'s T with the varargs
     overload's T (Ordering.kt:212, 2026-10-19)
     */
    private record Instantiation(ParameterizedType declared, ParameterizedType concrete, MethodInfo callee,
                                 Info where) {
    }

    private static boolean bodyTypeUse(Element e, Info where, List<TypeUse> uses, List<Instantiation> instantiations) {
        switch (e) {
            case ConstructorCall cc -> uses.add(new TypeUse(cc.parameterizedType(), where, false));
            // a constructor reference ('transform(entries, UnmodifiableEntry::new)' as a Function<Entry<K, V>, ...>)
            // instantiates the class with what its functional type passes: matched against each type argument
            // (MutableTypeToInstanceMap.kt:58, 2026-10-19)
            case MethodReference mr when mr.methodInfo() != null && mr.methodInfo().isConstructor()
                                         && !mr.methodInfo().typeInfo().typeParameters().isEmpty()
                                         && mr.parameterizedType() != null -> {
                for (ParameterInfo pi : mr.methodInfo().parameters()) {
                    for (ParameterizedType fa : mr.parameterizedType().parameters()) {
                        instantiations.add(new Instantiation(pi.parameterizedType(), fa, mr.methodInfo(), where));
                    }
                }
            }
            case MethodCall mc -> {
                uses.add(new TypeUse(mc.concreteReturnType(), where, false));
                MethodInfo callee = mc.methodInfo();
                if (callee != null && !callee.typeParameters().isEmpty()) {
                    instantiations.add(new Instantiation(callee.returnType(), mc.concreteReturnType(), callee, where));
                    List<Expression> args = mc.parameterExpressions();
                    for (int i = 0; i < args.size() && i < callee.parameters().size(); i++) {
                        instantiations.add(new Instantiation(callee.parameters().get(i).parameterizedType(),
                                args.get(i).parameterizedType(), callee, where));
                    }
                }
            }
            case Cast c -> uses.add(new TypeUse(c.parameterizedType(), where, false));
            case LocalVariableCreation lvc -> lvc.localVariableStream()
                    .forEach(lv -> uses.add(new TypeUse(lv.parameterizedType(), where, false)));
            default -> {
            }
        }
        return true;
    }

    private boolean carryNonNull(ParameterizedType use, Info where, boolean strict, Set<TypeInfo> types,
                                 Set<io.codelaser.maddi.cst.api.info.TypeParameter> blocked,
                                 Set<io.codelaser.maddi.cst.api.info.TypeParameter> conflicts) {
        if (use == null) return false;
        boolean changed = false;
        TypeInfo sup = use.typeInfo();
        for (int i = 0; i < use.parameters().size(); i++) {
            ParameterizedType a = use.parameters().get(i);
            changed |= carryNonNull(a, where, strict, types, blocked, conflicts);
            // a wildcard's bound too: 'List<? extends List<? extends B>>' into CartesianList.create's E (Lists.kt:309)
            var tp = a.typeParameter();
            if (tp == null || sup == null || i >= sup.typeParameters().size()) continue;
            var position = sup.typeParameters().get(i);
            /*
             'ImmutableList<@NonNull E>' (FluentIterable.toList()): Kotlin's 'E & Any'. A @Nullable-bounded variable
             is neither carried nor a conflict: the null-marked code that passes one on has made it non-null there
             (JSpecify-checked), though an inferred call type in its body ('ImmutableList.copyOf(...)') no longer
             says so; as a conflict, FluentIterable's E dropped ImmutableList's, and with it, through every class
             ImmutableList's E is passed to, the bounds of 57 more guava classes (2026-10-19).
             */
            boolean nullable = nullableBound(tp);
            if (!nonNullTypeParameters.contains(position) || nonNullTypeParameters.contains(tp)
                || !strict && (nullable || explicit(a.annotations()) == NullableState.NONNULL)) continue;
            /*
             in a strict use a @Nullable-bounded variable, '@NonNull' or not, leaves the position unbounded:
             'MutableTypeToInstanceMap<B extends @Nullable Object> extends ForwardingMap<TypeToken<? extends
             @NonNull B>, B>' (TypeToInstanceMap.kt:6, 2026-10-19)
             */
            if (nullable) {
                conflicts.add(position);
                continue;
            }
            boolean ours = tp.getOwner().isLeft() ? types.contains(tp.getOwner().getLeft())
                    : index.analysed.contains(tp.getOwner().getRight());
            if (ours && !blocked.contains(tp) && explicit(a.annotations()) != NullableState.NULLABLE) {
                nonNullTypeParameters.add(tp);
                changed = true;
            } else if (conflicts.add(position) && NULL_MARKED_DEBUG != null) {
                debugConflicts.add(position + " of " + sup.fullyQualifiedName() + " <- " + tp + " ("
                                   + (tp.getOwner().isLeft() ? tp.getOwner().getLeft().fullyQualifiedName()
                        : tp.getOwner().getRight().fullyQualifiedName()) + ", ours " + ours + ", bound "
                                   + nullableBound(tp) + ") in " + where.fullyQualifiedName());
            }
        }
        return changed;
    }

    private boolean carryInstantiation(ParameterizedType declared, ParameterizedType concrete, MethodInfo callee,
                                       Info where, Set<TypeInfo> types,
                                       Set<io.codelaser.maddi.cst.api.info.TypeParameter> blocked,
                                       Set<io.codelaser.maddi.cst.api.info.TypeParameter> conflicts) {
        if (declared == null || concrete == null || declared.arrays() != concrete.arrays()) return false;
        var position = declared.typeParameter();
        if (position != null) {
            var tp = concrete.typeParameter();
            boolean callees = position.isMethodTypeParameter()
                    ? position.getOwner().isRight() && position.getOwner().getRight() == callee
                    : callee.isConstructor() && position.getOwner().isLeft()
                      && position.getOwner().getLeft() == callee.typeInfo();
            if (tp == null || !callees || !nonNullTypeParameters.contains(position)
                || nonNullTypeParameters.contains(tp) || nullableBound(tp)
                || explicit(concrete.annotations()) == NullableState.NONNULL) return false;
            boolean ours = tp.getOwner().isLeft() ? types.contains(tp.getOwner().getLeft())
                    : index.analysed.contains(tp.getOwner().getRight());
            if (ours && !blocked.contains(tp) && explicit(concrete.annotations()) != NullableState.NULLABLE) {
                nonNullTypeParameters.add(tp);
                return true;
            }
            if (conflicts.add(position) && NULL_MARKED_DEBUG != null) {
                debugConflicts.add(position + " of " + callee.fullyQualifiedName() + " <- " + tp + " in "
                                   + where.fullyQualifiedName());
            }
            return false;
        }
        // within one generic type only: a 'Collection<? extends E>' given an 'ArrayList<X>' is not matched
        if (declared.typeInfo() == null || declared.typeInfo() != concrete.typeInfo()
            || declared.parameters().size() != concrete.parameters().size()) return false;
        boolean changed = false;
        for (int i = 0; i < declared.parameters().size(); i++) {
            changed |= carryInstantiation(declared.parameters().get(i), concrete.parameters().get(i), callee, where,
                    types, blocked, conflicts);
        }
        return changed;
    }

    // NULLMARKEDDEBUG=<file>: why each null-marked position was left unbounded, and the bounds kept (diagnostics)
    static final String NULL_MARKED_DEBUG = System.getenv("NULLMARKEDDEBUG");
    private final List<String> debugConflicts = new ArrayList<>();

    private void debugNullMarked(Set<TypeInfo> types, Set<io.codelaser.maddi.cst.api.info.TypeParameter> blocked) {
        List<String> lines = new ArrayList<>(debugConflicts);
        lines.add("blocked " + blocked.size() + ", non-null " + nonNullTypeParameters.size() + ", package-infos "
                  + packageInfoAnnotations.size());
        for (TypeInfo t : types) {
            for (var tp : t.typeParameters()) {
                lines.add((nonNullTypeParameters.contains(tp) ? "NONNULL " : "free ") + t.fullyQualifiedName() + "."
                          + tp.simpleName());
            }
        }
        try {
            java.nio.file.Files.write(java.nio.file.Path.of(NULL_MARKED_DEBUG), lines);
        } catch (java.io.IOException ignored) {
        }
    }

    // '<T extends @Nullable Object>' (or any @Nullable bound)
    private static boolean nullableBound(io.codelaser.maddi.cst.api.info.TypeParameter tp) {
        return tp.typeBounds().stream().anyMatch(b -> explicit(b.annotations()) == NullableState.NULLABLE);
    }

    // the type, an enclosing type, its package or its module is @NullMarked, the nearest @NullUnmarked winning
    private boolean nullMarked(TypeInfo type) {
        TypeInfo t = type;
        while (t != null) {
            Boolean marked = markedBy(t.annotations());
            if (marked != null) return marked;
            t = t.compilationUnitOrEnclosingType().isRight() ? t.compilationUnitOrEnclosingType().getRight() : null;
        }
        Boolean marked = markedBy(packageInfoAnnotations.getOrDefault(type.packageName(), List.of()));
        var cu = type.primaryType().compilationUnit();
        if (cu == null) return Boolean.TRUE.equals(marked);
        if (marked == null) marked = markedBy(cu.packageAnnotations());
        if (marked == null) marked = markedBy(cu.moduleAnnotations());
        return Boolean.TRUE.equals(marked);
    }

    private static Boolean markedBy(List<io.codelaser.maddi.cst.api.expression.AnnotationExpression> annotations) {
        if (annotations.stream().anyMatch(a -> a.typeInfo() != null && "NullUnmarked".equals(a.typeInfo().simpleName()))) {
            return false;
        }
        if (annotations.stream().anyMatch(a -> a.typeInfo() != null && "NullMarked".equals(a.typeInfo().simpleName()))) {
            return true;
        }
        return null;
    }
}
