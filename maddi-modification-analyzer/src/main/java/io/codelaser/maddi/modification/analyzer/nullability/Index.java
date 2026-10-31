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
import io.codelaser.maddi.cst.api.expression.Assignment;
import io.codelaser.maddi.cst.api.expression.ClassExpression;
import io.codelaser.maddi.cst.api.expression.ConstantExpression;
import io.codelaser.maddi.cst.api.expression.ConstructorCall;
import io.codelaser.maddi.cst.api.expression.Expression;
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
import io.codelaser.maddi.cst.api.statement.ExpressionAsStatement;
import io.codelaser.maddi.cst.api.statement.ReturnStatement;
import io.codelaser.maddi.cst.api.statement.Statement;
import io.codelaser.maddi.cst.api.type.ParameterizedType;
import io.codelaser.maddi.cst.api.variable.FieldReference;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.codelaser.maddi.modification.analyzer.nullability.Types.*;

/**
 * The read-only indexes of one round of the nullability pass, built before the graph (phase 1 of
 * {@link NullabilityPass}): the analysed methods and fields, the override families, the lambdas and method
 * references, the bodies that test a rejection of null, the keyed map fields (gap 2), and lazily the receivers of
 * the calls and the classes each method encloses.
 */
final class Index {
    private final Policy policy;

    Index(Policy policy) {
        this.policy = policy;
    }

    // analysed implementations per overridden method (any overridden method, also a library one)
    final Map<MethodInfo, List<MethodInfo>> implementations = new HashMap<>();
    // CodeLaser/maddi-mod#41: the lambdas in analysed bodies (members of their functional method's family); the analysed
    // methods a method reference implements (their family has a member the pass does not see); the methods a method
    // reference hands to whoever calls the function; the parameters a call passes the literal null to; per
    // precondition parameter, the calls and the node of what each passes
    private final Set<MethodInfo> lambdaMethods = new HashSet<>();
    // Kotlin: the bodies of 'assertThrows(NullPointerException.class, () -> ...)' (rejectsNull)
    final Set<String> nullRejectingBodies = new HashSet<>();
    final Set<MethodInfo> referencedSams = new HashSet<>();
    final Set<MethodInfo> referencedMethods = new HashSet<>();
    List<FieldInfo> analysedFields = List.of();
    // keyed map fields (CodeLaser/maddi-mod#22 gap 2): the value slot per field, the accessors that take the key as a
    // parameter, and the keys written per slot
    final Map<FieldInfo, Arg> keyedSlots = new HashMap<>();
    final Map<MethodInfo, KeyedAccessor> keyedPutters = new HashMap<>();
    final Map<MethodInfo, KeyedAccessor> keyedGetters = new HashMap<>();
    // the methods of this analysis: their own NULLABILITY_* may be this pass's output of an earlier run, so only the
    // others' (the library's, from the hints) are read as contracts
    final Set<MethodInfo> analysed = new HashSet<>();

    /*
     Per method called in the analysed code, the static types of the receivers of its calls; null for a receiver
     without a type, and for a method reference (any instance may be its receiver). The methods called through
     'super', which run on the caller's instance whatever overrides them.
     */
    record CallIndex(Map<MethodInfo, List<TypeInfo>> receivers, Set<MethodInfo> superCalled) {
    }

    CallIndex callIndex;

    CallIndex callIndex() {
        if (callIndex != null) return callIndex;
        Map<MethodInfo, List<TypeInfo>> receivers = new HashMap<>();
        Set<MethodInfo> superCalled = new HashSet<>();
        for (MethodInfo mi : analysed) {
            Block body = mi.methodBody();
            if (body == null) continue;
            body.visit(e -> {
                if (e instanceof MethodCall mc && mc.methodInfo() != null && !mc.methodInfo().isStatic()) {
                    Expression object = mc.object();
                    if (object instanceof VariableExpression ve
                        && ve.variable() instanceof io.codelaser.maddi.cst.api.variable.This t && t.writeSuper()) {
                        superCalled.add(mc.methodInfo());
                    }
                    ParameterizedType type = object == null ? null : object.parameterizedType();
                    receivers.computeIfAbsent(mc.methodInfo(), _ -> new ArrayList<>())
                            .add(type == null ? null : type.typeInfo());
                } else if (e instanceof MethodReference mr && mr.methodInfo() != null) {
                    receivers.computeIfAbsent(mr.methodInfo(), _ -> new ArrayList<>()).add(null);
                }
                return true;
            });
        }
        return callIndex = new CallIndex(receivers, superCalled);
    }

