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
import io.codelaser.maddi.cst.api.expression.Expression;
import io.codelaser.maddi.cst.api.expression.MethodCall;
import io.codelaser.maddi.cst.api.expression.VariableExpression;
import io.codelaser.maddi.cst.api.info.FieldInfo;
import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.ParameterInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.api.type.NullableState;
import io.codelaser.maddi.cst.api.type.ParameterizedType;
import io.codelaser.maddi.cst.api.variable.FieldReference;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.codelaser.maddi.modification.analyzer.nullability.Types.*;

/**
 * Phase 7 of {@link NullabilityPass}: a declaration's verdict from what null reached, and from what the outside
 * callers reached ({@link #verdict}); the values the use-site facts hand out as not nullable; Kotlin's parent class
 * verdicts; and the parameters stored only into nullable fields, which widen what null reached first.
 */
final class Verdicts {
    private final Policy policy;
    private final Trace trace;
    private final Index index;
    private final Seeds seeds;
    private final Contracts contracts;
    private final NullGraph graph;
    // what null reaches, after the last closure; what an outside caller's values reach (World.OPEN*)
    private final Set<Object> reached;
    private final Set<Object> external;

    Verdicts(Policy policy, Trace trace, Index index, Seeds seeds, Contracts contracts, NullGraph graph,
             Set<Object> reached, Set<Object> external) {
        this.policy = policy;
        this.trace = trace;
        this.index = index;
        this.seeds = seeds;
        this.contracts = contracts;
        this.graph = graph;
        this.reached = reached;
        this.external = external;
    }

    // the verdicts of the locals and of the reassigned parameters' shadows
    Map<Local, ParameterizedType> locals(Set<Object> guardedLocals) {
        Map<Local, ParameterizedType> locals = new LinkedHashMap<>();
        // an unreached local of a degraded method: its links are missing, so "no null reaches it" is not known
        graph.declaredLocals.forEach((local, lv) -> {
            ParameterizedType v = verdict(lv.parameterizedType(), local, contracts.degraded.contains(graph.localOwner.get(local)));
            locals.put(local, guardedLocals.contains(local) && !(v.isPrimitiveExcludingVoid() && v.arrays() == 0)
                    ? v.withNullable(NullableState.NULLABLE) : v);
        });
        graph.shadows.forEach((local, pi) -> locals.put(local, verdict(pi.parameterizedType(), local,
                contracts.degraded.contains(pi.methodInfo()))));
        return locals;
    }

    // the verdicts of the declarations, with the annotations' contracts over them; Kotlin's clamps last
    Map<Info, ParameterizedType> declarations(List<MethodInfo> methods, List<FieldInfo> fields,
                                              Map<Object, Object> cause) {
        Map<Info, ParameterizedType> verdicts = new LinkedHashMap<>();
        for (FieldInfo fi : fields) {
            verdicts.put(fi, contracts.contracted(fi, verdict(fi.type(), fi, false)));
            if (trace.why(fi)) {
                trace.print("NULLWHY final " + fi.fullyQualifiedName() + " reached=" + reached.contains(fi)
                                   + trace.chain(cause, seeds.seedOrigin, fi) + " elements reached=" + reached.contains(new Content(fi))
                                   + trace.chain(cause, seeds.seedOrigin, new Content(fi)) + " verdict=" + verdicts.get(fi));
            }
        }
        for (MethodInfo mi : methods) {
            boolean deg = contracts.degraded.contains(mi);
            for (ParameterInfo pi : mi.parameters()) {
                verdicts.put(pi, contracts.contracted(pi, verdict(pi.parameterizedType(), pi, false)));
            }
            if (!mi.isConstructor() && !mi.returnType().isVoid()) {
                verdicts.put(mi, contracts.contracted(mi, verdict(mi.returnType(), mi, deg)));
            }
            if (trace.why(mi)) {
                trace.print("NULLWHY final " + mi.fullyQualifiedName() + " reached=" + reached.contains(mi)
                                   + " seed=" + seeds.seedOrigin.get(mi) + " contract=" + contracts.nonNullContracts.contains(mi)
                                   + " degraded=" + deg + " verdict=" + verdicts.get(mi) + trace.chain(cause, seeds.seedOrigin, mi));
            }
        }
        if (policy.callResults()) {
            contracts.clampTestOverrides(methods, verdicts);
            contracts.clampTypeVariableOverrides(methods, verdicts);
        }
        return verdicts;
    }

