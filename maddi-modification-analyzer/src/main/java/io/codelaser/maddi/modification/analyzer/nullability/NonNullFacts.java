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
import io.codelaser.maddi.cst.api.type.NullableState;
import io.codelaser.maddi.cst.api.variable.DependentVariable;
import io.codelaser.maddi.cst.api.variable.FieldReference;
import io.codelaser.maddi.cst.api.variable.LocalVariable;
import io.codelaser.maddi.cst.api.variable.Variable;

import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * The use-site pass (docs/design/nullability.md M4), first cut: per statement, the locals and parameters KNOWN
 * NON-NULL when it starts, by a forward walk over the CST in statement order. Flow-sensitive where the declaration
 * pass (M3) is not: after {@code if (v == null) return;}, inside {@code if (v != null)}, after a dereference
 * {@code v.m()} (it would have thrown), after passing {@code v} to a parameter that demands non-null (a library
 * {@code @NotNull}, which promises an exception on null), after {@code assert v != null}, after
 * {@code v = <non-null expression>}.
 * <p>
 * Conservative where it must be: an assignment kills a fact unless the value is known non-null; a declaration kills
 * the facts of an earlier same-named local; a loop, a {@code try} and a {@code switch} keep only the facts of
 * variables they do not assign; facts established inside a branch that may continue (not ending in return, throw,
 * break or continue) are joined by intersection. Fields are not tracked (another call may change them). A lambda
 * body starts with the facts where it is written: it can only capture effectively final variables.
 * <p>
 * Variables are compared as the CST compares them: a {@link LocalVariable} by name, which is safe at one statement
 * because Java forbids a local to shadow a local, and a declaration kills the facts of an earlier same-named one.
 */
public final class NonNullFacts {

    private final Map<Statement, Set<Variable>> before = new IdentityHashMap<>();
    private final Function<ParameterInfo, NullableState> parameterContract;
    private final Function<MethodInfo, NullableState> returnContract;

    /**
     * @param parameterContract the contract on a callee's parameter (NONNULL: passing null throws), or null
     * @param returnContract    the contract on a callee's return (NONNULL: the call's value is not null), or null
     */
    public NonNullFacts(Function<ParameterInfo, NullableState> parameterContract,
                        Function<MethodInfo, NullableState> returnContract) {
        this.parameterContract = parameterContract;
        this.returnContract = returnContract;
    }

    /** Walks one method body; may be called for several methods. */
    public void walk(MethodInfo methodInfo) {
        if (methodInfo.methodBody() != null) block(methodInfo.methodBody(), Set.of());
    }

    /** The locals and parameters known non-null when {@code statement} starts; empty when it was not walked. */
    public Set<Variable> before(Statement statement) {
        return before.getOrDefault(statement, Set.of());
    }

    public boolean nonNullAt(Statement statement, Variable variable) {
        return before(statement).contains(variable);
    }

    // ------------------------------------------------------------------ statements

    private record Out(Set<Variable> facts, boolean completes) {
    }

    private Out block(Block block, Set<Variable> in) {
        Set<Variable> facts = new HashSet<>(in);
        for (Statement statement : block.statements()) {
            Out out = statement(statement, facts);
            if (!out.completes) return new Out(out.facts, false);
            facts = new HashSet<>(out.facts);
        }
        return new Out(facts, true);
    }

    private Out statement(Statement statement, Set<Variable> in) {
        before.put(statement, Set.copyOf(in));
        Set<Variable> facts = new HashSet<>(in);
        switch (statement) {
            case LocalVariableCreation lvc -> {
                lvc.localVariableStream().forEach(lv -> {
                    effects(lv.assignmentExpression(), facts);
                    facts.remove(lv);
                    if (nonNull(lv.assignmentExpression(), facts)) facts.add(lv);
                });
                return new Out(facts, true);
            }
            case ReturnStatement rs -> {
                effects(rs.expression(), facts);
                return new Out(facts, false);
            }
            case ThrowStatement ts -> {
                effects(ts.expression(), facts);
                return new Out(facts, false);
            }
            case BreakOrContinueStatement _ -> {
                return new Out(facts, false);
            }
            case AssertStatement as -> {
                effects(as.expression(), facts);
                facts.addAll(whenTrue(as.expression()));
                return new Out(facts, true);
            }
            case IfElseStatement ifElse -> {
                Expression condition = ifElse.expression();
                effects(condition, facts);
                Set<Variable> thenIn = new HashSet<>(facts);
                thenIn.addAll(whenTrue(condition));
                Set<Variable> elseIn = new HashSet<>(facts);
                elseIn.addAll(whenFalse(condition));
                Out thenOut = block(ifElse.block(), thenIn);
                Block elseBlock = ifElse.elseBlock();
                Out elseOut = elseBlock == null || elseBlock.isEmpty() ? new Out(elseIn, true) : block(elseBlock, elseIn);
                if (thenOut.completes && elseOut.completes) {
                    Set<Variable> joined = new HashSet<>(thenOut.facts);
                    joined.retainAll(elseOut.facts);
                    return new Out(joined, true);
                }
                if (thenOut.completes) return new Out(thenOut.facts, true);
                if (elseOut.completes) return new Out(elseOut.facts, true);
                return new Out(facts, false);
            }
            case ExpressionAsStatement eas -> {
                effects(eas.expression(), facts);
                return new Out(facts, true);
            }
            case Block b -> {
                return block(b, facts);
            }
            case SynchronizedStatement ss -> {
                effects(ss.expression(), facts);
                return block(ss.block(), facts);
            }
            default -> {
                // loops, try, switch, local type declarations, ...: the condition/selector is evaluated first (for
                // a loop's header only the first time, which still holds afterwards: the loop may not run); each
                // sub-block starts with the facts of variables the statement does not assign; afterwards the same
                if (statement.expression() != null && !(statement instanceof DoStatement)) {
                    effects(statement.expression(), facts);
                }
                Set<Variable> assigned = assignedIn(statement);
                Set<Variable> stable = new HashSet<>(facts);
                stable.removeAll(assigned);
                boolean loop = statement instanceof LoopStatement;
                statement.subBlockStream().forEach(sb -> {
                    Set<Variable> blockIn = new HashSet<>(stable);
                    if (loop && !(statement instanceof DoStatement) && statement.expression() != null) {
                        blockIn.addAll(whenTrue(statement.expression()));
                    }
                    block(sb, blockIn);
                });
                return new Out(stable, true);
            }
        }
    }