    /*
     Kotlin: 'assertThrows(NullPointerException.class, () -> map.putInstance(null, 1))' tests that the callee rejects
     null: its null argument is no evidence that the parameter takes one. guava (2026-10-17): 1371 cause lines began
     at a null in such a body (MutableTypeToInstanceMapTest's made the backing map's key 'TypeToken<out B>?', against
     ForwardingMap<TypeToken<out B>, B>.delegate(); PredicatesTest's made subtypeOf's 'Predicate<Class<*>?>'). The
     printer asserts such a test null at its call, and the NullPointerException still comes.
     */
    private void rejectsNull(MethodCall mc) {
        if (mc.methodInfo() == null || mc.parameterExpressions().size() < 2) return;
        String name = mc.methodInfo().name();
        if (!"assertThrows".equals(name) && !"expectThrows".equals(name)) return;
        if (!(NonNullFacts.unwrap(mc.parameterExpressions().get(0)) instanceof ClassExpression ce)
            || ce.type() == null || ce.type().typeInfo() == null
            || !"java.lang.NullPointerException".equals(ce.type().typeInfo().fullyQualifiedName())) {
            return;
        }
        if (NonNullFacts.unwrap(mc.parameterExpressions().get(1)) instanceof Lambda lambda && lambda.methodInfo() != null) {
            nullRejectingBodies.add(lambda.methodInfo().fullyQualifiedName());
        }
    }
    // gate NOREJECTEDNULLS: a null passed inside assertThrows(NullPointerException.class, ...) seeds as any (A/B)
    private static final Switches.Gate REJECTED_NULLS_OFF = Switches.gate("NOREJECTEDNULLS");

    void indexFunctionals(List<MethodInfo> methods) {
        for (MethodInfo mi : methods) {
            if (mi.methodBody() == null) continue;
            mi.methodBody().visit(e -> {
                if (e instanceof MethodCall mc && policy.callResults() && !REJECTED_NULLS_OFF.get()) rejectsNull(mc);
                if (e instanceof Lambda lambda && lambda.methodInfo() != null) {
                    lambdaMethods.add(lambda.methodInfo());
                } else if (e instanceof MethodReference mr && mr.methodInfo() != null) {
                    referencedMethods.add(mr.methodInfo());
                    ParameterizedType functional = mr.parameterizedType();
                    MethodInfo sam = functional == null || functional.typeInfo() == null ? null
                            : functional.typeInfo().singleAbstractMethod();
                    if (sam != null) referencedSams.add(sam);
                }
                return true;
            });
        }
    }

    // the analysed methods, and the lambdas in their bodies, connected by overriding; only methods in a family of two
    // or more. A method reference implementing an analysed method marks that method's family (referencedSams)
    Map<MethodInfo, Set<MethodInfo>> families() {
        Map<MethodInfo, Set<MethodInfo>> family = new HashMap<>();
        for (MethodInfo mi : analysed) {
            if (mi.isConstructor() || mi.isStatic()) continue;
            for (MethodInfo overridden : overrides(mi)) {
                if (analysed.contains(overridden)) merge(family, mi, overridden);
            }
        }
        for (MethodInfo mi : lambdaMethods) {
            for (MethodInfo overridden : overrides(mi)) {
                if (analysed.contains(overridden)) merge(family, mi, overridden);
            }
        }
        return family;
    }

    private static void merge(Map<MethodInfo, Set<MethodInfo>> family, MethodInfo a, MethodInfo b) {
        Set<MethodInfo> fa = family.computeIfAbsent(a, k -> new HashSet<>(Set.of(k)));
        Set<MethodInfo> fb = family.computeIfAbsent(b, k -> new HashSet<>(Set.of(k)));
        if (fa == fb) return;
        Set<MethodInfo> big = fa.size() >= fb.size() ? fa : fb;
        Set<MethodInfo> small = big == fa ? fb : fa;
        big.addAll(small);
        for (MethodInfo m : small) family.put(m, big);
    }

    // per analysed method, the analysed methods of the classes it encloses, at any depth
    Map<MethodInfo, List<MethodInfo>> enclosedMethods;

    Map<MethodInfo, List<MethodInfo>> enclosedMethods() {
        if (enclosedMethods != null) return enclosedMethods;
        enclosedMethods = new HashMap<>();
        for (MethodInfo inner : analysed) {
            for (MethodInfo outer = inner.typeInfo().enclosingMethod(); outer != null;
                 outer = outer.typeInfo().enclosingMethod()) {
                enclosedMethods.computeIfAbsent(outer, _ -> new ArrayList<>()).add(inner);
            }
        }
        return enclosedMethods;
    }

    boolean library(Object node) {
        return switch (node) {
            case Content c -> library(c.of());
            case Arg a -> library(a.of());
            case Keyed k -> library(k.slot());
            case ParameterInfo pi -> !analysed.contains(pi.methodInfo());
            case MethodInfo mi -> !analysed.contains(mi);
            default -> false;
        };
    }