    /*
     Kotlin: a class whose own code writes null into its generic superclass's slots ('class OwnerContext extends
     LinkedList<String>' calling 'addFirst(owner)' with a nullable owner; DiskSpaceMonitorDescriptor's
     'e.setValue(get(c))' into the Map<Computer, T> of its AbstractAsyncNodeMonitorDescriptor<DiskSpace>) extends a
     LinkedList<String?>, an AbstractAsyncNodeMonitorDescriptor<DiskSpace?> (jenkins RobustReflectionConverter.kt:449,
     DiskSpaceMonitorDescriptor.kt:38, 2026-10-22). Only the classes with such a slot.
     */
    Map<TypeInfo, ParameterizedType> parentClassVerdicts(List<MethodInfo> methods) {
        if (Nodes.SUPERTYPE_SLOTS_OFF.get() || !policy.callResults()) return Map.of();
        Map<TypeInfo, ParameterizedType> out = new LinkedHashMap<>();
        for (MethodInfo mi : methods) {
            TypeInfo type = mi.typeInfo();
            if (out.containsKey(type) || type.isAnonymous() || !Nodes.thisTypedBySuperclass(type)) continue;
            ParameterizedType parent = type.parentClass();
            AnonymousThis self = new AnonymousThis(type);
            boolean nullable = false;
            for (int i = 0; i < parent.parameters().size(); i++) nullable |= reached.contains(new Arg(self, i));
            if (nullable) out.put(type, verdict(parent, self, false));
        }
        return Map.copyOf(out);
    }

    /*
     A parameter whose only use is 'this.f = p', where f is nullable: passing null there means what not calling the
     method means, so the parameter takes null. A builder's setter of a field that starts null and is read through
     'getOrDefault(executor, ...)' (langchain4j DefaultRetrievalAugmentorBuilder.executor; its tests call
     executor(null), which no analysed caller does). Nothing downstream changes: the parameter flows into f only.
     Kotlin only (Policy.callResults), where the translated callers include the tests: under the Java policies a
     declared '@NonNull' setter parameter is the API's statement, and the oracles compare against it.
     */
    static Set<Object> storedOnlyIntoNullableFields(Index index, List<MethodInfo> methods,
                                                            Set<Object> reached, Map<Object, Object> cause) {
        Set<Object> out = null;
        for (MethodInfo mi : methods) {
            if (mi.isConstructor() || mi.isStatic() || mi.methodBody() == null || mi.methodBody().isEmpty()) continue;
            // after the closure, so nothing carries the change through a hierarchy: an override's parameter must
            // keep its overridden one's state (fernflower SwitchStatement.replaceExprent overrode nothing)
            if (!mi.overrides().isEmpty() || index.implementations.containsKey(mi)) continue;
            for (ParameterInfo pi : mi.parameters()) {
                if (reached.contains(pi) || pi.parameterizedType().isPrimitiveExcludingVoid()
                    || pi.isVarArgs()) continue;
                FieldInfo stored = storedOnlyInto(mi, pi);
                if (stored != null && reached.contains(stored)) {
                    if (out == null) out = new LinkedHashSet<>(reached);
                    out.add(pi);
                    cause.put(pi, stored);
                }
            }
        }
        return out == null ? reached : out;
    }

