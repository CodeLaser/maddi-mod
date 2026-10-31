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

import io.codelaser.maddi.cst.api.expression.ArrayInitializer;
import io.codelaser.maddi.cst.api.expression.Assignment;
import io.codelaser.maddi.cst.api.expression.ConstantExpression;
import io.codelaser.maddi.cst.api.expression.ConstructorCall;
import io.codelaser.maddi.cst.api.expression.Expression;
import io.codelaser.maddi.cst.api.expression.InlineConditional;
import io.codelaser.maddi.cst.api.expression.Lambda;
import io.codelaser.maddi.cst.api.expression.MethodCall;
import io.codelaser.maddi.cst.api.expression.MethodReference;
import io.codelaser.maddi.cst.api.expression.NullConstant;
import io.codelaser.maddi.cst.api.expression.StringConcat;
import io.codelaser.maddi.cst.api.expression.VariableExpression;
import io.codelaser.maddi.cst.api.info.FieldInfo;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.ParameterInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.api.statement.Block;
import io.codelaser.maddi.cst.api.statement.ExplicitConstructorInvocation;
import io.codelaser.maddi.cst.api.statement.Statement;
import io.codelaser.maddi.cst.api.type.ParameterizedType;
import io.codelaser.maddi.cst.api.variable.FieldReference;
import io.codelaser.maddi.modification.prepwork.Util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.codelaser.maddi.modification.analyzer.nullability.Types.*;

/**
 * CodeLaser/maddi-mod#22 gap 3: a read of a final field narrowed by the receiver's runtime type. This round's
 * {@link NarrowedReads}, as {@link NonNullFacts} asks them ({@link #narrowedInMethod}), and the next round's, computed
 * from what null and the outside callers reached in this one ({@link #next}).
 */
final class ReadNarrowing {
    private final Policy policy;
    private final Index index;
    private final Contracts contracts;
    // what an earlier round found about final fields per runtime class: a read narrowed by 'instanceof' (gap 3)
    private final NarrowedReads narrowed;

    ReadNarrowing(Policy policy, Index index, Contracts contracts, NarrowedReads narrowed) {
        this.policy = policy;
        this.index = index;
        this.contracts = contracts;
        this.narrowed = narrowed;
    }

    // ------------------------------------------------------------------ narrowed reads (CodeLaser/maddi-mod#22 gap 3)

    // gate NONARROWING: no read is narrowed by the receiver's runtime type (A/B)
    private static final Switches.Gate NARROWING_OFF = Switches.gate("NONARROWING");
    // gate NOOPENNARROWING: the narrowing in the closed world only, as before CodeLaser/maddi-mod#30's open-world part
    private static final Switches.Gate OPEN_NARROWING_OFF = Switches.gate("NOOPENNARROWING");

    /*
     CodeLaser/maddi-mod#30 3: a final field of this, read in method m, is non-null when no class whose instances may
     hold null in it can run m. A class S does not run m when S, or a class between S and m's class, overrides m (and
     no analysed code calls m through 'super'), or, for a method that is not public, when no analysed call of m, nor
     of a method m overrides, has a receiver whose static type S is a subtype of. timefold MixedVariableExhaustiveSearchDecider stores a null
     moveRepository, overrides every lifecycle method that reads it, and is never the receiver of the protected
     expandNode the other deciders call on this. Implies the narrowing by m's own class (NonNullFacts.narrowedThis).
     */
    boolean narrowedInMethod(FieldInfo fi, MethodInfo m) {
        Set<TypeInfo> classes = narrowed.nullClasses().get(fi);
        if (classes == null) return false;
        TypeInfo owner = m.typeInfo();
        return classes.stream().noneMatch(s -> isSubtype(s, owner) && mayRunOn(m, s));
    }

