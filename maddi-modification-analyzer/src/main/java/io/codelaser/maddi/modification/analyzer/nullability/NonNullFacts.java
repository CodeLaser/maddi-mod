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

import io.codelaser.maddi.cst.api.expression.*;
import io.codelaser.maddi.cst.api.info.FieldInfo;
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
 * until the next call that may change it (any but an array creation, or a library method not handed {@code this}),
 * and not into a lambda body; a lambda body otherwise starts with the
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
    // per method walked: the variables some path requires non-null where they are not known to be (demands)
    private final Map<MethodInfo, Set<Variable>> demands = new IdentityHashMap<>();
    // per method: the fields of this non-null at every normal exit (nonNullAtExit(MethodInfo, FieldInfo))
    private final Map<MethodInfo, Set<Variable>> fieldsAtExit = new IdentityHashMap<>();
    // CodeLaser/maddi-mod#48 A5: per private instance method, the fields of this non-null at every call of it walked
    // (a call that does not qualify adds none); the calls and method references walked (fieldsAtCalls)
    private final Map<MethodInfo, Set<Variable>> atCalls = new IdentityHashMap<>();
    private final Set<Expression> callsWalked = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    // per private instance method, the fields of this non-null at its entry (withFieldsAtEntry)
    private Map<MethodInfo, Set<Variable>> fieldsAtEntry = Map.of();
    // gate NOENTRYFIELDS (with NullabilityPass's): forget at a compound statement as before #48 A5 (A/B)
    private static final Switches.Gate ENTRY_FIELDS_OFF = Switches.gate("NOENTRYFIELDS");
    // gate NOLOOPBACKEDGES: a loop's start knows only what the loop does not change (A/B for #48 B8)
    private static final Switches.Gate LOOP_BACK_EDGES_OFF = Switches.gate("NOLOOPBACKEDGES");
    // the loops refined at once (nested), and the rounds per loop (assumedAtLoopStart): each round walks the body again
    private static final int MAX_LOOP_DEPTH = 2;
    private static final int MAX_LOOP_ROUNDS = 2;
    private int loopDepth;
    // the facts at each unlabeled 'continue' of the loop whose back edges are collected; null when none is
    private List<Set<Variable>> continues;
    // gate NOCHAINEDKEYS: no constant key, no map reached through pure calls in key presence (A/B for #48 A2)
    private static final Switches.Gate CHAINED_KEYS_OFF = Switches.gate("NOCHAINEDKEYS");
    // gate NOTERNARYFACTS: a ternary's branches without their condition's facts in nonNull (A/B for #48 A3)
    private static final Switches.Gate TERNARY_FACTS_OFF = Switches.gate("NOTERNARYFACTS");
    // the method being walked
    private MethodInfo walking;
    // the facts at each normal exit (a return, the end of the body) of the method being walked; null in a lambda
    private List<Set<Variable>> exits;
    private final Function<ParameterInfo, NullableState> parameterContract;
    private final Function<MethodInfo, NullableState> returnContract;
    private final boolean kotlinSmartCasts;
    // null-check predicates ('StringUtils.isNotBlank(s)'), as conditions; never for Kotlin's smart casts
    private NullPredicates predicates;
    // is the member (a getter, a field) non-null on every instance of the type? (Narrowing, CodeLaser/maddi-mod#22 gap 3)
    private java.util.function.BiPredicate<Object, TypeInfo> narrowedNonNull;
    private java.util.function.BiPredicate<FieldInfo, MethodInfo> narrowedInMethod;
    // the pass's verdict on a value, once its closure is known: no null of the analysis reaches it (gap 7); null
    // while the pass builds its graph
    private java.util.function.Predicate<Expression> notNullable;

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

    /**
     * A final field of {@code this} that no instance able to run the method holds null in:
     * {@code nonNull.test(field, method)} (CodeLaser/maddi-mod#30 3). Not in {@link #kotlinSmartCasts()}.
     */
    public NonNullFacts withNarrowedInMethod(java.util.function.BiPredicate<FieldInfo, MethodInfo> nonNull) {
        this.narrowedInMethod = nonNull;
        return this;
    }

    /**
     * CodeLaser/maddi-mod#22 gap 7: a value the pass's closure leaves unreached counts as non-null where it is assigned,
     * so a local is non-null after 'statement = connection.createStatement()' although its declaration also holds a
     * null ('Statement statement = null;', kept for the finally). Only for the facts the report hands out, after the
     * closure: the pass's own walk must not use its result. Not in {@link #kotlinSmartCasts()}.
     */
    public NonNullFacts withNotNullable(java.util.function.Predicate<Expression> notNullable) {
        this.notNullable = notNullable;
        return this;
    }

    /**
     * The fields of this non-null when a private instance method starts, because they are at every call of it: an
     * earlier round's {@link #fieldsAtCalls}. Not in {@link #kotlinSmartCasts()}: Kotlin does not smart-cast a
     * property.
     */
    public NonNullFacts withFieldsAtEntry(Map<MethodInfo, Set<Variable>> fieldsAtEntry) {
        this.fieldsAtEntry = fieldsAtEntry;
        return this;
    }

    /**
     * Does a call through {@code super} to this method return non-null? Such a call runs the method's own body, not
     * an override's: what null its overrides return does not reach it.
     */
    public NonNullFacts withSuperCalls(java.util.function.Predicate<MethodInfo> superCallNonNull) {
        this.superCallNonNull = superCallNonNull;
        return this;
    }

    // null when not given (withSuperCalls)
    private java.util.function.Predicate<MethodInfo> superCallNonNull;

    /** The same walk, restricted to what Kotlin's smart cast derives (class comment); walk it before use. */
    public NonNullFacts kotlinSmartCasts() {
        return new NonNullFacts(parameterContract, returnContract, true);
    }

    /** Walks one method body; may be called for several methods. */
    public void walk(MethodInfo methodInfo) {
        Block body = methodInfo.methodBody();
        if (body == null) return;
        exits = new ArrayList<>();
        walking = methodInfo;
        Set<Variable> in = new HashSet<>(narrowedThis(methodInfo, body));
        if (!kotlinSmartCasts) in.addAll(fieldsAtEntry.getOrDefault(methodInfo, Set.of()));
        Out out = block(body, in);
        walking = null;
        if (out.completes) exits.add(out.facts);
        if (!exits.isEmpty()) {
            Set<Variable> common = new HashSet<>(exits.getFirst());
            exits.forEach(common::retainAll);
            Set<Variable> fields = new HashSet<>();
            for (Variable v : common) {
                if (v instanceof FieldReference fr && fr.scopeIsThis() && !fr.fieldInfo().isStatic()) fields.add(v);
            }
            if (!fields.isEmpty()) fieldsAtExit.put(methodInfo, Set.copyOf(fields));
            common.removeAll(assignedIn(body));
            atExit.put(methodInfo, Set.copyOf(common));
        }
        exits = null;
    }

    /*
     The final fields of this that no instance of the method's class can hold null in (NullabilityPass.NarrowedReads,
     with this's own class as the narrowing type): a null written only by a constructor this class never reaches.
     timefold BavetAbstractConstraintStream: the source-stream constructor sets 'parent = null', the subclasses all
     chain to the one that dereferences and stores its argument, and dereference 'parent' (CodeLaser/maddi-mod#27 2).
     Not in a constructor, which may run before the field is assigned; not for Kotlin's smart casts.
     */
    private Set<Variable> narrowedThis(MethodInfo methodInfo, Block body) {
        if (kotlinSmartCasts || narrowedNonNull == null || methodInfo.isConstructor() || methodInfo.isStatic()) {
            return Set.of();
        }
        TypeInfo type = methodInfo.typeInfo();
        Set<Variable> set = new HashSet<>();
        body.visit(e -> {
            if (e instanceof VariableExpression ve && ve.variable() instanceof FieldReference fr && fr.scopeIsThis()
                && fr.fieldInfo().isFinal() && !fr.fieldInfo().isStatic() && !set.contains(fr)
                // this is an instance of the method's class, unless the method is a lambda's (its this is the
                // enclosing instance): the field must be one of that class
                && (fr.fieldInfo().owner() == type || type.superTypesExcludingJavaLangObject().contains(fr.fieldInfo().owner()))
                && (narrowedNonNull.test(fr.fieldInfo(), type)
                    || narrowedInMethod != null && narrowedInMethod.test(fr.fieldInfo(), methodInfo))) {
                set.add(fr);
            }
            return true;
        });
        return set;
    }

    /**
     * Is {@code field} of {@code this} non-null whenever {@code methodInfo} returns normally? Then a call to it on
     * {@code this} leaves the field non-null ({@code ensureBufferSize(n); buffer[pos++] = b}). Not in
     * {@link #kotlinSmartCasts()}: Kotlin does not smart-cast a property across a call.
     */
    public boolean nonNullAtExit(MethodInfo methodInfo, FieldInfo field) {
        return fieldsAtExit.getOrDefault(methodInfo, Set.of()).stream()
                .anyMatch(v -> v instanceof FieldReference fr && fr.fieldInfo().equals(field));
    }

    /**
     * Is {@code parameter} non-null whenever its method returns normally, without ever being assigned? Then a null
     * argument throws before the method completes (a dereference, {@code requireNonNull}, an explicit
     * {@code if (p == null) throw}): a precondition. False for a method that never completes normally.
     */
    public boolean nonNullAtExit(MethodInfo methodInfo, ParameterInfo parameter) {
        return atExit.getOrDefault(methodInfo, Set.of()).contains(parameter);
    }

    /**
     * The locals and parameters that some path of {@code methodInfo}'s body (its lambdas included) requires to be
     * non-null where they are not known to be: dereferenced, passed to a parameter that demands non-null, or rejected
     * ({@code if (v == null) throw ...}). A parameter that is not in here tolerates null in this body
     * (CodeLaser/maddi-mod#68). Not in {@link #kotlinSmartCasts()}.
     */
    public Set<Variable> demands(MethodInfo methodInfo) {
        return demands.getOrDefault(methodInfo, Set.of());
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
            forget(stableIn, statement);
            Set<Variable> assumed = assumedAtLoopStart(statement, in, stableIn);
            stableIn.addAll(assumed);
            before.put(statement, Set.copyOf(stableIn));
            Out out = loop(statement, stableIn, null);
            if (assumed.isEmpty()) return out;
            // a break leaves the loop with what its path has: not what the loop's start assumed
            Set<Variable> after = new HashSet<>(out.facts);
            after.removeAll(assumed);
            after.removeIf(v -> dependsOnAssigned(v, assumed));
            return new Out(after, true);
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
                    navigatedKey(lv, lv.assignmentExpression(), facts);
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
            case BreakOrContinueStatement bc -> {
                if (continues != null && bc instanceof ContinueStatement && bc.goToLabel() == null) {
                    continues.add(Set.copyOf(facts));
                }
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
                // 'if (v == null) throw ...': a rejection of null, on this path
                if (endsInThrow(ifElse.block())) whenFalse(condition).forEach(v -> demand(v, facts));
                if (endsInThrow(elseBlock)) whenTrue(condition).forEach(v -> demand(v, facts));
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
            case io.codelaser.maddi.cst.api.statement.ExplicitConstructorInvocation eci -> {
                // 'super(parent.getRetrievalSemantics())' dereferences parent before the body runs (timefold
                // BavetAbstractConstraintStream: its parameter is non-null whenever the constructor completes)
                // Not for Kotlin's smart casts: the supertype call's arguments are in the class header, and an init
                // block does not smart-cast from them. guava FinalizableWeakReference: 'super(referent, queue.queue);
                // queue.cleanUp();' printed 'init { queue.cleanUp() }' on a 'queue: FinalizableReferenceQueue?'
                // (FinalizableWeakReference.kt:9, 2026-10-17)
                Set<Variable> target = kotlinSmartCasts ? new HashSet<>(facts) : facts;
                for (Expression argument : eci.parameterExpressions()) effects(argument, target);
                return new Out(facts, true);
            }
            case Block b -> {
                return block(b, facts);
            }
            case SynchronizedStatement ss -> {
                effects(ss.expression(), facts);
                demandVariable(ss.expression(), facts); // locks on it: a demand only, as a for-each's iterable
                if (kotlinSmartCasts) {
                    // Kotlin's synchronized(lock) { } is a lambda: a check inside smart-casts nothing after it, and
                    // a variable it assigns is no longer known. guava ListenerCallQueue.PerListenerQueue.run():
                    // 'synchronized (...) { nextToRun = waitQueue.poll(); if (nextToRun == null) { ...; break; } }
                    // nextToRun.call(listener)' printed a plain receiver on an 'Event<L>?' (ListenerCallQueue.kt:93,
                    // 2026-10-17)
                    Out inside = block(ss.block(), new HashSet<>(facts));
                    Set<Variable> after = new HashSet<>(facts);
                    after.removeAll(assignedIn(ss.block()));
                    return new Out(after, inside.completes());
                }
                return block(ss.block(), facts);
            }
            case TryStatement ts -> {
                // the body runs first, with the facts at the try less what its resources assign; a catch can start
                // anywhere in the body, the finally anywhere at all. What follows keeps, as for any compound
                // statement, the facts of variables none of it assigns. timefold ReflectionHelper: 'while (c !=
                // null) { try { return c.getDeclaredField(n); } catch (...) { c = c.getSuperclass(); } }' lost the
                // loop's 'c != null' at the body's start, because the catch assigns c.
                Set<Variable> bodyIn = new HashSet<>(facts);
                Set<Variable> catchIn = new HashSet<>(facts);
                List<Statement> started = new ArrayList<>(ts.resources());
                for (Statement resource : ts.resources()) forget(bodyIn, resource);
                started.add(ts.block());
                for (Statement part : started) forget(catchIn, part);
                Set<Variable> stable = new HashSet<>(facts);
                forget(stable, statement);
                block(ts.block(), bodyIn);
                for (TryStatement.CatchClause cc : ts.catchClauses()) block(cc.block(), new HashSet<>(catchIn));
                if (ts.finallyBlock() != null && !ts.finallyBlock().isEmpty()) {
                    block(ts.finallyBlock(), new HashSet<>(stable));
                }
                return new Out(stable, true);
            }
            default -> {
                // do-while, try, switch, local type declarations, ...: the selector is evaluated first; each
                // sub-block starts with the facts of variables the statement does not assign; afterwards the same
                if (statement.expression() != null && !(statement instanceof DoStatement)) {
                    effects(statement.expression(), facts);
                }
                Set<Variable> stable = new HashSet<>(facts);
                forget(stable, statement);
                Set<Variable> bodyIn = new HashSet<>(stable);
                if (statement instanceof DoStatement) bodyIn.addAll(assumedAtLoopStart(statement, facts, stable));
                statement.subBlockStream().forEach(sb -> {
                    if (statement instanceof SwitchStatementOldStyle) {
                        // every case label is a jump target: no statement inherits the facts of the one before it
                        for (Statement s : sb.statements()) statement(s, new HashSet<>(stable));
                    } else {
                        block(sb, new HashSet<>(bodyIn));
                    }
                });
                return new Out(stable, true);
            }
        }
    }

    /*
     A while/for/for-each loop, with the facts that hold at every evaluation of its condition. backEdges, when not
     null, collects the facts at each way back to the start: the end of the body, an unlabeled continue.
     */
    private Out loop(Statement statement, Set<Variable> in, List<Set<Variable>> backEdges) {
        Set<Variable> facts = new HashSet<>(in);
        if (statement.expression() != null) effects(statement.expression(), facts);
        // 'for (x : v)' iterates v: a demand only, the facts stay as they were
        if (statement instanceof ForEachStatement fe) demandVariable(fe.expression(), facts);
        // a for's updaters run on every way back to the condition: after the body completes, and after each of its
        // continues. Evaluated with what every way back has, so that a call in them sees the condition's facts: in
        // 'for (T x = arg; x != null; x = mappings.remove(key(x)))' key's parameter takes no null from x (guava
        // TypeResolver, once the init clause and the updaters seeded x; CodeLaser/maddi-mod#72). Gate NOFORUPDATERFACTS.
        List<Expression> updaters = statement instanceof ForStatement fs && !FOR_UPDATER_FACTS_OFF.get()
                ? fs.updaters() : List.of();
        List<Set<Variable>> waysBack = backEdges != null ? backEdges : updaters.isEmpty() ? null : new ArrayList<>();
        List<Set<Variable>> enclosing = continues;
        continues = waysBack;
        statement.subBlockStream().forEach(sb -> {
            Set<Variable> blockIn = new HashSet<>(facts);
            if (statement.expression() != null) addCondition(blockIn, whenTrue(statement.expression()));
            if (statement instanceof ForEachStatement fe) {
                Variable present = keySetLoop(fe);
                if (present != null) blockIn.add(present);
            }
            Out out = block(sb, blockIn);
            if (waysBack != null && out.completes) waysBack.add(Set.copyOf(out.facts));
        });
        continues = enclosing;
        if (!updaters.isEmpty() && waysBack != null && !waysBack.isEmpty()) {
            Set<Variable> atUpdaters = null;
            for (Set<Variable> back : waysBack) {
                if (atUpdaters == null) atUpdaters = new HashSet<>(back);
                else atUpdaters.retainAll(back);
            }
            for (Expression updater : updaters) effects(updater, atUpdaters);
        }
        // the condition is evaluated on every way out (but the last evaluation's 'false' is not known here)
        return new Out(facts, true);
    }

    // gate NOFORUPDATERFACTS: a for's updaters are not evaluated; a call in them sees the loop's entry facts (A/B)
    private static final Switches.Gate FOR_UPDATER_FACTS_OFF = Switches.gate("NOFORUPDATERFACTS");

    /*
     CodeLaser/maddi-mod#48 B8, nacos RequestHandlerRegistry: 'while (!clazz.getSuperclass().equals(H.class)) { if
     (clazz.getSuperclass().equals(Object.class)) { ...; break; } clazz = clazz.getSuperclass(); }'. What holds when
     the loop is entered and at every way back to its start holds at every start, though the loop changes it: here
     clazz, assigned the pure call its condition dereferenced (gap 8). The facts the loop's changes drop (in less
     stable) are assumed, the body walked, and kept only where every way back has them, until that is stable; the
     caller then walks the body once more with them. Not for Kotlin's smart casts. Not in a loop with a labeled
     continue or a finally (a way back the walk does not see), nor for what a for's updaters or a do's condition,
     which the walk does not evaluate, assign; when they call, only for a local or a parameter. At most
     MAX_LOOP_ROUNDS rounds, and MAX_LOOP_DEPTH nested loops: each round walks the body again.
     */
    private Set<Variable> assumedAtLoopStart(Statement statement, Set<Variable> in, Set<Variable> stable) {
        if (kotlinSmartCasts || LOOP_BACK_EDGES_OFF.get() || loopDepth >= MAX_LOOP_DEPTH) return Set.of();
        Set<Variable> changed = new HashSet<>(in);
        changed.removeAll(stable);
        if (changed.isEmpty() || !refinable(statement)) return Set.of();
        List<Expression> tail = statement instanceof ForStatement fs ? fs.updaters()
                : statement instanceof DoStatement && statement.expression() != null ? List.of(statement.expression())
                : List.of();
        for (Expression e : tail) {
            e.visit(x -> {
                if (x instanceof Assignment a && a.variableTarget() != null) changed.remove(a.variableTarget());
                if (x instanceof MethodCall || x instanceof ConstructorCall) {
                    changed.removeIf(v -> !(v instanceof LocalVariable || v instanceof ParameterInfo));
                }
                return true;
            });
        }
        Set<Variable> candidates = changed;
        loopDepth++;
        try {
            for (int round = 0; round < MAX_LOOP_ROUNDS && !candidates.isEmpty(); round++) {
                Set<Variable> head = new HashSet<>(stable);
                head.addAll(candidates);
                List<Set<Variable>> backEdges = new ArrayList<>();
                if (statement instanceof DoStatement) {
                    List<Set<Variable>> enclosing = continues;
                    continues = backEdges;
                    Out out = block(statement.block(), head);
                    if (out.completes) backEdges.add(Set.copyOf(out.facts));
                    continues = enclosing;
                } else {
                    loop(statement, head, backEdges);
                }
                Set<Variable> next = new HashSet<>(candidates);
                for (Set<Variable> back : backEdges) next.retainAll(back);
                if (next.equals(candidates)) return Set.copyOf(candidates);
                candidates = next;
            }
            return Set.of();
        } finally {
            loopDepth--;
        }
    }

    // a loop whose ways back to its start are the end of its body and its unlabeled continues
    private static boolean refinable(Statement statement) {
        boolean[] other = {false};
        statement.visit(e -> {
            if (e instanceof ContinueStatement cs && cs.goToLabel() != null
                || e instanceof TryStatement ts && ts.finallyBlock() != null && !ts.finallyBlock().isEmpty()) {
                other[0] = true;
            }
            return !other[0];
        });
        return !other[0];
    }

    /*
     The facts that no longer hold once (part of) the statement may have run: a variable it may assign null, what
     depends on a variable it assigns, a field at a call that may change it. Outside Kotlin's smart casts, which drop
     whatever the statement assigns and every non-final field at any call, a variable it assigns only values non-null
     by themselves stays, and so do the fields of this when no call reaches this (callMade). CodeLaser/maddi-mod#48 A5,
     nacos CredentialWatcher: 'do { try { is = new FileInputStream(propertyPath); } catch (...) { if
     (propertyPath.equals(...)) { propertyPath = CREDENTIAL_PATH + DEFAULT; continue; } ... } break; } while (true);'
     */
    private void forget(Set<Variable> facts, Statement statement) {
        Set<Variable> assigned = assignedIn(statement);
        if (!kotlinSmartCasts && facts.stream().anyMatch(v -> v instanceof KeyPresence)) {
            List<Expression> calls = new ArrayList<>();
            statement.visit(e -> {
                if (e instanceof MethodCall || e instanceof ConstructorCall) calls.add((Expression) e);
                return true;
            });
            facts.removeIf(v -> v instanceof KeyPresence kp && calls.stream().anyMatch(c -> keyPresenceLost(kp, c)));
        }
        boolean coarse = kotlinSmartCasts || ENTRY_FIELDS_OFF.get();
        facts.removeAll(coarse ? assigned : mayAssignNull(statement));
        facts.removeIf(v -> dependsOnAssigned(v, assigned));
        if (coarse) {
            if (containsCall(statement)) facts.removeIf(NonNullFacts::isNonFinalField);
            return;
        }
        boolean[] call = {false};
        boolean[] reaches = {false};
        statement.visit(e -> {
            if (e instanceof MethodCall || e instanceof ConstructorCall) {
                call[0] = true;
                if (reachesThis((Expression) e)) reaches[0] = true;
            }
            return !reaches[0];
        });
        if (call[0]) {
            facts.removeIf(v -> isNonFinalField(v)
                                && (reaches[0] || !(v instanceof FieldReference fr && fr.scopeIsThis())));
        }
    }

    // the variables the statement declares, or assigns a value that is not non-null by itself
    private Set<Variable> mayAssignNull(Statement statement) {
        Set<Variable> set = new HashSet<>();
        statement.visit(e -> {
            if (e instanceof Assignment a && trackable(a.variableTarget())
                && (a.assignmentOperator() != null || !nonNull(a.value(), Set.of()))) {
                set.add(a.variableTarget());
            }
            if (e instanceof LocalVariableCreation lvc) lvc.localVariableStream().forEach(set::add);
            return true;
        });
        return set;
    }

    private static boolean changesReachedMap(Statement statement) {
        boolean[] found = {false};
        statement.visit(e -> {
            if (e instanceof Expression x && changesReachedMap(x)) found[0] = true;
            return !found[0];
        });
        return found[0];
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

    // locals, parameters, and the instance fields of 'this' (Kotlin smart casts: only the final ones). Kotlin also
    // smart-casts a val property of a stable receiver: 'cl.superClass != null && cl.superClass.name' with 'cl' a
    // parameter or a local and 'superClass' a final field of a source type (fernflower; a property of this module,
    // no custom getter). Forgotten when the receiver is assigned (dependsOnAssigned)
    private boolean trackable(Variable v) {
        return v instanceof ParameterInfo
               || v instanceof LocalVariable lv && !lv.simpleName().startsWith("$")
               || v instanceof FieldReference fr && fr.scopeIsRecursivelyThis() && !fr.fieldInfo().isStatic()
                  && (!kotlinSmartCasts || fr.fieldInfo().isFinal())
               || kotlinSmartCasts && stableReceiverProperty(v)
               || !kotlinSmartCasts && isConstantElement(v);
    }

    private static boolean stableReceiverProperty(Variable v) {
        return v instanceof FieldReference fr && !fr.fieldInfo().isStatic() && fr.fieldInfo().isFinal()
               && !fr.fieldInfo().owner().compilationUnit().externalLibrary()
               && fr.scopeVariable() != null
               && (fr.scopeVariable() instanceof ParameterInfo
                   || fr.scopeVariable() instanceof LocalVariable lv && !lv.simpleName().startsWith("$"));
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

    // a non-final field the call may assign: any, except one of this when the call cannot reach this
    private static boolean changesAtCall(Variable v, boolean keepsThis) {
        return isNonFinalField(v) && !(keepsThis && v instanceof FieldReference fr && fr.scopeIsThis());
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
            "size", "isEmpty", "keySet", "values", "entrySet", "forEach", "equals", "hashCode", "toString",
            "firstKey", "lastKey", "higherKey", "lowerKey", "ceilingKey", "floorKey", "navigableKeySet",
            "descendingKeySet");

    /*
     CodeLaser/maddi-mod#29 C: a key a navigable map hands out is one of its keys ('k = map.higherKey(k)', timefold
     TreeMultiSet). When there is none it is null instead, and 'map.get(null)' throws on a TreeMap rather than
     answering the absent key's null; a comparator that accepts null is the exception, and not looked at.
     */
    // gate NONAVKEYS: no key presence from navigation, nor forgetting it at a call through a non-final field (#29 C off)
    private static final Switches.Gate NAV_KEYS_OFF = Switches.gate("NONAVKEYS");
    private static final Set<String> MAP_KEYS = Set.of("firstKey", "lastKey", "higherKey", "lowerKey", "ceilingKey",
            "floorKey");
    // "entrySet": the loop's entry stands for its key, as 'map.get(e.getKey())' resolves (keyPresence)
    private static final Set<String> KEY_VIEWS = Set.of("keySet", "navigableKeySet", "descendingKeySet", "entrySet");

    /** Is the key of {@code lookup} ({@code map.get(k)}, {@code map.remove(k)}) known present when it is called? */
    public boolean keyPresentWhenCalled(MethodCall lookup) {
        Variable annotated = annotationPresence(lookup, "getAnnotation");
        if (annotated != null) return whenCalled.getOrDefault(lookup, Set.of()).contains(annotated);
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
        Variable map = mapVariable(mc.object());
        Variable key = keyVariable(mc.parameterExpressions().getFirst());
        if (map == null || key == null || !isMap(mc.object().parameterizedType())) return null;
        return keyPresence(map, key, mc.object().parameterizedType());
    }

    /*
     The map of a key-presence fact: a tracked variable, or a chain of pure calls on one ('m.getData()',
     'metadata.get().getExtendData()', CodeLaser/maddi-mod#48 A2, nacos InstanceOperatorClientImpl). Such a map is
     reached through objects a call may change: its presence is forgotten at any call that is neither pure nor a read
     (callMade), and at a compound statement holding one (forget).
     */
    private Variable mapVariable(Expression e) {
        Variable v = trackableVariable(e);
        if (v != null || CHAINED_KEYS_OFF.get() || !(unwrap(e) instanceof MethodCall mc)) return v;
        MethodInfo mi = mc.methodInfo();
        if (mi == null || mi.isStatic() || !mc.parameterExpressions().isEmpty() || mc.object() == null
            || !(mi.isNonModifying() || libraryGetter(mi)) || mi.returnType().isPrimitiveExcludingVoid()) return null;
        Variable receiver = mapVariable(mc.object());
        return receiver == null ? null : new PureCall(receiver, mi, mi.returnType());
    }

    // a call that may change a map reached through pure calls: not itself pure, not a map read
    private static boolean changesReachedMap(Expression call) {
        if (call instanceof ConstructorCall cc) return cc.constructor() != null || cc.anonymousClass() != null;
        if (!(call instanceof MethodCall mc)) return false;
        MethodInfo mi = mc.methodInfo();
        return mi == null || !MAP_READS.contains(mi.name()) && !mi.isNonModifying() && !libraryGetter(mi);
    }

    /*
     The variable that stands for a key: the key itself, or for 'e.getKey()' the entry e (a loop over the map's
     entrySet makes map[e] present, CodeLaser/maddi-mod#48 A4, nacos IstioCrdUtil). So an entry used as a key itself
     ('map.get(e)') has no presence of its own: it would claim e's key.
     */
    private Variable keyVariable(Expression key) {
        if (unwrap(key) instanceof MethodCall gk && gk.methodInfo() != null && "getKey".equals(gk.methodInfo().name())
            && gk.parameterExpressions().isEmpty() && gk.object() != null && isEntry(gk.object().parameterizedType())) {
            return trackableVariable(gk.object());
        }
        // a constant: 'containsKey(HEART_BEAT_INTERVAL)' (#48 A2)
        if (!CHAINED_KEYS_OFF.get() && unwrap(key) instanceof VariableExpression ve
            && ve.variable() instanceof FieldReference fr && fr.fieldInfo().isStatic() && fr.fieldInfo().isFinal()) {
            return fr;
        }
        Variable v = trackableVariable(key);
        return v == null || isEntry(v.parameterizedType()) ? null : v;
    }

    private static boolean isEntry(ParameterizedType pt) {
        TypeInfo ti = pt == null || pt.arrays() > 0 ? null : pt.typeInfo();
        if (ti == null) return false;
        return "java.util.Map.Entry".equals(ti.fullyQualifiedName())
               || ti.superTypesExcludingJavaLangObject().stream()
                       .anyMatch(t -> "java.util.Map.Entry".equals(t.fullyQualifiedName()));
    }

    /*
     A method of this class that leaves its key parameter present in a field map at every exit ('void ensure(String k)
     { if (!cache.containsKey(k)) cache.put(k, new X()); }'): after 'ensure(k)' on this, the argument is present in that
     map (nacos' initConfigGrayIfEmpty). Needs the callee walked first, as the analysis order has it.
     */
    private void presentAtCalleeExit(MethodCall mc, Set<Variable> facts) {
        MethodInfo callee = mc.methodInfo();
        if (kotlinSmartCasts || callee == null || callee.isStatic()
            || mc.object() != null && !(unwrap(mc.object()) instanceof VariableExpression ve
                                        && ve.variable() instanceof io.codelaser.maddi.cst.api.variable.This)) return;
        for (Variable v : atExit.getOrDefault(callee, Set.of())) {
            if (v instanceof KeyPresence kp && kp.map() instanceof FieldReference fr && fr.scopeIsRecursivelyThis()
                && kp.key() instanceof ParameterInfo p && p.methodInfo() == callee
                && p.index() < mc.parameterExpressions().size()) {
                Variable key = trackableVariable(mc.parameterExpressions().get(p.index()));
                if (key != null) facts.add(new KeyPresence(kp.map(), key, kp.valueType()));
            }
        }
    }

    /*
     A function that never returns null: a constructor reference ('X::new'), or a lambda whose single result is non-null
     by itself ('k -> new X()'). For computeIfAbsent's mapping function and ThreadLocal.withInitial's supplier.
     */
    static boolean nonNullFunction(Expression e) {
        Expression f = unwrap(e);
        if (f instanceof MethodReference mr) return mr.methodInfo() != null && mr.methodInfo().isConstructor();
        if (f instanceof Lambda lambda && lambda.methodBody() != null
            && lambda.methodBody().statements().size() == 1
            && lambda.methodBody().statements().getFirst() instanceof ReturnStatement rs) {
            Expression r = unwrap(rs.expression());
            return r instanceof ConstructorCall || r instanceof StringConstant || r instanceof StringConcat
                   || r instanceof ArrayInitializer;
        }
        return false;
    }

    /** Gap 1: {@code map.computeIfAbsent(k, f)} with f never returning null does not return null. */
    public static boolean computedNonNull(MethodCall mc) {
        return mc.methodInfo() != null && "computeIfAbsent".equals(mc.methodInfo().name())
               && mc.parameterExpressions().size() == 2 && nonNullFunction(mc.parameterExpressions().get(1));
    }

    /**
     * Gap 1: {@code TL.get()} on a final field initialized with {@code ThreadLocal.withInitial(s)}, s never returning
     * null (nacos). Not when anything calls {@code set}/{@code remove} on it: that is the field's analysis, not this.
     */
    public static boolean initialThreadLocal(MethodCall mc) {
        if (mc.methodInfo() == null || !"get".equals(mc.methodInfo().name()) || !mc.parameterExpressions().isEmpty()
            || !(unwrap(mc.object()) instanceof VariableExpression ve) || !(ve.variable() instanceof FieldReference fr)
            || !fr.fieldInfo().isFinal()) return false;
        Expression init = fr.fieldInfo().initializer();
        return unwrap(init) instanceof MethodCall wi && wi.methodInfo() != null
               && "withInitial".equals(wi.methodInfo().name())
               && "java.lang.ThreadLocal".equals(wi.methodInfo().typeInfo().fullyQualifiedName())
               && wi.parameterExpressions().size() == 1 && nonNullFunction(wi.parameterExpressions().getFirst());
    }

    // 'for (K k : map.keySet())' (or the navigable views): k is present in map throughout the body
    private Variable keySetLoop(ForEachStatement fe) {
        if (kotlinSmartCasts || !(unwrap(fe.expression()) instanceof MethodCall mc) || mc.methodInfo() == null
            || !KEY_VIEWS.contains(mc.methodInfo().name()) || mc.object() == null) return null;
        Variable map = trackableVariable(mc.object());
        LocalVariable k = fe.initializer().localVariable();
        if (map == null || k == null || !isMap(mc.object().parameterizedType())) return null;
        // an entry stands for its key (keyVariable): only the entrySet's entry, not a key that is an entry
        if (isEntry(k.parameterizedType()) != "entrySet".equals(mc.methodInfo().name())) return null;
        return keyPresence(map, k, mc.object().parameterizedType());
    }

    // 'k = map.higherKey(x)', 'k = map.isEmpty() ? null : map.firstKey()': k is present in map (MAP_KEYS)
    /*
     'element.isAnnotationPresent(X.class)' / 'element.getAnnotation(X.class)' on a parameter or local of a type that is
     a java.lang.reflect.AnnotatedElement (Method, Class, Field, ...): the presence fact, or null. A field as the element
     is left out: a call may assign it, and nothing forgets the fact then (diagnose, nacos 2026-10-13)
     */
    private Variable annotationPresence(MethodCall mc, String name) {
        if (kotlinSmartCasts || mc.methodInfo() == null || mc.object() == null || mc.parameterExpressions().size() != 1
            || !name.equals(mc.methodInfo().name())) return null;
        if (!(unwrap(mc.object()) instanceof VariableExpression ve)
            || !(ve.variable() instanceof ParameterInfo || ve.variable() instanceof LocalVariable)
            || !trackable(ve.variable())
            || !(unwrap(mc.parameterExpressions().getFirst()) instanceof ClassExpression ce)
            || ce.type() == null || ce.type().typeInfo() == null
            || !isAnnotatedElement(mc.methodInfo().typeInfo())) return null;
        return new AnnotationPresence(ve.variable(), ce.type().typeInfo().fullyQualifiedName());
    }

    private static boolean isAnnotatedElement(TypeInfo ti) {
        return ti != null && ("java.lang.reflect.AnnotatedElement".equals(ti.fullyQualifiedName())
                              || ti.superTypesExcludingJavaLangObject().stream()
                                      .anyMatch(t -> "java.lang.reflect.AnnotatedElement".equals(t.fullyQualifiedName())));
    }

    private void navigatedKey(Variable key, Expression value, Set<Variable> facts) {
        if (kotlinSmartCasts || key == null || NAV_KEYS_OFF.get()) return;
        Expression v = unwrap(value);
        if (v instanceof InlineConditional ic) {
            if (unwrap(ic.ifTrue()) instanceof NullConstant) v = unwrap(ic.ifFalse());
            else if (unwrap(ic.ifFalse()) instanceof NullConstant) v = unwrap(ic.ifTrue());
        }
        if (!(v instanceof MethodCall mc) || mc.methodInfo() == null || mc.object() == null
            || !MAP_KEYS.contains(mc.methodInfo().name())) return;
        Variable map = trackableVariable(mc.object());
        if (map == null || !isMap(mc.object().parameterizedType())) return;
        facts.add(keyPresence(map, key, mc.object().parameterizedType()));
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
        return v instanceof FieldReference fr && fr.scopeVariable() != null && !fr.scopeIsRecursivelyThis()
               && assigned.contains(fr.scopeVariable())
               || v instanceof DependentVariable dv
               && (dv.arrayVariable() != null && assigned.contains(dv.arrayVariable())
                   || dv.indexVariable() != null && assigned.contains(dv.indexVariable()))
               || v instanceof KeyPresence kp && (assigned.contains(kp.map()) || assigned.contains(kp.key())
                                                  || kp.map() instanceof PureCall pc && assigned.contains(pc.root()))
               || v instanceof AnnotationPresence ap && assigned.contains(ap.element())
               || v instanceof PureCall pc && assigned.contains(pc.receiver())
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
        // a pure call's result stays across calls that cannot change its receiver: a non-modifying one (the repeated
        // call itself), or one that neither runs on the receiver nor is handed it, unless the receiver is a field
        boolean modifying = call instanceof MethodCall mc ? mc.methodInfo() == null || mc.methodInfo().isModifying()
                : call instanceof ConstructorCall;
        boolean keepsThis = !reachesThis(call);
        MethodInfo callee = callee(call);
        facts.removeIf(v -> isNonFinalField(v) && !(keepsThis && v instanceof FieldReference fr && fr.scopeIsThis())
                            || v instanceof DependentVariable
                            || v instanceof KeyPresence kp && keyPresenceLost(kp, call)
                            || v instanceof PureCall pc && modifying
                               && (isNonFinalField(pc.receiver())
                                   || touches(pc.receiver(), receiver, false, arguments, callee)));
    }

    private static MethodInfo callee(Expression call) {
        return call instanceof MethodCall mc ? mc.methodInfo() : call instanceof ConstructorCall cc ? cc.constructor() : null;
    }

    // may the call remove the key: one on the map itself other than a read, or one handed the map (callMade)
    private static boolean keyPresenceLost(KeyPresence kp, Expression call) {
        if (kp.map() instanceof PureCall) return changesReachedMap(call);
        Variable receiver = call instanceof MethodCall mc && mc.object() != null
                            && unwrap(mc.object()) instanceof VariableExpression ve ? ve.variable() : null;
        boolean read = call instanceof MethodCall mc && mc.methodInfo() != null
                       && MAP_READS.contains(mc.methodInfo().name());
        List<Expression> arguments = call instanceof MethodCall mc ? mc.parameterExpressions()
                : call instanceof ConstructorCall cc ? cc.parameterExpressions() : List.of();
        boolean keepsThis = !reachesThis(call);
        return touches(kp.map(), receiver, read, arguments, callee(call))
               || !NAV_KEYS_OFF.get() && (changesAtCall(kp.key(), keepsThis) || changesAtCall(kp.map(), keepsThis));
    }

    /*
     A lazy-initialising helper of this class ('ensureBufferSize(n)', 'ensureOpen()'): the fields of this it leaves
     non-null on every normal exit are non-null after the call (nacos Base64.encode: 'ensureBufferSize(encodeSize);
     buffer[pos++] = ...'). Only on this, for a method no override can replace (private, final, or in a final
     class), of the class being walked, and already walked: callees come first in the analysis order, a recursive
     call adds nothing.
     */
    private void fieldsEnsured(MethodCall mc, Set<Variable> facts) {
        MethodInfo callee = mc.methodInfo();
        if (kotlinSmartCasts || callee == null || callee.isStatic() || walking == null
            || !callee.typeInfo().equals(walking.typeInfo())) return;
        if (mc.object() != null && !(unwrap(mc.object()) instanceof VariableExpression ve
                                     && ve.variable() instanceof io.codelaser.maddi.cst.api.variable.This)) return;
        if (!callee.access().isPrivate() && !callee.isFinal() && !callee.typeInfo().isFinal()) return;
        facts.addAll(fieldsAtExit.getOrDefault(callee, Set.of()));
    }

    /*
     A call of a private instance method of this class, on this, with the facts when it is made: the fields of this
     among them stay candidates for the method's entry (atCalls). Any other use, a method reference or a call from
     another class (an anonymous one, a nested one), leaves none.
     */
    private void callOfPrivate(Expression site, MethodInfo callee, Set<Variable> facts) {
        if (kotlinSmartCasts || callee == null || !privateInstanceMethod(callee)) return;
        callsWalked.add(site);
        Set<Variable> fields = new HashSet<>();
        if (facts != null && walking != null && callee.typeInfo().equals(walking.typeInfo())
            && site instanceof MethodCall mc
            && (mc.object() == null || unwrap(mc.object()) instanceof VariableExpression ve
                                       && ve.variable() instanceof io.codelaser.maddi.cst.api.variable.This)) {
            for (Variable v : facts) {
                if (v instanceof FieldReference fr && fr.scopeIsThis() && !fr.fieldInfo().isStatic()) fields.add(v);
            }
        }
        Set<Variable> known = atCalls.get(callee);
        if (known == null) atCalls.put(callee, fields);
        else known.retainAll(fields);
    }

    static boolean privateInstanceMethod(MethodInfo mi) {
        return mi.access().isPrivate() && !mi.isStatic() && !mi.isConstructor() && mi.methodBody() != null;
    }

    /**
     * Per private instance method, the fields of this non-null at every call of it walked, all on this from its own
     * class (CodeLaser/maddi-mod#48 A5); empty when a call or a method reference does not qualify. Complete only when
     * every use of the method was walked: see {@link #walked}.
     */
    public Map<MethodInfo, Set<Variable>> fieldsAtCalls() {
        return atCalls;
    }

    /** Was the call or method reference walked, as an expression of a method body? */
    public boolean walked(Expression callOrReference) {
        return callsWalked.contains(callOrReference);
    }

    /*
     Can the call change a field of this? An array creation runs no code. A library method reaches this only through
     a reference handed to it: as the receiver, as an argument, or captured by a lambda, a method reference or an
     anonymous class passed along (a listener stored and called back later is the one way around this, accepted).
     nacos Base64: 'if (buffer != null) { int len = Math.min(...); System.arraycopy(buffer, ...); }' and
     'byte[] b = new byte[buffer.length * 2]' kept 'buffer' non-null only until the call. An analysed method may.
     */
    private static boolean reachesThis(Expression call) {
        List<Expression> handed = new ArrayList<>();
        switch (call) {
            case ConstructorCall cc -> {
                if (cc.constructor() == null) return cc.anonymousClass() != null; // 'new byte[n]', '{ a, b }'
                if (cc.anonymousClass() != null || !library(cc.constructor())) return true;
                handed.addAll(cc.parameterExpressions());
            }
            case MethodCall mc -> {
                if (mc.methodInfo() == null || !library(mc.methodInfo())) return true;
                if (mc.object() != null) handed.add(mc.object());
                handed.addAll(mc.parameterExpressions());
            }
            default -> {
                return true;
            }
        }
        boolean[] found = {false};
        for (Expression e : handed) {
            e.visit(x -> {
                if (found[0]) return false;
                if (x instanceof Lambda || x instanceof MethodReference
                    || x instanceof ConstructorCall cc && cc.anonymousClass() != null
                    || x instanceof VariableExpression ve && ve.variable() instanceof io.codelaser.maddi.cst.api.variable.This) {
                    found[0] = true;
                    return false;
                }
                // 'this.pos', 'this.items[i]': the value read is handed on, not the object it is read from
                return !(x instanceof VariableExpression ve
                         && (ve.variable() instanceof FieldReference || ve.variable() instanceof DependentVariable));
            });
        }
        return found[0];
    }

    // a method of a library or the JDK: no source of ours, it cannot reach this unless handed it
    private static boolean library(MethodInfo mi) {
        var cu = mi.typeInfo().primaryType().compilationUnit();
        return cu != null && (cu.externalLibrary() || cu.partOfJdk());
    }

    /*
     Does the call change the variable's object: run on it (unless a read), or be handed it, unless the parameter that
     receives it is one the modification analysis finds unmodified (CodeLaser/maddi-mod#48 B6, nacos HttpLoginProcessor:
     'postForm(url, header, query, bodyMap, String.class)' keeps 'bodyMap.put(PASSWORD, ...)'s key).
     */
    private static boolean touches(Variable map, Variable receiver, boolean read, List<Expression> arguments,
                                   MethodInfo callee) {
        if (map.equals(receiver) && !read) return true;
        for (int i = 0; i < arguments.size(); i++) {
            if (unwrap(arguments.get(i)) instanceof VariableExpression ve && map.equals(ve.variable())
                && !unmodifiedParameter(callee, i)) {
                return true;
            }
        }
        return false;
    }

    // gate NOUNMODIFIEDARGS: a call handed a map forgets its keys whatever the parameter (A/B for #48 B6)
    private static final Switches.Gate UNMODIFIED_ARGS_OFF = Switches.gate("NOUNMODIFIEDARGS");

    private static boolean unmodifiedParameter(MethodInfo callee, int index) {
        if (UNMODIFIED_ARGS_OFF.get() || callee == null || callee.parameters().isEmpty()) return false;
        List<ParameterInfo> parameters = callee.parameters();
        ParameterInfo pi = index < parameters.size() ? parameters.get(index) : parameters.getLast();
        if (index >= parameters.size() - 1 && pi.isVarArgs()) return false;
        return pi.isUnmodified();
    }

    // ------------------------------------------------------------------ narrowed reads (CodeLaser/maddi-mod#22 gap 3)

    // a condition's facts, and what they make of the narrowed reads already known
    private void addCondition(Set<Variable> facts, Set<Variable> condition) {
        facts.addAll(condition);
        derive(facts);
    }

    private Set<Variable> withCondition(Set<Variable> facts, Set<Variable> condition) {
        if (condition.isEmpty() || TERNARY_FACTS_OFF.get()) return facts;
        Set<Variable> copy = new HashSet<>(facts);
        addCondition(copy, condition);
        return copy;
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

    // the variable compared with null in 'v == null' / 'v != null', or null. Also the target of an assignment compared
    // with null, the read loop's '(entry = zis.getNextEntry()) != null' / '(line = reader.readLine()) != null': the
    // comparison is of the value just assigned (diagnose, alpha.16's JSpecify seeds). Java only: Kotlin has no
    // assignment expression, and the printer rewrites such a loop
    private Variable nullCompared(BinaryOperator bo) {
        Expression other = bo.lhs() instanceof NullConstant ? bo.rhs()
                : bo.rhs() instanceof NullConstant ? bo.lhs() : null;
        if (unwrap(other) instanceof VariableExpression ve && trackable(ve.variable())) return ve.variable();
        if (!kotlinSmartCasts && unwrap(other) instanceof Assignment a && a.assignmentOperator() == null
            && a.variableTarget() != null && trackable(a.variableTarget())) {
            return a.variableTarget();
        }
        if (unwrap(other) instanceof MethodCall mc) return pureCall(mc);
        return null;
    }

    // ------------------------------------------------------------------ pure calls (CodeLaser/maddi-mod#22 gap 8)

    /*
     'x.m()' as a fact (PureCall): a non-static, non-modifying method without arguments, on a tracked variable; never in
     Kotlin's smart casts. 'if (ioe.getMessage() != null) { String errMsg = ioe.getMessage(); errMsg.contains(..) }'
     (nacos ConfigCacheService).
     */
    private Variable pureCall(MethodCall mc) {
        MethodInfo mi = mc.methodInfo();
        if (kotlinSmartCasts || mi == null || mi.isStatic() || !mc.parameterExpressions().isEmpty()
            || mc.object() == null || !(mi.isNonModifying() || libraryGetter(mi))
            || mi.returnType().isPrimitiveExcludingVoid()) return null;
        Variable receiver = trackableVariable(mc.object());
        return receiver == null ? null : new PureCall(receiver, mi, mi.returnType());
    }

    /*
     A getter of a library outside the JDK, which has no hints: its unannotated default is modifying, so the
     non-modifying test alone never admits it. Named 'getX'/'isX', no arguments; the fact is still forgotten at any
     call that may modify the receiver. Spring 'coreContext = null == ctx.getParent() ? ctx : ctx.getParent()' (nacos
     AbstractNacosDuplicateBeanPostProcessor).
     */
    private static boolean libraryGetter(MethodInfo mi) {
        if (mi.isConstructor() || mi.returnType().isVoid()) return false;
        var cu = mi.typeInfo().primaryType().compilationUnit();
        if (cu == null || !cu.externalLibrary() || cu.partOfJdk()) return false;
        String name = mi.name();
        int prefix = name.startsWith("get") ? 3 : name.startsWith("is") ? 2 : 0;
        return prefix > 0 && name.length() > prefix && Character.isUpperCase(name.charAt(prefix));
    }

    /** Is the result of {@code call} known non-null when it is made, by an earlier test of the same call (gap 8)? */
    public boolean pureCallNonNullWhenCalled(MethodCall call) {
        Variable v = pureCall(call);
        return v != null && whenCalled.getOrDefault(call, Set.of()).contains(v);
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
                Variable annotated = annotationPresence(mc, "isAnnotationPresent");
                if (annotated != null) set.add(annotated);
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
        // a no-argument test of this class on this ('hasNext()'): the fields it tells non-null
        if (mc.parameterExpressions().isEmpty() && walking != null
            && mc.methodInfo().typeInfo().equals(walking.typeInfo())
            && (mc.object() == null || unwrap(mc.object()) instanceof VariableExpression ve
                                       && ve.variable() instanceof io.codelaser.maddi.cst.api.variable.This)) {
            set.addAll(predicates.fieldsNonNullWhen(mc.methodInfo(), when == NullPredicates.When.FALSE_IF_NULL));
        }
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

    static boolean superCall(MethodCall mc) {
        return mc.object() instanceof VariableExpression ve
               && ve.variable() instanceof io.codelaser.maddi.cst.api.variable.This t && t.writeSuper();
    }

    /** Is the value of {@code e} certainly not null, given the facts? */
    boolean nonNull(Expression e, Set<Variable> facts) {
        Expression x = unwrap(e);
        return switch (x) {
            case null -> false;
            case NullConstant _ -> false;
            case ConstructorCall _, StringConstant _, StringConcat _, ArrayInitializer _, Lambda _,
                 MethodReference _, ClassExpression _, ConstantExpression<?> _ -> true; // NullConstant is handled above
            case VariableExpression ve -> ve.variable() instanceof io.codelaser.maddi.cst.api.variable.This
                                          || facts.contains(ve.variable())
                                          || notNullable != null && !kotlinSmartCasts && notNullable.test(ve);
            // each branch with its condition's facts: 'key = appName != null ? appName : NO_APP_NAME' (#48 A3, nacos
            // CredentialService), as Kotlin's 'if (appName != null) appName else NO_APP_NAME' is a String
            case InlineConditional ic -> nonNull(ic.ifTrue(), withCondition(facts, whenTrue(ic.condition())))
                                         && nonNull(ic.ifFalse(), withCondition(facts, whenFalse(ic.condition())));
            case MethodCall mc -> !kotlinSmartCasts && mc.methodInfo() != null
                                  && (returnContract.apply(mc.methodInfo()) == NullableState.NONNULL
                                      || superCallNonNull != null && superCall(mc)
                                         && superCallNonNull.test(mc.methodInfo())
                                      || pureCall(mc) instanceof Variable pc && facts.contains(pc)
                                      || notNullable != null && notNullable.test(mc));
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
                        List<Set<Variable>> enclosingLoop = continues;
                        continues = null;
                        block(lambda.methodBody(), in);
                        exits = enclosing;
                        continues = enclosingLoop;
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
                case BinaryOperator bo -> {
                    // an unboxed operand: 'old + 1', 'count == 0'; not 'a == b' of two references (a demand only)
                    if (!(isOperator(bo, "==") || isOperator(bo, "!=")) || primitive(bo.lhs()) || primitive(bo.rhs())) {
                        unboxed(bo.lhs(), facts);
                        unboxed(bo.rhs(), facts);
                    }
                    return true;
                }
                case UnaryOperator uo -> {
                    unboxed(uo.expression(), facts);
                    return true;
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
                        if (a.assignmentOperator() == null) {
                            narrowedRead(target, a.value(), facts);
                            navigatedKey(target, a.value(), facts);
                        }
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
                    callOfPrivate(mc, mc.methodInfo(), facts);
                    if (mc.methodInfo() != null) demanded(mc.methodInfo(), arguments, facts);
                    callMade(facts, mc);
                    // 'map.put(k, v)' with v non-null: k is present from here on
                    if (arguments.size() == 2 && nonNull(arguments.get(1), facts)) {
                        Variable present = keyPresence(mc, "put");
                        if (present != null) facts.add(present);
                    }
                    // 'map.computeIfAbsent(k, f)' with f never returning null: k is present from here on
                    if (arguments.size() == 2 && nonNullFunction(arguments.get(1))) {
                        Variable present = keyPresence(mc, "computeIfAbsent");
                        if (present != null) facts.add(present);
                    }
                    presentAtCalleeExit(mc, facts);
                    fieldsEnsured(mc, facts);
                    return false;
                }
                case MethodReference mr -> {
                    callOfPrivate(mr, mr.methodInfo(), null);
                    return true;
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

    private static boolean endsInThrow(Block block) {
        return block != null && !block.isEmpty() && block.statements().getLast() instanceof ThrowStatement;
    }

    private void demand(Variable v, Set<Variable> facts) {
        if (!kotlinSmartCasts && walking != null && !facts.contains(v)) {
            demands.computeIfAbsent(walking, _ -> new HashSet<>()).add(v);
        }
    }

    private static boolean primitive(Expression e) {
        ParameterizedType type = e.parameterizedType();
        return type != null && type.arrays() == 0 && type.isPrimitiveExcludingVoid();
    }

    private void unboxed(Expression e, Set<Variable> facts) {
        ParameterizedType type = unwrap(e).parameterizedType();
        if (type != null && type.arrays() == 0 && type.isBoxedExcludingVoid()) demandVariable(e, facts);
    }

    private void demandVariable(Expression e, Set<Variable> facts) {
        if (unwrap(e) instanceof VariableExpression ve && trackable(ve.variable())) demand(ve.variable(), facts);
    }

    private void dereference(Expression scope, Set<Variable> facts) {
        if (unwrap(scope) instanceof VariableExpression ve && trackable(ve.variable())) {
            demand(ve.variable(), facts);
            facts.add(ve.variable());
        }
        // 'clazz.getSuperclass().equals(X.class)': the same pure call again is non-null (gap 8, #48 B8)
        if (!LOOP_BACK_EDGES_OFF.get() && unwrap(scope) instanceof MethodCall mc && pureCall(mc) instanceof Variable pc) {
            facts.add(pc);
        }
    }

    private void demanded(MethodInfo callee, List<Expression> arguments, Set<Variable> facts) {
        if (kotlinSmartCasts) return;
        List<ParameterInfo> parameters = callee.parameters();
        for (int i = 0; i < arguments.size() && i < parameters.size(); i++) {
            ParameterInfo pi = parameters.get(i);
            if (pi.isVarArgs()) break;
            if (parameterContract.apply(pi) == NullableState.NONNULL
                && unwrap(arguments.get(i)) instanceof VariableExpression ve && trackable(ve.variable())) {
                demand(ve.variable(), facts);
                facts.add(ve.variable());
            }
        }
    }
}