    // the field of this object that 'this.f = p' assigns, when that is the parameter's only use in the body
    private static FieldInfo storedOnlyInto(MethodInfo mi, ParameterInfo pi) {
        FieldInfo[] field = {null};
        int[] uses = {0};
        mi.methodBody().visit(e -> {
            if (e instanceof Assignment a && a.assignmentOperator() == null
                && a.value() instanceof VariableExpression ve && pi.equals(ve.variable())
                && a.variableTarget() instanceof FieldReference fr && fr.scopeIsThis()
                && !fr.fieldInfo().isStatic() && fr.fieldInfo().owner() == mi.typeInfo()) {
                field[0] = fr.fieldInfo();
            }
            if (e instanceof VariableExpression ve && pi.equals(ve.variable())) uses[0]++;
            return true;
        });
        return uses[0] == 1 ? field[0] : null;
    }

    /*
     The verdict of a node of type 'declared': reached is NULLABLE; a degraded output, one an outside caller reaches
     (World.OPEN*), and a type variable are UNSPECIFIED; else Policy.unreached. An array's elements are the node's
     Content, recursively.
     */
    ParameterizedType verdict(ParameterizedType declared, Object node, boolean degradedOutput) {
        ParameterizedType arguments = declared;
        if (!declared.parameters().isEmpty()) {
            List<ParameterizedType> args = new ArrayList<>();
            for (int i = 0; i < declared.parameters().size(); i++) {
                ParameterizedType p = declared.parameters().get(i);
                // an array's type arguments are its element's (componentType); a wildcard's bound is not a value
                args.add(declared.arrays() > 0 || p.wildcard() != null && p.wildcard().isUnbound() ? unspecified(p)
                        : verdict(p, new Arg(node, i), degradedOutput));
            }
            arguments = declared.withParameters(args);
        }
        if (declared.arrays() > 0) {
            arguments = arguments.withComponentType(verdict(declared.componentType(), new Content(node),
                    degradedOutput));
        }
        NullableState state;
        if (declared.isPrimitiveExcludingVoid() && declared.arrays() == 0) state = NullableState.NONNULL;
        else if (isBoxedVoid(declared)) state = NullableState.NULLABLE; // its only value is null: Future<Void>
        else if (reached.contains(node)) state = NullableState.NULLABLE; // also a type variable: '@Nullable V get(Object)'
        else if (degradedOutput || external.contains(node) || isTypeVariable(declared)) {
            state = NullableState.UNSPECIFIED; // parametric
        } else state = policy.unreached();
        return arguments.withNullable(state);
    }

    private static ParameterizedType unspecified(ParameterizedType pt) {
        ParameterizedType arguments = pt.parameters().isEmpty() ? pt
                : pt.withParameters(pt.parameters().stream().map(Verdicts::unspecified).toList());
        return arguments.withNullable(NullableState.UNSPECIFIED);
    }

    /*
     Gap 7, for the facts the report hands out: a value no null of the analysis reaches, once the closure is known. A
     call to an analysed method whose return null does not reach (at this call: gap 9), a library call without a
     nullable contract (as the pass treats it everywhere: the same as a local with that one assignment), a parameter or
     a field null does not reach. A local is not looked up here: its node needs the scope of the read.
     */
    boolean notNullable(Expression e, Set<Expression> nonNullCallResults) {
        Expression x = NonNullFacts.unwrap(e);
        if (x instanceof MethodCall mc && mc.methodInfo() != null) {
            MethodInfo callee = mc.methodInfo();
            if (index.analysed.contains(callee)) return !reached.contains(callee) || nonNullCallResults.contains(mc);
            return contracts.libraryNullableCall(mc) == null;
        }
        if (x instanceof VariableExpression ve) {
            return switch (ve.variable()) {
                case ParameterInfo pi -> !reached.contains(pi);
                case FieldReference fr -> fr.fieldInfo().isFinal() && !reached.contains(fr.fieldInfo());
                default -> false;
            };
        }
        return false;
    }
}
