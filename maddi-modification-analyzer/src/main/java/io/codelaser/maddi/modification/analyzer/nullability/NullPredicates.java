/*
 * maddi: a modification analyzer for duplication detection and immutability.
 * Copyright 2020-2025, Bart Naudts, https://github.com/CodeLaser/maddi
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Lesser General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License for
 * more details. You should have received a copy of the GNU Lesser General Public
 * License along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package io.codelaser.maddi.modification.analyzer.nullability;

import io.codelaser.maddi.cst.api.expression.*;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.ParameterInfo;
import io.codelaser.maddi.cst.api.statement.*;
import io.codelaser.maddi.cst.api.variable.Variable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Null-check predicates: boolean methods whose result tells whether an argument is null, such as
 * {@code StringUtils.isNotBlank(s)} (false when {@code s} is null, so true means non-null) or
 * {@code Strings.isNullOrEmpty(s)} (true when null, so false means non-null). {@link NonNullFacts} uses them as
 * conditions, like {@code s != null}.
 * <p>
 * Two sources:
 * <ul>
 *     <li>a table of well-known library predicates (commons-lang and Spring {@code StringUtils}, {@code Objects},
 *     guava {@code Strings}, the {@code CollectionUtils}/{@code MapUtils}/{@code ArrayUtils} families);</li>
 *     <li>inference on analysed methods: the body evaluated with the parameter null, until every path returns the
 *     same constant. To a fixed point, so {@code isNotBlank(s) { return !isBlank(s); }} follows {@code isBlank}.</li>
 * </ul>
 * Not for Kotlin's smart casts: Kotlin does not see a Java method's result as a null check.
 */
public final class NullPredicates {

    /** What the method returns when the argument is null. */
    public enum When {TRUE_IF_NULL, FALSE_IF_NULL}

    private static final Set<String> FALSE_IF_NULL = Set.of(
            "org.apache.commons.lang3.StringUtils.isNotEmpty", "org.apache.commons.lang3.StringUtils.isNotBlank",
            "org.apache.commons.lang.StringUtils.isNotEmpty", "org.apache.commons.lang.StringUtils.isNotBlank",
            "org.springframework.util.StringUtils.hasText", "org.springframework.util.StringUtils.hasLength",
            "java.util.Objects.nonNull",
            "org.apache.commons.collections4.CollectionUtils.isNotEmpty",
            "org.apache.commons.collections.CollectionUtils.isNotEmpty",
            "org.apache.commons.collections4.MapUtils.isNotEmpty",
            "org.apache.commons.collections.MapUtils.isNotEmpty",
            "org.apache.commons.lang3.ArrayUtils.isNotEmpty");

    private static final Set<String> TRUE_IF_NULL = Set.of(
            "org.apache.commons.lang3.StringUtils.isEmpty", "org.apache.commons.lang3.StringUtils.isBlank",
            "org.apache.commons.lang.StringUtils.isEmpty", "org.apache.commons.lang.StringUtils.isBlank",
            "org.springframework.util.StringUtils.isEmpty", "org.springframework.util.ObjectUtils.isEmpty",
            "org.springframework.util.CollectionUtils.isEmpty",
            "java.util.Objects.isNull", "com.google.common.base.Strings.isNullOrEmpty",
            "org.apache.commons.collections4.CollectionUtils.isEmpty",
            "org.apache.commons.collections.CollectionUtils.isEmpty",
            "org.apache.commons.collections4.MapUtils.isEmpty", "org.apache.commons.collections.MapUtils.isEmpty",
            "org.apache.commons.lang3.ArrayUtils.isEmpty");

    private static final int MAX_ROUNDS = 5;

    // per analysed method, per parameter index
    private final Map<MethodInfo, Map<Integer, When>> inferred = new HashMap<>();

    /** Infers the analysed predicates among {@code methods}. */
    public static NullPredicates infer(List<MethodInfo> methods) {
        NullPredicates predicates = new NullPredicates();
        List<MethodInfo> candidates = methods.stream()
                .filter(m -> m.methodBody() != null && !m.isConstructor() && m.returnType().isBooleanOrBoxedBoolean()
                             && !m.parameters().isEmpty())
                .toList();
        for (int round = 0; round < MAX_ROUNDS; round++) {
            boolean changed = false;
            for (MethodInfo m : candidates) {
                for (ParameterInfo pi : m.parameters()) {
                    if (pi.parameterizedType().isPrimitiveExcludingVoid()) continue;
                    When when = predicates.evaluate(m, pi);
                    if (when == null) continue;
                    if (predicates.inferred.computeIfAbsent(m, _ -> new HashMap<>()).put(pi.index(), when) != when) {
                        changed = true;
                    }
                }
            }
            if (!changed) break;
        }
        return predicates;
    }

    /** What {@code method} returns when its parameter {@code index} is null; null when unknown. */
    public When of(MethodInfo method, int index) {
        if (method == null) return null;
        Map<Integer, When> byIndex = inferred.get(method);
        if (byIndex != null && byIndex.containsKey(index)) return byIndex.get(index);
        if (index != 0 || method.parameters().size() != 1) return null;
        String name = method.typeInfo().fullyQualifiedName() + "." + method.name();
        if (FALSE_IF_NULL.contains(name)) return When.FALSE_IF_NULL;
        if (TRUE_IF_NULL.contains(name)) return When.TRUE_IF_NULL;
        return null;
    }

    // ------------------------------------------------------------------ evaluation with the parameter null

    // a path's outcome: it returns this value (or one that is not known), it throws, or it falls through
    private enum Outcome {TRUE, FALSE, UNKNOWN, THROWS, CONTINUES}