    // ------------------------------------------------------------------ keyed maps (CodeLaser/maddi-mod#22 gap 2)

    // gate NOKEYEDMAPS: a map field's values stay one slot for all keys (A/B)
    private static final Switches.Gate KEYED_MAPS_OFF = Switches.gate("NOKEYEDMAPS");

    private static final Set<String> KEYED_MAP_READS = Set.of("get", "containsKey", "containsValue", "size", "isEmpty",
            "toString", "hashCode", "equals", "keySet", "values", "forEach", "remove", "clear");

    /*
     A final map field qualifies when every use the analysed code makes of it is seen: the receiver of a read, of a
     removal, of 'get(k)' or 'put(k, v)', the target of its initialisation with an empty new map, an operand of a
     string concatenation. Anything else (passed on, returned, assigned elsewhere, 'putAll', 'compute', 'entrySet'
     whose entries can be set) may put a value under any key: the field's values stay one slot. An accessor is a
     method whose single statement is 'return field.get(k)' or 'field.put(k, v)' with its own parameters; its callers
     write or read the key they pass.
     */
    void indexKeyedMaps(List<MethodInfo> methods, List<FieldInfo> fields) {
        if (KEYED_MAPS_OFF.get()) return;
        Map<FieldInfo, Boolean> candidates = new HashMap<>();
        for (FieldInfo fi : fields) {
            ParameterizedType type = fi.type();
            if (!fi.isFinal() || type.arrays() > 0 || type.parameters().size() != 2 || !isMap(type)
                || isTypeVariable(type.parameters().get(1))) continue;
            Expression initializer = fi.initializer();
            if (initializer != null && !initializer.isEmpty() && !emptyNewMap(initializer)) continue;
            candidates.put(fi, true);
        }
        if (candidates.isEmpty()) return;
        java.util.Set<Element> accounted = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        Map<MethodInfo, KeyedAccessor> putters = new HashMap<>();
        Map<MethodInfo, KeyedAccessor> getters = new HashMap<>();
        for (MethodInfo mi : methods) {
            if (mi.methodBody() == null) continue;
            mi.methodBody().visit(e -> {
                switch (e) {
                    case MethodCall mc when mc.object() != null && candidateField(mc.object(), candidates) != null -> {
                        FieldInfo fi = candidateField(mc.object(), candidates);
                        accounted.add(NonNullFacts.unwrap(mc.object()));
                        String name = mc.methodInfo() == null ? "" : mc.methodInfo().name();
                        int n = mc.parameterExpressions().size();
                        if ("put".equals(name) && n == 2) {
                            KeyedAccessor a = accessor(mi, mc, fi, true);
                            if (a != null) putters.put(mi, a);
                        } else if ("get".equals(name) && n == 1) {
                            KeyedAccessor a = accessor(mi, mc, fi, false);
                            if (a != null) getters.put(mi, a);
                        } else if (!KEYED_MAP_READS.contains(name)) {
                            candidates.put(fi, false);
                        }
                    }
                    case Assignment a when a.variableTarget() instanceof FieldReference fr
                                           && candidates.containsKey(fr.fieldInfo()) -> {
                        accounted.add(a.target());
                        if (!emptyNewMap(a.value())) candidates.put(fr.fieldInfo(), false);
                    }
                    // a string concatenation reads the map's toString(); also when the parser gives a plain '+'
                    case io.codelaser.maddi.cst.api.expression.BinaryOperator bo when bo instanceof StringConcat
                            || bo.parameterizedType() != null && bo.parameterizedType().arrays() == 0
                               && bo.parameterizedType().typeInfo() != null
                               && "java.lang.String".equals(bo.parameterizedType().typeInfo().fullyQualifiedName()) -> {
                        if (candidateField(bo.lhs(), candidates) != null) accounted.add(NonNullFacts.unwrap(bo.lhs()));
                        if (candidateField(bo.rhs(), candidates) != null) accounted.add(NonNullFacts.unwrap(bo.rhs()));
                    }
                    default -> {
                    }
                }
                return true;
            });
        }
        for (MethodInfo mi : methods) {
            if (mi.methodBody() == null) continue;
            mi.methodBody().visit(e -> {
                if (e instanceof VariableExpression ve && ve.variable() instanceof FieldReference fr
                    && candidates.containsKey(fr.fieldInfo()) && !accounted.contains(ve)) {
                    candidates.put(fr.fieldInfo(), false); // a use not seen through: the map escapes
                }
                return true;
            });
        }
        candidates.forEach((fi, ok) -> {
            if (ok) keyedSlots.put(fi, new Arg(fi, 1));
        });
        putters.forEach((mi, a) -> {
            if (keyedSlots.containsKey(a.field())) keyedPutters.put(mi, a);
        });
        // a getter some override replaces does not return the key's value
        getters.forEach((mi, a) -> {
            if (keyedSlots.containsKey(a.field()) && !implementations.containsKey(mi)
                && (policy.world() == World.CLOSED || !overridable(mi))) {
                keyedGetters.put(mi, a);
            }
        });
    }