    private boolean mayRunOn(MethodInfo m, TypeInfo s) {
        Index.CallIndex index = this.index.callIndex();
        if (!index.superCalled().contains(m) && overriddenBetween(m, s)) return false;
        // a public method may be called where the pass does not look: by reflection, a serializer reading getters
        // (timefold SolverWorkerEvent.getModel() on a FailedSolutionEvent, which no analysed call reaches). An
        // override still holds there: the reflective call dispatches to it.
        if (m.access().isPublic()) return true;
        // in an open world an unseen subclass calls what it can see: a protected method too
        if (policy.world() != World.CLOSED && visibleOutside(m)) return true;
        List<TypeInfo> receivers = new ArrayList<>(index.receivers().getOrDefault(m, List.of()));
        for (MethodInfo overridden : m.overrides()) {
            receivers.addAll(index.receivers().getOrDefault(overridden, List.of()));
        }
        // no analysed call: the method is reached from outside (a framework, reflection), on any instance
        if (receivers.isEmpty()) return true;
        for (TypeInfo r : receivers) {
            if (r == null || r.isJavaLangObject() || isSubtype(s, r)) return true;
        }
        return false;
    }

    // s, or one of its parents below m's class, declares a method overriding m
    private static boolean overriddenBetween(MethodInfo m, TypeInfo s) {
        for (TypeInfo t = s; t != null && t != m.typeInfo(); t = parentOf(t)) {
            if (t.methodStream().anyMatch(x -> x.overrides().contains(m))) return true;
        }
        return false;
    }

    private static TypeInfo parentOf(TypeInfo type) {
        ParameterizedType parent = type.parentClass();
        return parent == null ? null : parent.typeInfo();
    }

    // the values a constructor's body assigns to 'this.fi'
    private static java.util.stream.Stream<Expression> assignmentsOf(MethodInfo constructor, FieldInfo fi) {
        Block body = constructor.methodBody();
        if (body == null) return java.util.stream.Stream.of();
        List<Expression> values = new ArrayList<>();
        body.visit(e -> {
            if (e instanceof Assignment a && a.variableTarget() instanceof FieldReference fr && fr.fieldInfo() == fi
                && fr.scopeIsThis()) {
                values.add(a.value());
            }
            return true;
        });
        return values.stream();
    }

    // the next round's narrowed reads, from what null and the outside callers reach in this one
    NarrowedReads next(Set<Object> reached, Set<Object> external, NonNullFacts facts) {
        return new Next(reached, external, facts).narrowedReads();
    }

    // the computation at the end of a round, over that round's results
    private final class Next {
        private final Set<Object> reached;
        private final Set<Object> external;
        private final NonNullFacts facts;

        Next(Set<Object> reached, Set<Object> external, NonNullFacts facts) {
            this.reached = reached;
            this.external = external;
            this.facts = facts;
        }