    // every local or parameter assigned anywhere in the statement, its sub-blocks included (a loop's updaters too)
    private static Set<Variable> assignedIn(Statement statement) {
        Set<Variable> assigned = new HashSet<>();
        statement.visit(e -> {
            if (e instanceof Assignment a && trackable(a.variableTarget())) assigned.add(a.variableTarget());
            if (e instanceof LocalVariableCreation lvc) lvc.localVariableStream().forEach(assigned::add);
            return true;
        });
        return assigned;
    }

    private static boolean trackable(Variable v) {
        return v instanceof ParameterInfo || v instanceof LocalVariable lv && !lv.simpleName().startsWith("$");
    }

    // ------------------------------------------------------------------ conditions

    // the predefined operators, by name: '==' and '!=' on objects (a null operand is checked separately), '&&', '||'
    private static boolean isOperator(BinaryOperator bo, String name) {
        return bo.operator() != null && name.equals(bo.operator().name());
    }

    private static boolean isNot(UnaryOperator uo) {
        return uo.operator() != null && "!".equals(uo.operator().name());
    }

    // the variable compared with null in 'v == null' / 'v != null', or null
    private static Variable nullCompared(BinaryOperator bo) {
        Expression other = bo.lhs() instanceof NullConstant ? bo.rhs()
                : bo.rhs() instanceof NullConstant ? bo.lhs() : null;
        if (unwrap(other) instanceof VariableExpression ve && trackable(ve.variable())) return ve.variable();
        return null;
    }

    /** The variables non-null when {@code condition} evaluates to true. */
    Set<Variable> whenTrue(Expression condition) {
        Expression c = unwrap(condition);
        Set<Variable> set = new HashSet<>();
        switch (c) {
            case BinaryOperator bo when isOperator(bo, "!=") -> {
                Variable v = nullCompared(bo);
                if (v != null) set.add(v);
            }
            case BinaryOperator bo when isOperator(bo, "&&") -> {
                set.addAll(whenTrue(bo.lhs()));
                set.addAll(whenTrue(bo.rhs()));
            }
            case BinaryOperator bo when isOperator(bo, "||") -> {
                set.addAll(whenTrue(bo.lhs()));
                set.retainAll(whenTrue(bo.rhs()));
            }
            case And and -> and.expressions().forEach(e -> set.addAll(whenTrue(e)));
            case UnaryOperator uo when isNot(uo) -> set.addAll(whenFalse(uo.expression()));
            case Negation n -> set.addAll(whenFalse(n.expression()));
            case InstanceOf io when unwrap(io.expression()) instanceof VariableExpression ve
                                    && trackable(ve.variable()) -> set.add(ve.variable());
            default -> {
            }
        }
        return set;
    }

    /** The variables non-null when {@code condition} evaluates to false. */
    Set<Variable> whenFalse(Expression condition) {
        Expression c = unwrap(condition);
        Set<Variable> set = new HashSet<>();
        switch (c) {
            case BinaryOperator bo when isOperator(bo, "==") -> {
                Variable v = nullCompared(bo);
                if (v != null) set.add(v);
            }
            case BinaryOperator bo when isOperator(bo, "||") -> {
                set.addAll(whenFalse(bo.lhs()));
                set.addAll(whenFalse(bo.rhs()));
            }
            case BinaryOperator bo when isOperator(bo, "&&") -> {
                set.addAll(whenFalse(bo.lhs()));
                set.retainAll(whenFalse(bo.rhs()));
            }
            case Or or -> or.expressions().forEach(e -> set.addAll(whenFalse(e)));
            case UnaryOperator uo when isNot(uo) -> set.addAll(whenTrue(uo.expression()));
            case Negation n -> set.addAll(whenTrue(n.expression()));
            default -> {
            }
        }
        return set;
    }