    private When evaluate(MethodInfo m, ParameterInfo p) {
        Outcome outcome = block(m.methodBody(), p);
        return switch (outcome) {
            case TRUE -> When.TRUE_IF_NULL;
            case FALSE -> When.FALSE_IF_NULL;
            default -> null;
        };
    }

    private Outcome block(Block block, Variable p) {
        for (Statement statement : block.statements()) {
            Outcome outcome = statement(statement, p);
            if (outcome != Outcome.CONTINUES) return outcome;
        }
        return Outcome.CONTINUES;
    }

    private Outcome statement(Statement statement, Variable p) {
        switch (statement) {
            case ReturnStatement rs -> {
                Boolean value = value(rs.expression(), p);
                return value == null ? Outcome.UNKNOWN : value ? Outcome.TRUE : Outcome.FALSE;
            }
            case ThrowStatement _ -> {
                return Outcome.THROWS;
            }
            case IfElseStatement ifElse -> {
                Boolean condition = value(ifElse.expression(), p);
                Block elseBlock = ifElse.elseBlock();
                Outcome whenTrue = condition == Boolean.FALSE ? null : block(ifElse.block(), p);
                Outcome whenFalse = condition == Boolean.TRUE ? null
                        : elseBlock == null ? Outcome.CONTINUES : block(elseBlock, p);
                if (whenTrue == null) return whenFalse;
                if (whenFalse == null) return whenTrue;
                return join(whenTrue, whenFalse);
            }
            case LocalVariableCreation lvc -> {
                // 'int strLen;' and the like; an initializer that reads the parameter may dereference it
                boolean reads = lvc.localVariableStream()
                        .anyMatch(lv -> lv.assignmentExpression() != null && mentions(lv.assignmentExpression(), p));
                return reads ? Outcome.UNKNOWN : Outcome.CONTINUES;
            }
            default -> {
                return Outcome.UNKNOWN;
            }
        }
    }

    // both branches of an 'if' the null does not decide
    private static Outcome join(Outcome a, Outcome b) {
        if (a == Outcome.THROWS) return b;
        if (b == Outcome.THROWS) return a;
        return a == b ? a : Outcome.UNKNOWN;
    }

    // the value of a boolean expression when p is null: TRUE, FALSE, or null when not known
    private Boolean value(Expression expression, Variable p) {
        Expression e = NonNullFacts.unwrap(expression);
        switch (e) {
            case BooleanConstant bc -> {
                return bc.constant();
            }
            case BinaryOperator bo when operator(bo, "==") || operator(bo, "!=") -> {
                Expression other = bo.lhs() instanceof NullConstant ? bo.rhs()
                        : bo.rhs() instanceof NullConstant ? bo.lhs() : null;
                if (!isVariable(other, p)) return null;
                return operator(bo, "==");
            }
            case BinaryOperator bo when operator(bo, "&&") -> {
                return and(value(bo.lhs(), p), () -> value(bo.rhs(), p));
            }
            case BinaryOperator bo when operator(bo, "||") -> {
                return or(value(bo.lhs(), p), () -> value(bo.rhs(), p));
            }
            case And and -> {
                Boolean result = Boolean.TRUE;
                for (Expression operand : and.expressions()) {
                    Boolean current = result;
                    result = and(current, () -> value(operand, p));
                    if (result == Boolean.FALSE) return false;
                }
                return result;
            }
            case Or or -> {
                Boolean result = Boolean.FALSE;
                for (Expression operand : or.expressions()) {
                    Boolean current = result;
                    result = or(current, () -> value(operand, p));
                    if (result == Boolean.TRUE) return true;
                }
                return result;
            }
            case UnaryOperator uo when uo.operator() != null && "!".equals(uo.operator().name()) -> {
                Boolean v = value(uo.expression(), p);
                return v == null ? null : !v;
            }
            case Negation n -> {
                Boolean v = value(n.expression(), p);
                return v == null ? null : !v;
            }
            case MethodCall mc -> {
                List<Expression> arguments = mc.parameterExpressions();
                for (int i = 0; i < arguments.size(); i++) {
                    if (!isVariable(arguments.get(i), p)) continue;
                    When when = of(mc.methodInfo(), i);
                    if (when != null) return when == When.TRUE_IF_NULL;
                }
                return null;
            }
            default -> {
                return null;
            }
        }
    }

    // 'a && b': b is evaluated only when a is true; false whenever either is
    private static Boolean and(Boolean a, java.util.function.Supplier<Boolean> b) {
        if (a == Boolean.FALSE) return false;
        Boolean vb = b.get();
        if (a == Boolean.TRUE) return vb;
        return vb == Boolean.FALSE ? Boolean.FALSE : null;
    }

    private static Boolean or(Boolean a, java.util.function.Supplier<Boolean> b) {
        if (a == Boolean.TRUE) return true;
        Boolean vb = b.get();
        if (a == Boolean.FALSE) return vb;
        return vb == Boolean.TRUE ? Boolean.TRUE : null;
    }

    private static boolean operator(BinaryOperator bo, String name) {
        return bo.operator() != null && name.equals(bo.operator().name());
    }

    private static boolean isVariable(Expression e, Variable v) {
        return e != null && NonNullFacts.unwrap(e) instanceof VariableExpression ve && ve.variable().equals(v);
    }

    private static boolean mentions(Expression e, Variable v) {
        boolean[] found = {false};
        e.visit(x -> {
            if (x instanceof VariableExpression ve && ve.variable().equals(v)) found[0] = true;
            return !found[0];
        });
        return found[0];
    }
}