        /*
         Computed at the end of a round, from what null reached in it, for the next round's use-site facts: sound because a
         round's reachability over-approximates the next one's (edges only drop). A field is a candidate when it is final,
         of an instance, and its class has an analysed proper subclass (without one the narrowing tells nothing the field's
         own verdict does not).
         In an open world (the diagnostics' policy, CodeLaser/maddi-mod#30) a final field is still assigned only by its
         class's constructors, which the pass sees; outside code reaches them in two ways, both accounted for. It calls a
         constructor visible outside with what it likes: those parameters are in 'external', which classMayHoldNull reads.
         And it extends a class visible outside: an unseen subclass runs the constructors of a known class, so an
         extensible abstract class stands for its unseen subclasses, a concrete one already does. A final class has no
         unseen subclass at all.
         */
        private NarrowedReads narrowedReads() {
            boolean open = policy.world() != World.CLOSED;
            if (NARROWING_OFF.get() || open && OPEN_NARROWING_OFF.get()) return NarrowedReads.NONE;
            Set<TypeInfo> classes = new LinkedHashSet<>();
            for (MethodInfo mi : index.analysed) classes.add(mi.typeInfo());
            for (FieldInfo fi : index.analysedFields) classes.add(fi.owner());
            Map<FieldInfo, Set<TypeInfo>> nullClasses = new HashMap<>();
            for (FieldInfo fi : index.analysedFields) {
                if (!fi.isFinal() || fi.isStatic() || fi.type().isPrimitiveExcludingVoid() && fi.type().arrays() == 0) continue;
                TypeInfo owner = fi.owner();
                if (owner.typeNature().isRecord() || owner.isInterface()) continue;
                List<TypeInfo> concrete = classes.stream()
                        .filter(x -> x == owner || isSubtype(x, owner))
                        .filter(x -> !x.isInterface() && (!x.isAbstract() || x.isAnonymous()
                                                          || open && extensible(x) && visibleOutside(x))).toList();
                if (classes.stream().noneMatch(x -> x != owner && isSubtype(x, owner))) continue;
                // a final field is assigned exactly once: by its initializer or along every constructor chain; one of the
                // two must be visible here (an instance initializer block is not looked at)
                boolean initialized = fi.initializer() != null && !fi.initializer().isEmpty();
                if (!initialized && owner.constructors().stream().noneMatch(k -> assignmentsOf(k, fi).findAny().isPresent())) {
                    continue;
                }
                Set<TypeInfo> nullable = new LinkedHashSet<>();
                boolean initializerNull = initialized && mayBeNull(fi.initializer(), Map.of());
                for (TypeInfo x : concrete) {
                    if (initializerNull || classMayHoldNull(x, fi)) nullable.add(x);
                }
                nullClasses.put(fi, Set.copyOf(nullable));
            }
            if (nullClasses.isEmpty()) return NarrowedReads.NONE;
            Map<MethodInfo, FieldInfo> getters = new HashMap<>();
            for (MethodInfo mi : index.analysed) {
                io.codelaser.maddi.cst.api.analysis.Value.FieldValue fv = mi.getSetField();
                if (fv == null || fv.field() == null || fv.setter() || fv.hasIndex() || mi.isStatic()
                    || !mi.parameters().isEmpty() || index.implementations.containsKey(mi)
                    || !nullClasses.containsKey(fv.field())) continue;
                getters.put(mi, fv.field());
            }
            return new NarrowedReads(Map.copyOf(nullClasses), Map.copyOf(getters));
        }

        // may an instance whose runtime class is x hold null in the final field fi, through any of x's constructors?
        private boolean classMayHoldNull(TypeInfo x, FieldInfo fi) {
            List<MethodInfo> constructors = x.constructors();
            if (constructors.isEmpty()) {
                TypeInfo parent = parentOf(x);
                if (x.isAnonymous() && parent != null) {
                    // 'new C(args) { ... }': the arguments go to one of the parent's constructors, which one is not
                    // looked up here; each, with what reaches its parameters
                    return parent.constructors().isEmpty() ? implicitSuperMayHoldNull(parent, fi, 0)
                            : parent.constructors().stream().anyMatch(k -> constructsNull(k, reachedEnv(k), fi, 0));
                }
                return implicitSuperMayHoldNull(x, fi, 0);
            }
            return constructors.stream().anyMatch(k -> constructsNull(k, reachedEnv(k), fi, 0));
        }

        private Map<ParameterInfo, Boolean> reachedEnv(MethodInfo constructor) {
            Map<ParameterInfo, Boolean> env = new HashMap<>();
            for (ParameterInfo pi : constructor.parameters()) {
                env.put(pi, reached.contains(pi) || external.contains(pi));
            }
            return env;
        }