    static Expression unwrap(Expression e) {
        Expression x = e;
        while (true) {
            if (x instanceof EnclosedExpression ee) x = ee.inner();
            else if (x instanceof Cast c) x = c.expression();
            else return x;
        }
    }

    // ------------------------------------------------------------------ expressions

    /** Is the value of {@code e} certainly not null, given the facts? */
    boolean nonNull(Expression e, Set<Variable> facts) {
        Expression x = unwrap(e);
        return switch (x) {
            case null -> false;
            case NullConstant _ -> false;
            case ConstructorCall _, StringConstant _, StringConcat _, ArrayInitializer _, Lambda _,
                 MethodReference _, ClassExpression _, ConstantExpression<?> _ -> true; // NullConstant is handled above
            case VariableExpression ve -> ve.variable() instanceof io.codelaser.maddi.cst.api.variable.This
                                          || facts.contains(ve.variable());
            case InlineConditional ic -> nonNull(ic.ifTrue(), facts) && nonNull(ic.ifFalse(), facts);
            case MethodCall mc -> mc.methodInfo() != null && returnContract.apply(mc.methodInfo())
                                                             == NullableState.NONNULL;
            case Assignment a -> nonNull(a.value(), facts);
            default -> x.parameterizedType() != null && x.parameterizedType().isPrimitiveExcludingVoid()
                       && x.parameterizedType().arrays() == 0;
        };
    }

    /**
     * What evaluating {@code e} establishes, whenever it completes: a dereferenced variable is not null, an argument
     * to a parameter that demands non-null is not null, an assigned variable is non-null iff its value is. Only the
     * parts evaluated unconditionally: not the branches of {@code ?:}, not the right operand of {@code &&}/{@code ||},
     * not a lambda body (walked as its own block, starting from these facts).
     */
    void effects(Expression e, Set<Variable> facts) {
        if (e == null) return;
        e.visit(element -> {
            switch (element) {
                case Lambda lambda -> {
                    if (lambda.methodBody() != null) block(lambda.methodBody(), Set.copyOf(facts));
                    return false;
                }
                case InlineConditional ic -> {
                    effects(ic.condition(), facts);
                    return false;
                }
                case BinaryOperator bo when isOperator(bo, "&&")
                                            || isOperator(bo, "||") -> {
                    effects(bo.lhs(), facts);
                    return false;
                }
                case And and -> {
                    if (!and.expressions().isEmpty()) effects(and.expressions().getFirst(), facts);
                    return false;
                }
                case Or or -> {
                    if (!or.expressions().isEmpty()) effects(or.expressions().getFirst(), facts);
                    return false;
                }
                case Assignment a -> {
                    effects(a.value(), facts);
                    Variable target = a.variableTarget();
                    if (trackable(target)) {
                        boolean nonNull = a.assignmentOperator() == null && nonNull(a.value(), facts);
                        facts.remove(target);
                        if (nonNull) facts.add(target);
                    } else if (target instanceof FieldReference fr) {
                        dereference(fr.scope(), facts);
                    }
                    return false;
                }
                case MethodCall mc -> {
                    if (mc.methodInfo() != null && !mc.methodInfo().isStatic()) dereference(mc.object(), facts);
                    List<Expression> arguments = mc.parameterExpressions();
                    for (Expression argument : arguments) effects(argument, facts);
                    if (mc.object() != null) effects(mc.object(), facts);
                    if (mc.methodInfo() != null) demanded(mc.methodInfo(), arguments, facts);
                    return false;
                }
                case ConstructorCall cc -> {
                    for (Expression argument : cc.parameterExpressions()) effects(argument, facts);
                    if (cc.constructor() != null) demanded(cc.constructor(), cc.parameterExpressions(), facts);
                    return false;
                }
                case VariableExpression ve -> {
                    if (ve.variable() instanceof FieldReference fr && !fr.fieldInfo().isStatic()) dereference(fr.scope(), facts);
                    else if (ve.variable() instanceof DependentVariable dv) dereference(dv.arrayExpression(), facts);
                    return true;
                }
                default -> {
                    return true;
                }
            }
        });
    }

    private static void dereference(Expression scope, Set<Variable> facts) {
        if (unwrap(scope) instanceof VariableExpression ve && trackable(ve.variable())) facts.add(ve.variable());
    }

    private void demanded(MethodInfo callee, List<Expression> arguments, Set<Variable> facts) {
        List<ParameterInfo> parameters = callee.parameters();
        for (int i = 0; i < arguments.size() && i < parameters.size(); i++) {
            ParameterInfo pi = parameters.get(i);
            if (pi.isVarArgs()) break;
            if (parameterContract.apply(pi) == NullableState.NONNULL
                && unwrap(arguments.get(i)) instanceof VariableExpression ve && trackable(ve.variable())) {
                facts.add(ve.variable());
            }
        }
    }
}