    private static boolean overridable(MethodInfo mi) {
        return !mi.isStatic() && !mi.access().isPrivate() && !mi.isFinal() && !mi.typeInfo().isFinal();
    }

    private static FieldInfo candidateField(Expression e, Map<FieldInfo, Boolean> candidates) {
        return NonNullFacts.unwrap(e) instanceof VariableExpression ve && ve.variable() instanceof FieldReference fr
               && candidates.containsKey(fr.fieldInfo()) ? fr.fieldInfo() : null;
    }

    private static boolean emptyNewMap(Expression e) {
        return NonNullFacts.unwrap(e) instanceof ConstructorCall cc && cc.parameterExpressions().isEmpty()
               && cc.anonymousClass() == null;
    }

    private static boolean isMap(ParameterizedType pt) {
        TypeInfo ti = pt.typeInfo();
        if (ti == null) return false;
        return "java.util.Map".equals(ti.fullyQualifiedName())
               || ti.superTypesExcludingJavaLangObject().stream()
                       .anyMatch(t -> "java.util.Map".equals(t.fullyQualifiedName()));
    }

    // 'return field.get(k)' / 'field.put(k, v)' as the method's only statement, k (and v) its own parameters
    private static KeyedAccessor accessor(MethodInfo mi, MethodCall mc, FieldInfo fi, boolean put) {
        Block body = mi.methodBody();
        if (body == null || body.statements().size() != 1) return null;
        Statement only = body.statements().getFirst();
        Expression whole = only instanceof ReturnStatement rs ? rs.expression()
                : only instanceof ExpressionAsStatement eas ? eas.expression() : null;
        if (whole == null || NonNullFacts.unwrap(whole) != mc) return null;
        if (!put && !(only instanceof ReturnStatement)) return null;
        int key = ownParameter(mi, mc.parameterExpressions().getFirst());
        int value = put ? ownParameter(mi, mc.parameterExpressions().get(1)) : -1;
        if (key < 0 || put && value < 0) return null;
        return new KeyedAccessor(fi, key, value);
    }

    static int ownParameter(MethodInfo mi, Expression e) {
        return NonNullFacts.unwrap(e) instanceof VariableExpression ve && ve.variable() instanceof ParameterInfo pi
               && pi.methodInfo() == mi ? pi.index() : -1;
    }

    /*
     The key a constant expression stands for, by value: a literal, or a static final field whose initializer is one
     (FieldConstant.DATA_ID and "dataId" are the same key), or an enum constant (equal only to itself). Null when not
     a constant: a key computed at run time can be any key.
     */
    static Object keyOf(Expression e) {
        return keyOf(e, 0);
    }

    static Object keyOf(Expression e, int depth) {
        Expression x = NonNullFacts.unwrap(e);
        if (x == null || x instanceof NullConstant || depth > 8) return null;
        if (x instanceof ConstantExpression<?> ce && ce.constant() != null) {
            return ce.constant().getClass().getSimpleName() + ":" + ce.constant();
        }
        if (x instanceof VariableExpression ve && ve.variable() instanceof FieldReference fr) {
            FieldInfo fi = fr.fieldInfo();
            if (!fi.isStatic() || !fi.isFinal()) return null;
            if (fi.owner().typeNature().isEnum() && fi.type().typeInfo() == fi.owner()) {
                return "enum:" + fi.fullyQualifiedName();
            }
            Expression initializer = fi.initializer();
            return initializer == null || initializer.isEmpty() ? null : keyOf(initializer, depth + 1);
        }
        return null;
    }

    // the superclass constructor an anonymous class's creation calls: the one of that arity, when there is exactly one
    MethodInfo anonymousSuperConstructor(ConstructorCall cc) {
        TypeInfo anonymous = cc.anonymousClass();
        if (anonymous == null || anonymous.parentClass() == null || anonymous.parentClass().typeInfo() == null) return null;
        int n = cc.parameterExpressions().size();
        List<MethodInfo> candidates = anonymous.parentClass().typeInfo().constructors().stream()
                .filter(c -> c.parameters().size() == n && c.parameters().stream().noneMatch(ParameterInfo::isVarArgs))
                .toList();
        return candidates.size() == 1 && analysed.contains(candidates.getFirst()) ? candidates.getFirst() : null;
    }
}