        /*
         The constructor chain from k: what k's body assigns to the field when k is of the field's class, and what the
         constructor it delegates to ('this(...)', 'super(...)', or the implicit 'super()') makes of the arguments, given
         what k's own parameters may be (env).
         */
        private boolean constructsNull(MethodInfo k, Map<ParameterInfo, Boolean> given, FieldInfo fi, int depth) {
            if (depth > 32) return true;
            // a constructor that dereferences a parameter on every path completes only when it is non-null: the object it
            // builds never holds that null, whoever passes it ('Root(Root parent) { semantics = parent.semantics; ... }',
            // outside callers in an open world included)
            Map<ParameterInfo, Boolean> env = given;
            if (given.containsValue(true)) {
                Map<ParameterInfo, Boolean> checked = new HashMap<>(given);
                for (ParameterInfo pi : k.parameters()) {
                    if (Boolean.TRUE.equals(checked.get(pi)) && facts.nonNullAtExit(k, pi)) checked.put(pi, false);
                }
                env = checked;
            }
            Map<ParameterInfo, Boolean> known = env;
            TypeInfo owner = fi.owner();
            Block body = k.methodBody();
            if (body == null) return k.typeInfo() == owner || isSubtype(k.typeInfo(), owner);
            boolean mayBeNull = k.typeInfo() == owner
                                && assignmentsOf(k, fi).anyMatch(value -> mayBeNull(value, known));
            Statement first = body.isEmpty() ? null : body.statements().getFirst();
            if (first instanceof ExplicitConstructorInvocation eci) {
                MethodInfo target = eci.methodInfo();
                if (target == null) return true;
                if (target.typeInfo() == owner || isSubtype(target.typeInfo(), owner)) {
                    List<ParameterInfo> parameters = target.parameters();
                    List<Expression> arguments = eci.parameterExpressions();
                    Map<ParameterInfo, Boolean> next = new HashMap<>();
                    boolean plain = parameters.size() == arguments.size()
                                    && (parameters.isEmpty() || !parameters.getLast().isVarArgs());
                    for (int i = 0; i < parameters.size(); i++) {
                        next.put(parameters.get(i), !plain || mayBeNull(arguments.get(i), env));
                    }
                    mayBeNull |= constructsNull(target, next, fi, depth + 1);
                }
            } else if (k.typeInfo() != owner) {
                mayBeNull |= implicitSuperMayHoldNull(k.typeInfo(), fi, depth + 1);
            }
            return mayBeNull;
        }

        // 'super()', implicit: the parent's constructor without parameters (or the parent's own implicit one)
        private boolean implicitSuperMayHoldNull(TypeInfo type, FieldInfo fi, int depth) {
            if (type == fi.owner()) return false; // no constructor of its own: only the initializer assigns
            TypeInfo parent = parentOf(type);
            if (parent == null || !(parent == fi.owner() || isSubtype(parent, fi.owner()))) return false;
            if (parent.constructors().isEmpty()) return implicitSuperMayHoldNull(parent, fi, depth + 1);
            return parent.constructors().stream().filter(k -> k.parameters().isEmpty()).findFirst()
                    .map(k -> constructsNull(k, Map.of(), fi, depth + 1)).orElse(true);
        }

        // may this expression, in a constructor whose parameters may be null as env says, evaluate to null?
        private boolean mayBeNull(Expression e, Map<ParameterInfo, Boolean> env) {
            Expression x = NonNullFacts.unwrap(e);
            if (x == null || x instanceof NullConstant) return true;
            if (x.parameterizedType() != null && x.parameterizedType().isPrimitiveExcludingVoid()
                && x.parameterizedType().arrays() == 0) return false;
            return switch (x) {
                case VariableExpression ve -> switch (ve.variable()) {
                    case ParameterInfo pi -> env.containsKey(pi) ? env.get(pi) : reached.contains(pi) || external.contains(pi);
                    case io.codelaser.maddi.cst.api.variable.This _ -> false;
                    case FieldReference fr when !Util.virtual(fr) -> reached.contains(fr.fieldInfo())
                                                                     || external.contains(fr.fieldInfo());
                    default -> true;
                };
                case ConstructorCall _, Lambda _, MethodReference _, ArrayInitializer _, StringConcat _ -> false;
                case ConstantExpression<?> _ -> false;
                case InlineConditional ic -> mayBeNull(ic.ifTrue(), env) || mayBeNull(ic.ifFalse(), env);
                case MethodCall mc -> {
                    MethodInfo callee = mc.methodInfo();
                    if (callee == null) yield true;
                    if (index.analysed.contains(callee)) {
                        yield reached.contains(callee) || external.contains(callee) || contracts.degraded.contains(callee);
                    }
                    yield contracts.libraryNullableCall(mc) != null;
                }
                default -> true;
            };
        }
    }
}
