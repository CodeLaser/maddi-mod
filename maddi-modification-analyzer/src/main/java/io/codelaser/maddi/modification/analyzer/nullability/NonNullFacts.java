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
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.api.type.ParameterizedType;

import java.util.ArrayList;
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
 * break or continue) are joined by intersection. A field of {@code this} is tracked too, but a non-final one only
 * until the next call (which may change it), and not into a lambda body; a lambda body otherwise starts with the
 * facts where it is written: it can only capture effectively final variables.
 * <p>
 * Inside a statement the facts follow evaluation order: the right operand of {@code &&} sees the left one true,
 * that of {@code ||} sees it false, the branches of {@code ?:} see the condition true or false. Every call and every
 * field or array access records the facts at that point ({@link #at(Expression)}).
 * <p>
 * {@link #kotlinSmartCasts()} restricts the facts to what Kotlin's smart cast also derives, for a printer that drops
 * {@code !!} and relies on it: no fact from a Java parameter that demands non-null ({@code requireNonNull(v)}), from
 * {@code assert}, or from assigning a Java call's result (a platform type); of fields only the final ones (a
 * {@code val}; Kotlin never smart-casts a {@code var} property).
 * <p>
 * Variables are compared as the CST compares them: a {@link LocalVariable} by name, which is safe at one statement
 * because Java forbids a local to shadow a local, and a declaration kills the facts of an earlier same-named one.
 */
public final class NonNullFacts {

    private final Map<Statement, Set<Variable>> before = new IdentityHashMap<>();
    private final Map<Expression, Set<Variable>> atExpression = new IdentityHashMap<>();
    // per call: the facts once the receiver and all arguments are evaluated, when the callee runs
    private final Map<Expression, Set<Variable>> whenCalled = new IdentityHashMap<>();
    private final Map<MethodInfo, Set<Variable>> atExit = new IdentityHashMap<>();
    // the facts at each normal exit (a return, the end of the body) of the method being walked; null in a lambda
    private List<Set<Variable>> exits;
    private final Function<ParameterInfo, NullableState> parameterContract;
    private final Function<MethodInfo, NullableState> returnContract;
    private final boolean kotlinSmartCasts;
    // null-check predicates ('StringUtils.isNotBlank(s)'), as conditions; never for Kotlin's smart casts
    private NullPredicates predicates;
    // is the member (a getter, a field) non-null on every instance of the type? (Narrowing, CodeLaser/maddi-mod#22 gap 3)
    private java.util.function.BiPredicate<Object, TypeInfo> narrowedNonNull;

    /**
     * @param parameterContract the contract on a callee's parameter (NONNULL: passing null throws), or null
     * @param returnContract    the contract on a callee's return (NONNULL: the call's value is not null), or null
     */
    public NonNullFacts(Function<ParameterInfo, NullableState> parameterContract,
                        Function<MethodInfo, NullableState> returnContract) {
        this(parameterContract, returnContract, false);
    }

    private NonNullFacts(Function<ParameterInfo, NullableState> parameterContract,
                         Function<MethodInfo, NullableState> returnContract, boolean kotlinSmartCasts) {
        this.parameterContract = parameterContract;
        this.returnContract = returnContract;
        this.kotlinSmartCasts = kotlinSmartCasts;
    }

    /** Null-check predicates count as conditions ({@link NullPredicates}); not in {@link #kotlinSmartCasts()}. */
    public NonNullFacts withPredicates(NullPredicates predicates) {
        this.predicates = predicates;
        return this;
    }

    /**
     * A read narrowed by the receiver's type ({@link Narrowing}): {@code nonNull.test(member, type)} says that
     * {@code x.member} is not null on any instance of {@code type}. Not in {@link #kotlinSmartCasts()}.
     */
    public NonNullFacts withNarrowedReads(java.util.function.BiPredicate<Object, TypeInfo> nonNull) {
        this.narrowedNonNull = nonNull;
        return this;
    }

    /** The same walk, restricted to what Kotlin's smart cast derives (class comment); walk it before use. */
    public NonNullFacts kotlinSmartCasts() {
        return new NonNullFacts(parameterContract, returnContract, true);
    }

    /** Walks one method body; may be called for several methods. */
    public void walk(MethodInfo methodInfo) {
        Block body = methodInfo.methodBody();
        if (body == null) return;
        exits = new ArrayList<>();
        Out out = block(body, Set.of());
        if (out.completes) exits.add(out.facts);
        if (!exits.isEmpty()) {
            Set<Variable> common = new HashSet<>(exits.getFirst());
            exits.forEach(common::retainAll);
            common.removeAll(assignedIn(body));
            atExit.put(methodInfo, Set.copyOf(common));
        }
        exits = null;
    }

    /**
     * Is {@code parameter} non-null whenever its method returns normally, without ever being assigned? Then a null
     * argument throws before the method completes (a dereference, {@code requireNonNull}, an explicit
     * {@code if (p == null) throw}): a precondition. False for a method that never completes normally.
     */
    public boolean nonNullAtExit(MethodInfo methodInfo, ParameterInfo parameter) {
        return atExit.getOrDefault(methodInfo, Set.of()).contains(parameter);
    }

    /** The locals and parameters known non-null when {@code statement} starts; empty when it was not walked. */
    public Set<Variable> before(Statement statement) {
        return before.getOrDefault(statement, Set.of());
    }

    public boolean nonNullAt(Statement statement, Variable variable) {
        return before(statement).contains(variable);
    }

    /**
     * The variables known non-null when {@code expression} is evaluated, for a method call, a constructor call, or
     * a field or array access {@code v.f} / {@code v[i]}; empty when it was not walked. Includes what the enclosing
     * condition establishes: in {@code v != null && v.m()} the call {@code v.m()} has {@code v}. For a call: when
     * its evaluation BEGINS, before the receiver and the arguments (Kotlin smart-casts left to right, so in
     * {@code s.add(v.f)} the receiver {@code s} must not see {@code v} as dereferenced); for a field or array
     * access: after its scope, before its own dereference.
     */
    public Set<Variable> at(Expression expression) {
        return atExpression.getOrDefault(expression, Set.of());
    }

    public boolean nonNullAt(Expression expression, Variable variable) {
        return at(expression).contains(variable);
    }

    /**
     * Is {@code variable} known non-null when the callee of {@code call} starts: after the receiver and every argument
     * have been evaluated. In {@code s.add(x, x.id)} the call only happens with a non-null {@code x}, although
     * {@link #at(Expression)} (when the call's evaluation begins) does not have it.
     */
    public boolean nonNullWhenCalled(Expression call, Variable variable) {
        return whenCalled.getOrDefault(call, Set.of()).contains(variable);
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
        if (statement instanceof LoopStatement && !(statement instanceof DoStatement)) {
            // the condition is evaluated again after the body: what the loop assigns is not known at its start
            // ('while (node.parent != null) { node = node.parent; }')
            Set<Variable> stableIn = new HashSet<>(in);
            Set<Variable> assignedInLoop = assignedIn(statement);
            stableIn.removeAll(assignedInLoop);
            stableIn.removeIf(v -> dependsOnAssigned(v, assignedInLoop));
            if (containsCall(statement)) stableIn.removeIf(NonNullFacts::isNonFinalField);
            before.put(statement, Set.copyOf(stableIn));
            return loop(statement, stableIn);
        }
        before.put(statement, Set.copyOf(in));
        Set<Variable> facts = new HashSet<>(in);
        switch (statement) {
            case LocalVariableCreation lvc -> {
                lvc.localVariableStream().forEach(lv -> {
                    effects(lv.assignmentExpression(), facts);
                    facts.remove(lv);
                    facts.removeIf(v -> dependsOnAssigned(v, Set.of(lv)));
                    if (nonNull(lv.assignmentExpression(), facts)) facts.add(lv);
                    narrowedRead(lv, lv.assignmentExpression(), facts);
                });
                return new Out(facts, true);
            }
            case ReturnStatement rs -> {
                effects(rs.expression(), facts);
                if (exits != null) exits.add(Set.copyOf(facts));
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
                if (!kotlinSmartCasts) addCondition(facts, whenTrue(as.expression()));
                return new Out(facts, true);
            }
            case IfElseStatement ifElse -> {
                Expression condition = ifElse.expression();
                effects(condition, facts);
                Set<Variable> thenIn = new HashSet<>(facts);
                addCondition(thenIn, whenTrue(condition));
                Set<Variable> elseIn = new HashSet<>(facts);
                addCondition(elseIn, whenFalse(condition));
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
                // do-while, try, switch, local type declarations, ...: the selector is evaluated first; each
                // sub-block starts with the facts of variables the statement does not assign; afterwards the same
                if (statement.expression() != null && !(statement instanceof DoStatement)) {
                    effects(statement.expression(), facts);
                }
                Set<Variable> assigned = assignedIn(statement);
                Set<Variable> stable = new HashSet<>(facts);
                stable.removeAll(assigned);
                stable.removeIf(v -> dependsOnAssigned(v, assigned));
                if (containsCall(statement)) stable.removeIf(NonNullFacts::isNonFinalField);
                statement.subBlockStream().forEach(sb -> {
                    if (statement instanceof SwitchStatementOldStyle) {
                        // every case label is a jump target: no statement inherits the facts of the one before it
                        for (Statement s : sb.statements()) statement(s, new HashSet<>(stable));
                    } else {
                        block(sb, new HashSet<>(stable));
                    }
                });
                return new Out(stable, true);
            }
        }
    }

    // a while/for/for-each loop, with the facts that hold at every evaluation of its condition
    private Out loop(Statement statement, Set<Variable> in) {
        Set<Variable> facts = new HashSet<>(in);
        if (statement.expression() != null) effects(statement.expression(), facts);
        statement.subBlockStream().forEach(sb -> {
            Set<Variable> blockIn = new HashSet<>(facts);
            if (statement.expression() != null) addCondition(blockIn, whenTrue(statement.expression()));
            if (statement instanceof ForEachStatement fe) {
                Variable present = keySetLoop(fe);
                if (present != null) blockIn.add(present);
            }
            block(sb, blockIn);
        });
        // the condition is evaluated on every way out (but the last evaluation's 'false' is not known here)
        return new Out(facts, true);
    }

    private static boolean containsCall(Statement statement) {
        boolean[] call = {false};
        statement.visit(e -> {
            if (e instanceof MethodCall || e instanceof ConstructorCall) call[0] = true;
            return !call[0];
        });
        return call[0];
    }

    // every local or parameter assigned anywhere in the statement, its sub-blocks included (a loop's updaters too)
    private Set<Variable> assignedIn(Statement statement) {
        Set<Variable> assigned = new HashSet<>();
        statement.visit(e -> {
            if (e instanceof Assignment a && trackable(a.variableTarget())) assigned.add(a.variableTarget());
            if (e instanceof LocalVariableCreation lvc) lvc.localVariableStream().forEach(assigned::add);
            return true;
        });
        return assigned;
    }

    // locals, parameters, and the instance fields of 'this' (Kotlin smart casts: only the final ones)
    private boolean trackable(Variable v) {
        return v instanceof ParameterInfo
               || v instanceof LocalVariable lv && !lv.simpleName().startsWith("$")
               || v instanceof FieldReference fr && fr.scopeIsRecursivelyThis() && !fr.fieldInfo().isStatic()
                  && (!kotlinSmartCasts || fr.fieldInfo().isFinal())
               || !kotlinSmartCasts && isConstantElement(v);
    }

    /*
     'a[0]' of a local or parameter array, Java only (Kotlin does not smart-cast an element): fernflower's
     'Object[] res = f(); if (res[0] != null) g((X) res[0]);'. Forgotten at a call, which may write the array, and
     when the array variable is assigned.
     */
    private static boolean isConstantElement(Variable v) {
        return v instanceof DependentVariable dv && dv.indexExpression() instanceof IntConstant
               && (dv.arrayVariable() instanceof ParameterInfo
                   || dv.arrayVariable() instanceof LocalVariable lv && !lv.simpleName().startsWith("$"));
    }

    private static boolean isNonFinalField(Variable v) {
        return v instanceof FieldReference fr && !fr.fieldInfo().isFinal();
    }

    // ------------------------------------------------------------------ key presence (CodeLaser/maddi-mod#22 gap 1)

    /*
     'map[k]', a synthetic element variable: the key k is PRESENT in map, so 'map.get(k)' / 'map.remove(k)' is not
     the absent key's null (what the map holds for k still flows through the map's value slot). Established by
     'map.containsKey(k)' true, by 'map.put(k, v)' with v non-null, and inside 'for (K k : map.keySet())'. Forgotten
     when map or k is assigned, and at a call that may change the map: one on the map itself other than a read, or
     one handed the map. Java only: Kotlin does not smart-cast a lookup.
     */
    private static final Set<String> MAP_READS = Set.of("get", "getOrDefault", "containsKey", "containsValue",
            "size", "isEmpty", "keySet", "values", "entrySet", "forEach", "equals", "hashCode", "toString");

    /** Is the key of {@code lookup} ({@code map.get(k)}, {@code map.remove(k)}) known present when it is called? */
    public boolean keyPresentWhenCalled(MethodCall lookup) {
        if (lookup.methodInfo() == null
            || !("get".equals(lookup.methodInfo().name()) || "remove".equals(lookup.methodInfo().name()))) {
            return false;
        }
        Variable present = keyPresence(lookup, null);
        return present != null && whenCalled.getOrDefault(lookup, Set.of()).contains(present);
    }

    // the key-presence fact of a call on a map variable whose first argument is the key, or null
    private Variable keyPresence(MethodCall mc, String name) {
        if (kotlinSmartCasts || mc.methodInfo() == null || mc.object() == null || mc.parameterExpressions().isEmpty()
            || name != null && !name.equals(mc.methodInfo().name())) return null;
        Variable map = trackableVariable(mc.object());
        Variable key = trackableVariable(mc.parameterExpressions().getFirst());
        if (map == null || key == null || !isMap(mc.object().parameterizedType())) return null;
        return keyPresence(map, key, mc.object().parameterizedType());
    }

    // 'for (K k : map.keySet())': k is present in map throughout the body
    private Variable keySetLoop(ForEachStatement fe) {
        if (kotlinSmartCasts || !(unwrap(fe.expression()) instanceof MethodCall mc) || mc.methodInfo() == null
            || !"keySet".equals(mc.methodInfo().name()) || mc.object() == null) return null;
        Variable map = trackableVariable(mc.object());
        LocalVariable k = fe.initializer().localVariable();
        if (map == null || k == null || !isMap(mc.object().parameterizedType())) return null;
        return keyPresence(map, k, mc.object().parameterizedType());
    }

    private Variable trackableVariable(Expression e) {
        return unwrap(e) instanceof VariableExpression ve && trackable(ve.variable()) ? ve.variable() : null;
    }

    private static boolean isMap(ParameterizedType pt) {
        TypeInfo ti = pt == null || pt.arrays() > 0 ? null : pt.typeInfo();
        if (ti == null) return false;
        return "java.util.Map".equals(ti.fullyQualifiedName())
               || ti.superTypesExcludingJavaLangObject().stream()
                       .anyMatch(t -> "java.util.Map".equals(t.fullyQualifiedName()));
    }

    private static Variable keyPresence(Variable map, Variable key, ParameterizedType mapType) {
        ParameterizedType valueType = mapType.parameters().size() == 2 ? mapType.parameters().get(1) : mapType;
        return new KeyPresence(map, key, valueType);
    }

    // an element or key-presence fact of a variable that is assigned
    private static boolean dependsOnAssigned(Variable v, Set<Variable> assigned) {
        return v instanceof DependentVariable dv
               && (dv.arrayVariable() != null && assigned.contains(dv.arrayVariable())
                   || dv.indexVariable() != null && assigned.contains(dv.indexVariable()))
               || v instanceof KeyPresence kp && (assigned.contains(kp.map()) || assigned.contains(kp.key()))
               || v instanceof Narrowing n && (assigned.contains(n.subject())
                                               || n.local() != null && assigned.contains(n.local()));
    }

    // a call may change any non-final field and the elements of any array; a map's keys stay present unless the
    // call is on the map itself (other than a read) or is handed the map
    private static void callMade(Set<Variable> facts, Expression call) {
        Variable receiver = call instanceof MethodCall mc && mc.object() != null
                            && unwrap(mc.object()) instanceof VariableExpression ve ? ve.variable() : null;
        boolean read = call instanceof MethodCall mc && mc.methodInfo() != null
                       && MAP_READS.contains(mc.methodInfo().name());
        List<Expression> arguments = call instanceof MethodCall mc ? mc.parameterExpressions()
                : call instanceof ConstructorCall cc ? cc.parameterExpressions() : List.of();
        facts.removeIf(v -> isNonFinalField(v) || v instanceof DependentVariable
                            || v instanceof KeyPresence kp && touches(kp.map(), receiver, read, arguments));
    }

    private static boolean touches(Variable map, Variable receiver, boolean read, List<Expression> arguments) {
        if (map.equals(receiver) && !read) return true;
        return arguments.stream().anyMatch(a -> unwrap(a) instanceof VariableExpression ve && map.equals(ve.variable()));
    }

    // ------------------------------------------------------------------ narrowed reads (CodeLaser/maddi-mod#22 gap 3)

    // a condition's facts, and what they make of the narrowed reads already known
    private void addCondition(Set<Variable> facts, Set<Variable> condition) {
        facts.addAll(condition);
        derive(facts);
    }

    /*
     'l = x.getF()' / 'l = x.f' with x a tracked variable: remembered, so that a later 'x instanceof T' (or an earlier
     one still holding) makes l non-null when no instance of T has a null there. Java only: Kotlin does not smart-cast
     l on a test of x.
     */
    private void narrowedRead(Variable local, Expression value, Set<Variable> facts) {
        if (kotlinSmartCasts || narrowedNonNull == null || !(local instanceof LocalVariable)) return;
        Expression x = unwrap(value);
        Narrowing read = null;
        if (x instanceof MethodCall mc && mc.methodInfo() != null && !mc.methodInfo().isStatic()
            && mc.parameterExpressions().isEmpty() && mc.object() != null) {
            Variable subject = trackableVariable(mc.object());
            if (subject != null) read = Narrowing.read(local, subject, mc.methodInfo());
        } else if (x instanceof VariableExpression ve && ve.variable() instanceof FieldReference fr
                   && !fr.fieldInfo().isStatic() && fr.scope() != null) {
            Variable subject = trackableVariable(fr.scope());
            if (subject != null) read = Narrowing.read(local, subject, fr.fieldInfo());
        }
        if (read != null) {
            facts.add(read);
            derive(facts);
        }
    }

    // a narrowed read whose subject is known to be an instance of a type with no null there: the local is non-null
    private void derive(Set<Variable> facts) {
        if (kotlinSmartCasts || narrowedNonNull == null) return;
        List<Narrowing> instances = new ArrayList<>();
        List<Narrowing> reads = new ArrayList<>();
        for (Variable v : facts) {
            if (v instanceof Narrowing n) (n.isInstance() ? instances : reads).add(n);
        }
        for (Narrowing read : reads) {
            for (Narrowing instance : instances) {
                if (read.subject().equals(instance.subject()) && narrowedNonNull.test(read.member(), instance.type())) {
                    facts.add(read.local());
                }
            }
        }
    }

    // ------------------------------------------------------------------ conditions

    // the predefined operators, by name: '==' and '!=' on objects (a null operand is checked separately), '&&', '||'
    static boolean isOperator(BinaryOperator bo, String name) {
        return bo.operator() != null && name.equals(bo.operator().name());
    }

    private static boolean isNot(UnaryOperator uo) {
        return uo.operator() != null && "!".equals(uo.operator().name());
    }

    // the variable compared with null in 'v == null' / 'v != null', or null
    private Variable nullCompared(BinaryOperator bo) {
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
                                    && trackable(ve.variable()) -> {
                set.add(ve.variable());
                ParameterizedType tested = io.testType();
                if (!kotlinSmartCasts && tested != null && tested.arrays() == 0 && tested.typeInfo() != null) {
                    set.add(Narrowing.instance(ve.variable(), tested.typeInfo()));
                }
            }
            case MethodCall mc -> {
                set.addAll(predicated(mc, NullPredicates.When.FALSE_IF_NULL));
                Variable present = keyPresence(mc, "containsKey");
                if (present != null) set.add(present);
            }
            default -> {
            }
        }
        return set;
    }

    // the arguments a null-check predicate tells non-null when its result is the one it never has for a null
    private Set<Variable> predicated(MethodCall mc, NullPredicates.When when) {
        if (predicates == null || kotlinSmartCasts || mc.methodInfo() == null) return Set.of();
        Set<Variable> set = new HashSet<>();
        List<Expression> arguments = mc.parameterExpressions();
        for (int i = 0; i < arguments.size(); i++) {
            if (unwrap(arguments.get(i)) instanceof VariableExpression ve && trackable(ve.variable())
                && predicates.of(mc.methodInfo(), i) == when) {
                set.add(ve.variable());
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
            case MethodCall mc -> set.addAll(predicated(mc, NullPredicates.When.TRUE_IF_NULL));
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
            case MethodCall mc -> !kotlinSmartCasts && mc.methodInfo() != null
                                  && returnContract.apply(mc.methodInfo()) == NullableState.NONNULL;
            case Assignment a -> nonNull(a.value(), facts);
            default -> x.parameterizedType() != null && x.parameterizedType().isPrimitiveExcludingVoid()
                       && x.parameterizedType().arrays() == 0;
        };
    }

    /**
     * What evaluating {@code e} establishes, whenever it completes: a dereferenced variable is not null, an argument
     * to a parameter that demands non-null is not null, an assigned variable is non-null iff its value is; a call
     * forgets the non-final fields. A conditionally evaluated part (the right operand of {@code &&}/{@code ||}, a
     * branch of {@code ?:}) is walked with its condition's facts on a copy: what it establishes is not kept. A
     * lambda body is walked as its own block.
     */
    void effects(Expression e, Set<Variable> facts) {
        if (e == null) return;
        e.visit(element -> {
            switch (element) {
                case Lambda lambda -> {
                    if (lambda.methodBody() != null) {
                        Set<Variable> in = new HashSet<>(facts);
                        in.removeIf(NonNullFacts::isNonFinalField); // the body runs later
                        List<Set<Variable>> enclosing = exits;
                        exits = null; // a return in the lambda does not leave the method
                        block(lambda.methodBody(), in);
                        exits = enclosing;
                    }
                    return false;
                }
                case SwitchExpression se -> {
                    // each arm starts from the facts after the selector, not from the arm before it; afterwards,
                    // what every completing arm establishes
                    effects(se.selector(), facts);
                    Set<Variable> after = null;
                    for (SwitchEntry entry : se.entries()) {
                        Set<Variable> in = new HashSet<>(facts);
                        effects(entry.whenExpression(), in);
                        Out out = block(entry.statementAsBlock(), in);
                        if (!out.completes) continue;
                        if (after == null) after = new HashSet<>(out.facts);
                        else after.retainAll(out.facts);
                    }
                    if (after != null) facts.retainAll(after);
                    return false;
                }
                case InlineConditional ic -> {
                    effects(ic.condition(), facts);
                    conditionally(ic.ifTrue(), facts, whenTrue(ic.condition()));
                    conditionally(ic.ifFalse(), facts, whenFalse(ic.condition()));
                    return false;
                }
                case BinaryOperator bo when isOperator(bo, "&&") -> {
                    effects(bo.lhs(), facts);
                    conditionally(bo.rhs(), facts, whenTrue(bo.lhs()));
                    return false;
                }
                case BinaryOperator bo when isOperator(bo, "||") -> {
                    effects(bo.lhs(), facts);
                    conditionally(bo.rhs(), facts, whenFalse(bo.lhs()));
                    return false;
                }
                case And and -> {
                    sequence(and.expressions(), facts, true);
                    return false;
                }
                case Or or -> {
                    sequence(or.expressions(), facts, false);
                    return false;
                }
                case Assignment a -> {
                    effects(a.value(), facts);
                    Variable target = a.variableTarget();
                    // 'this.first.id = v' dereferences this.first: only 'this.f' itself has no scope to dereference
                    if (target instanceof FieldReference fr && !fr.scopeIsThis()) {
                        record(a.target(), facts);
                        dereference(fr.scope(), facts);
                    }
                    // a new array: what was known of its elements no longer holds; a new map or key: its presence
                    if (target != null) facts.removeIf(v -> dependsOnAssigned(v, Set.of(target)));
                    if (trackable(target)) {
                        boolean nonNull = a.assignmentOperator() == null && nonNull(a.value(), facts);
                        facts.remove(target);
                        if (nonNull) facts.add(target);
                        if (a.assignmentOperator() == null) narrowedRead(target, a.value(), facts);
                    }
                    return false;
                }
                case MethodCall mc -> {
                    record(mc, facts); // when its evaluation begins: what a smart cast at the receiver sees
                    if (mc.object() != null) effects(mc.object(), facts);
                    List<Expression> arguments = mc.parameterExpressions();
                    for (Expression argument : arguments) effects(argument, facts);
                    if (mc.methodInfo() != null && !mc.methodInfo().isStatic()) dereference(mc.object(), facts);
                    whenCalled.put(mc, Set.copyOf(facts));
                    if (mc.methodInfo() != null) demanded(mc.methodInfo(), arguments, facts);
                    callMade(facts, mc);
                    // 'map.put(k, v)' with v non-null: k is present from here on
                    if (arguments.size() == 2 && nonNull(arguments.get(1), facts)) {
                        Variable present = keyPresence(mc, "put");
                        if (present != null) facts.add(present);
                    }
                    return false;
                }
                case ConstructorCall cc -> {
                    record(cc, facts);
                    for (Expression argument : cc.parameterExpressions()) effects(argument, facts);
                    whenCalled.put(cc, Set.copyOf(facts));
                    if (cc.constructor() != null) demanded(cc.constructor(), cc.parameterExpressions(), facts);
                    callMade(facts, cc);
                    return false;
                }
                case VariableExpression ve -> {
                    if (ve.variable() instanceof FieldReference fr && !fr.fieldInfo().isStatic()
                        && !fr.scopeIsThis()) {
                        effects(fr.scope(), facts);
                        record(ve, facts);
                        dereference(fr.scope(), facts);
                        return false;
                    }
                    if (ve.variable() instanceof DependentVariable dv) {
                        effects(dv.arrayExpression(), facts);
                        effects(dv.indexExpression(), facts);
                        record(ve, facts);
                        dereference(dv.arrayExpression(), facts);
                        return false;
                    }
                    return true;
                }
                default -> {
                    return true;
                }
            }
        });
    }

    // a part evaluated only when the condition holds: walked on a copy, so what it establishes is not kept
    private void conditionally(Expression e, Set<Variable> facts, Set<Variable> condition) {
        Set<Variable> copy = new HashSet<>(facts);
        addCondition(copy, condition);
        effects(e, copy);
    }

    // the evaluated form of '&&' (and=true) or '||': each operand sees the previous ones true (false)
    private void sequence(List<Expression> operands, Set<Variable> facts, boolean and) {
        if (operands.isEmpty()) return;
        effects(operands.getFirst(), facts);
        Set<Variable> copy = new HashSet<>(facts);
        for (int i = 1; i < operands.size(); i++) {
            addCondition(copy, and ? whenTrue(operands.get(i - 1)) : whenFalse(operands.get(i - 1)));
            effects(operands.get(i), copy);
        }
    }

    private void record(Expression e, Set<Variable> facts) {
        atExpression.put(e, Set.copyOf(facts));
    }

    private void dereference(Expression scope, Set<Variable> facts) {
        if (unwrap(scope) instanceof VariableExpression ve && trackable(ve.variable())) facts.add(ve.variable());
    }

    private void demanded(MethodInfo callee, List<Expression> arguments, Set<Variable> facts) {
        if (kotlinSmartCasts) return;
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
