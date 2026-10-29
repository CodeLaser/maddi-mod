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

import io.codelaser.maddi.cst.api.analysis.Property;
import io.codelaser.maddi.cst.api.element.Element;
import io.codelaser.maddi.cst.api.expression.And;
import io.codelaser.maddi.cst.api.expression.ArrayInitializer;
import io.codelaser.maddi.cst.api.expression.Assignment;
import io.codelaser.maddi.cst.api.expression.BinaryOperator;
import io.codelaser.maddi.cst.api.expression.Cast;
import io.codelaser.maddi.cst.api.expression.ClassExpression;
import io.codelaser.maddi.cst.api.expression.ConstantExpression;
import io.codelaser.maddi.cst.api.expression.ConstructorCall;
import io.codelaser.maddi.cst.api.expression.Expression;
import io.codelaser.maddi.cst.api.expression.InlineConditional;
import io.codelaser.maddi.cst.api.expression.Lambda;
import io.codelaser.maddi.cst.api.expression.MethodCall;
import io.codelaser.maddi.cst.api.expression.StringConstant;
import io.codelaser.maddi.cst.api.expression.MethodReference;
import io.codelaser.maddi.cst.api.expression.NullConstant;
import io.codelaser.maddi.cst.api.expression.StringConcat;
import io.codelaser.maddi.cst.api.expression.Or;
import io.codelaser.maddi.cst.api.expression.SwitchExpression;
import io.codelaser.maddi.cst.api.expression.VariableExpression;
import io.codelaser.maddi.cst.api.info.FieldInfo;
import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.ParameterInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.api.statement.Block;
import io.codelaser.maddi.cst.api.statement.BreakOrContinueStatement;
import io.codelaser.maddi.cst.api.statement.DoStatement;
import io.codelaser.maddi.cst.api.statement.ExplicitConstructorInvocation;
import io.codelaser.maddi.cst.api.statement.ExpressionAsStatement;
import io.codelaser.maddi.cst.api.statement.ForEachStatement;
import io.codelaser.maddi.cst.api.statement.ForStatement;
import io.codelaser.maddi.cst.api.statement.LocalVariableCreation;
import io.codelaser.maddi.cst.api.statement.ReturnStatement;
import io.codelaser.maddi.cst.api.statement.Statement;
import io.codelaser.maddi.cst.api.statement.ThrowStatement;
import io.codelaser.maddi.cst.api.statement.TryStatement;
import io.codelaser.maddi.cst.api.statement.YieldStatement;
import io.codelaser.maddi.cst.api.analysis.Value;
import io.codelaser.maddi.cst.api.type.NullableState;
import io.codelaser.maddi.cst.api.type.ParameterizedType;
import io.codelaser.maddi.cst.api.variable.DependentVariable;
import io.codelaser.maddi.cst.api.variable.FieldReference;
import io.codelaser.maddi.cst.api.variable.LocalVariable;
import io.codelaser.maddi.cst.api.variable.This;
import io.codelaser.maddi.cst.api.variable.Variable;
import io.codelaser.maddi.cst.impl.analysis.NullAnnotations;
import io.codelaser.maddi.cst.impl.analysis.NullabilityFacts;
import io.codelaser.maddi.cst.impl.analysis.PropertyImpl;
import io.codelaser.maddi.cst.impl.analysis.ValueImpl;
import io.codelaser.maddi.modification.link.LinkComputer;
import io.codelaser.maddi.modification.link.impl.LinkComputerImpl;
import io.codelaser.maddi.modification.link.impl.LinkNatureImpl;
import io.codelaser.maddi.modification.prepwork.Util;
import io.codelaser.maddi.modification.prepwork.variable.Link;
import io.codelaser.maddi.modification.prepwork.variable.LinkNature;
import io.codelaser.maddi.modification.prepwork.variable.Links;
import io.codelaser.maddi.modification.prepwork.variable.ReturnVariable;
import io.codelaser.maddi.modification.prepwork.variable.VariableData;
import io.codelaser.maddi.modification.prepwork.variable.VariableInfoContainer;
import io.codelaser.maddi.modification.prepwork.variable.VariableInfo;
import io.codelaser.maddi.modification.prepwork.variable.impl.VariableDataImpl;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.IntStream;

/**
 * Declaration nullability as reachability over the converged link facts (docs/design/nullability.md §4.1, M3).
 * Modelled on {@code ShadowModificationPass}: a one-shot pass after the iterating analyzer has converged, so no
 * optimistic value is frozen before its evidence arrives.
 * <p>
 * <b>Nodes</b>: fields, parameters, methods standing for their return value, and local variables. A local is
 * keyed by its DECLARATION ({@link Local}), resolved through the block scopes, so same-named locals of sibling
 * scopes are distinct nodes (a {@link LocalVariable} is equal by name). <b>Seeds</b> (may be null): a null constant flowing into a node, as a link to a null-constant marker
 * or as a {@code null} argument; a field that can still hold Java's default value (not final, no initializer, not
 * assigned in every constructor); a local or field assigned the literal {@code null} (read from the code, so also in
 * a degraded method); under {@link Policy#nullTests()}, a parameter or field compared with null; library null
 * contracts (a library method's {@code NULLABILITY_*} property, from the analysis hints, says NULLABLE):
 * overriding a method that may return null or accepts null, and using such a method's result directly; under
 * {@link Policy#contracts()}, a source declaration annotated nullable. A source declaration annotated NON-NULL is
 * a contract too: null does not travel through it (passing null there is the caller's error, an M5 finding), and
 * its verdict is the annotation's.
 * <b>Edges</b>, in the direction null travels (source to recipient): {@code ←}/{@code ≡}/{@code →} links between
 * nodes in each method's variable data; an argument's nodes to the callee's parameter (the per-call argument
 * links); an implementation's return to the return it overrides; and a parameter to the parameter at the same
 * index along the override chain, both ways (Kotlin's rule: one nullability per chain).
 * <b>Use sites</b> ({@link NonNullFacts}, M4): an edge from a link or an argument is not made where the source is a
 * local or parameter known non-null at that statement (after {@code if (v == null) return;}, inside
 * {@code if (v != null)}, after a dereference, in the {@code ?:} branch that excludes null).
 * <p>
 * <b>Verdict</b>: reached is {@link NullableState#NULLABLE}; not reached is {@link Policy#unreached()}; a primitive
 * is {@link NullableState#NONNULL}; a type-variable type and the outputs of a degraded method (no links) are
 * {@link NullableState#UNSPECIFIED}, as are the unreached locals of a degraded method. Locals get a verdict too ({@link Report#local}).
 * <p>
 * <b>Content</b>: an array's elements ({@link Content}) and each type argument ({@link Arg}) are nodes of their own,
 * from the link engine's element and hidden-content variables ({@code a[i]}, {@code list.§$s},
 * {@code map.§$$s[-2]}, {@code box.t}), from writes into a generic receiver ({@code list.add(x)}), and from
 * invariance: wherever an array or a generic value flows, both ends' slots are tied both ways.
 * <p>
 * Requires the analyzer to have run with {@code Configuration.nullability()}: without
 * {@code LinkComputer.Options.nullConstantReturns} a null does not cross a call, and without
 * {@code trackObjectCreations} there are no argument links.
 */
public final class NullabilityPass {

    /**
     * @param unreached the verdict for a declaration no null reaches: NONNULL is the {@code @NullMarked} reading
     *                  (annotate only {@code @Nullable}), UNSPECIFIED claims nothing it cannot see
     * @param nullTests a parameter or field compared with {@code null} ({@code p == null}, {@code f != null}) is
     *                  taken as nullable: the author expects null there, even when no null is seen arriving (a
     *                  public parameter no analysed caller passes null to; a lazily initialized field)
     * @param contracts the null annotations on source declarations are contracts (M2): a nullable one is a seed, a
     *                  non-null one stops null. Off to measure the inference against those annotations (the oracle).
     * @param world     who else calls the analysed code: see {@link World}
     * @param assertContentWrites the Kotlin translation's choice: a value that is nullable only through indirect
     *                  evidence (a library contract such as {@code Map.get}, a field's default value, a comparison
     *                  with null) does not make an array's elements or a type argument nullable where it is written
     *                  into them; the slot stays non-null and the printer asserts at the write
     *                  ({@code list.add(map.get(k)!!)}), instead of a {@code !!} at every read of a
     *                  {@code List<X?>}. A null literal, a null argument or initializer, and a source annotation,
     *                  still make the slot nullable. Off for Java annotations, which say what can happen.
     * @param callResults a local, field or return assigned directly the result of an analysed method (not one typed
     *                  by a type variable) receives that method's nullability. Off for guava's annotations, where it
     *                  carried the flow-insensitivity of 'v = get(k); if (v == null) ...' into the callers (+947 noise,
     *                  2026-10-06); on for Kotlin, which does not compile a non-null variable holding a nullable
     *                  result.
     * @param assertAtDeclaration the Kotlin translation's choice for a local that null reaches, but that its own
     *                  block dereferences unconditionally further on (before any jump out, never reassigned):
     *                  the printer asserts at the declaration ({@code val block = blocks.getWithKey(j)!!}), so
     *                  the local is non-null and no null flows on from it (fernflower's
     *                  {@code protectedRange.add(block); block.addSuccessorException(handle)}). It moves the NPE
     *                  forward, from the dereference to the declaration. Off for Java annotations.
     * @param preconditionsNonNull a parameter that is a precondition ({@link Report#preconditions}: its method, and
     *                  in a closed world every method of its override family, dereferences it or throws on null on
     *                  every normal exit) is declared non-null, so no null flows on from it into the method's slots;
     *                  a nullable argument is asserted at the call ({@link Report#nullArguments}). Not where a call
     *                  passes the literal null, nor for a method a method reference or a JUnit null source hands to
     *                  outside code. Off in every preset (CodeLaser/maddi-mod#41): for Java annotations and C# it
     *                  changes nothing at run time; for Kotlin it moves the NPE from inside the method to the call.
     */
    public record Policy(NullableState unreached, boolean nullTests, boolean contracts, World world,
                         boolean assertContentWrites, boolean callResults, boolean assertAtDeclaration,
                         boolean libraryCallbacks, boolean testNulls, boolean preconditionsNonNull,
                         boolean rejectionsNullable) {
        public Policy(NullableState unreached, boolean nullTests, boolean contracts, World world,
                      boolean assertContentWrites, boolean callResults, boolean assertAtDeclaration,
                      boolean libraryCallbacks, boolean testNulls, boolean preconditionsNonNull) {
            this(unreached, nullTests, contracts, world, assertContentWrites, callResults, assertAtDeclaration,
                    libraryCallbacks, testNulls, preconditionsNonNull, false);
        }

        public static final Policy NULL_MARKED = new Policy(NullableState.NONNULL, true, true, World.CLOSED, false,
                false, false, false, true, false);
        public static final Policy NULL_MARKED_FLOW_ONLY = new Policy(NullableState.NONNULL, false, true,
                World.CLOSED, false, false, false, false, true, false);
        public static final Policy CAUTIOUS = new Policy(NullableState.UNSPECIFIED, true, true, World.CLOSED, false,
                false, false, false, true, false);
        /**
         * {@link #NULL_MARKED} for the Java→Kotlin translation: content writes are asserted, and a variable assigned an
         * analysed method's nullable result is nullable (Kotlin will not compile it otherwise), and a parameter of an
         * override of a library method is nullable unless the library declares it non-null (the library calls it back
         * with what it likes; Kotlin checks a non-null parameter on entry).
         */
        public static final Policy KOTLIN = new Policy(NullableState.NONNULL, true, true, World.CLOSED, true, true,
                true, true, true, false);

        public Policy withoutContracts() {
            return new Policy(unreached, nullTests, false, world, assertContentWrites, callResults, assertAtDeclaration,
                    libraryCallbacks, testNulls, preconditionsNonNull, rejectionsNullable);
        }

        public Policy withWorld(World world) {
            return new Policy(unreached, nullTests, contracts, world, assertContentWrites, callResults, assertAtDeclaration,
                    libraryCallbacks, testNulls, preconditionsNonNull, rejectionsNullable);
        }

        /**
         * Nulls that only test code writes do not count for production code (CodeLaser/maddi-mod#30 3, the
         * diagnostics): a null a test passes to a production method or constructor, or stores into a production
         * object, reaches nothing outside the test sources. Nulls inside the tests still count there.
         */
        public Policy withoutTestNulls() {
            return new Policy(unreached, nullTests, contracts, world, assertContentWrites, callResults, assertAtDeclaration,
                    libraryCallbacks, false, preconditionsNonNull, rejectionsNullable);
        }

        /** The precondition parameters are declared non-null ({@link #preconditionsNonNull}). */
        public Policy withPreconditionsNonNull() {
            return new Policy(unreached, nullTests, contracts, world, assertContentWrites, callResults, assertAtDeclaration,
                    libraryCallbacks, testNulls, true, rejectionsNullable);
        }

        /**
         * A parameter whose method answers null with its own exception ({@link Report#rejectsNull}) is nullable: a Java
         * caller's null reaches that check instead of Kotlin's entry check (CodeLaser/maddi-mod#28). Opt-in, for a
         * translation that must keep its Java callers' exceptions; every defensively checked parameter becomes '?'.
         */
        public Policy withRejectionsNullable() {
            return new Policy(unreached, nullTests, contracts, world, assertContentWrites, callResults, assertAtDeclaration,
                    libraryCallbacks, testNulls, preconditionsNonNull, true);
        }

        public Policy withAssertContentWrites(boolean assertContentWrites) {
            return new Policy(unreached, nullTests, contracts, world, assertContentWrites, callResults, assertAtDeclaration,
                    libraryCallbacks, testNulls, preconditionsNonNull, rejectionsNullable);
        }
    }

    /**
     * Whether the analysed invocations are all the invocations. A parameter's verdict comes from the null that
     * reaches it from the calls the pass sees; code outside the analysis (a library's users; the JDK calling back an
     * override) may pass null where no analysed call does.
     */
    public enum World {
        /** An application analysed with all its callers: a parameter no analysed call passes null to is non-null. */
        CLOSED,
        /**
         * A library: a parameter of a method callable from outside (public, or protected in an extensible type, of a
         * type reachable from outside; or overriding such a method, or a library method that does not declare its
         * parameter non-null) that no null reaches is UNSPECIFIED, and so is every declaration that parameter flows
         * into. For measurement: {@link #OPEN} is the useful form.
         */
        OPEN_VISIBILITY,
        /**
         * {@link #OPEN_VISIBILITY}, except that a parameter the body makes non-null on every normal exit (dereferenced,
         * passed to a non-null parameter, rejected by {@code if (p == null) throw}) is non-null: a precondition,
         * whoever the caller (docs/design/nullability.md §8).
         */
        OPEN
    }

    /**
     * A call that may pass null to a precondition parameter ({@link Report#preconditions}).
     *
     * @param call     the method call, constructor call, or {@code super(...)}/{@code this(...)} statement's call
     * @param argument the argument expression
     * @param literal  the argument is (or may be, as a conditional's branch) the literal {@code null}
     */
    public record NullArgument(Element call, ParameterInfo parameter, Expression argument, boolean literal) {
    }

    /**
     * @param verdicts fields, parameters, and methods (their return value)
     * @param locals   every declared local variable, by its declaration
     * @param useSites per statement, the locals and parameters known non-null when it starts (M4)
     * @param smartCasts the same, restricted to what Kotlin's smart cast derives: for a printer that drops
     *                   {@code !!} where Kotlin will see the variable as non-null
     * @param nonNullCallResults calls to an analysed method whose return null reaches, where it reaches only through
     *                   parameters whose arguments at that call cannot be null (by identity; see {@link #callResultNonNull})
     * @param preconditions the parameters a null argument makes throw before their method completes (dereferenced,
     *                   required, rejected on every normal exit), in a closed world with their whole override family
     *                   (CodeLaser/maddi-mod#41). A fact about the program, whatever the policy: diagnose reports
     *                   their {@link #nullArguments}, an annotator writes {@code @NonNull}, a printer under
     *                   {@link Policy#preconditionsNonNull} declares them non-null
     * @param nullArguments the calls that may pass null to a precondition parameter, as the analysis without
     *                   {@link Policy#preconditionsNonNull} sees them: where the NPE inside the callee comes from
     * @param preconditionsNonNull under {@link Policy#preconditionsNonNull}, the precondition parameters it declared
     *                   non-null that null reached without it: where a printer reports the change
     *                   ({@link #preconditionNonNull}). Empty under any other policy
     * @implNote the report is the {@link NullabilityFacts} a printer of a null-checked language asks for: the
     * declaration verdicts, the locals, the assertions at a declaration, the use-site facts, as smart casts only, and
     * the call results. A printer with hints for the library's declarations composes it with
     * {@link NullabilityFacts#STORED} ({@link NullabilityFacts#orElse}).
     */
    public record Report(Map<Info, ParameterizedType> verdicts, Map<Local, ParameterizedType> locals,
                         Map<Object, Object> cause, Map<Object, String> seedOrigin, NonNullFacts useSites,
                         NonNullFacts smartCasts, Set<Local> asserted, Set<Local> unobserved,
                         Set<Expression> nonNullCallResults, Set<ParameterInfo> preconditions,
                         List<NullArgument> nullArguments, Set<ParameterInfo> preconditionsNonNull,
                         Set<ParameterInfo> rejectsNull, Set<Object> writtenReach,
                         Set<io.codelaser.maddi.cst.api.info.TypeParameter> nonNullTypeParameters,
                         Map<TypeInfo, ParameterizedType> parentClasses)
            implements NullabilityFacts {

        /** Kotlin: a class's superclass with a nullable type argument its own code writes ('LinkedList<String?>'). */
        @Override
        public ParameterizedType parentClass(TypeInfo typeInfo) {
            return parentClasses.get(typeInfo);
        }

        /** Kotlin: a class or method type parameter the verdicts hold non-null ({@link #nonNullBounds}): the printer bounds it by Any. */
        @Override
        public boolean nonNullTypeParameter(io.codelaser.maddi.cst.api.info.TypeParameter typeParameter) {
            return nonNullTypeParameters.contains(typeParameter);
        }

        /**
         * A written null reaches this node: a literal, an argument, an initializer, an annotation, a default value
         * (the pass's seed), not only a library contract or other indirect evidence (CodeLaser/maddi-mod#49). Reached
         * but not by a written null: by contract only. A node can be reached both ways; this says whether the first
         * holds, whatever {@link #seedOrigin} and {@link #cause} recorded first.
         */
        public boolean reachedByWrittenNull(Object node) {
            return writtenReach.contains(node);
        }

        @Override
        public ParameterizedType field(FieldInfo fieldInfo) {
            return verdicts.get(fieldInfo);
        }

        @Override
        public ParameterizedType parameter(ParameterInfo parameterInfo) {
            return verdicts.get(parameterInfo);
        }

        @Override
        public ParameterizedType returnType(MethodInfo methodInfo) {
            return verdicts.get(methodInfo);
        }

        @Override
        public ParameterizedType reassignedParameter(ParameterInfo parameterInfo) {
            return reassigned(parameterInfo);
        }

        /** The smart casts only: {@link #useSites} also counts requireNonNull and contracts, which the target does not. */
        @Override
        public boolean nonNullAt(Statement statement, Variable variable) {
            return smartCasts.nonNullAt(statement, variable);
        }

        /** The same at a call: also what the enclosing condition establishes, and final fields of this. */
        @Override
        public boolean nonNullAt(Expression expression, Variable variable) {
            return smartCasts.nonNullAt(expression, variable);
        }

        @Override
        public boolean preconditionNonNull(ParameterInfo parameterInfo) {
            return preconditionsNonNull.contains(parameterInfo);
        }

        @Override
        public boolean rejectsNull(ParameterInfo parameterInfo) {
            return rejectsNull.contains(parameterInfo);
        }

        /** The pass analysed the program: a declaration without a nullable verdict is not null. */
        @Override
        public boolean nullabilityAnalysed() {
            return true;
        }

        /**
         * CodeLaser/maddi-mod#22 gap 9: the callee's return is nullable (its verdict), but not the result of THIS call,
         * because null reaches the return only from parameters this call's arguments do not make null.
         * {@code getInteger(key, defaultValue)} returning {@code search(key).orElse(defaultValue)}, called as
         * {@code getInteger(KEY, 5)} by one caller and {@code getInteger(key, null)} by another. Compared by identity.
         */
        public boolean callResultNonNull(Expression call) {
            return nonNullCallResults.contains(call);
        }

        /**
         * Whether the printer asserts this local at its declaration ({@link Policy#assertAtDeclaration}): null reaches
         * its initializer, the block dereferences it unconditionally further on, and its verdict is non-null.
         * Same arguments as {@link #local}.
         */
        public boolean assertedAtDeclaration(MethodInfo methodInfo, Element declaration, LocalVariable variable) {
            return asserted.contains(new Local(methodInfo, declaration, variable.simpleName()));
        }

        /**
         * For a local {@link #assertedAtDeclaration}: the linking engine proves that no statement between the
         * declaration and the first unconditional dereference refers to it, directly or through a linked variable,
         * so asserting it earlier changes no behaviour. False without that proof. Same arguments as {@link #local}.
         */
        public boolean unobservedBeforeDereference(MethodInfo methodInfo, Element declaration, LocalVariable variable) {
            Local local = new Local(methodInfo, declaration, variable.simpleName());
            return asserted.contains(local) && unobserved.contains(local);
        }

        /**
         * The verdict of a local variable, or null when there is none (a pattern variable; a declaration the pass
         * did not see).
         *
         * @param declaration the {@link LocalVariableCreation} (also of a for-each loop, a {@code for} initializer,
         *                    a try resource), the {@link ForEachStatement}, or the {@link TryStatement.CatchClause}
         *                    that declares {@code variable}; compared by identity
         */
        /**
         * A parameter the body assigns: the verdict of what the body assigns it, the type of the Kotlin printer's
         * {@code var p = p} (CodeLaser/maddi-mod#22 gap 5). The parameter's own verdict is the caller's value only. Null when
         * the body never assigns it.
         */
        public ParameterizedType reassigned(ParameterInfo parameterInfo) {
            return locals.get(new Local(parameterInfo.methodInfo(), null, parameterInfo.name()));
        }

        public ParameterizedType local(MethodInfo methodInfo, Element declaration, LocalVariable variable) {
            Element d = declaration instanceof ForEachStatement fe ? fe.initializer() : declaration;
            return locals.get(new Local(methodInfo, d, variable.simpleName()));
        }

        /** The chain that made a node nullable: node, its cause, ..., the seed and its origin. */
        public String explain(Object node) {
            StringBuilder sb = new StringBuilder(label(node));
            Object n = node;
            Set<Object> seen = new LinkedHashSet<>();
            while (cause.containsKey(n) && seen.add(n)) {
                n = cause.get(n);
                sb.append(" <- ").append(label(n));
            }
            String origin = seedOrigin.get(n);
            return origin == null ? sb.toString() : sb.append(" <- ").append(origin).toString();
        }

        static String label(Object node) {
            return switch (node) {
                case Content c -> "elements of " + label(c.of());
                case Arg a -> "type argument " + a.index() + " of " + label(a.of());
                case Keyed k -> (k.key() == null ? "values under a key not known" : "values under key " + k.key())
                                + " in " + label(k.slot());
                case MethodInfo mi -> "return " + mi.fullyQualifiedName();
                case Info info -> info.fullyQualifiedName();
                default -> String.valueOf(node);
            };
        }
    }

    private final Policy policy;
    // analysed implementations per overridden method (any overridden method, also a library one)
    private final Map<MethodInfo, List<MethodInfo>> implementations = new HashMap<>();
    // each null literal of an analysed body, by identity: a NullConstant equals every other, a Source only compares
    // line and column; a marker whose literal is not found here (a decoded summary) is seeded where it arrives
    private final Map<Expression, MethodInfo> nullOwners = new java.util.IdentityHashMap<>();
    // array creations ('new T[n]') the next statement fills completely before anything reads them (indexFills)
    private final Set<Expression> filledCreations = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    // gate NOFIELDFILL: only a local's declaration counts as the creation the next loop fills (A/B for CodeLaser/maddi-mod#22 gap 4)
    private static final Switches.Gate FIELD_FILLS_OFF = Switches.gate("NOFIELDFILL");
    // analysed methods whose return an earlier round found unreached by null (see go)
    private final Set<MethodInfo> trustedReturns;
    // analysed parameters an earlier round found non-null on every normal exit of their method: passing null throws
    private final Set<ParameterInfo> preconditions;
    // CodeLaser/maddi-mod#41: the lambdas in analysed bodies (members of their functional method's family); the analysed
    // methods a method reference implements (their family has a member the pass does not see); the methods a method
    // reference hands to whoever calls the function; the parameters a call passes the literal null to; per
    // precondition parameter, the calls and the node of what each passes
    private final Set<MethodInfo> lambdaMethods = new HashSet<>();
    // Kotlin: the bodies of 'assertThrows(NullPointerException.class, () -> ...)' (rejectsNull)
    private final Set<String> nullRejectingBodies = new HashSet<>();
    private final Set<MethodInfo> referencedSams = new HashSet<>();
    private final Set<MethodInfo> referencedMethods = new HashSet<>();
    private final Set<ParameterInfo> nullLiteralParameters = new HashSet<>();
    private final List<Object[]> preconditionArguments = new ArrayList<>();
    // what an earlier round found about final fields per runtime class: a read narrowed by 'instanceof' (gap 3)
    private final NarrowedReads narrowed;
    // CodeLaser/maddi-mod#48 A5: what an earlier round found non-null at every call of a private instance method, per
    // method the fields of this; and this round's, for the next (fieldsAtEntry)
    private final Map<MethodInfo, Set<Variable>> fieldsAtEntry;
    private Map<MethodInfo, Set<Variable>> nextFieldsAtEntry = Map.of();
    // CodeLaser/maddi-mod#48 B6: the lookups an earlier round's report knew the key of present (useSites), which the
    // graph's walk cannot know itself: its put's value is non-null by a library call only the closure clears
    private final Set<Expression> presentLookups;
    private Set<Expression> nextPresentLookups = Set.of();
    private final Set<MethodCall> lookups = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    // gate NOROUNDKEYS: no key presence from an earlier round (A/B for #48 B6)
    private static final Switches.Gate ROUND_KEYS_OFF = Switches.gate("NOROUNDKEYS");
    // gate NOENTRYFIELDS: no field of this is non-null at a private method's entry by its calls (A/B for #48 A5)
    private static final Switches.Gate ENTRY_FIELDS_OFF = Switches.gate("NOENTRYFIELDS");
    private List<FieldInfo> analysedFields = List.of();
    // keyed map fields (CodeLaser/maddi-mod#22 gap 2): the value slot per field, the accessors that take the key as a
    // parameter, and the keys written per slot
    private final Map<FieldInfo, Arg> keyedSlots = new HashMap<>();
    private final Map<MethodInfo, KeyedAccessor> keyedPutters = new HashMap<>();
    private final Map<MethodInfo, KeyedAccessor> keyedGetters = new HashMap<>();
    private final Map<Arg, Set<Object>> keysUsed = new LinkedHashMap<>();
    private Set<Object> reached = Set.of();
    // what a written null reaches (the strict closure, from strictSeeds), as of the last closure: Report.writtenReach
    private Set<Object> writtenReach = Set.of();
    // locals their block dereferences unconditionally further on (Policy.assertAtDeclaration), and those null reaches
    private final Set<Local> assertCandidates = new HashSet<>();
    private final Set<Local> asserted = new HashSet<>();
    // of the candidates, those the links prove unobserved between declaration and dereference
    private final Set<Local> unobservedCandidates = new HashSet<>();
    // not reached by null, but by a value of an outside caller (World.OPEN*): UNSPECIFIED
    private Set<Object> external = Set.of();
    // every flow between two nodes, also one dropped as known non-null: the array Contents they tie (coupleContent)
    private final List<List<Object>> flows = new ArrayList<>();
    // Kotlin's invariance between an instantiation's slot and the receiver's: {from, to, the formal slot}; added as
    // edges in once, unless the class itself makes the formal slot nullable (instantiatedSlots)
    private final List<Object[]> invariantTies = new ArrayList<>();
    // nodes seeded by a null that is written (a literal, an argument, an initializer, an annotation), not by indirect
    // evidence only (seedLibrary): Policy.assertContentWrites. Insertion-ordered: it seeds a closure whose cause
    // chains are reported, and a Local hashes by identity, so a hash order differs between runs (maddi-mod#33)
    private final Set<Object> strictSeeds = new LinkedHashSet<>();
    // per seed, the types whose code writes its null (a literal, a default value, a comparison); a null element: a
    // seed without a writer in the code (an annotation, a library contract). Read by holderFields.
    private final Map<Object, List<TypeInfo>> seedWriters = new HashMap<>();
    // holderFields under Policy.assertContentWrites: a field's edges to the instances' slots, added only when a null
    // written outside the field's class reaches it
    private final Map<FieldInfo, List<Arg>> pendingHolders = new LinkedHashMap<>();
    private final Map<Object, Set<Object>> successors = new LinkedHashMap<>();

    /*
     Culprits (Culprits.ENABLED, off by default): where each edge and seed was first built, the statement the walk was
     at (narrowed to the call it was visiting), so the chain that made a node nullable can be told as statements.
     */
    private record Site(MethodInfo in, Element element) {
    }

    private final boolean recordSites = io.codelaser.maddi.modification.common.util.Culprits.ENABLED;
    private final Map<Object, Map<Object, Site>> edgeSites = new HashMap<>();
    private final Map<Object, Site> seedSites = new HashMap<>();
    private Element currentSite;
    private MethodInfo currentSiteMethod;
    private static final int CULPRIT_STEPS = 12;
    private static final int CULPRIT_TEXT = 60;
    private final Map<Object, String> seedOrigin = new LinkedHashMap<>();
    private final Set<MethodInfo> degraded = new LinkedHashSet<>();
    // the methods of this analysis: their own NULLABILITY_* may be this pass's output of an earlier run, so only the
    // others' (the library's, from the hints) are read as contracts
    private final Set<MethodInfo> analysed = new HashSet<>();
    // source declarations annotated non-null (Policy.contracts): null stops there
    private final Set<Object> nonNullContracts = new HashSet<>() {
        @Override
        public boolean add(Object o) {
            boolean added = super.add(o);
            if (added && why(o)) whyLog("contract", o, null);
            return added;
        }
    };

    // NULLWHY=<substring>[|<substring>…]: why a node matching one is (not) nullable: its seeds, the non-null contracts put on it
    // (with the code that did), and its final state. A debugging aid, as NULLMARKEDDEBUG.
    private static final String NULL_WHY = System.getenv("NULLWHY");

    private static boolean why(Object node) {
        if (NULL_WHY == null || node == null) return false;
        String name = whyName(node);
        for (String part : NULL_WHY.split("\\|")) if (name.contains(part)) return true;
        return false;
    }

    private static String whyName(Object node) {
        return node instanceof Info info ? info.fullyQualifiedName() : String.valueOf(node);
    }

    // ' chain=' and the nodes the closure reached this one through, back to the seed and its origin
    private String whyChain(Map<Object, Object> cause, Object node) {
        StringBuilder sb = new StringBuilder(" chain=");
        Object n = node;
        Set<Object> seen = new HashSet<>();
        while (cause.containsKey(n) && seen.add(n)) {
            n = cause.get(n);
            sb.append(" <- ").append(Report.label(n));
        }
        String origin = seedOrigin.get(n);
        return origin == null ? sb.toString() : sb.append(" <- ").append(origin).toString();
    }

    private static void whyLog(String what, Object node, String detail) {
        StringBuilder sb = new StringBuilder("NULLWHY ").append(what).append(' ').append(whyName(node));
        if (detail != null) sb.append(" :: ").append(detail);
        StackTraceElement[] trace = Thread.currentThread().getStackTrace();
        for (int i = 3; i < Math.min(trace.length, 7); i++) {
            sb.append("\n    at ").append(trace[i].getMethodName()).append(':').append(trace[i].getLineNumber());
        }
        System.err.println(sb);
    }
    // (method, null marker): a return statement guarded the marker's link into the method's return (linksOf)
    private final Set<List<Object>> guardedReturnMarkers = new HashSet<>();
    // the same for a local: (method, marker, local) whose every assignment carrying the marker was guarded
    private final Set<List<Object>> guardedLocalMarkers = new HashSet<>();
    // (method, null marker): a statement carried the marker's link into the method's return (linksOf)
    private final Set<List<Object>> returnMarkersSeen = new HashSet<>();
    // the analysed method a local belongs to (a lambda's local: the method the lambda is in)
    private final Map<Local, MethodInfo> localOwner = new HashMap<>();
    private MethodInfo owner;
    private NonNullFacts facts;
    // link-derived edges, decided after every statement was seen: dropped when the source is known non-null at every
    // statement that assigns it directly to the recipient (M4)
    private final Map<List<Object>, int[]> linkEdges = new LinkedHashMap<>();
    private final Map<Local, LocalVariable> declaredLocals = new LinkedHashMap<>();
    // CodeLaser/maddi-mod#22 gap 9: a call's own arguments. Each analysed call gets an id; SiteArg(id, i) receives what
    // argument i of that call carries, and nothing more (no successors). A call's result is the callee's return only
    // where that return is nullable by itself, or through a parameter whose argument at THIS call is nullable
    private final Map<Expression, Integer> callIds = new IdentityHashMap<>();
    private final Map<Integer, MethodInfo> callees = new HashMap<>();
    private final List<GatedSite> gatedSites = new ArrayList<>();
    private final Set<Integer> argumentsSeen = new HashSet<>();
    private NullPredicates predicates;
    private java.util.function.BiPredicate<Object, TypeInfo> narrowedReads;
    // gate NOGAP9 for A/B: every call's result is the callee's return, as before
    private static final Switches.Gate GAP9_OFF = Switches.gate("NOGAP9");
    // NODIRECT: read the closure's products (Link.derived()) as before, for an A/B against the direct links only
    private static final Switches.Gate DERIVED_LINKS = Switches.gate("NODIRECT");
    // see the element-slot identity rule in the link walk
    private static final Switches.Gate SLOT_IDENTITY = Switches.gate("NOSLOTIDENTITY");
    // a parameter the body assigns (CodeLaser/maddi-mod#22 gap 5, #9): the parameter node is the caller's value only; what
    // the body assigns goes to a shadow local of the same name (the Kotlin printer's `var p = p`), which the parameter
    // flows into, and which reads after the first assignment resolve to (from the loop's start, for an assignment
    // inside a loop)
    private final Set<ParameterInfo> reassigned = new HashSet<>();
    private final Map<Local, ParameterInfo> shadows = new LinkedHashMap<>();
    private final Set<ParameterInfo> shadowed = new HashSet<>(); // walk state: reads resolve to the shadow from here on
    // per method (a lambda is its own), the declarations of each name: the fallback when scopes do not resolve one
    private final Map<MethodInfo, Map<String, List<Local>>> declaredByName = new HashMap<>();

    public NullabilityPass(Policy policy) {
        this(policy, Set.of(), Set.of(), NarrowedReads.NONE, Map.of(), Set.of());
    }

    private NullabilityPass(Policy policy, Set<MethodInfo> trustedReturns, Set<ParameterInfo> preconditions,
                            NarrowedReads narrowed, Map<MethodInfo, Set<Variable>> fieldsAtEntry,
                            Set<Expression> presentLookups) {
        this.policy = policy;
        this.trustedReturns = trustedReturns;
        this.preconditions = preconditions;
        this.narrowed = narrowed;
        this.fieldsAtEntry = fieldsAtEntry;
        this.presentLookups = presentLookups;
    }

    private static final int MAX_ROUNDS = 5;

    /*
     Kotlin: the calls that fill a lazily cached field of this ('return (result == null) ? keySet = createKeySet() :
     result'), trusted in round 1. Each later round keeps only what that round found unreached, so a null that really
     reaches one still wins; what goes is the cycle that justifies itself. guava's keySet() was nullable because
     createKeySet() might be, and createKeySet() because some members return another map's keySet()
     ('ImmutableMap.this.keySet()', 'fromMultimap.keySet()'): the whole family printed 'createKeySet(): ImmutableSet<K>?'
     (ImmutableMap.kt:105/107, AbstractMultimap.kt:76, 2026-10-17). Gate NOLAZYCACHES.
     */
    private Set<MethodInfo> lazyCacheCalls(List<Info> analysisOrder) {
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

    /**
     * Runs the pass in rounds. Round 1 trusts no analysed method's return. Each later round takes the analysed
     * methods whose return the previous round found unreached by null (sound: that round's reachability
     * over-approximates) as known non-null values: {@code field = create()} then carries no null. Dropping edges
     * only shrinks what null reaches, so the trusted set grows until it is stable. Likewise the parameters an earlier
     * round found to be preconditions ({@link NonNullFacts#nonNullAtExit}): an argument passed to one is non-null
     * after the call, which may make the caller's own parameter a precondition. And the fields of this non-null at
     * every call of a private method ({@link NonNullFacts#fieldsAtCalls}): non-null when its body starts.
     */
    public Report go(List<Info> analysisOrder) {
        Set<MethodInfo> trusted = lazyCacheCalls(analysisOrder);
        Set<ParameterInfo> nonNullAtExit = Set.of();
        NarrowedReads narrowedReads = NarrowedReads.NONE;
        Map<MethodInfo, Set<Variable>> atEntry = Map.of();
        Set<Expression> present = Set.of();
        for (int round = 1; ; round++) {
            NullabilityPass pass = new NullabilityPass(policy, trusted, nonNullAtExit, narrowedReads, atEntry, present);
            Report report = pass.once(analysisOrder);
            Set<MethodInfo> next = pass.unreachedReturns();
            Set<ParameterInfo> nextPreconditions = pass.preconditions();
            NarrowedReads nextNarrowed = pass.narrowedReads();
            Map<MethodInfo, Set<Variable>> nextAtEntry = pass.nextFieldsAtEntry;
            Set<Expression> nextPresent = pass.nextPresentLookups;
            if (next.equals(trusted) && nextPreconditions.equals(nonNullAtExit) && nextNarrowed.equals(narrowedReads)
                && nextAtEntry.equals(atEntry) && nextPresent.equals(present) || round == MAX_ROUNDS) {
                if (io.codelaser.maddi.modification.common.util.Culprits.ENABLED) pass.writeCulprits(report);
                return report;
            }
            trusted = next;
            nonNullAtExit = nextPreconditions;
            narrowedReads = nextNarrowed;
            atEntry = nextAtEntry;
            present = nextPresent;
        }
    }

    /*
     CodeLaser/maddi-mod#48 A5, nacos CredentialWatcher: 'loadPropertyPath(init); InputStream is =
     loadPropertyPathToStream();', the first leaving 'propertyPath' non-null, the second dereferencing it. Per private
     instance method, the fields of this the walk found non-null at every call, provided it walked every use of the
     method: a call in a field's initializer runs before any method, and is not walked. A private method has no
     callers outside its class's nest, in an open world too.
     */
    private static Map<MethodInfo, Set<Variable>> fieldsAtEntry(NonNullFacts walked, List<MethodInfo> methods,
                                                                List<FieldInfo> fields) {
        if (ENTRY_FIELDS_OFF.get()) return Map.of();
        Set<MethodInfo> unwalked = new HashSet<>();
        java.util.function.Predicate<Element> uses = e -> {
            MethodInfo callee = e instanceof MethodCall mc ? mc.methodInfo()
                    : e instanceof MethodReference mr ? mr.methodInfo() : null;
            if (callee != null && NonNullFacts.privateInstanceMethod(callee) && !walked.walked((Expression) e)) {
                unwalked.add(callee);
            }
            return true;
        };
        for (MethodInfo mi : methods) if (mi.methodBody() != null) mi.methodBody().visit(uses);
        for (FieldInfo fi : fields) if (fi.initializer() != null) fi.initializer().visit(uses);
        Map<MethodInfo, Set<Variable>> map = new HashMap<>();
        walked.fieldsAtCalls().forEach((method, set) -> {
            if (!set.isEmpty() && !unwalked.contains(method)) map.put(method, Set.copyOf(set));
        });
        return Map.copyOf(map);
    }

    // ------------------------------------------------------------------ narrowed reads (CodeLaser/maddi-mod#22 gap 3)

    // gate NONARROWING: no read is narrowed by the receiver's runtime type (A/B)
    private static final Switches.Gate NARROWING_OFF = Switches.gate("NONARROWING");
    // gate NOOPENNARROWING: the narrowing in the closed world only, as before CodeLaser/maddi-mod#30's open-world part
    private static final Switches.Gate OPEN_NARROWING_OFF = Switches.gate("NOOPENNARROWING");

    /**
     * Per final instance field, the concrete classes whose instances may hold null in it; per getter, its field.
     * {@link NonNullFacts} asks it whether a read {@code x.getF()} / {@code x.f} is non-null where {@code x} is known
     * to be an instance of a type: when none of those classes is a subtype of it. A field absent from the map is not
     * known (not final, no analysed subclass, an open world): never narrowed.
     */
    record NarrowedReads(Map<FieldInfo, Set<TypeInfo>> nullClasses, Map<MethodInfo, FieldInfo> getters) {
        static final NarrowedReads NONE = new NarrowedReads(Map.of(), Map.of());

        boolean isEmpty() {
            return nullClasses.isEmpty();
        }

        boolean nonNull(Object member, TypeInfo type) {
            FieldInfo field = member instanceof FieldInfo f ? f : member instanceof MethodInfo m ? getters.get(m) : null;
            Set<TypeInfo> classes = field == null ? null : nullClasses.get(field);
            if (classes == null) return false;
            return classes.stream().noneMatch(x -> isSubtype(x, type));
        }
    }

    private static boolean isSubtype(TypeInfo sub, TypeInfo sup) {
        return sub == sup || sub.superTypesExcludingJavaLangObject().contains(sup);
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
        for (MethodInfo mi : analysed) classes.add(mi.typeInfo());
        for (FieldInfo fi : analysedFields) classes.add(fi.owner());
        Map<FieldInfo, Set<TypeInfo>> nullClasses = new HashMap<>();
        for (FieldInfo fi : analysedFields) {
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
        for (MethodInfo mi : analysed) {
            io.codelaser.maddi.cst.api.analysis.Value.FieldValue fv = mi.getSetField();
            if (fv == null || fv.field() == null || fv.setter() || fv.hasIndex() || mi.isStatic()
                || !mi.parameters().isEmpty() || implementations.containsKey(mi)
                || !nullClasses.containsKey(fv.field())) continue;
            getters.put(mi, fv.field());
        }
        return new NarrowedReads(Map.copyOf(nullClasses), Map.copyOf(getters));
    }

    /*
     CodeLaser/maddi-mod#30 3: a final field of this, read in method m, is non-null when no class whose instances may
     hold null in it can run m. A class S does not run m when S, or a class between S and m's class, overrides m (and
     no analysed code calls m through 'super'), or, for a method that is not public, when no analysed call of m, nor
     of a method m overrides, has a receiver whose static type S is a subtype of. timefold MixedVariableExhaustiveSearchDecider stores a null
     moveRepository, overrides every lifecycle method that reads it, and is never the receiver of the protected
     expandNode the other deciders call on this. Implies the narrowing by m's own class (NonNullFacts.narrowedThis).
     */
    private boolean narrowedInMethod(FieldInfo fi, MethodInfo m) {
        Set<TypeInfo> classes = narrowed.nullClasses().get(fi);
        if (classes == null) return false;
        TypeInfo owner = m.typeInfo();
        return classes.stream().noneMatch(s -> isSubtype(s, owner) && mayRunOn(m, s));
    }

    private boolean mayRunOn(MethodInfo m, TypeInfo s) {
        CallIndex index = callIndex();
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

    /*
     Per method called in the analysed code, the static types of the receivers of its calls; null for a receiver
     without a type, and for a method reference (any instance may be its receiver). The methods called through
     'super', which run on the caller's instance whatever overrides them.
     */
    private record CallIndex(Map<MethodInfo, List<TypeInfo>> receivers, Set<MethodInfo> superCalled) {
    }

    private CallIndex callIndex;

    private CallIndex callIndex() {
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

    private static TypeInfo parentOf(TypeInfo type) {
        ParameterizedType parent = type.parentClass();
        return parent == null ? null : parent.typeInfo();
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
                if (analysed.contains(callee)) {
                    yield reached.contains(callee) || external.contains(callee) || degraded.contains(callee);
                }
                yield libraryNullableCall(mc) != null;
            }
            default -> true;
        };
    }

    // gate NOFAMILYPRECONDITIONS: only methods no override replaces have preconditions, as before CodeLaser/maddi-mod#41
    private static final Switches.Gate FAMILY_PRECONDITIONS_OFF = Switches.gate("NOFAMILYPRECONDITIONS");

    private Set<ParameterInfo> preconditions() {
        Set<ParameterInfo> set = new HashSet<>();
        Map<MethodInfo, Set<MethodInfo>> families = policy.world() == World.CLOSED && !FAMILY_PRECONDITIONS_OFF.get()
                ? families() : Map.of();
        for (MethodInfo mi : analysed) {
            if (families.containsKey(mi)) continue;
            // a call runs this body only when no override replaces it: an analysed one; in an open world any
            boolean overridable = !mi.isConstructor() && !mi.isStatic() && !mi.access().isPrivate()
                                  && !mi.isFinal() && !mi.typeInfo().isFinal();
            if (overridable && (policy.world() != World.CLOSED || implementations.containsKey(mi))) continue;
            for (ParameterInfo pi : mi.parameters()) {
                if (facts.nonNullAtExit(mi, pi)) set.add(pi);
            }
        }
        Set<Set<MethodInfo>> distinct = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        distinct.addAll(families.values());
        for (Set<MethodInfo> family : distinct) familyPreconditions(family, set);
        return Set.copyOf(set);
    }

    /*
     CodeLaser/maddi-mod#41. In a closed world a call to a method an override may replace runs one of its family: the
     analysed methods connected to it by overriding, up and down (an interface method, its implementations, their
     overrides, the lambdas implementing it). Kotlin and C# need the family to agree on a parameter's type anyway
     (f7c595bd, reverted: fernflower's ExprentIterator.processExprent kept a nullable interface parameter under non-null
     implementations). A library method does not join a family: a call to it is the library's contract, and Kotlin
     lets each override of a Java declaration choose ('equals(Object)' would otherwise make all of them one family).
     A parameter position is a precondition of the whole family when
     - no method reference implements one of its methods: the referenced method is not a member the pass sees;
     - every member with a body throws on null there: by its own body (NonNullFacts.nonNullAtExit), or by handing the
       parameter to a member that does, through 'super' (fernflower's IfStatement.replaceStatement);
     - at least one member has a body.
     */
    private void familyPreconditions(Set<MethodInfo> family, Set<ParameterInfo> set) {
        MethodInfo first = family.iterator().next();
        int n = first.parameters().size();
        for (MethodInfo m : family) {
            if (m.parameters().size() != n || referencedSams.contains(m)) {
                return;
            }
        }
        for (int i = 0; i < n; i++) {
            int index = i;
            Set<MethodInfo> throwing = new HashSet<>();
            for (MethodInfo m : family) {
                if (hasBody(m) && facts.nonNullAtExit(m, m.parameters().get(index))) throwing.add(m);
            }
            boolean added = true;
            while (added) {
                added = false;
                for (MethodInfo m : family) {
                    if (hasBody(m) && !throwing.contains(m) && passesToSuper(m, index, throwing)) {
                        added = throwing.add(m);
                    }
                }
            }
            if (throwing.isEmpty() || family.stream().anyMatch(m -> hasBody(m) && !throwing.contains(m))) continue;
            family.forEach(m -> set.add(m.parameters().get(index)));
        }
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

    private static final String NULL_ARGUMENT = "null argument in ";
    // gate NOREJECTEDNULLS: a null passed inside assertThrows(NullPointerException.class, ...) seeds as any (A/B)
    private static final Switches.Gate REJECTED_NULLS_OFF = Switches.gate("NOREJECTEDNULLS");

    private void indexFunctionals(List<MethodInfo> methods) {
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

    // what a call passes to a precondition parameter: per alternative of the argument, the literal null or its node,
    // decided after the closure (nullArguments). Not the caller's own precondition parameter passed on
    // ('super.replace(oldstat, newstat)'): a null there is reported where it enters the caller
    private void preconditionArgument(MethodInfo mi, Scope scope, Statement statement, Expression call,
                                      ParameterInfo parameter, Expression argument) {
        if (NonNullFacts.unwrap(argument) instanceof VariableExpression own && own.variable() instanceof ParameterInfo pi
            && preconditions.contains(pi) && !reassigned.contains(pi)) {
            return;
        }
        boolean literal = false;
        List<Object> nodes = new ArrayList<>();
        for (Expression alternative : alternatives(argument)) {
            Expression unwrapped = NonNullFacts.unwrap(alternative);
            if (unwrapped instanceof NullConstant) {
                literal = true;
            } else if (!(unwrapped instanceof VariableExpression ve
                         && (call != null && facts.nonNullWhenCalled(call, ve.variable())
                             || facts.nonNullAt(statement, ve.variable())))) {
                Object node = argumentNode(mi, scope, alternative);
                if (node != null) nodes.add(node);
            }
        }
        if (literal) nullLiteralParameters.add(parameter);
        preconditionArguments.add(new Object[]{call != null ? call : statement, parameter, argument, literal, nodes});
    }

    private List<NullArgument> nullArguments(Set<Object> reached) {
        List<NullArgument> list = new ArrayList<>();
        for (Object[] a : preconditionArguments) {
            boolean literal = (Boolean) a[3];
            @SuppressWarnings("unchecked") List<Object> nodes = (List<Object>) a[4];
            if (literal || nodes.stream().anyMatch(reached::contains)) {
                list.add(new NullArgument((Element) a[0], (ParameterInfo) a[1], (Expression) a[2], literal));
            }
        }
        return List.copyOf(list);
    }

    /*
     Policy.preconditionsNonNull: the precondition parameters declared non-null, a family at a time (its members' types
     must agree). Not a family a call passes the literal null to ('f(null)' always throws, and 'f(null!!)' says nothing
     more), nor one a method reference hands to outside code, a member overriding a library method calls back
     (Policy.libraryCallbacks), or JUnit fills from a null source. Nor one a member compares with null: its
     'if (p == null) throw new IllegalArgumentException(...)' is the method's answer to null, which the caller's
     assertion would replace by an NPE (langchain4j's ensureNotNull; CodeLaser/maddi-mod#28).
     */
    private Set<ParameterInfo> declaredPreconditions() {
        Map<MethodInfo, Set<MethodInfo>> families = policy.world() == World.CLOSED && !FAMILY_PRECONDITIONS_OFF.get()
                ? families() : Map.of();
        Set<ParameterInfo> declared = new HashSet<>();
        for (ParameterInfo pi : preconditions) {
            Set<MethodInfo> family = families.getOrDefault(pi.methodInfo(), Set.of(pi.methodInfo()));
            boolean excluded = family.stream().anyMatch(m -> m.parameters().size() <= pi.index()
                    || nullLiteralParameters.contains(m.parameters().get(pi.index()))
                    || referencedMethods.contains(m) || junitNullSource(m)
                    || hasBody(m) && comparedWithNull(m, m.parameters().get(pi.index()), new HashSet<>())
                    || overrides(m).stream().anyMatch(o -> !analysed.contains(o)));
            if (!excluded) declared.add(pi);
        }
        return declared;
    }

    /*
     CodeLaser/maddi-mod#28: the parameters whose method answers null with its own check rather than a dereference. A
     precondition (needed on every normal exit) that the method, or an analysed callee it hands it to, compares with
     null first: 'if (p == null) throw new IllegalArgumentException(...)', langchain4j's ensureNotBlank. The printer
     reports them where they stay non-null; Policy.rejectionsNullable makes them nullable.
     */
    private Set<ParameterInfo> rejectsNull() {
        Set<ParameterInfo> set = new LinkedHashSet<>();
        for (ParameterInfo pi : preconditions) {
            if (rejects(pi)) set.add(pi);
        }
        return Set.copyOf(set);
    }

    private boolean rejects(ParameterInfo pi) {
        ParameterizedType type = pi.parameterizedType();
        MethodInfo m = pi.methodInfo();
        return preconditions.contains(pi) && !(type.isPrimitiveExcludingVoid() && type.arrays() == 0) && hasBody(m)
               && comparedWithNull(m, pi, new HashSet<>());
    }

    /*
     The parameter is compared with null in m's body, or handed to an analysed method that compares its own parameter
     with null, transitively: langchain4j's 'if (isNullOrBlank(string)) throw illegalArgument(...)' in ensureNotBlank,
     where isNullOrBlank says 'string == null || ...' (CodeLaser/maddi#125, the Kotlin measurement).
     */
    private boolean comparedWithNull(MethodInfo m, ParameterInfo pi, Set<ParameterInfo> seen) {
        if (!seen.add(pi)) return false;
        boolean[] found = {false};
        m.methodBody().visit(e -> {
            if (e instanceof BinaryOperator bo) {
                Expression other = bo.lhs() instanceof NullConstant ? bo.rhs()
                        : bo.rhs() instanceof NullConstant ? bo.lhs() : null;
                if (other instanceof VariableExpression ve && pi.equals(ve.variable())) found[0] = true;
            } else {
                // a call, a constructor call (Document.from(text) is 'new DefaultDocument(text)'), this(...), super(...)
                MethodInfo callee = null;
                List<Expression> arguments = null;
                if (e instanceof MethodCall mc) {
                    callee = mc.methodInfo();
                    arguments = mc.parameterExpressions();
                } else if (e instanceof ConstructorCall cc && cc.constructor() != null && cc.anonymousClass() == null) {
                    callee = cc.constructor();
                    arguments = cc.parameterExpressions();
                } else if (e instanceof ExplicitConstructorInvocation eci && eci.methodInfo() != null) {
                    callee = eci.methodInfo();
                    arguments = eci.parameterExpressions();
                }
                if (callee != null && analysed.contains(callee) && hasBody(callee)
                    && passedOnAndTested(pi, callee, arguments, seen)) {
                    found[0] = true;
                }
            }
            return !found[0];
        });
        return found[0];
    }

    private boolean passedOnAndTested(ParameterInfo pi, MethodInfo callee, List<Expression> arguments,
                                      Set<ParameterInfo> seen) {
        List<ParameterInfo> calleeParameters = callee.parameters();
        for (int i = 0; i < arguments.size() && i < calleeParameters.size(); i++) {
            if (arguments.get(i) instanceof VariableExpression ve && pi.equals(ve.variable())
                && !calleeParameters.get(i).isVarArgs()
                && comparedWithNull(callee, calleeParameters.get(i), seen)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasBody(MethodInfo m) {
        return !m.isAbstract() && m.methodBody() != null;
    }

    // the analysed methods, and the lambdas in their bodies, connected by overriding; only methods in a family of two
    // or more. A method reference implementing an analysed method marks that method's family (referencedSams)
    private Map<MethodInfo, Set<MethodInfo>> families() {
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

    /*
     The method hands its parameter to a member of 'to' through 'super', as a statement of its body that every normal
     exit passes (nothing before it may leave), and never assigns the parameter.
     */
    private static boolean passesToSuper(MethodInfo m, int index, Set<MethodInfo> to) {
        ParameterInfo pi = m.parameters().get(index);
        boolean[] assigned = {false};
        m.methodBody().visit(e -> {
            if (e instanceof Assignment a && pi.equals(a.variableTarget())) assigned[0] = true;
            return !assigned[0];
        });
        if (assigned[0]) return false;
        for (Statement statement : m.methodBody().statements()) {
            if (statement instanceof ExpressionAsStatement eas && eas.expression() instanceof MethodCall mc
                && to.contains(mc.methodInfo()) && index < mc.parameterExpressions().size()
                && mc.object() instanceof VariableExpression receiver
                && receiver.variable() instanceof io.codelaser.maddi.cst.api.variable.This t && t.writeSuper()
                && NonNullFacts.unwrap(mc.parameterExpressions().get(index)) instanceof VariableExpression ve
                && pi.equals(ve.variable())) {
                return true;
            }
            if (mayLeave(statement)) return false;
        }
        return false;
    }

    // analysed methods with a reference return no null reached, not degraded. A type-variable return counts too:
    // its verdict stays parametric, but no null of this program reaches it, so its calls here are non-null values
    // (guava's own 'checkNotNull(T)')
    private Set<MethodInfo> unreachedReturns() {
        Set<MethodInfo> set = new HashSet<>();
        for (MethodInfo mi : analysed) {
            ParameterizedType rt = mi.returnType();
            if (mi.isConstructor() || rt.isVoid() || rt.isPrimitiveExcludingVoid() && rt.arrays() == 0
                || degraded.contains(mi) || reached.contains(mi) || external.contains(mi)) continue;
            set.add(mi);
        }
        return Set.copyOf(set);
    }

    private Report once(List<Info> analysisOrder) {
        List<MethodInfo> methods = analysisOrder.stream()
                .filter(i -> i instanceof MethodInfo).map(i -> (MethodInfo) i).toList();
        List<FieldInfo> fields = analysisOrder.stream()
                .filter(i -> i instanceof FieldInfo).map(i -> (FieldInfo) i).toList();
        analysed.addAll(methods);
        analysedFields = fields;
        nonNullBounds(analysisOrder, methods, fields);
        methods.forEach(this::indexNullLiterals);
        for (MethodInfo mi : methods) if (mi.methodBody() != null) indexFills(mi.methodBody());
        for (MethodInfo mi : methods) {
            for (MethodInfo overridden : overrides(mi)) {
                implementations.computeIfAbsent(overridden, _ -> new java.util.ArrayList<>()).add(mi);
            }
        }
        indexFunctionals(methods);
        indexKeyedMaps(methods, fields);
        predicates = NullPredicates.infer(methods);
        narrowedReads = narrowed.isEmpty() ? null : narrowed::nonNull;
        facts = new NonNullFacts(this::parameterContract, this::returnContract)
                .withPredicates(predicates)
                .withNarrowedReads(narrowedReads)
                .withNarrowedInMethod(narrowedReads == null ? null : this::narrowedInMethod)
                .withFieldsAtEntry(fieldsAtEntry)
                .withSuperCalls(policy.callResults() && !SUPER_CALLS_OFF.get() ? this::ownReturnsNonNull : null);
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
        if (policy.callResults()) {
            for (MethodInfo mi : methods) {
                if (boundedTypeVariableOverride(mi) || overridesKotlinNonNullReturn(mi)
                    || overridesLibraryNonNullReturn(mi) || testOverrideOfInstantiatedReturn(mi)) nonNullContracts.add(mi);
            }
            nonNullOverrides(methods);
        }
        if (policy.callResults() && !DEAD_NULLS_OFF.get()) {
            for (MethodInfo mi : methods) if (mi.methodBody() != null) deadNullAssignments(mi.methodBody());
        }
        for (MethodInfo mi : methods) {
            owner = mi;
            buildForMethod(mi);
        }
        keyedEdges(methods);
        for (FieldInfo fi : fields) seedDefaultValue(fi);
        linkEdges.forEach((edge, count) -> {
            // [0] assigned (or passed) where the source is known non-null, [1] where it is not, [2] assigned by a
            // value not seen through. Never observed at all: only the links' transitive closure. In
            // 'imp.getNestedName(root ? node.classStruct.qualifiedName : node.simpleName)' both alternatives are '≡'
            // the argument, and so each other: fernflower's ClassNode.simpleName (compared with null) reached
            // StructClass.qualifiedName and from there most of the program. A slot is written by calls, without
            // an assignment the statement shows: kept.
            // [3] a link of a method's summary (no statement): kept as before, unless guarded somewhere
            boolean slot = edge.get(1) instanceof Arg || edge.get(1) instanceof Content;
            if (policy.callResults() && !DIFFERENT_SHAPES_OFF.get() && differentShapes(edge.get(0), edge.get(1))) return;
            if (liftedIntoNarrower(edge.get(0), edge.get(1))) return;
            if (count[1] > 0 || count[2] > 0 || count[0] == 0 && slot) {
                addEdge(edge.get(0), edge.get(1));
            } else {
                flows.add(List.of(edge.get(0), edge.get(1))); // the value is not null there; its elements may be
            }
        });
        holderFields(methods, fields);
        fieldInitializerNullArguments(fields);
        assignedParameterSlots(methods);
        enumValueOfContracts(methods);
        nullFilteredParameters(methods);
        resolveTypeVariableArguments();
        coupleContent();
        if (!invariantTies.isEmpty()) {
            // a class type variable's slot is reached only by the class's own nulls (coupleContent never ties it
            // to an instantiation's slot), so that is known before the invariance edges are added
            Set<Object> own = closure(new HashMap<>());
            boolean added = false;
            for (Object[] tie : invariantTies) {
                if (!own.contains(tie[2])) {
                    addEdge(tie[0], tie[1]);
                    added = true;
                }
            }
            if (added) coupleContent(); // the nested slots of the new flows
        }

        libraryParameterContracts();
        if (!TOLERANT_OFF.get()) tolerantParameters(methods);
        Map<Object, Object> cause = new LinkedHashMap<>();
        reached = closure(cause);
        List<NullArgument> nullArguments = nullArguments(reached);
        Set<ParameterInfo> preconditionsNonNull = Set.of();
        if (policy.preconditionsNonNull()) {
            Set<ParameterInfo> declared = declaredPreconditions();
            Set<Object> reachedBefore = reached;
            // the printers report only these: elsewhere the parameter is non-null without the policy
            preconditionsNonNull = declared.stream().filter(reachedBefore::contains)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            if (!declared.isEmpty()) {
                nonNullContracts.addAll(declared);
                cause.clear();
                reached = closure(cause);
            }
        }
        if (policy.assertAtDeclaration()) {
            // only where null arrives: elsewhere the assertion would be a '!!' on a non-null value
            assertCandidates.forEach(local -> {
                if (reached.contains(local)) asserted.add(local);
            });
            if (!asserted.isEmpty()) {
                nonNullContracts.addAll(asserted);
                cause.clear();
                reached = closure(cause);
            }
        }
        if (policy.world() != World.CLOSED) external = externalClosure(methods);
        if (policy.callResults()) reached = storedOnlyIntoNullableFields(methods, reached, cause);

        Map<Info, ParameterizedType> verdicts = new LinkedHashMap<>();
        Map<Local, ParameterizedType> locals = new LinkedHashMap<>();
        // an unreached local of a degraded method: its links are missing, so "no null reaches it" is not known
        declaredLocals.forEach((local, lv) -> {
            ParameterizedType v = verdict(lv.parameterizedType(), local, degraded.contains(localOwner.get(local)));
            locals.put(local, guardedLocals.contains(local) && !(v.isPrimitiveExcludingVoid() && v.arrays() == 0)
                    ? v.withNullable(NullableState.NULLABLE) : v);
        });
        shadows.forEach((local, pi) -> locals.put(local, verdict(pi.parameterizedType(), local,
                degraded.contains(pi.methodInfo()))));
        for (FieldInfo fi : fields) {
            verdicts.put(fi, contracted(fi, verdict(fi.type(), fi, false)));
            if (why(fi)) {
                System.err.println("NULLWHY final " + fi.fullyQualifiedName() + " reached=" + reached.contains(fi)
                                   + whyChain(cause, fi) + " elements reached=" + reached.contains(new Content(fi))
                                   + whyChain(cause, new Content(fi)) + " verdict=" + verdicts.get(fi));
            }
        }
        for (MethodInfo mi : methods) {
            boolean deg = degraded.contains(mi);
            for (ParameterInfo pi : mi.parameters()) {
                verdicts.put(pi, contracted(pi, verdict(pi.parameterizedType(), pi, false)));
            }
            if (!mi.isConstructor() && !mi.returnType().isVoid()) {
                verdicts.put(mi, contracted(mi, verdict(mi.returnType(), mi, deg)));
            }
            if (why(mi)) {
                System.err.println("NULLWHY final " + mi.fullyQualifiedName() + " reached=" + reached.contains(mi)
                                   + " seed=" + seedOrigin.get(mi) + " contract=" + nonNullContracts.contains(mi)
                                   + " degraded=" + deg + " verdict=" + verdicts.get(mi) + whyChain(cause, mi));
            }
        }
        if (policy.callResults()) {
            clampTestOverrides(methods, verdicts);
            clampTypeVariableOverrides(methods, verdicts);
        }
        NonNullFacts smartCasts = facts.kotlinSmartCasts();
        methods.forEach(smartCasts::walk);
        Set<Expression> nonNullCallResults = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        Map<MethodInfo, Boolean> intrinsic = new HashMap<>();
        Set<Object> finalReached = reached;
        callIds.forEach((call, id) -> {
            MethodInfo callee = callees.get(id);
            if (finalReached.contains(callee)
                && !callResultNullable(callee, id, finalReached, finalReached::contains, intrinsic)) {
                nonNullCallResults.add(call);
            }
        });
        // gap 7: the facts handed out know what the closure decided; the walk that built the graph could not
        NonNullFacts useSites = new NonNullFacts(this::parameterContract, this::returnContract)
                .withPredicates(predicates)
                .withNarrowedReads(narrowedReads)
                .withNarrowedInMethod(narrowedReads == null ? null : this::narrowedInMethod)
                .withNotNullable(e -> notNullable(e, nonNullCallResults))
                .withFieldsAtEntry(fieldsAtEntry);
        methods.forEach(useSites::walk);
        nextFieldsAtEntry = fieldsAtEntry(useSites, methods, fields);
        if (!ROUND_KEYS_OFF.get()) {
            Set<Expression> present = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
            for (MethodCall lookup : lookups) if (useSites.keyPresentWhenCalled(lookup)) present.add(lookup);
            nextPresentLookups = present;
        }
        return new Report(verdicts, locals, cause, seedOrigin, useSites, smartCasts, Set.copyOf(asserted),
                Set.copyOf(unobservedCandidates), nonNullCallResults, preconditions, nullArguments, preconditionsNonNull,
                rejectsNull(), writtenReach, Set.copyOf(nonNullTypeParameters), parentClassVerdicts(methods));
    }

    /*
     Kotlin: a class whose own code writes null into its generic superclass's slots ('class OwnerContext extends
     LinkedList<String>' calling 'addFirst(owner)' with a nullable owner; DiskSpaceMonitorDescriptor's
     'e.setValue(get(c))' into the Map<Computer, T> of its AbstractAsyncNodeMonitorDescriptor<DiskSpace>) extends a
     LinkedList<String?>, an AbstractAsyncNodeMonitorDescriptor<DiskSpace?> (jenkins RobustReflectionConverter.kt:449,
     DiskSpaceMonitorDescriptor.kt:38, 2026-10-22). Only the classes with such a slot.
     */
    private Map<TypeInfo, ParameterizedType> parentClassVerdicts(List<MethodInfo> methods) {
        if (SUPERTYPE_SLOTS_OFF.get() || !policy.callResults()) return Map.of();
        Map<TypeInfo, ParameterizedType> out = new LinkedHashMap<>();
        for (MethodInfo mi : methods) {
            TypeInfo type = mi.typeInfo();
            if (out.containsKey(type) || type.isAnonymous() || !thisTypedBySuperclass(type)) continue;
            ParameterizedType parent = type.parentClass();
            AnonymousThis self = new AnonymousThis(type);
            boolean nullable = false;
            for (int i = 0; i < parent.parameters().size(); i++) nullable |= reached.contains(new Arg(self, i));
            if (nullable) out.put(type, verdict(parent, self, false));
        }
        return Map.copyOf(out);
    }

    private static final Switches.Gate SUPERTYPE_SLOTS_OFF = Switches.gate("NOSUPERTYPESLOTS");
    private static final Switches.Gate VIEW_LOOPS_OFF = Switches.gate("NOVIEWLOOPS");

    /*
     a class whose superclass has a type argument that is not a type variable: its 'this' has slots of its own. A
     private one only: its superclass is its type nowhere else. jenkins' public EnvVars as a TreeMap<String, String?>
     broke the ~15 places that pass it as a Map<String, String> (Kotlin's maps are invariant), FingerprintMap and
     DiskSpaceMonitorDescriptor likewise: 127 -> 159 errors (worktree-maddi-all 2026-10-22). Gate
     NOPRIVATESUPERTYPESONLY (any class, as before).
     */
    private static boolean thisTypedBySuperclass(TypeInfo type) {
        ParameterizedType parent = type.parentClass();
        return parent != null && parent.arrays() == 0 && parent.typeInfo() != null
               && (type.access().isPrivate() || PRIVATE_SUPERTYPES_ONLY_OFF.get())
               && parent.parameters().stream().anyMatch(a -> a.typeParameter() == null && a.wildcard() == null);
    }

    private static final Switches.Gate PRIVATE_SUPERTYPES_ONLY_OFF = Switches.gate("NOPRIVATESUPERTYPESONLY");

    /*
     A parameter whose only use is 'this.f = p', where f is nullable: passing null there means what not calling the
     method means, so the parameter takes null. A builder's setter of a field that starts null and is read through
     'getOrDefault(executor, ...)' (langchain4j DefaultRetrievalAugmentorBuilder.executor; its tests call
     executor(null), which no analysed caller does). Nothing downstream changes: the parameter flows into f only.
     Kotlin only (Policy.callResults), where the translated callers include the tests: under the Java policies a
     declared '@NonNull' setter parameter is the API's statement, and the oracles compare against it.
     */
    private Set<Object> storedOnlyIntoNullableFields(List<MethodInfo> methods, Set<Object> reached,
                                                     Map<Object, Object> cause) {
        Set<Object> out = null;
        for (MethodInfo mi : methods) {
            if (mi.isConstructor() || mi.isStatic() || mi.methodBody() == null || mi.methodBody().isEmpty()) continue;
            // after the closure, so nothing carries the change through a hierarchy: an override's parameter must
            // keep its overridden one's state (fernflower SwitchStatement.replaceExprent overrode nothing)
            if (!mi.overrides().isEmpty() || implementations.containsKey(mi)) continue;
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

    /**
     * Writes the declaration verdicts as the B2 properties ({@code NULLABILITY_FIELD}, {@code _PARAMETER},
     * {@code _METHOD}), which the annotation decorator and the printers read. Locals have no property: they are
     * not {@link Info}s; a printer asks the {@link Report}. A declaration that carries a null annotation keeps the
     * value that annotation gave it: a contract wins over inference (M2).
     */
    /*
     Culprits: each field, parameter and method return whose verdict is nullable, and which the closure reached, gets
     NULLABLE_CULPRIT, its chain back to the seed told as the statements that built each edge, the seed's own
     statement last with its origin ("null argument in ..."). An edge built outside the walk (an override, a type
     argument slot) says nothing and is left out; the chain is cut at CULPRIT_STEPS.
     */
    private void writeCulprits(Report report) {
        report.verdicts().forEach((info, pt) -> {
            if (ValueImpl.NullabilityImpl.of(pt).state() != NullableState.NULLABLE) return;
            if (!report.cause().containsKey(info) && !report.seedOrigin().containsKey(info)) return;
            List<Value.Culprit.Step> steps = new ArrayList<>();
            Object at = info;
            Set<Object> seen = new HashSet<>();
            while (report.cause().containsKey(at) && seen.add(at) && steps.size() < CULPRIT_STEPS) {
                Object from = report.cause().get(at);
                Site site = edgeSites.getOrDefault(from, Map.of()).get(at);
                if (site != null) {
                    io.codelaser.maddi.modification.common.util.Culprits.add(steps,
                            io.codelaser.maddi.modification.common.util.Culprits.step(site.in(), site.element(),
                                    culpritText(site.element())));
                }
                at = from;
            }
            String origin = report.seedOrigin().get(at);
            Site seed = seedSites.get(at);
            if (seed != null) {
                io.codelaser.maddi.modification.common.util.Culprits.add(steps,
                        io.codelaser.maddi.modification.common.util.Culprits.step(seed.in(), seed.element(),
                                culpritText(seed.element()) + (origin == null ? "" : " (" + shorten(origin) + ")")));
            } else if (origin != null) {
                steps.add(new Value.Culprit.Step(null, 0, 0, shorten(origin)));
            }
            if (steps.isEmpty()) return;
            ValueImpl.CulpritImpl culprit = new ValueImpl.CulpritImpl(steps);
            if (info.analysis().haveAnalyzedValueFor(PropertyImpl.NULLABLE_CULPRIT)) {
                info.analysis().overwrite(PropertyImpl.NULLABLE_CULPRIT, culprit);
            } else {
                info.analysis().set(PropertyImpl.NULLABLE_CULPRIT, culprit);
            }
        });
    }

    private static String culpritText(Element element) {
        return shorten(element.toString().replaceAll("\\s+", " ").strip());
    }

    private static String shorten(String s) {
        return s.length() <= CULPRIT_TEXT ? s : s.substring(0, CULPRIT_TEXT - 1) + "…";
    }

    public static void write(Report report) {
        report.verdicts().forEach((info, pt) -> {
            Property property = switch (info) {
                case FieldInfo _ -> PropertyImpl.NULLABILITY_FIELD;
                case ParameterInfo _ -> PropertyImpl.NULLABILITY_PARAMETER;
                case MethodInfo _ -> PropertyImpl.NULLABILITY_METHOD;
                default -> null;
            };
            if (property != null && !NullAnnotations.hasNullnessAnnotation(info)) {
                info.analysis().setAllowControlledOverwrite(property, ValueImpl.NullabilityImpl.of(pt));
            }
        });
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
    private boolean overridesKotlinNonNullReturn(MethodInfo mi) {
        if (mi.isConstructor() || mi.returnType().isVoid()) return false;
        for (MethodInfo overridden : overrides(mi)) {
            if (!analysed.contains(overridden) && isTypeVariable(overridden.returnType())
                && KOTLIN_COLLECTIONS.contains(overridden.typeInfo().fullyQualifiedName())
                && stateOf(overridden, PropertyImpl.NULLABILITY_METHOD) != NullableState.NULLABLE) {
                return true;
            }
        }
        return false;
    }

    private static final Set<String> KOTLIN_COLLECTIONS = Set.of("java.lang.Iterable", "java.util.Iterator",
            "java.util.ListIterator", "java.util.Collection", "java.util.List", "java.util.Set", "java.util.Map",
            "java.util.Map.Entry");

    /*
     Kotlin: the analysed overrides of a return the pass holds non-null (nonNullContracts: an annotation, the rules
     above) are non-null too, a null value asserted: an override may not widen its overridden member's return.
     */
    private void nonNullOverrides(List<MethodInfo> methods) {
        java.util.ArrayDeque<MethodInfo> work = new java.util.ArrayDeque<>();
        for (MethodInfo mi : methods) if (nonNullContracts.contains(mi)) work.add(mi);
        while (!work.isEmpty()) {
            MethodInfo m = work.poll();
            for (MethodInfo implementation : implementations.getOrDefault(m, List.of())) {
                if (!implementation.returnType().isVoid() && nonNullContracts.add(implementation)) work.add(implementation);
            }
        }
    }

    /*
     Kotlin: type arguments are invariant, so the type-argument slots of an override's return or parameter equal the
     analysed overridden member's, where both declare the same class ('List<String>' over 'List<E>'): edges both ways
     (guava: AbstractMapBasedMultimap's remove, MutableCollection<V?>? over MutableCollection<V>?).
     */
    private void invariantSlots(ParameterizedType ours, Object node, ParameterizedType theirs, Object theirNode,
                                boolean upward) {
        if (ours == null || theirs == null || ours.arrays() != 0 || theirs.arrays() != 0 || ours.typeInfo() == null
            || ours.typeInfo() != theirs.typeInfo() || ours.parameters().size() != theirs.parameters().size()) return;
        for (int j = 0; j < ours.parameters().size(); j++) {
            if (upward) addEdge(new Arg(node, j), new Arg(theirNode, j));
            addEdge(new Arg(theirNode, j), new Arg(node, j));
        }
    }

    /*
     Kotlin, a test's override of a main member (guava's tests, worktree-maddi-all 2026-10-16: ForwardingDequeTest's
     anonymous Function, DiscreteDomainTest.MyIntegerDomain.next): its parameters, type arguments included, take
     main's states exactly, as an override of a library member does; what the tests pass is asserted at their calls.
     Its return is not clamped: a null it returns widens main's return through the upward edge (buildForMethod).
     */
    private void clampTestOverrides(List<MethodInfo> methods, Map<Info, ParameterizedType> verdicts) {
        for (MethodInfo mi : methods) {
            if (mi.isConstructor() || !testCode(mi)) continue;
            for (MethodInfo overridden : overrides(mi)) {
                if (!analysed.contains(overridden) || testCode(overridden)) continue;
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
    private void clampTypeVariableOverrides(List<MethodInfo> methods, Map<Info, ParameterizedType> verdicts) {
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
                        if (analysed.contains(overridden)) {
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

    private static boolean boundedTypeVariableOverride(MethodInfo mi) {
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
    private NullableState explicitState(Info info) {
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
    private void contract(Info info, Object node) {
        NullableState state = explicitState(info);
        if (state == NullableState.NULLABLE) seed(node, "annotated nullable");
        else if (state == NullableState.NONNULL) nonNullContracts.add(node);
        if (nullableElementsByDeclaration(info)) seed(new Content(node), "annotated nullable elements");
    }

    private ParameterizedType contracted(Info info, ParameterizedType verdict) {
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
    private void elementContract(ParameterizedType declared, Object node) {
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
        if (state == NullableState.NULLABLE) seed(slot, "annotated nullable");
        else if (state == NullableState.NONNULL) nonNullContracts.add(slot);
    }

    private static NullableState explicit(List<io.codelaser.maddi.cst.api.expression.AnnotationExpression> list) {
        boolean nonNull = false;
        for (io.codelaser.maddi.cst.api.expression.AnnotationExpression ae : list) {
            String name = ae.typeInfo().simpleName();
            if (NullAnnotations.NULLABLE.contains(name)) return NullableState.NULLABLE;
            if (NullAnnotations.NON_NULL.contains(name)) nonNull = true;
        }
        return nonNull ? NullableState.NONNULL : null;
    }

    // ------------------------------------------------------------------ library contracts (the analysis hints)

    private static NullableState stateOf(Info info, Property property) {
        return info.analysis().getOrDefault(property, ValueImpl.NullabilityImpl.UNSPECIFIED).state();
    }

    // what a callee promises, for the use-site facts: a library method's hints; an analysed one's annotation
    private NullableState parameterContract(ParameterInfo pi) {
        if (!analysed.contains(pi.methodInfo())) return stateOf(pi, PropertyImpl.NULLABILITY_PARAMETER);
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
    private boolean ownReturnsNonNull(MethodInfo mi) {
        if (!analysed.contains(mi) || mi.methodBody() == null || mi.isAbstract()) return false;
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

    private final Map<MethodInfo, Boolean> ownReturnsNonNull = new HashMap<>();
    // gate NOSUPERCALLS: a call through super returns what the method's override family does (A/B)
    private static final Switches.Gate SUPER_CALLS_OFF = Switches.gate("NOSUPERCALLS");

    private NullableState returnContract(MethodInfo mi) {
        if (!analysed.contains(mi)) {
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

    /**
     * The methods {@code mi} overrides; for a lambda's synthetic method (which overrides nothing in the model) the
     * functional interface's abstract method: a lambda runs where that method is called, and Kotlin types its
     * parameters by it.
     */
    private static Set<MethodInfo> overrides(MethodInfo mi) {
        Set<MethodInfo> overrides = mi.overrides();
        if (!overrides.isEmpty() || mi.isConstructor() || !mi.typeInfo().isAnonymous()
            || mi.typeInfo().interfacesImplemented().size() != 1) {
            return overrides;
        }
        TypeInfo functional = mi.typeInfo().interfacesImplemented().getFirst().typeInfo();
        MethodInfo sam = functional == null ? null : functional.singleAbstractMethod();
        if (sam == null || sam == mi || sam.parameters().size() != mi.parameters().size()) return overrides;
        return Set.of(sam);
    }

    /** A library method, {@code mi} or one it overrides, whose return may be null; null when there is none. */
    private String libraryNullableReturn(MethodInfo mi) {
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
                .filter(m -> !analysed.contains(m))
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
    private boolean overridesLibraryNonNullReturn(MethodInfo mi) {
        if (NEAREST_LIBRARY_RETURN_OFF.get() || mi.isConstructor() || mi.returnType().isVoid()
            || mi.returnType().isPrimitiveExcludingVoid() && mi.returnType().arrays() == 0
            || NullAnnotations.explicitState(mi) == NullableState.NULLABLE) return false;
        return overrides(mi).stream().anyMatch(m -> !analysed.contains(m)
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
    private void libraryNullableContent(Object node, ParameterizedType declared,
                                        java.util.function.Function<MethodInfo, Value.Nullability> nullabilityOf,
                                        java.util.function.Function<MethodInfo, ParameterizedType> typeOf) {
        MethodInfo mi = node instanceof ParameterInfo pi ? pi.methodInfo() : (MethodInfo) node;
        if (declared == null || declared.isVoid() || declared.isPrimitiveExcludingVoid() && declared.arrays() == 0) return;
        for (MethodInfo m : overrides(mi)) {
            if (analysed.contains(m)) continue;
            ParameterizedType theirs = typeOf.apply(m);
            if (theirs == null || theirs.arrays() != declared.arrays() || theirs.typeInfo() != declared.typeInfo()
                || theirs.parameters().size() != declared.parameters().size()) continue;
            List<? extends Value.Nullability> arguments = nullabilityOf.apply(m).arguments();
            for (int j = 0; j < arguments.size(); j++) {
                if (arguments.get(j).state() != NullableState.NULLABLE) continue;
                Object content = declared.arrays() > 0 ? new Content(node) : new Arg(node, j);
                seed(content, "overrides " + m.fullyQualifiedName() + ", whose "
                              + (declared.arrays() > 0 ? "elements" : "type argument " + j) + " may be null");
            }
        }
    }

    /** A library method, {@code mi} or one it overrides, whose parameter {@code index} accepts null. */
    private String libraryNullableParameter(MethodInfo mi, int index) {
        return java.util.stream.Stream.concat(java.util.stream.Stream.of(mi), overrides(mi).stream())
                .filter(m -> !analysed.contains(m) && index < m.parameters().size())
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
    private void libraryParameterContracts() {
        if (LIBRARY_CONTRACTS_OFF.get() || !policy.callResults()) return;
        Set<ParameterInfo> seen = new HashSet<>();
        for (Set<Object> targets : successors.values()) {
            for (Object t : targets) {
                if (t instanceof ParameterInfo pi && !analysed.contains(pi.methodInfo()) && seen.add(pi)
                    && libraryParameterState(pi) == NullableState.NONNULL) {
                    nonNullContracts.add(pi);
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
    private String libraryUnhintedParameter(MethodInfo mi, int index) {
        if (libraryNonNullParameter(mi, index)) return null;
        return mi.overrides().stream()
                .filter(m -> !analysed.contains(m) && index < m.parameters().size())
                .filter(m -> {
                    ParameterizedType type = m.parameters().get(index).parameterizedType();
                    return !isTypeVariable(type) && !(type.isPrimitiveExcludingVoid() && type.arrays() == 0)
                           && libraryParameterState(m.parameters().get(index)) == NullableState.UNSPECIFIED;
                })
                .map(MethodInfo::fullyQualifiedName).findFirst().orElse(null);
    }

    // ------------------------------------------------------------------ tolerated nulls (CodeLaser/maddi-mod#68)

    // gate NOTOLERANT: a visible parameter whose body tolerates null is decided by the flow alone, as before #68
    private static final Switches.Gate TOLERANT_OFF = Switches.gate("NOTOLERANT");
    // the nodes one tolerance search visits at most; beyond that the parameter is not tolerant
    private static final int MAX_TOLERANCE_SEARCH = 2000;

    /*
     CodeLaser/maddi-mod#68: a parameter callable from outside whose body tolerates null on every path is nullable,
     whatever the analysed callers pass. guava marks Preconditions' message arguments, the exception constructors'
     message and cause, and Synchronized's mutex @Nullable because they accept null; no analysed call passes one, so
     the closed world said non-null, and a Kotlin translation would throw at a Java caller guava's API permits.
     Not a type variable (parametric), not a primitive, not a declared non-null contract, not an override of a
     library method declaring the parameter non-null. Not a parameter an overridden method never reads: an empty
     hook (guava's Service.Listener.failed) is no evidence. Not a parameter annotated non-null either, also where the policy
     does not hold the annotation as a contract (withoutContracts: a diagnosis checking the annotation): the author has
     declared it, and a defensive null check under it is no null reaching it (diagnostics' TestNullIntoNonNull).
     Not a parameter an overload shares the position and type of (overloadedAt), nor one of an override of an
     analysed method (overridesAnalysed).
     */
    private void tolerantParameters(List<MethodInfo> methods) {
        // what null reaches without these seeds: a field or a return that is nullable anyway may take the parameter
        Set<Object> before = closure(new HashMap<>());
        for (MethodInfo mi : methods) {
            if (!hasBody(mi) || !externallyCallable(mi)) continue;
            for (ParameterInfo pi : mi.parameters()) {
                ParameterizedType type = pi.parameterizedType();
                if (primitive(type) || isTypeVariable(type) || before.contains(pi) || nonNullContracts.contains(pi)
                    || explicitState(pi) == NullableState.NONNULL || libraryNonNullParameter(mi, pi.index())
                    || hook(mi, pi) || overloadedAt(mi, pi) || overridesAnalysed(mi)) continue;
                if (tolerates(pi, before)) {
                    seedIndirect(pi, mi.fullyQualifiedName() + " tolerates null in " + pi.name()
                                     + ", callable from outside");
                }
            }
        }
    }

    /*
     An overridden method that ignores a parameter is a hook (guava's Service.Listener.failed(from, failure) { }): its
     analysed overrides decide what the parameter needs, and Kotlin wants one nullability along the family ("'failed'
     overrides nothing"). A method without an analysed override that ignores a parameter tolerates null, as #68 has
     it: an overridable one too (guava's StringCatcher.methodWithoutAnnotation(string)).
     */
    private boolean hook(MethodInfo mi, ParameterInfo pi) {
        return implementations.containsKey(mi) && !read(mi, pi);
    }

    /*
     An override of an analysed method takes its parameters' nullability from the family, which Kotlin wants as one: its
     own tolerance would raise the declaration's (guava's ServiceManager listener logs 'failure', which Logger.log
     accepts null for, and Service.Listener.failed's empty hook became 'failure: Throwable?'). The declaration's own
     body decides. An override of a library method only (a Comparator's compare) is #68's case.
     */
    private boolean overridesAnalysed(MethodInfo mi) {
        return mi.overrides().stream().anyMatch(analysed::contains);
    }

    /*
     An overload of the method (same name, a method or a constructor as it is) has a parameter of the same type at the
     same position. Java picks between them by arity or varargs; Kotlin first by the most specific parameter types,
     where String is more specific than String?: langchain4j's ensureNotNull(object, String name) widened to
     'name: String?', kotlinc resolves ensureNotNull(text, "text") to ensureNotNull(object, String format, Object...
     args), and the message is "text", not "text cannot be null". Only an overload that takes the same arguments
     counts: the same parameter types followed by one varargs parameter. Overloads of one arity that differ elsewhere
     (Preconditions' checkArgument(boolean,String,Object,char) and (boolean,String,char,Object)) or in arity
     (an exception's (String) and (String,Throwable)) are told apart by those arguments in Kotlin too.
     */
    private static boolean overloadedAt(MethodInfo mi, ParameterInfo pi) {
        List<ParameterInfo> parameters = mi.parameters();
        int n = parameters.size();
        if (pi.isVarArgs()) return false;
        return mi.typeInfo().constructorAndMethodStream()
                .filter(m -> m != mi && m.isConstructor() == mi.isConstructor() && m.name().equals(mi.name())
                             && m.parameters().size() == n + 1 && m.parameters().get(n).isVarArgs())
                .anyMatch(m -> IntStream.range(0, n).allMatch(i -> sameErasure(
                        parameters.get(i).parameterizedType(), m.parameters().get(i).parameterizedType())));
    }

    /*
     Each overload has its own type parameters: ensureNotNull's <T> T(T object, String name) and <T> T(T object, String
     format, Object... args) agree at position 0 by their erasure, Object, not by T == T.
     */
    private static boolean sameErasure(ParameterizedType a, ParameterizedType b) {
        if (a.arrays() != b.arrays()) return false;
        String ea = erasure(a);
        return ea != null && ea.equals(erasure(b));
    }

    private static String erasure(ParameterizedType t) {
        if (t.typeParameter() != null) {
            List<ParameterizedType> bounds = t.typeParameter().typeBounds();
            return bounds.isEmpty() ? "java.lang.Object" : erasure(bounds.getFirst());
        }
        return t.typeInfo() == null ? null : t.typeInfo().fullyQualifiedName();
    }

    // the body reads the parameter
    private static boolean read(MethodInfo mi, ParameterInfo pi) {
        boolean[] found = {false};
        mi.methodBody().visit(e -> {
            if (e instanceof VariableExpression ve && pi.equals(ve.variable())) found[0] = true;
            return !found[0];
        });
        return found[0];
    }

    /*
     No path requires the parameter non-null (NonNullFacts.demands), nor any local or parameter it flows into: along
     the null edges, into the analysed callees it is passed to (a callee's tolerant parameter tolerates; checkArgument
     hands its message arguments to lenientFormat), and both ways along the override chain. A field or return it flows
     into must be nullable anyway; a non-null contract, or a method without a body, ends the tolerance.
     */
    private boolean tolerates(ParameterInfo start, Set<Object> before) {
        Set<Object> seen = new HashSet<>();
        Deque<Object> queue = new ArrayDeque<>();
        seen.add(start);
        queue.add(start);
        while (!queue.isEmpty()) {
            Object n = queue.removeFirst();
            if (seen.size() > MAX_TOLERANCE_SEARCH || nonNullContracts.contains(n)) return false;
            switch (n) {
                case ParameterInfo pi -> {
                    MethodInfo m = pi.methodInfo();
                    if (!hasBody(m) || demanded(m, v -> pi.equals(v))) return false;
                }
                case Local local -> {
                    // by name, as NonNullFacts compares locals; a reassigned parameter's shadow is the parameter
                    if (demanded(local.methodInfo(), v -> (v instanceof LocalVariable || v instanceof ParameterInfo)
                                                          && v.simpleName().equals(local.name()))) return false;
                }
                case FieldInfo _, MethodInfo _ -> {
                    if (!before.contains(n)) return false;
                    continue; // nullable anyway: what it flows into is decided without this parameter
                }
                case Content _, Arg _ when declaredSlot(n) && !TOLERANT_SLOTS_OFF.get() -> {
                    if (!before.contains(n)) return false;
                    continue;
                }
                default -> {
                }
            }
            for (Object s : successors.getOrDefault(n, Set.of())) {
                if (seen.add(s)) queue.addLast(s);
            }
        }
        return true;
    }

    /*
     An element slot of a field, of a return or of a class's own supertype is part of that declaration, as the field
     itself is: 'public void add(String item) { items.add(item); }' does not tolerate null in item when 'items' is a
     List<String> nothing writes null into; the null would make it a List<String?>. A parameter's or a local's slot
     is followed on (checkArgument's message arguments go into lenientFormat's varargs). Gate NOTOLERANTSLOTS: every
     slot followed on, as before (the maddi-java-demo's public adders and setters all came out nullable, 2026-10-27).
     */
    private static boolean declaredSlot(Object node) {
        Object holder = node;
        while (true) {
            if (holder instanceof Content c) holder = c.of();
            else if (holder instanceof Arg a) holder = a.of();
            else break;
        }
        return holder instanceof FieldInfo || holder instanceof MethodInfo || holder instanceof AnonymousThis;
    }

    private static final Switches.Gate TOLERANT_SLOTS_OFF = Switches.gate("NOTOLERANTSLOTS");

    // a demand in m's body, or in the body of the method that encloses m's lambda or local class
    private boolean demanded(MethodInfo m, java.util.function.Predicate<Variable> variable) {
        for (MethodInfo mi = m; mi != null; mi = mi.typeInfo().enclosingMethod()) {
            if (facts.demands(mi).stream().anyMatch(variable)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ outside callers (World.OPEN*)

    /*
     The parameters an outside caller may pass anything to, and what they flow into, along the same edges as null.
     Only the unreached ones matter: a reached node is nullable anyway.
     */
    private Set<Object> externalClosure(List<MethodInfo> methods) {
        Set<Object> set = new LinkedHashSet<>();
        for (MethodInfo mi : methods) {
            if (!externallyCallable(mi)) continue;
            for (ParameterInfo pi : mi.parameters()) {
                if (reached.contains(pi) || nonNullContracts.contains(pi)
                    || policy.world() == World.OPEN && facts.nonNullAtExit(mi, pi)
                    || libraryNonNullParameter(mi, pi.index())) {
                    addContent(set, pi, pi.parameterizedType());
                    continue;
                }
                set.add(pi);
                addContent(set, pi, pi.parameterizedType());
            }
        }
        Deque<Object> queue = new ArrayDeque<>(set);
        while (!queue.isEmpty()) {
            Object n = queue.removeFirst();
            for (Object s : successors.getOrDefault(n, Set.of())) {
                if (!nonNullContracts.contains(s) && !reached.contains(s) && set.add(s)) queue.add(s);
            }
        }
        return set;
    }

    // an outside caller chooses the elements of an array it passes, whatever it does with the array itself
    private void addContent(Set<Object> set, Object node, ParameterizedType type) {
        if (type.arrays() > 0) {
            Content content = new Content(node);
            if (!reached.contains(content) && !nonNullContracts.contains(content)) set.add(content);
            addContent(set, content, type.componentType());
            return;
        }
        for (int i = 0; i < type.parameters().size(); i++) {
            Arg arg = new Arg(node, i);
            if (!reached.contains(arg) && !nonNullContracts.contains(arg)) set.add(arg);
            addContent(set, arg, type.parameters().get(i));
        }
    }

    private boolean externallyCallable(MethodInfo mi) {
        if (visibleOutside(mi)) return true;
        // an override is called through what it overrides: from outside, or by the library itself
        return overrides(mi).stream().anyMatch(o -> !analysed.contains(o) || visibleOutside(o));
    }

    private static boolean visibleOutside(MethodInfo mi) {
        TypeInfo owner = mi.typeInfo();
        boolean access = mi.access().isPublic() || mi.access().isProtected() && extensible(owner);
        return access && visibleOutside(owner);
    }

    private static boolean visibleOutside(TypeInfo ti) {
        if (ti.isAnonymous()) return false;
        if (ti.compilationUnitOrEnclosingType().isLeft()) return ti.access().isPublic();
        TypeInfo enclosing = ti.compilationUnitOrEnclosingType().getRight();
        boolean access = ti.access().isPublic() || ti.access().isProtected() && extensible(enclosing);
        return access && visibleOutside(enclosing);
    }

    private static boolean extensible(TypeInfo ti) {
        return !ti.isFinal() && !ti.isSealed() && !ti.typeNature().isEnum() && !ti.typeNature().isRecord();
    }

    /** A library method {@code mi} overrides declares parameter {@code index} non-null: its callers honour that. */
    private boolean libraryNonNullParameter(MethodInfo mi, int index) {
        return overrides(mi).stream()
                .filter(m -> !analysed.contains(m) && index < m.parameters().size())
                .anyMatch(m -> libraryParameterState(m.parameters().get(index)) == NullableState.NONNULL);
    }

    /*
     The verdict of a node of type 'declared': reached is NULLABLE; a degraded output, one an outside caller reaches
     (World.OPEN*), and a type variable are UNSPECIFIED; else Policy.unreached. An array's elements are the node's
     Content, recursively.
     */
    private ParameterizedType verdict(ParameterizedType declared, Object node, boolean degradedOutput) {
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
                : pt.withParameters(pt.parameters().stream().map(NullabilityPass::unspecified).toList());
        return arguments.withNullable(NullableState.UNSPECIFIED);
    }

    // ------------------------------------------------------------------ graph

    private void addEdge(Object from, Object to) {
        if (from == null || to == null || from.equals(to)) return;
        if (!policy.testNulls() && testCode(from) && !testCode(to)) return;
        // Kotlin: a primitive value carries no null on: unboxing into it already threw. guava's
        // 'Boolean apply(T t) { return predicate.apply(t); }' over 'boolean Predicate.apply' was 'Boolean?' through a
        // test's MockEquivalence.doEquivalent and the boolean equivalent()/doEquivalent() family (Functions.kt:103,
        // 2026-10-18). Gate NOPRIMITIVECUTS.
        if (policy.callResults() && !PRIMITIVE_CUTS_OFF.get() && !(from instanceof Content) && primitive(typeOf(from))) return;
        flows.add(List.of(from, to));
        if (recordSites && currentSite != null) {
            edgeSites.computeIfAbsent(from, _ -> new HashMap<>()).putIfAbsent(to, new Site(currentSiteMethod, currentSite));
        }
        if (successors.computeIfAbsent(from, _ -> new LinkedHashSet<>()).add(to) && to instanceof Content c
            && why(c.of())) {
            whyLog(from instanceof Content ? "content edge" : "element write", c.of(),
                    "from " + whyName(from) + " (" + from.getClass().getSimpleName() + ")");
        }
    }

    private static boolean primitive(ParameterizedType type) {
        return type != null && type.arrays() == 0 && type.isPrimitiveExcludingVoid();
    }

    private static final Switches.Gate PRIMITIVE_CUTS_OFF = Switches.gate("NOPRIMITIVECUTS");

    private void seed(Object node, String origin) {
        seed(node, origin, null);
    }

    // writer: the type whose code writes the null; null when the seed has none (an annotation, a contract)
    private void seed(Object node, String origin, TypeInfo writer) {
        if (origin.startsWith(NULL_ARGUMENT) && nullRejectingBodies.contains(origin.substring(NULL_ARGUMENT.length()))) {
            return;
        }
        if (node != null) {
            if (why(node)) whyLog("seed", node, origin);
            seedOrigin.putIfAbsent(node, origin);
            if (recordSites && currentSite != null) seedSites.putIfAbsent(node, new Site(currentSiteMethod, currentSite));
            strictSeeds.add(node);
            seedWriters.computeIfAbsent(node, _ -> new ArrayList<>()).add(writer);
        }
    }

    private void seedIndirect(Object node, String origin) {
        seedIndirect(node, origin, null);
    }

    // indirect evidence (Policy.assertContentWrites): a library contract, a field's default value (the Kotlin
    // reading is a 'lateinit' field), a comparison with null (the nullTests heuristic). Not a null that is written.
    private void seedIndirect(Object node, String origin, TypeInfo writer) {
        if (node != null) {
            seedOrigin.putIfAbsent(node, origin);
            if (recordSites && currentSite != null) seedSites.putIfAbsent(node, new Site(currentSiteMethod, currentSite));
            seedWriters.computeIfAbsent(node, _ -> new ArrayList<>()).add(writer);
        }
    }

    /*
     Policy.withoutTestNulls: whether a node of the graph belongs to test code, by the source set of the type that
     declares it. A node without a type (a library's, the null markers' own) is not test code.
     */
    private static boolean testCode(Object node) {
        if (node instanceof Content c) return testCode(c.of());
        if (node instanceof Arg a) return testCode(a.of());
        TypeInfo type = switch (node) {
            case TypeInfo t -> t;
            case ParameterInfo pi -> pi.methodInfo().typeInfo();
            case MethodInfo mi -> mi.typeInfo();
            case FieldInfo fi -> fi.owner();
            case Local l -> l.methodInfo() == null ? null : l.methodInfo().typeInfo();
            case null, default -> null;
        };
        if (type == null) return false;
        var cu = type.primaryType().compilationUnit();
        return cu != null && cu.sourceSet() != null && cu.sourceSet().test();
    }

    private static TypeInfo writer(MethodInfo mi) {
        return mi == null ? null : mi.typeInfo();
    }

    private Set<Object> closure(Map<Object, Object> cause) {
        if (!policy.assertContentWrites()) {
            // the strict closure only for the Report (CodeLaser/maddi-mod#49): the verdicts are the full closure's
            writtenReach = Set.copyOf(closure(new HashMap<>(), strictSeeds, null));
            return closure(cause, seedOrigin.keySet(), null);
        }
        // Policy.assertContentWrites: first what null reaches without the library contracts; a value outside it
        // enters a slot only as an asserted write, so that edge is not followed, and a library seed on a slot itself
        // is an asserted write too
        Map<Object, Object> strictCause = new HashMap<>();
        Set<Object> strict = closure(strictCause, strictSeeds, null);
        writtenReach = Set.copyOf(strict);
        Set<Object> seeds = new LinkedHashSet<>(seedOrigin.keySet());
        seeds.removeIf(n -> isSlot(n) && !strictSeeds.contains(n));
        Set<Object> reached = closure(cause, seeds, strict);
        // explain a node null reaches by a written null with that chain: it is the one that lets the node's value
        // into a slot, where the first chain found may begin at a library contract or a default value
        strict.forEach(n -> {
            if (strictSeeds.contains(n)) cause.remove(n);
            else if (strictCause.containsKey(n)) cause.put(n, strictCause.get(n));
        });
        return reached;
    }

    /*
     The value an Optional holds is never null: 'Optional.ofNullable(x)' is empty for a null x, 'Optional.of(null)'
     throws. So its type argument stays non-null, as the interfaces declare it (langchain4j
     DefaultAiServiceStartedEvent.systemMessage(): 'Optional.ofNullable(this.systemMessage)' of a @Nullable field
     is an Optional<SystemMessage>, and Kotlin refused the override returning Optional<SystemMessage?>).
     */
    private boolean optionalValue(Object node) {
        if (!(node instanceof Arg arg) || arg.index() != 0) return false;
        ParameterizedType type = typeOf(arg.of());
        return type != null && type.arrays() == 0 && type.typeInfo() != null
               && "java.util.Optional".equals(type.typeInfo().fullyQualifiedName());
    }

    private static boolean isSlot(Object node) {
        return node instanceof Arg || node instanceof Content || node instanceof Keyed;
    }

    /*
     The elements of a varargs parameter are not stored content: the call builds the array from its arguments, so an
     argument nullable only through indirect evidence ('if (i == null || i < 0) throw illegalArgument("%s", i)')
     still makes them nullable. Asserting at the call instead ('i!!') would throw where Java formats "null"
     (langchain4j Exceptions.illegalArgument(format, vararg args: Any?)).
     */
    private static boolean isVarargElements(Object node) {
        return node instanceof Content c && c.of() instanceof ParameterInfo pi && pi.isVarArgs();
    }

    /*
     Kotlin: the slot of a type parameter bounded by something other than Object ('Range<C extends Comparable>',
     'EnumSet<E extends Enum<E>>') cannot hold a nullable argument, so null stops there as at a non-null contract; a
     write of a value that may be null is asserted (guava, worktree-maddi-all 2026-10-16: 'DiscreteDomain<C?>',
     'TreeRangeMap<K?, ...>', 'EnumSet<E?>', most of 129 "type argument is not within its bounds").
     */
    private boolean boundedSlot(Object node) {
        if (!policy.callResults() || !(node instanceof Arg a)) return false;
        Boolean known = boundedSlots.get(node);
        if (known != null) return known;
        ParameterizedType outer = typeOf(a.of());
        TypeInfo ti = outer == null || outer.arrays() > 0 ? null : outer.typeInfo();
        boolean bounded = !BOUNDED_SLOTS_OFF.get() && ti != null && a.index() < ti.typeParameters().size()
                          && ti.typeParameters().get(a.index()).typeBounds().stream()
                                  .anyMatch(b -> b.typeParameter() == null && b.typeInfo() != null
                                                 && !b.typeInfo().isJavaLangObject())
                          // a slot holding a type variable stays that variable ('Converter<A, B>', never
                          // 'Converter<A?, B>'): it already ranges over nullable instantiations, and Kotlin's
                          // invariant generics make the two incompatible (guava's Converter, Maps, TreeRangeSet, ...)
                          || !TYPE_VARIABLE_SLOTS_OFF.get() && outer != null && a.index() < outer.parameters().size()
                             && isTypeVariable(outer.parameters().get(a.index()))
                             && !typeVariableElementSlots.contains(a)
                          || erasedSingleton(a, outer)
                          || nonNullSlot(a)
                          || tiedToNonNullSlots().contains(a);
        boundedSlots.put(node, bounded);
        return bounded;
    }

    // the slot of a null-marked type parameter, or one holding a type variable the null-marked bounds hold non-null
    private boolean nonNullSlot(Arg a) {
        if (nonNullTypeParameters.isEmpty()) return false;
        ParameterizedType outer = typeOf(a.of());
        if (outer == null || outer.arrays() > 0) return false;
        TypeInfo ti = outer.typeInfo();
        if (ti != null && a.index() < ti.typeParameters().size()
            && nonNullTypeParameters.contains(ti.typeParameters().get(a.index()))) return true;
        if (a.index() >= outer.parameters().size()) return false;
        ParameterizedType p = outer.parameters().get(a.index());
        return p.typeParameter() != null && (p.wildcard() == null || !p.wildcard().isSuper())
               && nonNullTypeParameters.contains(p.typeParameter());
    }

    /*
     Kotlin's generics are invariant, and an 'out' projection of a non-null bound is non-null too: a container given
     to a null-marked slot ('LinkedHashMultiset<Object> multiset' given to '<E : Any> copyOf(Iterable<out E>)')
     is bounded as well, so null stops there and its writes are asserted ('multiset.add(elements[i]!!, ...)').
     ImmutableMultiset.kt:182 had 'LinkedHashMultiset<Any?>' (2026-10-19). Not into a '? super' slot. Computed once,
     when the graph is complete. Gate NOTIEDBOUNDS.
     */
    private Set<Arg> tiedToNonNullSlots() {
        if (tiedToNonNull != null) return tiedToNonNull;
        tiedToNonNull = new HashSet<>();
        if (TIED_BOUNDS_OFF.get() || nonNullTypeParameters.isEmpty()) return tiedToNonNull;
        List<String> why = NULL_MARKED_DEBUG == null ? null : new ArrayList<>();
        // the container itself given to a null-marked slot that the graph does not tie, a method type variable's:
        // 'copyOf(multiset)'. Not along the slot edges: those carry values ('accessQueue.add(table.get(i))'), which
        // Kotlin asserts at the write, and had made Segment.table's elements non-null through
        // ImmutableList.copyOf(accessQueue), where Segment.clear() writes null (guava, 2026-10-19)
        for (List<Object> flow : flows) {
            ParameterizedType t0 = typeOf(flow.get(0));
            ParameterizedType t1 = typeOf(flow.get(1));
            if (t0 == null || t1 == null || t0.arrays() > 0 || t1.arrays() > 0
                || t0.parameters().size() != t1.parameters().size() || fedByUnboundWildcard(flow.get(0))) continue;
            for (int i = 0; i < t0.parameters().size(); i++) {
                Arg from = new Arg(flow.get(0), i);
                if (nonNullSlot(new Arg(flow.get(1), i)) && tiedToNonNull.add(from) && why != null) {
                    why.add(from + " <- " + flow.get(1));
                }
            }
        }
        if (why != null) {
            try {
                java.nio.file.Files.write(java.nio.file.Path.of(NULL_MARKED_DEBUG + ".tied"), why);
            } catch (java.io.IOException ignored) {
            }
        }
        return tiedToNonNull;
    }

    /*
     a local made from a '?' argument ('List<Object> result = newArrayList(keys)' of an 'Iterable<? extends Object>'):
     Kotlin infers its initializer's elements as 'Any?' from the '*', so it cannot be declared non-null. The model
     does not tell '? extends Object' from '?' (Striped.kt:34, 2026-10-19)
     */
    private static boolean fedByUnboundWildcard(Object node) {
        if (!(node instanceof Local l) || !(l.declaration() instanceof LocalVariableCreation lvc)) return false;
        return lvc.localVariableStream().filter(lv -> lv.simpleName().equals(l.name()))
                .map(lv -> lv.assignmentExpression() == null ? null : NonNullFacts.unwrap(lv.assignmentExpression()))
                .anyMatch(init -> {
                    List<Expression> args = switch (init) {
                        case MethodCall mc -> mc.parameterExpressions();
                        case ConstructorCall cc -> cc.parameterExpressions();
                        case null, default -> List.of();
                    };
                    return args.stream().map(Expression::parameterizedType).anyMatch(t -> t != null
                            && t.parameters().stream().anyMatch(p -> p.wildcard() != null && p.wildcard().isUnbound()));
                });
    }

    private Set<Arg> tiedToNonNull;
    private static final Switches.Gate TIED_BOUNDS_OFF = Switches.gate("NOTIEDBOUNDS");

    /*
     Kotlin: a static final singleton with Object type arguments ('RegularImmutableMap.EMPTY', an
     ImmutableMap<Object, Object>) is cast to each instantiation at its use; its slots hold nothing. Tied to every
     Object-keyed map that the links see as it, it carried a test's null ('read from *' in
     ImmutableMapFloodingTest) into ImmutableListMultimap.readObject's tmpMap (guava 2026-10-17, after 594f20b7).
     */
    private static boolean erasedSingleton(Arg a, ParameterizedType outer) {
        return !ERASED_SINGLETONS_OFF.get() && a.of() instanceof FieldInfo fi && fi.isStatic() && fi.isFinal()
               && outer != null && outer.arrays() == 0 && !outer.parameters().isEmpty()
               && outer.parameters().stream().allMatch(p -> p.wildcard() == null && p.typeParameter() == null
                                                            && p.arrays() == 0 && p.typeInfo() != null
                                                            && p.typeInfo().isJavaLangObject());
    }

    // gate NOERASEDSINGLETONS: an Object-typed singleton's slots take null as any other (A/B)
    private static final Switches.Gate ERASED_SINGLETONS_OFF = Switches.gate("NOERASEDSINGLETONS");

    private final Map<Object, Boolean> boundedSlots = new HashMap<>();

    /*
     Kotlin: a class type parameter of a @NullMarked class without a @Nullable bound ('class LocalCache<K, V>' in
     guava) is non-null: the printer bounds it by Any (NullabilityFacts.nonNullTypeParameter), and null stops at its
     slots as at a bounded one, so no verdict instantiates it as 'X?'. Carried over to a subclass's type parameter
     passed into such a position ('X<E> extends ImmutableList<E>' in a test outside the null-marked scope), which Kotlin
     would otherwise reject, and to any type variable used in such a position. guava LocalCache.kt:1102/1020,
     CollectCollectors.kt:24 (2026-10-18). Gate NONULLMARKEDBOUNDS.
     */
    private final Set<io.codelaser.maddi.cst.api.info.TypeParameter> nonNullTypeParameters = new HashSet<>();

    private static final Switches.Gate NULL_MARKED_BOUNDS_OFF = Switches.gate("NONULLMARKEDBOUNDS");
    private static final Switches.Gate MEMBERLESS_BOUNDS_OFF = Switches.gate("NOMEMBERLESSBOUNDS");
    private static final Switches.Gate BOUND_USES_OFF = Switches.gate("NOBOUNDUSES");
    private static final Switches.Gate REJECTED_FLOWS_OFF = Switches.gate("NOREJECTEDFLOWS");

    private void nonNullBounds(List<Info> analysisOrder, List<MethodInfo> methods, List<FieldInfo> fields) {
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
                if (om.typeParameters().size() != n || !analysed.contains(om)) continue;
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
                    : analysed.contains(tp.getOwner().getRight());
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
                    : analysed.contains(tp.getOwner().getRight());
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
    private static final String NULL_MARKED_DEBUG = System.getenv("NULLMARKEDDEBUG");
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
    // slots holding a type variable that a 'T[]' of the same method fills (arrayElementsPassedThrough): 'List<T?>'
    // where the array is an 'Array<T?>'
    private final Set<Arg> typeVariableElementSlots = new HashSet<>();
    // gate NOBOUNDEDSLOTS: a bounded type parameter's slot takes null as any other (A/B)
    private static final Switches.Gate BOUNDED_SLOTS_OFF = Switches.gate("NOBOUNDEDSLOTS");
    // gate NOWILDCARDSLOTS: a slot flows back out of an unbound '?' parameter (A/B)
    private static final Switches.Gate WILDCARD_SLOTS_OFF = Switches.gate("NOWILDCARDSLOTS");
    // gate NOUNCHECKEDSLOTS: slots of different concrete type arguments are tied as any other (A/B)
    private static final Switches.Gate UNCHECKED_SLOTS_OFF = Switches.gate("NOUNCHECKEDSLOTS");
    // gate NOTYPEVARSLOTS: a slot holding a type variable takes null as any other (A/B)
    private static final Switches.Gate TYPE_VARIABLE_SLOTS_OFF = Switches.gate("NOTYPEVARSLOTS");

    // strict: when not null, an edge from a value into a slot is followed only from a node in it
    private Set<Object> closure(Map<Object, Object> cause, Set<Object> seeds, Set<Object> strict) {
        // a seed on a non-null contract is the caller's error (an M5 finding), not a source of null
        Set<Object> reached = new LinkedHashSet<>(seeds);
        reached.removeAll(nonNullContracts);
        reached.removeIf(this::optionalValue);
        reached.removeIf(this::boundedSlot);
        Deque<Object> queue = new ArrayDeque<>(reached);
        while (true) {
            while (!queue.isEmpty()) {
                Object n = queue.removeFirst();
                for (Object s : successors.getOrDefault(n, Set.of())) {
                    if (strict != null && isSlot(s) && !isSlot(n) && !strict.contains(n) && !isVarargElements(s)) continue;
                    if (optionalValue(s) || boundedSlot(s)) continue;
                    if (!nonNullContracts.contains(s) && reached.add(s)) {
                        cause.put(s, n);
                        queue.addLast(s);
                    }
                }
            }
            // gap 9: the gated call results, once nothing else moves; monotone, so repeated until none opens
            Map<MethodInfo, Boolean> intrinsic = new HashMap<>();
            for (GatedSite site : gatedSites) {
                if (!reached.contains(site.callee()) || reached.contains(site.target())
                    || nonNullContracts.contains(site.target())
                    || !callResultNullable(site.callee(), site.call(), reached, reached::contains, intrinsic)) continue;
                reached.add(site.target());
                cause.put(site.target(), site.callee());
                queue.addLast(site.target());
            }
            if (queue.isEmpty()) return reached;
        }
    }

    /*
     Gap 9. Whether the result of call 'call' to 'callee' may be null, the callee's return being reached: by itself
     (reached without passing through one of its own parameters), or through a parameter whose argument at this call is
     (its SiteArg reached). SearchableProperties.getInteger(key, defaultValue) is 'search(key).orElse(defaultValue)', and
     getInteger(key) calls getInteger(key, null): only that call's result is nullable, not 'getInteger(KEY, 5)'.
     */
    private boolean callResultNullable(MethodInfo callee, int call, Set<Object> reached,
                                       java.util.function.Predicate<Object> isReached,
                                       Map<MethodInfo, Boolean> intrinsicMemo) {
        if (GAP9_OFF.get() || !argumentsSeen.contains(call)) return true;
        Set<ParameterInfo> through = passThrough(callee);
        if (through.isEmpty()) return true;
        if (intrinsicMemo.computeIfAbsent(callee, m -> reachedWithout(m, through, reached))) return true;
        for (ParameterInfo pi : through) {
            if (isReached.test(new SiteArg(call, pi.index()))) return true;
        }
        return false;
    }

    /*
     Gap 7, for the facts the report hands out: a value no null of the analysis reaches, once the closure is known. A
     call to an analysed method whose return null does not reach (at this call: gap 9), a library call without a
     nullable contract (as the pass treats it everywhere: the same as a local with that one assignment), a parameter or
     a field null does not reach. A local is not looked up here: its node needs the scope of the read.
     */
    private boolean notNullable(Expression e, Set<Expression> nonNullCallResults) {
        Expression x = NonNullFacts.unwrap(e);
        if (x instanceof MethodCall mc && mc.methodInfo() != null) {
            MethodInfo callee = mc.methodInfo();
            if (analysed.contains(callee)) return !reached.contains(callee) || nonNullCallResults.contains(mc);
            return libraryNullableCall(mc) == null;
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

    private Map<Object, Set<Object>> predecessors;
    private int predecessorsOf = -1;
    private final Map<MethodInfo, Set<ParameterInfo>> passThroughMemo = new HashMap<>();

    private Map<Object, Set<Object>> predecessors() {
        int size = flows.size() + gatedSites.size();
        if (predecessors == null || predecessorsOf != size) {
            predecessors = new HashMap<>();
            successors.forEach((from, tos) -> tos.forEach(to ->
                    predecessors.computeIfAbsent(to, _ -> new HashSet<>()).add(from)));
            // a gated result's predecessor is its callee: over-approximates "by itself" (conservative)
            gatedSites.forEach(g -> predecessors.computeIfAbsent(g.target(), _ -> new HashSet<>()).add(g.callee()));
            predecessorsOf = size;
            passThroughMemo.clear();
        }
        return predecessors;
    }

    /* the callee's own (non-varargs) parameters from which the graph reaches its return value */
    private Set<ParameterInfo> passThrough(MethodInfo callee) {
        Map<Object, Set<Object>> pred = predecessors();
        return passThroughMemo.computeIfAbsent(callee, m -> {
            Set<ParameterInfo> out = new HashSet<>();
            Set<Object> seen = new HashSet<>();
            Deque<Object> todo = new ArrayDeque<>(List.of(m));
            while (!todo.isEmpty()) {
                Object n = todo.removeFirst();
                if (!seen.add(n)) continue;
                if (n instanceof ParameterInfo pi && pi.methodInfo() == m && !pi.isVarArgs()) {
                    out.add(pi);
                    continue;
                }
                todo.addAll(pred.getOrDefault(n, Set.of()));
            }
            return out;
        });
    }

    /* whether null reaches 'callee' backwards from a seed, without passing through one of 'through' */
    private boolean reachedWithout(MethodInfo callee, Set<ParameterInfo> through, Set<Object> reached) {
        Map<Object, Set<Object>> pred = predecessors();
        Set<Object> seen = new HashSet<>();
        Deque<Object> todo = new ArrayDeque<>(List.of(callee));
        while (!todo.isEmpty()) {
            Object n = todo.removeFirst();
            if (!seen.add(n) || through.contains(n) || !reached.contains(n)) continue;
            if (seedOrigin.containsKey(n)) return true;
            todo.addAll(pred.getOrDefault(n, Set.of()));
        }
        return false;
    }

    private static boolean isTypeVariable(ParameterizedType pt) {
        return pt.typeParameter() != null && pt.arrays() == 0;
    }

    /**
     * A local variable as a graph node, keyed by its DECLARATION (by identity) and name: Java forbids a local to
     * shadow a local, so in scope a name denotes one declaration, but same-named locals of sibling blocks or of
     * different methods are different variables. The method is for display; a key without a declaration (a pattern
     * variable, a name no scope resolved) is per method and name.
     */
    /** The elements of an array node (a field, parameter, return, local, or the elements of an outer array). */
    public record Content(Object of) {
        @Override
        public String toString() {
            return "elements of " + Report.label(of);
        }
    }

    /** What argument {@code index} of call {@code call} carries (gap 9): a node with no successors. */
    record SiteArg(int call, int index) {
        @Override
        public String toString() {
            return "argument " + index + " of call #" + call;
        }
    }

    /** {@code callee -> target}, followed only where that call's own arguments let the null through (gap 9). */
    record GatedSite(MethodInfo callee, Object target, int call) {
    }

    /** Type argument {@code index} of a node's declared type: the elements of a {@code List<String>}, a map's values. */
    public record Arg(Object of, int index) {
        @Override
        public String toString() {
            return "type argument " + index + " of " + Report.label(of);
        }
    }

    // the declared type of a node; null when unknown (a local the pass did not see declared)
    private ParameterizedType typeOf(Object node) {
        return switch (node) {
            case Arg a -> {
                ParameterizedType outer = typeOf(a.of());
                yield outer == null || outer.arrays() > 0 || a.index() >= outer.parameters().size() ? null
                        : outer.parameters().get(a.index());
            }
            case Keyed k -> typeOf(k.slot());
            case AnonymousThis at -> at.type().parentClass();
            case CallResult cr -> cr.call().concreteReturnType();
            case ParameterInfo pi -> pi.parameterizedType();
            case FieldInfo fi -> fi.type();
            case MethodInfo mi -> mi.returnType();
            case Local local -> declaredLocals.containsKey(local) ? declaredLocals.get(local).parameterizedType()
                    : shadows.containsKey(local) ? shadows.get(local).parameterizedType() : null;
            case Content c -> {
                ParameterizedType outer = typeOf(c.of());
                yield outer == null || outer.arrays() == 0 ? null : outer.componentType();
            }
            default -> null;
        };
    }

    /*
     A null literal given to a call in a field initializer ('private final RecAbilityContext recAbilityContext = new
     RecAbilityContext(null)'): the initializers are not method bodies, so the call sites never saw it (nacos
     GrpcClient.java:91, 2026-10-19). Gate NOFIELDINITNULLS.
     */
    private void fieldInitializerNullArguments(List<FieldInfo> fields) {
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
                    case ConstructorCall cc when anonymous -> anonymousSuperConstructor(cc);
                    case ConstructorCall cc -> cc.constructor();
                    default -> null;
                };
                if (anonymous && callee == null) return false;
                if (callee == null || !analysed.contains(callee)) return true;
                List<Expression> args = e instanceof MethodCall mc ? mc.parameterExpressions()
                        : ((ConstructorCall) e).parameterExpressions();
                for (int i = 0; i < args.size() && i < callee.parameters().size(); i++) {
                    ParameterInfo pi = callee.parameters().get(i);
                    // not a type-variable parameter: the null instantiates the variable for that call, as at any call
                    // site ('static final ImmediateFuture<?> NULL = new ImmediateFuture<>(null)', guava)
                    if (!pi.isVarArgs() && !isTypeVariable(pi.parameterizedType())
                        && NonNullFacts.unwrap(args.get(i)) instanceof NullConstant) {
                        seed(pi, NULL_ARGUMENT + fi.fullyQualifiedName(), fi.owner());
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
    private void assignedParameterSlots(List<MethodInfo> methods) {
        if (ASSIGNED_SLOTS_OFF.get() || !policy.callResults()) return;
        for (MethodInfo mi : methods) {
            if (mi.methodBody() == null) continue;
            mi.methodBody().visit(e -> {
                if (e instanceof Lambda || e instanceof ConstructorCall cc && cc.anonymousClass() != null) return false;
                if (e instanceof Assignment a && a.assignmentOperator() == null
                    && a.variableTarget() instanceof FieldReference fr && (fr.scopeIsThis() || fr.fieldInfo().isStatic())
                    && NonNullFacts.unwrap(a.value()) instanceof VariableExpression ve
                    && ve.variable() instanceof ParameterInfo pi && !reassigned.contains(pi)
                    && pi.methodInfo() == mi && !pi.parameterizedType().parameters().isEmpty()
                    && pi.parameterizedType().arrays() == 0) {
                    flows.add(List.of(pi, fr.fieldInfo()));
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
    private void enumValueOfContracts(List<MethodInfo> methods) {
        if (ENUM_VALUE_OF_OFF.get() || !policy.callResults()) return;
        for (MethodInfo mi : methods) {
            if (mi.isStatic() && "valueOf".equals(mi.name()) && mi.typeInfo().typeNature().isEnum()
                && mi.parameters().size() == 1 && mi.parameters().getFirst().parameterizedType().isJavaLangString()) {
                nonNullContracts.add(mi.parameters().getFirst());
            }
        }
    }

    private static final Switches.Gate ENUM_VALUE_OF_OFF = Switches.gate("NOENUMVALUEOF");

    /*
     A field typed by its class's type variable ('T t' in 'Box<T>') holds, in each box, a value of that box's type
     argument: null in the field (its default value, 'this.t = null', a 'set(null)') reaches the argument slot of
     every node typed 'Box<…>'.
     */
    private void holderFields(List<MethodInfo> methods, List<FieldInfo> fields) {
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
        nodes.addAll(declaredLocals.keySet());
        for (Object n : nodes) {
            ParameterizedType type = typeOf(n);
            if (type == null || type.arrays() > 0 || type.typeInfo() == null) continue;
            for (FieldInfo fi : byOwner.getOrDefault(type.typeInfo(), List.of())) {
                int index = classTypeVariable(fi);
                if (index >= type.parameters().size()) continue;
                if (policy.assertContentWrites()) {
                    pendingHolders.computeIfAbsent(fi, _ -> new ArrayList<>()).add(new Arg(n, index));
                } else {
                    addEdge(fi, new Arg(n, index));
                }
            }
        }
        if (!pendingHolders.isEmpty()) holdersReachedFromOutside();
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
        pendingHolders.keySet().forEach(fi -> byOwner.computeIfAbsent(fi.owner(), _ -> new ArrayList<>()).add(fi));
        byOwner.forEach((holder, holders) -> {
            Set<Object> outside = new LinkedHashSet<>();
            seedOrigin.keySet().forEach(seed -> {
                List<TypeInfo> writers = seedWriters.getOrDefault(seed, List.of());
                if (writers.isEmpty() || writers.stream().anyMatch(w -> !within(w, holder))) outside.add(seed);
            });
            Set<Object> reachedFromOutside = closure(new HashMap<>(), outside, null);
            for (FieldInfo fi : holders) {
                if (reachedFromOutside.contains(fi)) pendingHolders.get(fi).forEach(arg -> addEdge(fi, arg));
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
     Arrays are invariant in what they hold (Kotlin's Array<T>; Java's covariance is a store check at run time, not a
     licence): wherever an array flows, both ends denote the same objects, so their elements are one slot. Every
     flow between two array nodes, also one the top-level verdict drops because the array is known non-null there,
     ties their Contents both ways, recursively for nested arrays.
     */
    /*
     Kotlin: an array passed where an array of a wider element type is expected ('asImmutableList(entryArray)' with
     an ImmutableEntry<E>[] for guava's 'asImmutableList(@Nullable Object[] elements)') is cast by the printer
     ('entryArray as Array<Any?>'): the two are no one Array<T>. Elements flow on from the narrower array into the
     wider one, but not back, unless the wider one is written through. Without this, the parameter's nullable
     elements made RegularImmutableMultiset.create's entryArray, and with it entries, getEntry and
     IndexedImmutableSet.get, nullable (worktree-maddi-all, 2026-10-27). True when 'to' holds a proper subtype of what
     'from' holds. Gate NOARRAYWIDENING.
     */
    private boolean narrowedArray(ParameterizedType from, ParameterizedType to) {
        if (!policy.callResults() || ARRAY_WIDENING_OFF.get() || from.arrays() != to.arrays()) return false;
        TypeInfo f = from.copyWithoutArrays().typeInfo();
        TypeInfo t = to.copyWithoutArrays().typeInfo();
        if (f == null || t == null || f == t || t.isJavaLangObject()) return false;
        return f.isJavaLangObject() || t.superTypesExcludingJavaLangObject().contains(f);
    }

    // gate NOARRAYWIDENING: a wider array parameter's element nulls flow back into the argument (A/B)
    private static final Switches.Gate ARRAY_WIDENING_OFF = Switches.gate("NOARRAYWIDENING");

    private void coupleContent() {
        Set<Object> writtenThrough = writtenThrough(true);
        // narrowedArray: an element annotation alone writes nothing into the caller's array
        Set<Object> written = policy.callResults() && !ARRAY_WIDENING_OFF.get() ? writtenThrough(false) : writtenThrough;
        Set<ParameterInfo> readOnlyArrays = readOnlyArrayParameters();
        Deque<List<Object>> queue = new ArrayDeque<>(flows);
        Set<List<Object>> seen = new HashSet<>();
        while (!queue.isEmpty()) {
            List<Object> flow = queue.removeFirst();
            if (!seen.add(flow)) continue;
            // not through a library method: its parameter would tie together the arrays of all its callers
            if (library(flow.get(0)) || library(flow.get(1))) continue;
            ParameterizedType t0 = typeOf(flow.get(0));
            ParameterizedType t1 = typeOf(flow.get(1));
            if (t0 == null || t1 == null) continue;
            if (t0.arrays() > 0 && t1.arrays() > 0) {
                Content c0 = new Content(flow.get(0));
                Content c1 = new Content(flow.get(1));
                // the links run both ways: 'join(upperBounds)' also gives a flow from join's 'parts' to the caller's
                // array. A read-only parameter flows on only into another or a library's (readOnlyArrayParameters);
                // anything else is that reversed link
                if ((!readOnlyArrayParameter(flow.get(0), readOnlyArrays)
                     || readOnlyArrayParameter(flow.get(1), readOnlyArrays))
                    && !varargsPasses.contains(List.of(flow.get(1), flow.get(0)))
                    && !(narrowedArray(t0, t1) && !written.contains(flow.get(0)))) {
                    successors.computeIfAbsent(c0, _ -> new LinkedHashSet<>()).add(c1);
                    if (why(c1.of())) whyLog("coupled forward", c1.of(), "from " + whyName(c0.of()) + " " + t0 + " -> " + t1);
                }
                if (!readOnlyArrayParameter(flow.get(1), readOnlyArrays) && !varargsPasses.contains(flow)
                    && (narrowedArray(t1, t0) ? written.contains(flow.get(1))
                        : policy.assertContentWrites() || writtenThrough.contains(flow.get(1)))) {
                    successors.computeIfAbsent(c1, _ -> new LinkedHashSet<>()).add(c0);
                    if (why(c0.of())) whyLog("coupled back", c0.of(), "from " + whyName(c1.of()) + " " + t1 + " -> " + t0);
                }
                queue.add(List.of(c0, c1));
            } else if (t0.arrays() == 0 && t1.arrays() == 0 && !t0.parameters().isEmpty()
                       && t0.parameters().size() == t1.parameters().size()) {
                // generic types are invariant too (List<String?> is not a List<String>), except into a
                // '? extends' argument, which only receives; matched by position (ArrayList<E> -> List<E>)
                for (int i = 0; i < t0.parameters().size(); i++) {
                    ParameterizedType p0 = t0.parameters().get(i);
                    ParameterizedType p1 = t1.parameters().get(i);
                    // not through a generic method's own type variable: each call instantiates it anew
                    // ('ImmutableMap.copyOf(Map<? extends K, ? extends V>)' would tie the maps of all its callers)
                    if (isMethodTypeVariable(p0) || isMethodTypeVariable(p1)) continue;
                    // Kotlin: nor between two different concrete arguments, which only an unchecked conversion
                    // relates ('(ImmutableMap<K, V>) RegularImmutableMap.EMPTY', an ImmutableMap<Object, Object> the
                    // links see as every caller's map): each conversion starts an instantiation of its own
                    if (policy.callResults() && !UNCHECKED_SLOTS_OFF.get() && uncheckedConversion(p0, p1)) continue;
                    // A slot typed by a CLASS type variable ('FastFixedSet<E> spawnEmptySet()' in Factory<E>,
                    // 'union(FastFixedSet<E> set)', 'Factory(Collection<E> set)') is parametric: it receives nothing
                    // from one instantiation's slot ('FastFixedSet<Statement?> tmpSet'), or Kotlin would see 'E?'
                    // inside the class (fernflower FastFixedSetFactory.kt:19/33). What the class itself puts into
                    // such a slot reaches every instantiation, so that direction stays. The instantiation's null
                    // belongs to the receiver's slot: instantiatedSlots ties it there.
                    boolean class0 = isClassTypeVariable(p0);
                    boolean class1 = isClassTypeVariable(p1);
                    Arg a0 = new Arg(flow.get(0), i);
                    Arg a1 = new Arg(flow.get(1), i);
                    if (!class1 || class0) successors.computeIfAbsent(a0, _ -> new LinkedHashSet<>()).add(a1);
                    // Kotlin: nor out of an unbound '?' ('Joiner.MapJoiner.join(Map<?, ?>)'): Kotlin's '*' only
                    // receives too. Back out, JoinerTest's null reached every map passed to join, and through
                    // ImmutableMap.of()'s RegularImmutableMap.EMPTY all of guava's empty maps (2026-10-17)
                    boolean unbound = policy.callResults() && !WILDCARD_SLOTS_OFF.get() && p1.wildcard() != null
                                      && p1.wildcard().isUnbound();
                    // Kotlin: nor out of a position declared nullable ('Predicate<@Nullable Object> equalTo1 =
                    // Equivalence.equals().equivalentTo("1")'): it is 'T?' whatever flows in, and its null is not the
                    // source's (EquivalenceTest's 'equalTo1.apply(null)' made Equivalence.equals() an
                    // Equivalence<Any?>, Equivalence.kt:98/99, 2026-10-18)
                    boolean declaredNullable = policy.callResults() && !NULLABLE_PARAMETER_SLOTS_OFF.get()
                                               && explicit(p1.annotations()) == NullableState.NULLABLE;
                    boolean read = viewLoopReads.contains(flow);
                    if (!isExtendsWildcard(p1) && !unbound && !declaredNullable && (!class0 || class1) && !read) {
                        successors.computeIfAbsent(a1, _ -> new LinkedHashSet<>()).add(a0);
                    }
                    if (read) viewLoopReads.add(List.of(a0, a1));
                    queue.add(List.of(a0, a1));
                }
            }
        }
    }

    /*
     Kotlin: an array parameter the method only reads prints as 'Array<out T>' (the printer, maddi cd8372ee4), which
     takes the caller's Array<Type> as it is: its elements need not flow back. Joiner.join(@Nullable Object[] parts)
     had pushed its '?' into every array joined, TypeResolver's upperBounds and from there resolveTypes' result and
     TypeToken (guava kotlinc-97a.log, 2026-10-17). "Only reads", as the printer decides it, from the source: the
     method assigns no element ('parts[i] = ...'; not writtenThrough, which also counts the elements a caller puts
     into 'join(new Object[]{x, y})', nor the unmodified verdict, absent where the modification analysis did not
     run); no lambda or anonymous class captures the array (AbstractCompositeHashFunction's fromHashers hands it on
     from there); and the array flows on only into other such parameters (cycles allowed) or a library's: not
     returned, assigned, or held. Not a varargs parameter, nor one of an override or an overridden method.
     Gate NOREADONLYARRAYS.
     */
    private Set<ParameterInfo> readOnlyArrayParameters() {
        if (READ_ONLY_ARRAYS_OFF.get() || !policy.callResults()) return Set.of();
        Set<ParameterInfo> candidates = new HashSet<>();
        Map<ParameterInfo, Set<Object>> out = new HashMap<>();
        for (List<Object> flow : flows) {
            if (flow.get(0) instanceof ParameterInfo pi) out.computeIfAbsent(pi, _ -> new HashSet<>()).add(flow.get(1));
        }
        for (MethodInfo mi : analysed) {
            if (!overrides(mi).isEmpty() || implementations.containsKey(mi)) continue;
            for (ParameterInfo pi : mi.parameters()) {
                ParameterizedType t = pi.parameterizedType();
                if (t.arrays() == 1 && !pi.isVarArgs() && !t.copyWithoutArrays().isPrimitiveExcludingVoid()
                    && !writesOrCaptures(mi, pi)) {
                    candidates.add(pi);
                }
            }
        }
        boolean changed = true;
        while (changed) {
            changed = false;
            for (java.util.Iterator<ParameterInfo> it = candidates.iterator(); it.hasNext(); ) {
                ParameterInfo pi = it.next();
                for (Object to : out.getOrDefault(pi, Set.of())) {
                    if (to instanceof ParameterInfo target && (candidates.contains(target) || library(target))) continue;
                    it.remove();
                    changed = true;
                    break;
                }
            }
        }
        return candidates;
    }

    // does the method's body assign an element of the array parameter, or capture it in a lambda or anonymous class
    private static boolean writesOrCaptures(MethodInfo mi, ParameterInfo pi) {
        if (mi.methodBody() == null) return true;
        boolean[] found = {false};
        mi.methodBody().visit(e -> {
            if (found[0]) return false;
            if (e instanceof Assignment a && a.variableTarget() instanceof DependentVariable dv
                && pi.equals(dv.arrayVariable())) {
                found[0] = true;
            } else if (e instanceof Lambda lambda) {
                if (lambda.methodBody() != null && mentionsParameter(lambda.methodBody(), pi)) found[0] = true;
                return false;
            } else if (e instanceof ConstructorCall cc && cc.anonymousClass() != null) {
                if (cc.parameterExpressions().stream().anyMatch(x -> mentionsParameter(x, pi))) found[0] = true;
                for (MethodInfo m : cc.anonymousClass().methods()) {
                    if (m.methodBody() != null && mentionsParameter(m.methodBody(), pi)) found[0] = true;
                }
                return false;
            }
            return true;
        });
        return found[0];
    }

    private static boolean mentionsParameter(Element e, ParameterInfo pi) {
        boolean[] found = {false};
        e.visit(x -> {
            if (x instanceof VariableExpression ve && pi.equals(ve.variable())) found[0] = true;
            return !found[0];
        });
        return found[0];
    }

    /*
     Kotlin: a varargs parameter takes a copy ('newParameterizedType(Map.class, *typesIn)'), so its elements do not
     flow back into the caller's array, whatever the method or the caller does with either afterwards. TypesTest's
     'typesIn[0] = null' after the call (testing guava's defensive copy) reached resolveTypes' result through
     newParameterizedType(Class, Type...); ImmutableList.construct(Object... elements)'s annotated elements reached
     Types.newTypeVariableImpl's and newArtificialTypeVariable's bounds and captureAsTypeVariable's upperBounds
     (TypeResolver.kt:234, Types.kt:325). Exactly the (array, varargs parameter) pairs of such calls: the caller's
     elements still flow forward into the parameter's ('isVarReferenced(obj, *whitelist)' with a nullable element,
     fernflower VarExprent.kt:205), only the back edge and the forward edge of the reversed link go. An override
     family's varargs parameters stay tied as any other (ImmutableCollection.Builder.add(E...)). Only under
     Policy.callResults. Gate NOVARARGCOPIES.
     */
    private final Set<List<Object>> varargsPasses = new HashSet<>();

    /*
     Kotlin: the loop variable over a library view reads the map's slots, but what the loop writes through it does not
     flow back. jenkins' EnvVars.resolve 'entry.setValue(Util.replaceMacro(entry.getValue(), env))' (an @Nullable
     return, null only for a null input) made masterEnvVars, EnvVars(Map) and through them EnvVars a map of 'String?'
     (EnvVars.kt:22/25/79, AbstractBuild.kt:179); DiskSpaceMonitorDescriptor.monitor() a Map<Computer, DiskSpace?>
     against its superclass's (DiskSpaceMonitorDescriptor.kt:20/42): 127 -> 133 errors (worktree-maddi-all
     2026-10-22). The write stays one mismatch at the loop. The (view slot, loop variable) flows, and the slot pairs
     under them. Gate NOVIEWLOOPWRITES.
     */
    private final Set<List<Object>> viewLoopReads = new HashSet<>();

    private static final Switches.Gate VIEW_LOOP_WRITES_OFF = Switches.gate("NOVIEWLOOPWRITES");

    private static final Switches.Gate VARARG_COPIES_OFF = Switches.gate("NOVARARGCOPIES");

    private static boolean readOnlyArrayParameter(Object node, Set<ParameterInfo> readOnlyArrays) {
        Object root = node;
        while (root instanceof Content c) root = c.of();
        return root instanceof ParameterInfo pi && readOnlyArrays.contains(pi);
    }

    private static final Switches.Gate READ_ONLY_ARRAYS_OFF = Switches.gate("NOREADONLYARRAYS");

    /*
     For Java annotations an array's elements need to flow only forward: Java arrays are covariant, and so is
     JSpecify. Backward only where the downstream array is written through ('void f(Object[] a) { a[0] = null; }'
     puts the null in the caller's array), directly or via another array it flows on into. Kotlin's Array<T> is
     invariant, so under Policy.assertContentWrites both ways regardless. Guava 2026-10-07: coupling both ways made
     ImmutableMap.Builder.entries, ImmutableList.array, Joiner.join(Object[]) and TypeToken's Type[] one slot.
     */
    private Set<Object> writtenThrough(boolean annotations) {
        // 'annotations': whether an element annotation counts ('join(@Nullable Object[] parts)'); not for narrowedArray
        Set<Object> written = new HashSet<>();
        // NULLWHY: why a node is written, its downstream node or the write itself
        Map<Object, String> because = NULL_WHY == null ? null : new HashMap<>();
        successors.forEach((from, tos) -> {
            // narrowedArray: nor is an element "written" from a lifted field ('RegularImmutableList.array ∋
            // RegularImmutableList.element'), the links' stand-in for any element, not code writing into the array
            if (from instanceof Content || !annotations && liftedField(from)) return;
            for (Object to : tos) {
                if (to instanceof Content c && written.add(c.of()) && because != null) {
                    because.put(c.of(), "written from " + whyName(from));
                }
            }
        });
        seedOrigin.forEach((node, origin) -> {
            if (node instanceof Content c && !origin.startsWith("array created") && !origin.startsWith("null in an array initializer")
                && (annotations || !origin.startsWith("annotated"))) {
                if (written.add(c.of()) && because != null) because.put(c.of(), "seed " + origin);
            }
        });
        Map<Object, List<Object>> upstream = new HashMap<>();
        for (List<Object> flow : flows) upstream.computeIfAbsent(flow.get(1), _ -> new ArrayList<>()).add(flow.get(0));
        Deque<Object> queue = new ArrayDeque<>(written);
        while (!queue.isEmpty()) {
            Object down = queue.removeFirst();
            if (!annotations && (liftedField(down) || library(down))) {
                if (because != null && why(down)) System.err.println("NULLWHY written(false) stops at " + whyName(down));
                continue;
            }
            for (Object up : upstream.getOrDefault(down, List.of())) {
                // narrowedArray: an array is written through arrays only, not through a type argument's slot, a
                // return value or a call's argument that happens to link to it
                if (!annotations && !LIFTED_WRITES_OFF.get() && !(isArray(up) && isArray(down))) continue;
                if (written.add(up)) {
                    queue.add(up);
                    if (because != null) because.put(up, "flows into " + whyName(down));
                }
            }
        }
        // narrowedArray: nor is the lifted field itself written, nor does it pass its elements on as if it were
        if (!annotations) written.removeIf(NullabilityPass::liftedField);
        if (because != null) {
            for (Object node : written) {
                if (!why(node)) continue;
                StringBuilder sb = new StringBuilder("NULLWHY written(" + annotations + ") " + whyName(node)
                        + (liftedField(node) ? " (lifted)" : ""));
                Object n = node;
                for (int i = 0; i < 40 && because.get(n) != null; i++) {
                    String b = because.get(n);
                    sb.append("\n    ").append(b);
                    if (!b.startsWith("flows into ")) break;
                    n = written.stream().filter(w -> whyName(w).equals(b.substring(11))).findFirst().orElse(null);
                    if (n == null) break;
                }
                System.err.println(sb);
            }
        }
        return written;
    }

    /*
     Kotlin, for narrowedArray: a write booked on a field its owner does not declare does not travel upstream to what
     flows into it. Such a field is the link engine's lift of a subtype's field onto a supertype
     (VariableTranslationAllowHierarchy): 'ImmutableList.array' for RegularImmutableList.array, the content of every
     list of the type. So 'ImmutableList.of(e1, ..)' filling its own array before that became a list would count as a
     write into every array passed in, guava's 'asImmutableList(@Nullable Object[], int)' included: it made
     RegularImmutableMultiset.create's entryArray, and with it entries and IndexedImmutableSet.get, nullable
     (CodeLaser/maddi-mod#74). For the same reason the lifted field is not written itself, and its elements, linked
     into a narrower array, are not that array's (liftedIntoNarrower): they are what the other arrays of the type
     hold. Nor does a write travel upstream out of a library method's parameter, which ties together the arrays of
     all its callers (as in coupleContent), or through anything but arrays. The declared field, written through,
     still counts. Gate NOLIFTEDWRITES.
     */
    private static boolean liftedField(Object node) {
        Object root = node;
        while (root instanceof Content c) root = c.of(); // also its elements, at any depth
        return root instanceof FieldInfo fi && !LIFTED_WRITES_OFF.get() && fi.owner() != null
               && !fi.owner().fields().contains(fi);
    }

    private boolean isArray(Object node) {
        if (node instanceof Content || node instanceof Arg) return false;
        ParameterizedType t = typeOf(node);
        return t != null && t.arrays() > 0;
    }

    // the elements of a lifted field (liftedField) linked into a narrower array: what other arrays of the type hold
    private boolean liftedIntoNarrower(Object from, Object to) {
        if (!(from instanceof Content cf) || !(to instanceof Content ct) || !liftedField(cf)) return false;
        ParameterizedType tf = typeOf(cf.of());
        ParameterizedType tt = typeOf(ct.of());
        return tf != null && tt != null && narrowedArray(tf, tt);
    }

    private static final Switches.Gate LIFTED_WRITES_OFF = Switches.gate("NOLIFTEDWRITES");

    // no type variable among the type arguments (or an array's element), at any depth
    private static boolean concreteArguments(ParameterizedType pt) {
        if (pt.arrays() > 0) return concreteArguments(pt.componentType()) && pt.componentType().typeParameter() == null;
        for (ParameterizedType a : pt.parameters()) {
            if (a.typeParameter() != null || !concreteArguments(a)) return false;
        }
        return true;
    }

    private static boolean isBoxedVoid(ParameterizedType pt) {
        return pt.arrays() == 0 && pt.typeInfo() != null && "java.lang.Void".equals(pt.typeInfo().fullyQualifiedName());
    }

    private static boolean isMethodTypeVariable(ParameterizedType pt) {
        return pt.typeParameter() != null && pt.typeParameter().isMethodTypeParameter();
    }

    // a type variable of a class ('E' in Factory<E>), also as the bound of a wildcard ('? extends E')
    private static boolean isClassTypeVariable(ParameterizedType pt) {
        return pt.typeParameter() != null && pt.arrays() == 0 && !pt.typeParameter().isMethodTypeParameter();
    }

    /*
     Kotlin: a parameter's '?' argument prints as '*', whose values read as Any?: 'for (key in keys)' over
     'getAllPresent(keys: MutableIterable<*>)' hands an Any? to 'getIfPresent(key)' (guava AbstractCache.kt:17, once
     3fd57564 cut the hub that had made that parameter nullable by accident). Nothing flows back out of the slot
     (coupleContent), so the seed reaches only what is read from it.
     */
    private void starSlots(ParameterInfo pi) {
        if (!policy.callResults() || STAR_SLOTS_OFF.get()) return;
        ParameterizedType pt = pi.parameterizedType();
        if (pt.arrays() != 0) return;
        for (int i = 0; i < pt.parameters().size(); i++) {
            ParameterizedType a = pt.parameters().get(i);
            if (a.wildcard() != null && (a.wildcard().isUnbound() || a.wildcard().isExtends()
                                          && a.typeParameter() == null && a.typeInfo() != null
                                          && a.typeInfo().isJavaLangObject())) {
                seed(new Arg(pi, i), "read from '*' in " + pi.methodInfo().fullyQualifiedName());
            }
        }
    }

    // gate NOSTARRESULTS: a generic call given a '?' argument does not read its result from that slot (A/B)
    private static final Switches.Gate STAR_RESULTS_OFF = Switches.gate("NOSTARRESULTS");

    // gate NOSTARSLOTS: a '?' argument's values are not read as nullable (A/B)
    private static final Switches.Gate STAR_SLOTS_OFF = Switches.gate("NOSTARSLOTS");

    /*
     Kotlin: a call on this object to a method of its own class instantiates the class's type variables as the
     caller has them, so the result's slots are the target's ('table = newEntryArray(n)' in guava's
     LocalCache.Segment: the null elements of AtomicReferenceArray<ReferenceEntry<K, V>?> table belong to
     newEntryArray's result too; Striped.SmallLazyStriped likewise). Not with a method type variable in the result,
     which each call instantiates anew.
     */
    private static boolean sameInstantiation(MethodCall mc, MethodInfo caller) {
        MethodInfo callee = mc.methodInfo();
        if (SAME_INSTANTIATION_OFF.get() || callee.isStatic() || callee.typeInfo() != caller.typeInfo()
            || caller.isStatic() || hasMethodTypeVariable(callee.returnType())) {
            return false;
        }
        Expression object = mc.object() == null ? null : NonNullFacts.unwrap(mc.object());
        return object == null || object.isEmpty()
               || object instanceof VariableExpression ve && ve.variable() instanceof This;
    }

    private static boolean hasMethodTypeVariable(ParameterizedType pt) {
        if (isMethodTypeVariable(pt)) return true;
        if (pt.arrays() > 0 && pt.componentType() != null && hasMethodTypeVariable(pt.componentType())) return true;
        for (ParameterizedType a : pt.parameters()) if (hasMethodTypeVariable(a)) return true;
        return false;
    }

    // gate NOSAMEINSTANTIATION: a call on this does not tie its result's slots to the target's (A/B)
    private static final Switches.Gate SAME_INSTANTIATION_OFF = Switches.gate("NOSAMEINSTANTIATION");

    /*
     Kotlin: two generic types that differ in a concrete type argument at some depth are not one object, whatever
     the links say. fernflower's 'class_mapping = mapping.computeIfAbsent(className, k -> new LinkedHashMap<>())' has
     the link class_mapping ← mapping: a Map<String, Map<String, Map<Integer, Integer>>> into a Map<String,
     Map<Integer, Integer>>. Matched by position, mapping's key slot (a nullable className) became class_mapping's,
     and through the view loops of dumpMapping lstBytecodeOffsets a List<Int?> that Collections.sort rejects
     (BytecodeSourceMapper.kt:40, worktree-maddi-all 2026-10-23). Link edges between such nodes, or between their slots
     at the same depth, are dropped. uncheckedConversion skips only the one position that differs. Gate NODIFFERENTSHAPES.
     */
    private boolean differentShapes(Object from, Object to) {
        while (from instanceof Arg(Object f, int _) && to instanceof Arg(Object t, int _)) {
            from = f;
            to = t;
        }
        ParameterizedType t0 = typeOf(from);
        ParameterizedType t1 = typeOf(to);
        return t0 != null && t1 != null && t0.arrays() == 0 && t1.arrays() == 0 && t0.typeInfo() != null
               && t0.typeInfo() == t1.typeInfo() && differentShapes(t0, t1);
    }

    private static boolean differentShapes(ParameterizedType t0, ParameterizedType t1) {
        for (int i = 0; i < t0.parameters().size() && i < t1.parameters().size(); i++) {
            ParameterizedType p0 = t0.parameters().get(i);
            ParameterizedType p1 = t1.parameters().get(i);
            if (uncheckedConversion(p0, p1)) return true;
            if (p0.wildcard() == null && p1.wildcard() == null && p0.typeParameter() == null
                && p1.typeParameter() == null && p0.typeInfo() != null && p0.typeInfo() == p1.typeInfo()
                && p0.arrays() == p1.arrays() && differentShapes(p0, p1)) return true;
        }
        return false;
    }

    private static final Switches.Gate DIFFERENT_SHAPES_OFF = Switches.gate("NODIFFERENTSHAPES");

    private static boolean uncheckedConversion(ParameterizedType p0, ParameterizedType p1) {
        return p0.wildcard() == null && p1.wildcard() == null && p0.typeParameter() == null
               && p1.typeParameter() == null && p0.typeInfo() != null && p1.typeInfo() != null
               && p0.arrays() == p1.arrays() && p0.typeInfo() != p1.typeInfo();
    }

    private static boolean isExtendsWildcard(ParameterizedType pt) {
        return pt.wildcard() != null && pt.wildcard().isExtends();
    }

    private boolean library(Object node) {
        return switch (node) {
            case Content c -> library(c.of());
            case Arg a -> library(a.of());
            case Keyed k -> library(k.slot());
            case ParameterInfo pi -> !analysed.contains(pi.methodInfo());
            case MethodInfo mi -> !analysed.contains(mi);
            default -> false;
        };
    }

    public record Local(MethodInfo methodInfo, Element declaration, String name) {
        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Local(MethodInfo m, Element d, String n)) || !name.equals(n)) return false;
            return declaration == null ? d == null && methodInfo.equals(m) : declaration == d;
        }

        @Override
        public int hashCode() {
            return declaration == null ? Objects.hash(methodInfo, name)
                    : 31 * System.identityHashCode(declaration) + name.hashCode();
        }

        @Override
        public String toString() {
            return name + " in " + methodInfo.fullyQualifiedName();
        }
    }

    // the locals in scope: a block, or a statement's own (for initializers, for-each variable, try resources)
    private record Scope(Scope parent, Map<String, Local> names) {
        Scope(Scope parent) {
            this(parent, new HashMap<>());
        }

        Local resolve(String name) {
            for (Scope s = this; s != null; s = s.parent) {
                Local local = s.names.get(name);
                if (local != null) return local;
            }
            return null;
        }
    }

    // the shadow local of a reassigned parameter; created once, the caller's value flows into it
    private Local shadow(ParameterInfo pi) {
        Local local = new Local(pi.methodInfo(), null, pi.name());
        if (shadows.putIfAbsent(local, pi) == null) {
            localOwner.put(local, pi.methodInfo());
            addEdge(pi, local);
        }
        return local;
    }

    // the parameters of mi assigned (plainly, not '+=') anywhere in element, lambdas excluded
    // gate NOPARAMSHADOW: a reassigned parameter keeps one node for the caller's and the body's values (A/B)
    private static final Switches.Gate PARAMETER_SHADOWS_OFF = Switches.gate("NOPARAMSHADOW");

    private static Set<ParameterInfo> parametersAssignedIn(Element element, MethodInfo mi) {
        Set<ParameterInfo> set = new HashSet<>();
        if (element == null || PARAMETER_SHADOWS_OFF.get()) return set;
        element.visit(e -> {
            if (e instanceof Lambda) return false;
            if (e instanceof Assignment a && a.assignmentOperator() == null
                && a.variableTarget() instanceof ParameterInfo pi && pi.methodInfo() == mi) set.add(pi);
            return true;
        });
        return set;
    }

    // the node an assignment writes: a reassigned parameter's shadow, else the variable's node
    private Object assignmentTarget(MethodInfo mi, Scope scope, Variable target) {
        return target instanceof ParameterInfo pi && reassigned.contains(pi) ? shadow(pi) : node(mi, scope, target);
    }

    private void declare(Scope scope, MethodInfo mi, Element declaration, LocalVariable lv) {
        Local local = new Local(mi, declaration, lv.simpleName());
        scope.names.put(lv.simpleName(), local);
        declaredLocals.put(local, lv);
        localOwner.put(local, owner);
        declaredByName.computeIfAbsent(mi, _ -> new HashMap<>())
                .computeIfAbsent(lv.simpleName(), _ -> new ArrayList<>()).add(local);
    }

    // the node a variable stands for: a parameter, a field (field-insensitive in its object), a return value, a
    // local in scope. Markers ($_ce, $_v) and the link engine's intermediates ($__) are not nodes.
    private Object node(MethodInfo mi, Scope scope, Variable v) {
        return switch (v) {
            case ParameterInfo pi -> shadowed.contains(pi) ? shadow(pi) : pi;
            case DependentVariable dv when dv.arrayVariable() instanceof FieldReference fr && Util.virtual(fr) -> {
                // a slice of a multi-parameter container, 'map.§$$s[-2]': type argument 1 (the value)
                Object base = node(mi, scope, fr.scopeVariable());
                int slice = sliceIndex(dv);
                yield base == null || slice < 0 ? null : argSlot(base, slice);
            }
            case DependentVariable dv -> {
                Object array = node(mi, scope, dv.arrayVariable());
                yield array == null ? null : new Content(array);
            }
            case ReturnVariable rv -> rv.methodInfo();
            case FieldReference fr when Util.virtual(fr) -> {
                // the hidden content of a one-parameter type: 'list.§$s', 'opt.§$' (not '§m', the modification marker)
                if (fr.fieldInfo().name().startsWith("§m")) yield null;
                Object base = node(mi, scope, fr.scopeVariable());
                ParameterizedType type = base == null ? null : typeOf(base);
                yield type == null || type.arrays() > 0 || type.parameters().size() != 1 ? null : argSlot(base, 0);
            }
            case FieldReference fr when !fr.scopeIsRecursivelyThis() && fr.scopeVariable() != null
                                        && classTypeVariable(fr.fieldInfo()) >= 0 -> {
                // 'b.t' of a generic holder 'Box<String> b': its type argument, not the field shared by all boxes
                Object base = node(mi, scope, fr.scopeVariable());
                ParameterizedType type = base == null ? null : typeOf(base);
                int index = classTypeVariable(fr.fieldInfo());
                yield type != null && type.arrays() == 0 && type.typeInfo() == fr.fieldInfo().owner()
                      && index < type.parameters().size() ? argSlot(base, index) : fr.fieldInfo();
            }
            case FieldReference fr when !Util.virtual(fr) -> fr.fieldInfo();
            case LocalVariable lv when !lv.simpleName().startsWith("$") -> local(mi, scope, lv.simpleName());
            case null, default -> null;
        };
    }

    // 'x.§$$s[-k]' is the k-th type argument's slice; -1 when the index is not such a constant
    private static int sliceIndex(DependentVariable dv) {
        Expression index = dv.indexExpression();
        if (index instanceof io.codelaser.maddi.cst.api.expression.UnaryOperator uo
            && uo.expression() instanceof io.codelaser.maddi.cst.api.expression.IntConstant ic) {
            return ic.constant() - 1;
        }
        if (index instanceof io.codelaser.maddi.cst.api.expression.IntConstant ic && ic.constant() < 0) {
            return -ic.constant() - 1;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\[-(\\d+)]$").matcher(dv.simpleName());
        return m.find() ? Integer.parseInt(m.group(1)) - 1 : -1;
    }

    // the index of the owner's type parameter that is the field's type ('T t' in 'Box<T>'); -1 otherwise
    private static int classTypeVariable(FieldInfo fi) {
        ParameterizedType type = fi.type();
        if (type.arrays() > 0 || type.typeParameter() == null || type.typeParameter().isMethodTypeParameter()) return -1;
        return type.typeParameter().getOwner().isLeft() && type.typeParameter().getOwner().getLeft() == fi.owner()
                ? type.typeParameter().getIndex() : -1;
    }

    // the slot of type argument 'index' of 'base', when its declared type has one there
    private Object argSlot(Object base, int index) {
        ParameterizedType type = typeOf(base);
        if (type == null || type.arrays() > 0 || index >= type.parameters().size()) return null;
        return new Arg(base, index);
    }

    private Local local(MethodInfo mi, Scope scope, String name) {
        Local inScope = scope == null ? null : scope.resolve(name);
        if (inScope != null) return inScope;
        List<Local> declared = declaredByName.getOrDefault(mi, Map.of()).getOrDefault(name, List.of());
        return declared.size() == 1 ? declared.getFirst() : new Local(mi, null, name);
    }

    /*
     A null marker reaches a node. With LinkComputer.Options.nullConstantReturns a callee's 'return null' crosses the
     call as the callee's own marker: then the null is the callee's return, an edge (the cause chain names the
     callee, 'return relay <- return find <- null in find'), not a null written here.
     */
    private void nullMarker(MethodInfo mi, Variable marker, Object target, String origin) {
        // Policy.withoutTestNulls: a test's null literal written into a production node ('new Decider(null)')
        if (!policy.testNulls() && testCode(mi.typeInfo()) && !testCode(target)) return;
        MethodInfo owner = nullOwner(((LocalVariable) marker).assignmentExpression());
        if (owner != null && !owner.equals(mi) && analysed.contains(owner) && !owner.returnType().isVoid()) {
            addEdge(owner, target);
        } else {
            seed(target, origin, writer(mi));
        }
    }

    // a face ('x.f.list.§s') on a field of an object reached through another field: the field holding the slot is
    // not read from this, a local or a parameter
    private static boolean deepFace(Variable face) {
        if (!(face instanceof FieldReference virtual) || !(NonNullFacts.unwrap(virtual.scope()) instanceof VariableExpression ve)
            || !(ve.variable() instanceof FieldReference holder)) return false;
        Expression base = NonNullFacts.unwrap(holder.scope());
        return !(base instanceof VariableExpression bv && (bv.variable() instanceof This
                                                           || bv.variable() instanceof LocalVariable
                                                           || bv.variable() instanceof ParameterInfo));
    }

    /*
     Kotlin: guava's '@ParametricNullness T get()' returns T as each instantiation has it, which the printer keeps
     ('Supplier<T>.get(): T'). An implementation's own null ('return uncheckedCastNullableTToT(value)' from a
     '@Nullable T value' cache, Suppliers.ExpiringMemoizingSupplier) belongs to a nullable instantiation only: it does
     not reach the result of 'factory.get()' on a Supplier<Map<C, V>> (StandardTable.backingMap's and
     AbstractMapBasedMultimap.map's values, guava 2026-10-17).
     */
    private static boolean parametricNullness(MethodInfo mi) {
        return !PARAMETRIC_OFF.get() && mi.annotations().stream().anyMatch(a -> a.typeInfo() != null
                && a.typeInfo().simpleName().equals("ParametricNullness"));
    }

    /*
     Kotlin: an implementation whose class passes a nullable type argument for the overridden return's type variable
     ('new Tables.AbstractCell<R, C, @Nullable V>()' in guava's ArrayTable.getCell, over Table.Cell's
     '@ParametricNullness V getValue()'): its null belongs to that instantiation, which the printer writes as
     'AbstractCell<R, C, V?>', so 'getValue(): V?' overrides 'getValue(): V' as it is. Without this, the upward edge
     made Cell.getValue V? and AbstractTable's callers failed (worktree-maddi-all, 2026-10-27). A direct supertype
     only. Gate NONULLABLEINSTANTIATION.
     */
    private static boolean nullableInstantiation(MethodInfo mi, MethodInfo overridden) {
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
    private boolean testOverrideOfInstantiatedReturn(MethodInfo mi) {
        if (TEST_INSTANTIATION_OFF.get() || mi.isConstructor() || mi.returnType().isVoid() || !testCode(mi)
            || returnsNullLiteral(mi)) return false;
        for (MethodInfo overridden : overrides(mi)) {
            if (!analysed.contains(overridden) || testCode(overridden)) continue;
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

    // gate NOPARAMETRIC: a '@ParametricNullness' type-variable return's own null reaches every call (A/B)
    private static final Switches.Gate PARAMETRIC_OFF = Switches.gate("NOPARAMETRIC");

    // '@NullSource' / '@NullAndEmptySource' (org.junit.jupiter.params.provider) on a parameterized test
    private static boolean junitNullSource(MethodInfo mi) {
        return mi.annotations().stream().anyMatch(a -> a.typeInfo() != null
                && (a.typeInfo().simpleName().equals("NullSource")
                    || a.typeInfo().simpleName().equals("NullAndEmptySource")));
    }

    // the analysed method whose body holds this null literal (a lambda's body is the lambda's method)
    private MethodInfo nullOwner(Expression nullConstant) {
        return nullOwners.get(nullConstant);
    }

    private void indexNullLiterals(MethodInfo mi) {
        if (mi.methodBody() == null) return;
        mi.methodBody().visit(e -> {
            if (e instanceof Lambda) return false;
            if (e instanceof NullConstant nc) nullOwners.put(nc, mi);
            return true;
        });
    }

    // a constant marker ('$_ceN', the link module's MarkerVariable, seen through the API) holding the null constant
    private static boolean isNullMarker(Variable v) {
        return v instanceof LocalVariable lv && lv.simpleName().startsWith("$_ce")
               && lv.assignmentExpression() instanceof NullConstant;
    }

    private void buildForMethod(MethodInfo mi) {
        // overrides: an implementation's null return makes the overridden return nullable; a parameter has one
        // nullability along the chain. DOWNWARD always: a null passed to the overridden method can be dispatched to
        // any implementation. UPWARD (implementation to overridden) not through a position whose overridden type is
        // a TYPE VARIABLE: there the nullability belongs to each instantiation, and joining them made every
        // implementation of a generic interface one hub (guava: Function.apply's 'F input' tied
        // ToStringFunction.apply to a Map.remove key).
        if (!mi.isConstructor()) {
            for (MethodInfo overridden : overrides(mi)) {
                // Kotlin: a test's override of a main member takes main's parameter types as they are, as for a
                // library's (clampTestOverrides): no null of the tests widens main's parameters. Its return does
                // widen main's: a test implementation returning null (langchain4j's DocumentTransformerTest
                // 'return null') makes the interface's result nullable, as the printer cannot assert a null literal
                boolean upward = !(policy.callResults() && testCode(mi) && !testCode(overridden));
                // Kotlin: an override's return may not be wider than its overridden member's, so a null returned
                // by an implementation makes an analysed type-variable return nullable too ('Holder<T>.get(): T?')
                if (!isTypeVariable(overridden.returnType())
                    || policy.callResults() && analysed.contains(overridden) && !nullableInstantiation(mi, overridden)) {
                    addEdge(mi, overridden);
                }
                if (policy.callResults() && analysed.contains(overridden)) {
                    invariantSlots(mi.returnType(), mi, overridden.returnType(), overridden, true);
                    int m = Math.min(mi.parameters().size(), overridden.parameters().size());
                    for (int i = 0; i < m; i++) {
                        invariantSlots(mi.parameters().get(i).parameterizedType(), mi.parameters().get(i),
                                overridden.parameters().get(i).parameterizedType(), overridden.parameters().get(i),
                                upward);
                    }
                }
                int n = Math.min(mi.parameters().size(), overridden.parameters().size());
                for (int i = 0; i < n; i++) {
                    // DOWNWARD not from a type-variable parameter into an implementation that instantiates it with a
                    // concrete type other than Object: a null passed through the generic 'Comparator<T>.compare'
                    // reaches 'compare(boolean[] ...)' only where T is boolean[], which nothing here tells. Measured
                    // on guava (2026-10-07): -142 noise; keeping Object implementations ('IdentityFunction.apply')
                    // keeps the unsafe count. A heuristic: a null passed to a Comparator<String> does reach a String
                    // implementation. Not for Kotlin (Policy.callResults), where an override's parameter must be
                    // nullable when the overridden one is ('doForward(s: String?)' over 'doForward(a: A?)', guava's
                    // Converter, CharMatcher over Predicate.apply).
                    ParameterizedType implementationType = mi.parameters().get(i).parameterizedType();
                    if (policy.callResults() || !isTypeVariable(overridden.parameters().get(i).parameterizedType())
                        || isTypeVariable(implementationType)
                        || implementationType.typeInfo() != null && implementationType.arrays() == 0
                           && implementationType.typeInfo().isJavaLangObject()) {
                        addEdge(overridden.parameters().get(i), mi.parameters().get(i));
                    }
                    if (upward && !isTypeVariable(overridden.parameters().get(i).parameterizedType())) {
                        addEdge(mi.parameters().get(i), overridden.parameters().get(i));
                    }
                }
            }
        }
        if (!mi.isConstructor()) {
            String lib = libraryNullableReturn(mi);
            if (lib != null && !mi.returnType().isVoid()) seed(mi, "overrides " + lib);
            libraryNullableContent(mi, mi.returnType(), m -> m.analysis().getOrDefault(PropertyImpl.NULLABILITY_METHOD,
                    ValueImpl.NullabilityImpl.UNSPECIFIED), MethodInfo::returnType);
            for (ParameterInfo pi : mi.parameters()) {
                int index = pi.index();
                starSlots(pi);
                libraryNullableContent(pi, pi.parameterizedType(), m -> index < m.parameters().size()
                        ? m.parameters().get(index).analysis().getOrDefault(PropertyImpl.NULLABILITY_PARAMETER,
                        ValueImpl.NullabilityImpl.UNSPECIFIED) : ValueImpl.NullabilityImpl.UNSPECIFIED,
                        m -> index < m.parameters().size() ? m.parameters().get(index).parameterizedType() : null);
                String libParam = libraryNullableParameter(mi, pi.index());
                if (libParam != null) {
                    seed(pi, "overrides " + libParam);
                } else if (policy.libraryCallbacks()) {
                    String unhinted = libraryUnhintedParameter(mi, pi.index());
                    if (unhinted != null) seed(pi, "overrides " + unhinted + ", which does not declare it non-null");
                }
            }
            // JUnit injects null into every parameter of a parameterized test with a null source, a caller the
            // analysis does not see: langchain4j's DocumentTest.constructor_should_fail_on_empty_text hands it to
            // Document.from(text), whose ensureNotBlank(text) must throw the IllegalArgumentException the test
            // expects, not Kotlin's entry check
            if (junitNullSource(mi)) {
                for (ParameterInfo pi : mi.parameters()) {
                    ParameterizedType type = pi.parameterizedType();
                    if (type.isPrimitiveExcludingVoid() && type.arrays() == 0) continue;
                    seed(pi, "JUnit null source of " + mi.fullyQualifiedName());
                }
            }
        }
        // Policy.rejectionsNullable (#28): a Java caller's null should reach the method's own check; constructors too
        if (policy.rejectionsNullable()) {
            for (ParameterInfo pi : mi.parameters()) {
                if (rejects(pi)) seed(pi, mi.fullyQualifiedName() + " rejects null with its own exception");
            }
        }
        if (mi.analysis().getOrDefault(PropertyImpl.DEGRADED_ANALYSIS_METHOD,
                ValueImpl.BoolImpl.FALSE).isTrue()) {
            degraded.add(mi);
        }
        // the body first: it declares the locals; the method's own variable data is the state at the end of the
        // body, in the body's scope
        facts.walk(mi);
        shadowed.clear();
        reassigned.addAll(parametersAssignedIn(mi.methodBody(), mi));
        for (ParameterInfo pi : mi.parameters()) if (reassigned.contains(pi)) shadow(pi);
        Scope body = mi.methodBody() == null ? new Scope(null) : handleBlock(mi, mi.methodBody(), null);
        VariableData vd = VariableDataImpl.of(mi);
        if (vd != null) {
            vd.variableInfoStream().forEach(vi -> linksOf(mi, body, vi, null));
        } else if (!mi.isConstructor() && !mi.returnType().isVoid() && mi.parameters().isEmpty()) {
            // a getter the links do not cover: a record accessor is synthesised without source ('return field;'),
            // and its statement carries no variable data, so nothing tied the component to the accessor's return.
            // Fernflower SwitchPatternHelper: 'initializer.instance' read as non-null while 'Initializer.instance'
            // was nullable (SwitchPatternHelper.kt:109). The getter/setter association says what it returns.
            io.codelaser.maddi.cst.api.analysis.Value.FieldValue getter = mi.getSetField();
            if (getter != null && getter.field() != null && !getter.setter() && !getter.hasIndex()) {
                addEdge(getter.field(), mi);
            }
        }
    }

    private void linksOf(MethodInfo mi, Scope scope, VariableInfo vi, Statement statement) {
        Variable v = vi.variable();
        Object recipient = node(mi, scope, v);
        // the statement assigns a reassigned parameter: the assignment's links are the shadow's, the reads' the
        // parameter's ('value = decode(value.trim())')
        Object assignedTo = v instanceof ParameterInfo pi && reassigned.contains(pi) && statement != null
                            && assignsHere(statement, pi) ? shadow(pi) : recipient;
        Links links = vi.linkedVariables();
        if (recipient == null || links == null) return;
        // 'x = ctx.get(KEY)' on a keyed map: the value read is the key's, not every key's (gap 2)
        Keyed keyed = statement == null ? null : keyedRead(statement, v);
        for (Link link : links) {
            // a product of the link graph's closure says that two variables are related, not where: the pass
            // composes the statements' own links itself, over its locals (Link.derived()). A null marker's link
            // is still read from the closure: the reconstruction of an assignment group does not spell the hop
            // through a local's face ('return b.get()' on a 'Box<String> b = new Box<>(null)' has no 'read ← b.t'),
            // and the marker rules below decide per statement anyway.
            if (link.derived() && !DERIVED_LINKS.get() && !isNullMarker(link.to()) && !updaterLink(statement, link)) continue;
            if (updaterLink(statement, link) && !link.from().equals(v)
                && link.linkNature().isIdenticalToOrAssignedFromTo()) {
                // 'child.parent → node', stored with child: a face of v that the updater assigns to the loop variable
                Object source = node(mi, scope, link.from());
                Object loopVariable = node(mi, scope, link.to());
                if (source != null && loopVariable instanceof Local) {
                    linkEdge(source, loopVariable, link.from(), link.to(), statement);
                }
                continue;
            }
            // links about a face of v ('this.f.g') are not about v; an element of v ('r[1] <- null', an initializer)
            // is v's Content, its hidden content ('l.§$s', 'm.§$$s[-2]', 'b.t') a type argument's slot
            Object from;
            if (link.from().equals(v)) {
                from = link.linkNature().isIdenticalTo() || link.linkNature().isAssignedFrom() ? assignedTo : recipient;
            } else {
                Object face = node(mi, scope, link.from());
                from = face instanceof Content || face instanceof Arg ? face : null;
            }
            if (from == null) continue;
            Variable fromVar = link.from();
            LinkNature nature = link.linkNature();
            if (nature == LinkNatureImpl.CONTAINS_AS_MEMBER || nature == LinkNatureImpl.IS_ELEMENT_OF) {
                membership(mi, scope, from, fromVar, nature == LinkNatureImpl.CONTAINS_AS_MEMBER, link.to(),
                        statement);
                continue;
            }
            if (nature == LinkNatureImpl.IS_SUBSET_OF || nature == LinkNatureImpl.IS_SUPERSET_OF) {
                // content copied: 'copy.§$s ⊆ in.§$s', the elements of 'in' flow into those of 'copy'
                Object other = node(mi, scope, link.to());
                if ((other instanceof Arg || other instanceof Content) && (from instanceof Arg || from instanceof Content)) {
                    if (nature == LinkNatureImpl.IS_SUBSET_OF) addEdge(other, from);
                    else addEdge(from, other);
                }
                continue;
            }
            // a type argument's slot is written by an assignment ('b.t ← null') or at a call site, never by identity:
            // a '≡' between a local and a slot's value runs both ways and would carry the local's other sources in
            if (from instanceof Arg && link.linkNature().isIdenticalTo()) continue;
            // the same for an element slot facing another: 'hashTable[b] ≡ entryArray[i]' after both were assigned
            // 'newEntry' is one slot each, not the arrays' elements at large -- the pass keeps one Content per array,
            // so the '≡' carried hashTable's null slots into entryArray (guava RegularImmutableMultiset.create, once
            // CodeLaser/maddi-mod#77 put the stored slot in its value's group). The assignments carry the value.
            // Gate NOSLOTIDENTITY.
            if (from instanceof Content && link.linkNature().isIdenticalTo() && !SLOT_IDENTITY.get()
                && node(mi, scope, link.to()) instanceof Content) {
                continue;
            }
            if (link.linkNature().isIdenticalTo() || link.linkNature().isAssignedFrom()) {
                if (isNullMarker(link.to())) {
                    // a return whose value is known non-null returns no null, whatever '≡' links it to: in 'var d =
                    // find(c); if (d == null) throw ...; return d;' the return is linked to find's 'return null'
                    // through d (timefold SolutionDescriptor.findEntityDescriptorOrFail, the diagnose session). The
                    // method's own summary repeats the link (no statement): skipped once a return guarded it.
                    if (why(mi)) {
                        whyLog("marker", mi, "from " + from + " to " + link.to() + " statement "
                                             + (statement == null ? "-" : statement.toString().strip()));
                    }
                    if (fromVar instanceof ReturnVariable) {
                        List<Object> key = List.of(mi, link.to());
                        if (statement == null) {
                            if (guardedReturnMarkers.contains(key)) continue;
                        } else {
                            // the graph is persistent: every later statement repeats the return's link to the
                            // marker, so the first statement carrying it (in walk order, the statement that
                            // introduced it) decides. In 'var v = pick(); if (v != null) return v; ...; return
                            // other();' the second return's data still says 'return ← pick's null', though it
                            // returns something else (timefold ValueRangeState.getReachableValues)
                            Expression returned = assignedValue(statement, fromVar);
                            // a 'return null' itself is never guarded: the link engine can give its null the marker
                            // of an earlier call's ('Node n = getNode(name); if (n instanceof Slave) return (Slave)
                            // n; return null;'), whose first, guarded return then hid it (jenkins Hudson.kt:52,
                            // PluginWrapper.kt:478, 2026-10-21). Gate NORETURNNULLLITERALS.
                            boolean literal = !RETURN_NULL_LITERALS_OFF.get() && returned != null
                                              && NonNullFacts.unwrap(returned) instanceof NullConstant;
                            if (!returnMarkersSeen.add(key) && !literal) continue;
                            if (!literal && returned != null && facts.nonNull(returned, facts.before(statement))) {
                                guardedReturnMarkers.add(key);
                                continue;
                            }
                        }
                    }
                    // a variable becomes null only where it is assigned: '≡' is transitive in the links, so in
                    // 'checked = check(key)' with 'check' returning its argument or null, 'key ≡ return ≡ null'
                    // reaches 'key' (nacos SystemEnvPropertySource, reported by the diagnose session). A slot
                    // (Content, Arg) is written by calls too.
                    boolean slot = from instanceof Content || from instanceof Arg;
                    // a callee's 'return null' does not become an element of a collection reached through another
                    // object's fields: 'cfg.selector.solutionDescriptor.genuineEntityDescriptorList.§s ← $_ce3', the
                    // links' transitive closure through calls, in a method that never writes that list (timefold
                    // DefaultConstructionHeuristicPhaseFactory.getBuilder, CodeLaser/maddi-mod#27 a). A null this
                    // method writes is kept, and so are the call-site writes ('list.add(x)', receiverSlots).
                    if (slot && deepFace(link.from()) && !mi.equals(nullOwner(((LocalVariable) link.to()).assignmentExpression()))) {
                        continue;
                    }
                    // a local assigned a value known non-null takes no null, whatever the closure links it to: in
                    // 'Object o = null; for (..) o = b.call(o); Integer c = o instanceof Integer ? (Integer) o : 0;'
                    // 'c ← o ← null' holds in the graph, but neither branch can be null (jenkins
                    // CLIRegisterer.main, once CodeLaser/maddi-mod#72 4a let the zero-iteration null reach 'o').
                    // As for a return above. Gate NOASSIGNEDNONNULL.
                    // The method's own data repeats the link without a statement: skipped once an assignment guarded
                    // it, as an unguarded one has carried the null in at its own statement.
                    if (!slot && !(fromVar instanceof ReturnVariable) && !ASSIGNED_NON_NULL_OFF.get()) {
                        List<Object> key = List.of(mi, link.to(), fromVar);
                        if (statement == null) {
                            if (guardedLocalMarkers.contains(key)) continue;
                        } else {
                            Expression assigned = assignedValue(statement, fromVar);
                            if (assigned != null && !(NonNullFacts.unwrap(assigned) instanceof NullConstant)
                                && facts.nonNull(assigned, facts.before(statement))) {
                                guardedLocalMarkers.add(key);
                                continue;
                            }
                        }
                    }
                    if (slot || statement == null || fromVar instanceof ReturnVariable
                        || assignsHere(statement, fromVar)) {
                        nullMarker(mi, link.to(), from, "null in " + mi.fullyQualifiedName());
                    }
                } else {
                    linkEdge(keyedSource(keyed, node(mi, scope, link.to())), from, link.to(), fromVar, statement);
                }
            } else if (link.linkNature().isIdenticalToOrAssignedFromTo()) {
                // '→': v is assigned to link.to()
                Keyed keyedTo = statement == null ? null : keyedRead(statement, link.to());
                linkEdge(keyedSource(keyedTo, from), node(mi, scope, link.to()), fromVar, link.to(), statement);
            }
        }
    }

    /*
     'container ∋ member' / 'member ∈ container', where the container side is a slot (Arg or Content). The link
     engine writes membership both ways for both a write ('list.add(p)') and a read ('x = list.get(0)'), and derives
     more by transitivity (the null that 'Maps.safeGet' returns besides 'map.get(k)' comes out as a null element of
     the map). So membership is read only as a READ: into a return value, and into a local or field in the statement
     that assigns it (a statement's data carries the variable's earlier links too). Writes come from the call site
     (receiverSlots: 'add(E)', 'put(K, V)', a helper's parameter through invariance).
     */
    private void membership(MethodInfo mi, Scope scope, Object side, Variable sideVar, boolean sideIsContainer,
                            Variable otherVar, Statement statement) {
        Object other = node(mi, scope, otherVar);
        Variable memberVar = sideIsContainer ? otherVar : sideVar;
        // the member read from a keyed map by this statement ('ids = ctx.get(IDS)'): the key's values (gap 2)
        Keyed keyed = statement == null ? null : keyedRead(statement, memberVar);
        Object container = keyedSource(keyed, sideIsContainer ? side : other);
        Object member = sideIsContainer ? other : side;
        if (!(container instanceof Arg || container instanceof Content || container instanceof Keyed)) return;
        if (member == null || member instanceof Arg || member instanceof Content || member instanceof Keyed) return;
        switch (memberVar) {
            case ParameterInfo _ -> {
            }
            case ReturnVariable _ -> addEdge(container, member);
            default -> {
                if (statement != null && assignsHere(statement, memberVar)) addEdge(container, member);
            }
        }
    }

    // does the statement give 'variable' its value: a declaration with initializer, an assignment, a for-each variable
    private static boolean assignsHere(Statement statement, Variable variable) {
        if (statement instanceof ForEachStatement fe && fe.initializer() != null) {
            return fe.initializer().localVariableStream().anyMatch(lv -> lv.equals(variable));
        }
        return assignedValue(statement, variable) != null;
    }

    // an edge from a link: sourceVar's value flows into recipientVar. Counted per statement that assigns the
    // recipient (decided in go): where every position in which the source can BE the assigned value is one where it
    // is known non-null, the statement carries no null from it (M4)
    private void linkEdge(Object from, Object to, Variable sourceVar, Variable recipientVar, Statement statement) {
        if (from == null || to == null || from.equals(to)) return;
        int[] count = linkEdges.computeIfAbsent(List.of(from, to), _ -> new int[4]);
        if (statement == null) {
            count[3]++; // a link of the method's summary, not of one statement
            return;
        }
        Expression value = assignedValue(statement, recipientVar);
        Guard guard;
        if (value == null && statement instanceof io.codelaser.maddi.cst.api.statement.ForStatement fs) {
            // 'for (ClassNode c = child; c != null; c = c.parent)': the loop assigns its variable in its init clause
            // and in its updaters, statements of their own that the walk does not visit. fernflower
            // NestedClassProcessor.insertLocalVars printed 'classNode = classNode.parent!!' once the pass read direct
            // links only (23f8bf65): the closure's composed null had covered it.
            List<Expression> values = forAssignedValues(fs, recipientVar);
            value = values.isEmpty() ? null : values.getFirst();
            guard = Guard.ABSENT;
            for (Expression v : values) guard = combine(guard, guard(v, sourceVar, facts.before(statement)));
            if (values.isEmpty()) guard = argumentGuard(statement, sourceVar, recipientVar);
        } else {
            guard = value == null ? argumentGuard(statement, sourceVar, recipientVar)
                    : guard(value, sourceVar, facts.before(statement));
        }
        if (guard == Guard.GUARDED) count[0]++;
        else if (guard == Guard.UNGUARDED) count[1]++;
        else if (guard == Guard.OPAQUE) count[2]++; // a value (or part) not seen through
        // a call that is not given the source can still return it through an alias ('table = this.table; return
        // table.get(i)', guava LocalCache.Segment.getFirst): assigned here by a value not seen through
        else if (value != null && NonNullFacts.unwrap(value) instanceof MethodCall) count[2]++;
    }

    /*
     A recipient the statement does not assign itself: the source reaches it through a call it is passed to
     ('converter = new IdentifierConverter(..., interceptor)' links the local to the new object's field). GUARDED when
     the source is known non-null at every call that takes it as an argument, as argument() decides for the parameter.
     */
    private Guard argumentGuard(Statement statement, Variable source, Variable recipient) {
        if (statement.expression() == null) return Guard.ABSENT;
        // only a call that can reach the recipient: a field of an object the statement assigns ('converter = new
        // X(..., interceptor)') or hands to the call; never a local, a parameter or a return. In fernflower's
        // 'mapSimpleNames.put(outerShortName, ...)' the link engine's closure linked 'outerShortName' to an
        // unrelated 'node.parent.classStruct.qualifiedName'.
        if (!(recipient instanceof FieldReference fr)) return Guard.ABSENT;
        Variable base = fr;
        while (base instanceof FieldReference f && f.scopeVariable() != null) base = f.scopeVariable();
        Variable recipientBase = base;
        boolean assignsBase = assignsHere(statement, recipientBase);
        Guard[] guard = {Guard.ABSENT};
        statement.expression().visit(e -> {
            if (e instanceof Lambda) return false;
            List<Expression> arguments = switch (e) {
                case MethodCall mc -> mc.parameterExpressions();
                case ConstructorCall cc -> cc.parameterExpressions();
                default -> List.of();
            };
            boolean reaches = assignsBase || e instanceof MethodCall mc && mc.object() != null && mentions(mc.object(), recipientBase)
                              || arguments.stream().anyMatch(a -> mentions(a, recipientBase));
            for (Expression argument : arguments) {
                if (!reaches) break;
                if (NonNullFacts.unwrap(argument) instanceof VariableExpression ve && ve.variable().equals(source)) {
                    guard[0] = combine(guard[0], facts.nonNullWhenCalled((Expression) e, source)
                                                 || facts.nonNullAt(statement, source) ? Guard.GUARDED : Guard.UNGUARDED);
                }
            }
            return true;
        });
        return guard[0];
    }

    /*
     A for loop's updater 'node = node.parent' reaches the links only through the loop's reconstruction, as the derived
     'child.parent → node' (the loop variable's first value is child): the engine emits no direct link for it. Read
     it as the updater's own assignment. fernflower NestedClassProcessor.insertLocalVars printed 'classNode =
     classNode.parent!!' once the pass read direct links only, and the null-constant composition no longer put the
     parent's null on the loop variable itself (23f8bf65).
     */
    private static boolean updaterLink(Statement statement, Link link) {
        if (!(statement instanceof io.codelaser.maddi.cst.api.statement.ForStatement fs)) return false;
        for (Expression updater : fs.updaters()) {
            if (NonNullFacts.unwrap(updater) instanceof Assignment a && a.assignmentOperator() == null
                && (a.variableTarget().equals(link.to()) || a.variableTarget().equals(link.from()))) {
                return true;
            }
        }
        return false;
    }

    // the values a for loop's init clause and updaters assign to 'recipient'
    private static List<Expression> forAssignedValues(io.codelaser.maddi.cst.api.statement.ForStatement fs,
                                                      Variable recipient) {
        List<Expression> values = new ArrayList<>();
        for (Element init : fs.initializers()) {
            if (init instanceof LocalVariableCreation lvc) {
                lvc.localVariableStream().filter(lv -> lv.equals(recipient))
                        .map(LocalVariable::assignmentExpression).filter(java.util.Objects::nonNull).forEach(values::add);
            } else if (init instanceof Assignment a && recipient.equals(a.variableTarget()) && a.assignmentOperator() == null) {
                values.add(a.value());
            }
        }
        for (Expression updater : fs.updaters()) {
            if (NonNullFacts.unwrap(updater) instanceof Assignment a && recipient.equals(a.variableTarget())
                && a.assignmentOperator() == null) {
                values.add(a.value());
            }
        }
        return values;
    }

    // gate NONESTEDASSIGNMENTS: a link into a variable assigned inside an expression is not counted (A/B)
    private static final Switches.Gate NESTED_ASSIGNMENTS_OFF = Switches.gate("NONESTEDASSIGNMENTS");

    // the value 'recipient' gets in this statement: 'recipient = v;', 'T recipient = v;', 'return v;'; else null
    private static Expression assignedValue(Statement statement, Variable recipient) {
        return switch (statement) {
            case LocalVariableCreation lvc -> lvc.localVariableStream().filter(lv -> lv.equals(recipient))
                    .map(LocalVariable::assignmentExpression).findFirst().orElse(null);
            case ReturnStatement rs -> recipient instanceof ReturnVariable ? rs.expression() : null;
            default -> statement.expression() instanceof Assignment a && recipient.equals(a.variableTarget())
                       && a.assignmentOperator() == null ? a.value() : null;
        };
    }

    /**
     * How {@code source} can become the value of an assigned expression. ABSENT: it does not occur as a value
     * (it may still flow in through an alias, so this decides nothing); GUARDED: every value position it occurs in
     * is one where it is known non-null, or it is assigned a non-null value right there
     * ({@code return result == null ? field = new X() : result}); UNGUARDED: somewhere it may be null; OPAQUE: a
     * part is not seen through (a call, another object's field).
     */
    private enum Guard {ABSENT, GUARDED, UNGUARDED, OPAQUE}

    private static final Switches.Gate KNOWN_ARGUMENTS_OFF = Switches.gate("NOKNOWNARGUMENTS");

    private Guard guard(Expression value, Variable source, Set<Variable> known) {
        // a value known non-null as a whole carries no null, whatever flows into it ('return requireNonNull(f)')
        if (facts.nonNull(value, known)) return Guard.GUARDED;
        Expression e = NonNullFacts.unwrap(value);
        switch (e) {
            case VariableExpression ve -> {
                if (sameVariable(ve.variable(), source)) {
                    return known.contains(source) ? Guard.GUARDED : Guard.UNGUARDED;
                }
                return ve.variable() instanceof io.codelaser.maddi.cst.api.variable.FieldReference fr
                       && !fr.scopeIsRecursivelyThis() ? Guard.OPAQUE : Guard.ABSENT;
            }
            case InlineConditional ic -> {
                Set<Variable> whenTrue = new java.util.HashSet<>(known);
                whenTrue.addAll(facts.whenTrue(ic.condition()));
                Set<Variable> whenFalse = new java.util.HashSet<>(known);
                whenFalse.addAll(facts.whenFalse(ic.condition()));
                return combine(guard(ic.ifTrue(), source, whenTrue), guard(ic.ifFalse(), source, whenFalse));
            }
            case Assignment a -> {
                if (a.assignmentOperator() != null) return Guard.OPAQUE;
                if (source.equals(a.variableTarget())) {
                    return facts.nonNull(a.value(), known) ? Guard.GUARDED : Guard.UNGUARDED;
                }
                return guard(a.value(), source, known);
            }
            case NullConstant _, ConstructorCall _, Lambda _, MethodReference _, ConstantExpression<?> _ -> {
                return Guard.ABSENT;
            }
            case MethodCall mc when !mentions(mc, source) -> {
                // a call that is not given the source cannot return it ('a == null ? Collections.emptyList() : a')
                return Guard.ABSENT;
            }
            case MethodCall _ when policy.callResults() && !KNOWN_ARGUMENTS_OFF.get() && known.contains(source) -> {
                // Kotlin: a call given the source where it is known non-null returns no null of it. guava
                // TypeResolver.TypeTable.resolveInternal: 'type = map.get(key); if (type == null) { ...; return ...; }
                // return new TypeResolver(...).resolveType(type)' carried ImmutableMap.get's null into the return,
                // TypeToken.runtimeType and MutableClassToInstanceMap.getInstance's parameter
                // (MutableClassToInstanceMap.kt:57, 2026-10-18). Gate NOKNOWNARGUMENTS.
                return Guard.GUARDED;
            }
            default -> {
                return Guard.OPAQUE;
            }
        }
    }

    private static boolean mentions(Expression e, Variable v) {
        boolean[] found = {false};
        e.visit(x -> {
            if (x instanceof VariableExpression ve && sameVariable(ve.variable(), v)) found[0] = true;
            return !found[0];
        });
        return found[0];
    }

    /*
     The variable a link names against the one an expression reads. An element is named by the link engine with its
     own index ('arr[1]' for the 'arr[i]' of 'val = arr[i]' in a loop), so an element of the same array counts as the
     same variable whatever the index: the pass keeps one Content per array anyway. Without it the direct link
     'val ← arr[1]' was never observed in its statement, and the element's null did not reach 'val' (fernflower
     SFormsFastMapDirect.removeAllFields, 'val val = arr!![i]!!', after 23f8bf65 dropped the derived 'val ∈ arr').
     */
    private static boolean sameVariable(Variable read, Variable linked) {
        if (read.equals(linked)) return true;
        return read instanceof DependentVariable r && linked instanceof DependentVariable l
               && sameVariable(r.arrayVariable(), l.arrayVariable());
    }

    private static Guard combine(Guard g1, Guard g2) {
        if (g1 == Guard.OPAQUE || g2 == Guard.OPAQUE) return Guard.OPAQUE;
        if (g1 == Guard.UNGUARDED || g2 == Guard.UNGUARDED) return Guard.UNGUARDED;
        if (g1 == Guard.GUARDED || g2 == Guard.GUARDED) return Guard.GUARDED;
        return Guard.ABSENT;
    }

    // returns the block's scope at its end
    private Scope handleBlock(MethodInfo mi, Block block, Scope parent) {
        Scope scope = new Scope(parent);
        List<Statement> statements = block.statements();
        for (int index = 0; index < statements.size(); index++) {
            Statement statement = statements.get(index);
            if (policy.assertAtDeclaration() && statement instanceof LocalVariableCreation lvc) {
                assertCandidates(mi, lvc, statements, index);
            }
            // a loop that assigns a parameter: every read inside it may see the assigned value (a later iteration)
            if (statement instanceof io.codelaser.maddi.cst.api.statement.LoopStatement) {
                shadowed.addAll(parametersAssignedIn(statement, mi));
            }
            // the statement's own expression is evaluated before its sub-blocks: what they assign does not shadow it
            // ('if (args == null) { args = …; }' tests the caller's value)
            Set<ParameterInfo> shadowedBefore = Set.copyOf(shadowed);
            // a statement's own declarations are visible in the statement only; a local variable creation's in the
            // rest of the block
            Scope own = new Scope(scope);
            Map<Block, TryStatement.CatchClause> catchBlocks = new IdentityHashMap<>();
            switch (statement) {
                case LocalVariableCreation lvc -> lvc.localVariableStream().forEach(lv -> declare(scope, mi, lvc, lv));
                case ForEachStatement fe when fe.initializer() != null -> fe.initializer().localVariableStream()
                        .forEach(lv -> declare(own, mi, fe.initializer(), lv));
                case ForStatement fs -> fs.initializers().forEach(e -> {
                    if (e instanceof LocalVariableCreation lvc) {
                        lvc.localVariableStream().forEach(lv -> declare(own, mi, lvc, lv));
                    }
                });
                case TryStatement ts -> {
                    ts.resources().forEach(r -> {
                        if (r instanceof LocalVariableCreation lvc) {
                            lvc.localVariableStream().forEach(lv -> declare(own, mi, lvc, lv));
                        }
                    });
                    ts.catchClauses().forEach(cc -> catchBlocks.put(cc.block(), cc));
                }
                default -> {
                }
            }
            statement.subBlockStream().forEach(sb -> {
                TryStatement.CatchClause cc = catchBlocks.get(sb);
                Scope blockParent = own;
                if (cc != null && cc.catchVariable() != null) {
                    blockParent = new Scope(own);
                    declare(blockParent, mi, cc, cc.catchVariable());
                }
                handleBlock(mi, sb, blockParent);
            });
            Set<ParameterInfo> shadowedBySubBlocks = new HashSet<>(shadowed);
            shadowed.retainAll(shadowedBefore);
            currentSite = statement;
            currentSiteMethod = mi;
            VariableData vd = VariableDataImpl.of(statement);
            if (vd != null) vd.variableInfoStream().forEach(vi -> linksOf(mi, own, vi, statement));
            if (statement instanceof TryStatement ts) {
                // 'try (In in = open(name))': each resource is a statement with its own variable data, a declaration
                // like any other (fernflower ContextUnit.reload passed a nullable resource on unnoticed)
                for (Statement resource : ts.resources()) {
                    VariableData rvd = VariableDataImpl.of(resource);
                    if (rvd != null) rvd.variableInfoStream().forEach(vi -> linksOf(mi, own, vi, resource));
                    syntacticSeeds(mi, own, resource);
                }
            }
            statement.visit(e -> {
                if (e instanceof Lambda lambda) {
                    if (lambda.methodBody() != null) handleBlock(lambda.methodInfo(), lambda.methodBody(), own);
                    return false;
                }
                // a switch expression's block arms are statements inside an expression: no sub-block of the
                // statement (fernflower StructTypeAnnotationAttribute.parse declared and filled an array there)
                if (e instanceof SwitchExpression se) {
                    se.entries().forEach(entry -> {
                        if (entry.statement() instanceof Block arm) handleBlock(mi, arm, own);
                    });
                }
                if (e instanceof Block) return false; // nested statements are handled with their own vd
                if (policy.nullTests() && e instanceof BinaryOperator bo) nullTest(mi, own, bo);
                if (e instanceof MethodCall || e instanceof ConstructorCall) {
                    currentSite = e;
                    currentSiteMethod = mi;
                }
                if (e instanceof MethodCall mc && mc.methodInfo() != null) {
                    callSite(mi, own, statement, mc, mc.methodInfo(), mc.analysis(), mc.parameterExpressions());
                } else if (e instanceof ConstructorCall cc && cc.constructor() != null) {
                    callSite(mi, own, statement, cc, cc.constructor(), cc.analysis(), cc.parameterExpressions());
                }
                if (e instanceof ConstructorCall cc) {
                    anonymousSuperArguments(mi, own, statement, cc);
                    anonymousThisTarget(mi, own, statement, cc);
                }
                return true;
            });
            currentSite = statement;
            currentSiteMethod = mi;
            syntacticSeeds(mi, own, statement);
            if (statement instanceof ExplicitConstructorInvocation eci && eci.methodInfo() != null) {
                callSite(mi, own, statement, null, eci.methodInfo(), eci.analysis(), eci.parameterExpressions());
            }
            // from here on a read of a parameter the statement assigned is a read of the shadow
            shadowed.addAll(shadowedBySubBlocks);
            shadowed.addAll(parametersAssignedIn(statement, mi));
            currentSite = null;
        }
        return scope;
    }

    /*
     A local declared with an initializer that the rest of its block dereferences before anything may leave the block,
     and that is never assigned again: in Java a null there throws at the dereference anyway (Policy
     .assertAtDeclaration). 'BasicBlock block = blocks.getWithKey(j); protectedRange.add(block);
     block.addSuccessorException(handle);' (fernflower ControlFlowGraph.setExceptionEdges).
     */
    private void assertCandidates(MethodInfo mi, LocalVariableCreation lvc, List<Statement> statements, int index) {
        lvc.localVariableStream().forEach(lv -> {
            Expression init = lv.assignmentExpression();
            if (init == null || init.isEmpty() || NonNullFacts.unwrap(init) instanceof NullConstant
                || lv.parameterizedType().isPrimitiveExcludingVoid()) return;
            List<Statement> rest = statements.subList(index + 1, statements.size());
            if (rest.stream().anyMatch(st -> assigns(st, lv))) return;
            for (int j = 0; j < rest.size(); j++) {
                Statement st = rest.get(j);
                if (dereferencesFirst(st, lv)) {
                    Local local = new Local(mi, lvc, lv.simpleName());
                    assertCandidates.add(local);
                    if (unobserved(lvc, rest.subList(0, j), lv)) unobservedCandidates.add(local);
                    return;
                }
                if (mayLeave(st)) return;
            }
        });
    }

    /*
     The linking engine's proof that nothing between the declaration and the dereference observes the local (then the
     printer reports it as INFO, not as a behaviour change): no statement in between refers to it, and no variable
     such a statement uses is an alias of it ('≡'/'←' links; also of a variable built on it: 'block.f'). Without the
     links of each statement (a degraded method) there is no proof.
     */
    private static boolean unobserved(LocalVariableCreation declaration, List<Statement> between, LocalVariable lv) {
        if (VariableDataImpl.of(declaration) == null) return false;
        for (Statement st : between) {
            VariableData vd = VariableDataImpl.of(st);
            if (vd == null) return false;
            Set<Variable> used = new HashSet<>();
            boolean[] direct = {false};
            st.visit(e -> {
                if (e instanceof VariableExpression ve) {
                    if (refersTo(ve.variable(), lv)) direct[0] = true;
                    used.add(ve.variable());
                }
                return !direct[0];
            });
            if (direct[0]) return false;
            for (Variable v : used) {
                VariableInfoContainer vic = vd.variableInfoContainerOrNull(v.fullyQualifiedName());
                if (vic == null) continue; // not a variable of this statement's data (a constant, a type)
                Links links = vic.best().linkedVariables();
                if (links == null) return false;
                for (Link link : links) {
                    // only the identity family of the link algebra ('≡', '←', '→') makes a variable the local's
                    // value; the others leave them possibly unrelated: 'from ∈ instrBlocks' and 'to ∈ instrBlocks'
                    // say nothing of 'from' against 'to'
                    if (link.linkNature().isIdenticalToOrAssignedFromTo()
                        && (refersTo(link.from(), lv) || refersTo(link.to(), lv))) return false;
                }
            }
        }
        return true;
    }

    private static boolean refersTo(Variable v, LocalVariable lv) {
        return v != null && v.variableStreamDescendIntoScope().anyMatch(lv::equals);
    }

    private static boolean assigns(Statement statement, LocalVariable lv) {
        boolean[] found = {false};
        statement.visit(e -> {
            if (e instanceof Assignment a && lv.equals(a.variableTarget())) found[0] = true;
            return !found[0];
        });
        return found[0];
    }

    // a return, throw, yield, break or continue anywhere in the statement, outside lambdas and nested classes
    private static boolean mayLeave(Statement statement) {
        boolean[] found = {false};
        statement.visit(e -> {
            if (e instanceof Lambda || e instanceof ConstructorCall cc && cc.anonymousClass() != null) return false;
            if (e instanceof ReturnStatement || e instanceof ThrowStatement || e instanceof YieldStatement
                || e instanceof BreakOrContinueStatement) found[0] = true;
            return !found[0];
        });
        return found[0];
    }

    // the statement's first evaluated expressions dereference lv, whatever their values
    private static boolean dereferencesFirst(Statement statement, LocalVariable lv) {
        return switch (statement) {
            case LocalVariableCreation lvc -> lvc.localVariableStream()
                    .anyMatch(v -> v.assignmentExpression() != null && dereferences(v.assignmentExpression(), lv));
            case ForEachStatement fe -> isVariable(fe.expression(), lv) || dereferences(fe.expression(), lv);
            // the init clause, then the condition, run before any iteration: 'for (int i = arr.length - 1; ...)'
            // (fernflower SFormsFastMapDirect.removeAllFields: 'val arr = elements[2]' then 'arr!!.size', 'arr!![i]')
            case ForStatement fs -> fs.initializers().stream().anyMatch(init -> switch (init) {
                case LocalVariableCreation lvc -> lvc.localVariableStream()
                        .anyMatch(v -> v.assignmentExpression() != null && dereferences(v.assignmentExpression(), lv));
                case Expression e -> dereferences(e, lv);
                default -> false;
            }) || fs.expression() != null && dereferences(fs.expression(), lv);
            case DoStatement _, TryStatement _, Block _ -> false;
            default -> statement.expression() != null && dereferences(statement.expression(), lv);
        };
    }

    // evaluating e certainly dereferences lv: not in a conditionally evaluated part, a lambda, or a switch arm
    private static boolean dereferences(Expression e, LocalVariable lv) {
        boolean[] found = {false};
        e.visit(x -> {
            if (found[0]) return false;
            switch (x) {
                case Lambda _ -> {
                    return false;
                }
                case InlineConditional ic -> {
                    found[0] = dereferences(ic.condition(), lv);
                    return false;
                }
                case BinaryOperator bo when NonNullFacts.isOperator(bo, "&&") || NonNullFacts.isOperator(bo, "||") -> {
                    found[0] = dereferences(bo.lhs(), lv);
                    return false;
                }
                case And and -> {
                    found[0] = !and.expressions().isEmpty() && dereferences(and.expressions().getFirst(), lv);
                    return false;
                }
                case Or or -> {
                    found[0] = !or.expressions().isEmpty() && dereferences(or.expressions().getFirst(), lv);
                    return false;
                }
                case SwitchExpression se -> {
                    found[0] = dereferences(se.selector(), lv);
                    return false;
                }
                case ConstructorCall cc when cc.anonymousClass() != null -> {
                    found[0] = cc.parameterExpressions().stream().anyMatch(a -> dereferences(a, lv));
                    return false;
                }
                case MethodCall mc when mc.methodInfo() != null && !mc.methodInfo().isStatic()
                                        && mc.object() != null && isVariable(mc.object(), lv) -> found[0] = true;
                case VariableExpression ve when ve.variable() instanceof FieldReference fr && !fr.fieldInfo().isStatic()
                                                && fr.scope() != null && isVariable(fr.scope(), lv) -> found[0] = true;
                case VariableExpression ve when ve.variable() instanceof DependentVariable dv
                                                && isVariable(dv.arrayExpression(), lv) -> found[0] = true;
                case io.codelaser.maddi.cst.api.expression.ArrayLength al when isVariable(al.scope(), lv) ->
                        found[0] = true;
                default -> {
                }
            }
            return !found[0];
        });
        return found[0];
    }

    // read from the code, not from the links: the literal null assigned to a local or field (so also in a degraded
    // method, which has no links), and a library call whose result may be null USED DIRECTLY: returned, assigned,
    // initializing a local (its value reaches the link graph only as an opaque '$_v')
    // the method and scope of the statement syntacticSeeds is reading, for callResult
    private record ReadScope(MethodInfo mi, Scope scope) {
    }

    private ReadScope readScope;
    private Statement seedStatement;

    /*
     Kotlin: 'x = lib(); if (x == null || ...) throw ...;' the library's null never leaves the assignment: the very
     next statement exits on it. Its seed on the local would reach every use after an if/else merge that loses the
     fact (guava TreeRangeMap.SubRangeMap.span(): 'lowerBound = entriesByLowerBound.ceilingKey(...)' in one branch,
     'subRange.lowerBound' in the other, made Range.lowerBound and ImmutableRangeSet's 'Cut<C>?' nullable,
     2026-10-17). Only an if without else whose block ends in a throw or return.
     */
    private final Set<Statement> deadNullAssignments = java.util.Collections.newSetFromMap(new IdentityHashMap<>());

    private void deadNullAssignments(Block block) {
        List<Statement> statements = block.statements();
        for (int i = 0; i < statements.size(); i++) {
            Statement statement = statements.get(i);
            for (Block sub : statement.subBlocks()) deadNullAssignments(sub);
            if (i + 1 >= statements.size()
                || !(statements.get(i + 1) instanceof io.codelaser.maddi.cst.api.statement.IfElseStatement ifs)
                || ifs.elseBlock() != null && !ifs.elseBlock().isEmpty() || !exits(ifs.block())) {
                continue;
            }
            Variable local = assignedLocal(statement);
            if (local != null && facts.whenFalse(ifs.expression()).contains(local)) deadNullAssignments.add(statement);
        }
    }

    private static boolean exits(Block block) {
        if (block == null || block.statements().isEmpty()) return false;
        Statement last = block.statements().getLast();
        return last instanceof ThrowStatement || last instanceof ReturnStatement;
    }

    private static Variable assignedLocal(Statement statement) {
        if (statement instanceof LocalVariableCreation lvc && lvc.localVariableStream().count() == 1) {
            LocalVariable lv = lvc.localVariableStream().findFirst().orElseThrow();
            return lv.assignmentExpression() == null ? null : lv;
        }
        if (statement instanceof ExpressionAsStatement && statement.expression() instanceof Assignment a
            && a.assignmentOperator() == null && a.variableTarget() instanceof LocalVariable lv) {
            return lv;
        }
        return null;
    }

    /*
     Kotlin: the local of such a dead library null is still declared nullable, though its null flows nowhere: the
     platform value checked into a non-null local throws on the very null the next statement handles. fernflower's
     ExprProcessor.writeNestedTypeAnnotations 'path = helper.getPaths().peek(); if (path == null) {...; return false;}'
     printed 'val path = ...peek()!!' (ExprProcessor.kt:932: SingleClassesTest and JavaTypeAnnotationsDecompilerTest,
     6 tests, worktree-maddi-all 2026-10-23). After the exit Kotlin smart-casts it, also across an if/else merge whose
     other branch assigns a non-null value. Gate NOGUARDEDLOCALS.
     */
    private final Set<Object> guardedLocals = new HashSet<>();

    private static final Switches.Gate GUARDED_LOCALS_OFF = Switches.gate("NOGUARDEDLOCALS");

    // gate NODEADNULLS: a library null assigned to a local seeds it even when the next statement exits on it (A/B)
    private static final Switches.Gate DEAD_NULLS_OFF = Switches.gate("NODEADNULLS");

    private void syntacticSeeds(MethodInfo mi, Scope scope, Statement statement) {
        readScope = new ReadScope(mi, scope);
        seedStatement = statement;
        try {
            syntacticSeedsIn(mi, scope, statement);
        } finally {
            readScope = null;
        }
    }

    private void syntacticSeedsIn(MethodInfo mi, Scope scope, Statement statement) {
        nestedAssignments(mi, scope, statement);
        if (statement instanceof ReturnStatement rs && !mi.isConstructor() && !mi.returnType().isVoid()) {
            callResult(mi, rs.expression());
            seedCreated(mi, rs.expression(), mi);
            constructorCopies(mi, scope, mi, rs.expression());
        } else if (statement instanceof LocalVariableCreation lvc) {
            lvc.localVariableStream().forEach(lv -> {
                Object local = local(mi, scope, lv.simpleName());
                if (lv.assignmentExpression() instanceof NullConstant) {
                    seed(local, "initialized null in " + mi.fullyQualifiedName(), mi.typeInfo());
                }
                callResult(local, lv.assignmentExpression());
                seedCreated(local, lv.assignmentExpression(), mi);
                constructorCopies(mi, scope, local, lv.assignmentExpression());
            });
        } else if (statement.expression() instanceof Assignment a && a.variableTarget() != null) {
            Object target = assignmentTarget(mi, scope, a.variableTarget());
            if (a.value() instanceof NullConstant) seed(target, "assigned null in " + mi.fullyQualifiedName(), mi.typeInfo());
            callResult(target, a.value());
            seedCreated(target, a.value(), mi);
            constructorCopies(mi, scope, target, a.value());
        } else if (statement instanceof ForEachStatement fe && fe.initializer() != null) {
            // for (T x : array): the elements flow into x
            Expression iterated = NonNullFacts.unwrap(fe.expression());
            Object array = iterated instanceof VariableExpression ve ? node(mi, scope, ve.variable())
                    : iterated instanceof MethodCall mc && mc.methodInfo() != null && analysed.contains(mc.methodInfo())
                    ? mc.methodInfo() : null;
            // Kotlin: a library view ('for (Map.Entry<K, V> e : data.entrySet())') holds the receiver's data: the loop
            // variable's slots are the map's, so 'e.setValue(null)' writes it (jenkins DiskSpaceMonitorDescriptor.kt:38,
            // 2026-10-22). Gate NOVIEWLOOPS.
            if (array == null && policy.callResults() && !VIEW_LOOPS_OFF.get()) array = viewArgument(mi, scope, iterated);
            ParameterizedType type = array == null ? null : typeOf(array);
            if (type != null && (type.arrays() > 0 || type.parameters().size() == 1)) {
                // over an array its elements, over an Iterable<T> its one type argument; the statement's own scope
                // declares the loop variable
                Object elements = type.arrays() > 0 ? new Content(array) : new Arg(array, 0);
                boolean view = !(iterated instanceof VariableExpression) && !(array instanceof MethodInfo);
                fe.initializer().localVariableStream().forEach(lv -> {
                    Object local = local(mi, scope, lv.simpleName());
                    addEdge(elements, local);
                    if (view && !VIEW_LOOP_WRITES_OFF.get()) viewLoopReads.add(List.of(elements, local));
                });
            }
        }
    }

    // an assignment inside an expression: 'if ((res = isHead(h)) != null)', 'while ((x = next()) != null)'
    private void nestedAssignments(MethodInfo mi, Scope scope, Statement statement) {
        Expression top = statement.expression();
        if (top == null) return;
        top.visit(e -> {
            if (e instanceof Lambda) return false;
            if (e instanceof Assignment a && a != top && a.variableTarget() != null && a.assignmentOperator() == null) {
                Object target = assignmentTarget(mi, scope, a.variableTarget());
                if (a.value() instanceof NullConstant) seed(target, "assigned null in " + mi.fullyQualifiedName(), mi.typeInfo());
                callResult(target, a.value());
                seedCreated(target, a.value(), mi);
                nestedCopy(mi, scope, statement, target, a.value());
            }
            return true;
        });
    }

    /*
     Kotlin: a variable copied by an assignment inside an expression ('while ((queuedTask = nextTask) != null)', guava
     ExecutionSequencer) takes the value as it is; the null test comes after. The links leave that copy out, and the
     local printed 'Runnable' (worktree-maddi-all 2026-10-17).
     */
    private void nestedCopy(MethodInfo mi, Scope scope, Statement statement, Object target, Expression value) {
        if (!policy.callResults() || NESTED_ASSIGNMENTS_OFF.get() || target == null
            || !(NonNullFacts.unwrap(value) instanceof VariableExpression ve)) return;
        Object source = node(mi, scope, ve.variable());
        if (source == null) return;
        if (facts.nonNull(value, facts.before(statement))) flows.add(List.of(source, target));
        else addEdge(source, target);
    }

    /*
     'new ArrayList<>(c)', 'new HashMap<>(m)': a library constructor copying the content of its argument into the
     new object, which is the target's ('l = new ArrayList<>(c)'): the argument's slots flow into the target's.
     */
    private void constructorCopies(MethodInfo mi, Scope scope, Object target, Expression value) {
        if (target == null || !(NonNullFacts.unwrap(value) instanceof ConstructorCall cc) || cc.constructor() == null
            || cc.parameterizedType() == null || cc.parameterizedType().arrays() > 0) return;
        contentCopies(mi, scope, target, cc.constructor(), cc.parameterExpressions());
        // an analysed generic class: an argument to a parameter typed with the class's type variables inside a
        // generic type ('Factory(Collection<E> set)') instantiates them with the new object's, i.e. the target's,
        // slots (instantiatedSlots)
        MethodInfo constructor = cc.constructor();
        ParameterizedType targetType = typeOf(target);
        if (!analysed.contains(constructor) || targetType == null || constructor.typeInfo().typeParameters().isEmpty()) {
            return;
        }
        List<ParameterInfo> parameters = constructor.parameters();
        List<Expression> arguments = cc.parameterExpressions();
        for (int i = 0; i < arguments.size() && i < parameters.size(); i++) {
            ParameterizedType formal = parameters.get(i).parameterizedType();
            if (formal.arrays() > 0 || formal.parameters().isEmpty()) continue;
            Object argument = argumentNode(mi, scope, arguments.get(i));
            if (argument == null) argument = viewArgument(mi, scope, arguments.get(i));
            if (argument == null) continue;
            ParameterizedType type = typeOf(argument);
            instantiatedSlots(argument, type != null ? type : arguments.get(i).parameterizedType(), parameters.get(i),
                    formal, target, targetType, constructor.typeInfo(), true);
        }
    }

    /*
     Kotlin: a library view passed as the argument ('new ProviderChain<>(installStateFilterChain.iterator())'): a node of
     its own (CallResult), its slots tied to the receiver's (libraryView). The list's 'Function<…, InstallState?>' then
     instantiates the chain's T (jenkins InstallUtil.kt:62, 2026-10-20). Gate NOVIEWARGUMENTS.
     */
    private Object viewArgument(MethodInfo mi, Scope scope, Expression argument) {
        if (VIEW_ARGUMENTS_OFF.get() || !(NonNullFacts.unwrap(argument) instanceof MethodCall mc) || mc.methodInfo() == null
            || analysed.contains(mc.methodInfo()) || mc.methodInfo().isStatic() || mc.concreteReturnType() == null) {
            return null;
        }
        CallResult result = new CallResult(mc);
        ReadScope outer = readScope;
        if (outer == null) readScope = new ReadScope(mi, scope); // a call site's argument (callSite has none)
        try {
            libraryView(result, mc, 0);
        } finally {
            readScope = outer;
        }
        return result;
    }

    private static final Switches.Gate VIEW_ARGUMENTS_OFF = Switches.gate("NOVIEWARGUMENTS");
    private static final Switches.Gate VIEW_METHOD_ARGUMENTS_OFF = Switches.gate("NOVIEWMETHODARGUMENTS");

    /*
     A parameter whose type arguments are the declaring class's type variables ('addAll(Collection<? extends E>)',
     'putAll(Map<? extends K, ? extends V>)', 'ArrayList(Collection<? extends E>)'): the argument's slot j flows into
     the receiver's slot of that variable. Slot to slot: the content of one object copied into another.
     */
    private void contentCopies(MethodInfo mi, Scope scope, Object receiver, MethodInfo callee,
                               List<Expression> arguments) {
        ParameterizedType type = typeOf(receiver);
        int arity = callee.typeInfo().typeParameters().size();
        if (type == null || type.arrays() > 0 || arity == 0 || type.parameters().size() != arity) return;
        List<ParameterInfo> parameters = callee.parameters();
        for (int i = 0; i < arguments.size() && i < parameters.size(); i++) {
            ParameterizedType pt = parameters.get(i).parameterizedType();
            if (pt.arrays() > 0 || pt.parameters().isEmpty()) continue;
            for (int j = 0; j < pt.parameters().size(); j++) {
                ParameterizedType a = pt.parameters().get(j);
                if (a.typeParameter() == null || a.typeParameter().isMethodTypeParameter()
                    || a.typeParameter().getOwner().getLeft() != callee.typeInfo()) continue;
                int index = a.typeParameter().getIndex();
                if (index >= arity) continue;
                for (Object source : slotSources(mi, scope, arguments.get(i), j)) {
                    addEdge(source, new Arg(receiver, index));
                }
            }
        }
    }

    /*
     Type argument j of what an expression denotes: of its node (argumentNode), or of a library view of a generic
     receiver, whose own type arguments are the receiver's class's type variables ('mapNext.values()' is a
     Collection<V>: its argument 0 is the map's V; fernflower FinallyProcessor 'new HashSet<>(mapNext.values())').
     */
    private Object slotOf(MethodInfo mi, Scope scope, Expression expression, int j) {
        Object node = argumentNode(mi, scope, expression);
        if (node != null) return new Arg(node, j);
        if (!(NonNullFacts.unwrap(expression) instanceof MethodCall mc) || mc.methodInfo() == null
            || mc.methodInfo().isStatic() || mc.object() == null || analysed.contains(mc.methodInfo())) return null;
        ParameterizedType rt = mc.methodInfo().returnType();
        if (rt.arrays() > 0 || j >= rt.parameters().size()) return null;
        ParameterizedType a = rt.parameters().get(j);
        if (!isTypeVariable(a) || a.typeParameter().isMethodTypeParameter()
            || a.typeParameter().getOwner().getLeft() != mc.methodInfo().typeInfo()) return null;
        Object receiver = argumentNode(mi, scope, mc.object());
        ParameterizedType receiverType = receiver == null ? null : typeOf(receiver);
        int index = receiverType == null ? -1
                : slotIndex(receiverType, mc.methodInfo().typeInfo(), a.typeParameter().getIndex());
        return index < 0 ? null : new Arg(receiver, index);
    }

    /*
     The node an expression denotes: a variable; an analysed method's result; or a read of a generic receiver's
     content, 'recv.get(k)' where the method returns a type variable of its class ('Map.get' returns V): that slot
     of the receiver, at any depth ('map.get(a).get(b)').
     */
    private Object argumentNode(MethodInfo mi, Scope scope, Expression argument) {
        Expression e = NonNullFacts.unwrap(argument);
        if (e instanceof VariableExpression ve) return node(mi, scope, ve.variable());
        if (!(e instanceof MethodCall mc) || mc.methodInfo() == null) return null;
        MethodInfo callee = mc.methodInfo();
        ParameterizedType rt = callee.returnType();
        if (!callee.isStatic() && mc.object() != null && rt.arrays() == 0 && rt.typeParameter() != null
            && !rt.typeParameter().isMethodTypeParameter()) {
            Object receiver = argumentNode(mi, scope, mc.object());
            ParameterizedType type = receiver == null ? null : typeOf(receiver);
            int index = type == null ? -1 : slotIndex(type, callee.typeInfo(), rt.typeParameter().getIndex());
            return index < 0 ? null : new Arg(receiver, index);
        }
        if (analysed.contains(callee) && !rt.isVoid()) return callee;
        return null;
    }

    // the node of a call's receiver: a variable; an analysed method's result with concrete type arguments; or a slot
    // read by a lookup ('ranges.computeIfAbsent(h, …)' is the map's value). Null otherwise: a static call, 'this', an
    // expression the pass has no node for.
    private Object receiverNode(MethodInfo mi, Scope scope, MethodCall mc) {
        if (mc.object() == null || mc.methodInfo() == null || mc.methodInfo().isStatic()) return null;
        Expression object = NonNullFacts.unwrap(mc.object());
        if (object instanceof VariableExpression ve && anonymousThis(ve.variable()) instanceof AnonymousThis at) {
            return at; // 'getData()' on a class's own 'this', typed by its generic superclass
        }
        if (object instanceof VariableExpression ve) return node(mi, scope, ve.variable());
        if (object instanceof MethodCall getter && getter.methodInfo() != null
            && analysed.contains(getter.methodInfo()) && !getter.methodInfo().isConstructor()
            && concreteArguments(getter.methodInfo().returnType())) {
            return getter.methodInfo();
        }
        if (object instanceof MethodCall lookup && argumentNode(mi, scope, lookup) instanceof Arg slot) return slot;
        return null;
    }

    /*
     Substitution. A call on a receiver of generic class C instantiates C's type variables with the receiver's type
     arguments, so a position typed by one of them denotes the receiver's slot for it. 'tmpSet = factory.spawnEmptySet()'
     with 'FastFixedSet<E> spawnEmptySet()' in Factory<E>: tmpSet's argument 0 is the factory's argument 0 (fernflower
     DomHelper). 'tmpSet.union(set)' with 'union(FastFixedSet<E> set)': the argument's argument 0 is the receiver's.
     'new Factory<>(lstStats)' with 'Factory(Collection<E> set)': lstStats' argument 0 is the new object's, which is the
     target's. Before, coupleContent tied such a slot to the declaration's own type-variable slot, which made 'E'
     nullable for the whole class (FastFixedSetFactory.kt:19/33, 'FastFixedSet<E?>').

     Directions: the receiver's slot flows into a result's, and an argument's slot flows into the receiver's (content
     copied in): real flows, always. The opposite directions are Kotlin's invariance (Policy.assertContentWrites, as
     for arrays): 'tmpSet.add(x)' with a nullable x needs 'FastFixedSetFactory<Statement?>'. Not into a '? extends'
     position, which only receives; and not where the class's own null makes the position 'E?' ('List<E> all() {
     ...; l.add(null); return l; }'): a 'List<Node?>' then matches a Factory<Node> already. That is decided after the
     graph is built (invariantTies, in once). A position typed by another type variable (a method's, another class's)
     is skipped; a concrete generic position ('Map<K, List<V>>') recurses.
     */
    private void instantiatedSlots(Object concrete, ParameterizedType concreteType, Object formalNode,
                                   ParameterizedType formal, Object receiver, ParameterizedType receiverType,
                                   TypeInfo owner, boolean intoReceiver) {
        if (concrete == null || concreteType == null || formal == null || concreteType.arrays() > 0
            || formal.arrays() > 0 || concreteType.parameters().size() != formal.parameters().size()) return;
        for (int i = 0; i < formal.parameters().size(); i++) {
            ParameterizedType f = formal.parameters().get(i);
            ParameterizedType c = concreteType.parameters().get(i);
            Arg slot = new Arg(concrete, i);
            Arg formalSlot = new Arg(formalNode, i);
            if (f.typeParameter() != null) {
                if (f.arrays() > 0 || f.typeParameter().isMethodTypeParameter()
                    || !f.typeParameter().getOwner().isLeft() || f.typeParameter().getOwner().getLeft() != owner) {
                    continue;
                }
                int j = slotIndex(receiverType, owner, f.typeParameter().getIndex());
                if (j < 0) continue;
                Arg receiverSlot = new Arg(receiver, j);
                // Kotlin: a position declared nullable ('Predicate<@Nullable T> equivalentTo(...)') is 'T?' for any
                // instantiation: it ties nothing back into the receiver's slot. EquivalenceTest's 'equalTo1.apply(null)'
                // on a Predicate<@Nullable Object> had made Equivalence.equals() an Equivalence<Any?>, which
                // Equals (an Equivalence<Object>) is not (Equivalence.kt:98/99, 2026-10-18)
                boolean declaredNullable = policy.callResults() && !NULLABLE_PARAMETER_SLOTS_OFF.get()
                                           && explicit(f.annotations()) == NullableState.NULLABLE;
                boolean receivesOnly = isExtendsWildcard(f) || declaredNullable;
                boolean consumes = f.wildcard() != null && f.wildcard().isSuper(); // 'sort(Comparator<? super E>)'
                // Kotlin (Policy.callResults): the class's own null in that position ('List<E> all() { ...;
                // l.add(null); return l; }') reaches this instantiation, and the receiver's slot flows into a result
                // ('multimap.keySet()' holds the multimap's keys) or into a consumer argument. Real flows, but for
                // Java annotations they carried guava's flow-insensitive '@Nullable Object key' (AsMap.get) through
                // the ImmutableSet<E> override hub into the EMPTY singletons: +246 noise (2026-10-09). The Java
                // policies keep the ties the links give them, as before.
                if (policy.callResults()) addEdge(formalSlot, slot);
                if (intoReceiver) {
                    if (consumes) {
                        if (policy.callResults()) addEdge(receiverSlot, slot);
                    } else {
                        addEdge(slot, receiverSlot);
                        if (policy.assertContentWrites() && !receivesOnly) {
                            invariantTies.add(new Object[]{receiverSlot, slot, formalSlot});
                        }
                    }
                } else {
                    if (policy.callResults()) addEdge(receiverSlot, slot);
                    if (policy.assertContentWrites() && !receivesOnly) {
                        invariantTies.add(new Object[]{slot, receiverSlot, formalSlot});
                    }
                }
            } else if (!f.parameters().isEmpty() && c.typeParameter() == null && c.arrays() == 0) {
                instantiatedSlots(slot, c, formalSlot, f, receiver, receiverType, owner, intoReceiver);
            }
        }
    }

    // the arguments of a call whose parameters are typed with the callee's class type variables inside a generic type
    private void instantiatedArguments(MethodInfo mi, Scope scope, Expression call, MethodInfo callee,
                                       List<Expression> arguments) {
        if (!(call instanceof MethodCall mc) || callee.typeInfo().typeParameters().isEmpty()) return;
        Object receiver = receiverNode(mi, scope, mc);
        ParameterizedType receiverType = receiver == null ? null : typeOf(receiver);
        if (receiverType == null) return;
        List<ParameterInfo> parameters = callee.parameters();
        for (int i = 0; i < arguments.size() && i < parameters.size(); i++) {
            ParameterizedType formal = parameters.get(i).parameterizedType();
            if (formal.arrays() > 0 || formal.parameters().isEmpty()) continue;
            Object argument = argumentNode(mi, scope, arguments.get(i));
            if (argument == null) continue;
            ParameterizedType type = typeOf(argument);
            instantiatedSlots(argument, type != null ? type : arguments.get(i).parameterizedType(), parameters.get(i),
                    formal, receiver, receiverType, callee.typeInfo(), true);
        }
    }

    /*
     Streams. The pass has no node for a stream; it follows the pipeline to the nodes whose values are its elements:
     'c.stream()' (the collection's slot), 'Arrays.stream(a)', 'Stream.of(x, y)'; through filter, sorted, distinct, …;
     through 'map(t -> t.f)' to the lambda body's node (a field, a call result, a slot read) or a method reference's
     method. A terminal 'toList()', 'collect(Collectors.toList() / toSet() / toCollection(…) / toMap(k, v))' or
     'findFirst()' then makes those nodes values of the result's slot. Fernflower's SwitchPatternHelper carries
     'List<@Nullable Exprent>' elements through two such pipelines and two records into 'FullCase.exprents'.
     */
    private static final Set<String> STREAM_PASS_THROUGH = Set.of("filter", "sorted", "distinct", "limit", "skip",
            "peek", "parallel", "sequential", "unordered", "onClose", "boxed", "takeWhile", "dropWhile");

    private static boolean isStreamType(MethodInfo mi) {
        return mi.typeInfo().fullyQualifiedName().startsWith("java.util.stream.");
    }

    // the nodes whose values are the elements of a stream expression; empty when the pass does not model it
    private List<Object> streamElements(MethodInfo mi, Scope scope, Expression expression) {
        if (expression == null) return List.of();
        Expression e = NonNullFacts.unwrap(expression);
        if (!(e instanceof MethodCall mc) || mc.methodInfo() == null) return List.of();
        MethodInfo callee = mc.methodInfo();
        String name = callee.name();
        List<Expression> args = mc.parameterExpressions();
        if (isStreamType(callee)) {
            // Kotlin: 'filter(Objects::nonNull)', 'filter(x -> x != null)' lets no null through (jenkins CspBuilder.kt:52,
            // Jenkins.kt:619, 2026-10-21). Gate NONULLFILTERS.
            if ("filter".equals(name) && args.size() == 1 && nullFilter(args.getFirst())) return List.of();
            if (STREAM_PASS_THROUGH.contains(name)) return streamElements(mi, scope, mc.object());
            if (("map".equals(name) || "mapToObj".equals(name)) && args.size() == 1) {
                return mapped(mi, scope, mc.object(), args.getFirst());
            }
            if ("of".equals(name) && callee.isStatic()) {
                List<Object> nodes = new ArrayList<>();
                for (Expression a : args) {
                    Object n = argumentNode(mi, scope, a);
                    if (n != null) nodes.add(n);
                }
                return nodes;
            }
            if ("concat".equals(name) && callee.isStatic() && args.size() == 2) {
                List<Object> nodes = new ArrayList<>(streamElements(mi, scope, args.get(0)));
                nodes.addAll(streamElements(mi, scope, args.get(1)));
                return nodes;
            }
            return List.of();
        }
        if (("stream".equals(name) || "parallelStream".equals(name)) && callee.parameters().isEmpty()
            && mc.object() != null && !callee.isStatic()) {
            Object slot = slotOf(mi, scope, mc.object(), 0);
            return slot == null ? List.of() : List.of(slot);
        }
        if ("java.util.Arrays".equals(callee.typeInfo().fullyQualifiedName()) && "stream".equals(name)
            && !args.isEmpty()) {
            Object array = argumentNode(mi, scope, args.getFirst());
            return array == null ? List.of() : List.of(new Content(array));
        }
        return List.of();
    }

    private static final Switches.Gate NULL_FILTERS_OFF = Switches.gate("NONULLFILTERS");

    // a predicate that holds only for a non-null element: 'Objects::nonNull', 'x -> x != null', 'x -> null != x'
    private boolean nullFilter(Expression predicate) {
        if (NULL_FILTERS_OFF.get() || !policy.callResults()) return false;
        Expression p = NonNullFacts.unwrap(predicate);
        if (p instanceof MethodReference mr && mr.methodInfo() != null) {
            return "nonNull".equals(mr.methodInfo().name())
                   && "java.util.Objects".equals(mr.methodInfo().typeInfo().fullyQualifiedName());
        }
        if (!(p instanceof Lambda lambda) || lambda.methodInfo() == null || lambda.methodInfo().parameters().size() != 1) {
            return false;
        }
        if (lambda.singleExpression() == null) return false;
        Expression body = NonNullFacts.unwrap(lambda.singleExpression());
        if (!(body instanceof BinaryOperator bo) || !NonNullFacts.isOperator(bo, "!=")) return false;
        ParameterInfo element = lambda.methodInfo().parameters().getFirst();
        Expression l = NonNullFacts.unwrap(bo.lhs());
        Expression r = NonNullFacts.unwrap(bo.rhs());
        return l instanceof NullConstant && r instanceof VariableExpression rv && element.equals(rv.variable())
               || r instanceof NullConstant && l instanceof VariableExpression lv && element.equals(lv.variable());
    }

    // the nodes a mapping function ('map(t -> t.f)', 'map(Foo::bar)') produces from the elements of 'source'
    private List<Object> mapped(MethodInfo mi, Scope scope, Expression source, Expression function) {
        Expression f = NonNullFacts.unwrap(function);
        if (f instanceof MethodReference mr && mr.methodInfo() != null) {
            MethodInfo m = mr.methodInfo();
            return !m.isConstructor() && !m.returnType().isVoid() && analysed.contains(m) ? List.of(m) : List.of();
        }
        if (!(f instanceof Lambda lambda) || lambda.methodInfo() == null) return List.of();
        Expression body = lambda.singleExpression();
        if (body == null && lambda.methodBody() != null && lambda.methodBody().statements().size() == 1
            && lambda.methodBody().statements().getFirst() instanceof ReturnStatement rs) {
            body = rs.expression();
        }
        if (body == null) return List.of();
        Expression value = NonNullFacts.unwrap(body);
        // the element itself ('map(t -> t)'): the source's elements
        if (value instanceof VariableExpression ve && ve.variable() instanceof ParameterInfo pi
            && lambda.methodInfo().equals(pi.methodInfo())) {
            return streamElements(mi, scope, source);
        }
        Object node = argumentNode(lambda.methodInfo(), scope, body);
        return node == null ? List.of() : List.of(node);
    }

    // a terminal operation: slot index of its result -> the nodes whose values it collects there; empty otherwise
    private Map<Integer, List<Object>> streamTerminal(MethodInfo mi, Scope scope, Expression expression) {
        Expression e = NonNullFacts.unwrap(expression);
        if (!(e instanceof MethodCall mc) || mc.methodInfo() == null || mc.object() == null
            || !isStreamType(mc.methodInfo())) return Map.of();
        String name = mc.methodInfo().name();
        List<Expression> args = mc.parameterExpressions();
        List<Object> elements;
        switch (name) {
            case "toList", "findFirst", "findAny", "min", "max" -> elements = streamElements(mi, scope, mc.object());
            case "collect" -> {
                if (args.size() != 1 || !(NonNullFacts.unwrap(args.getFirst()) instanceof MethodCall collector)
                    || collector.methodInfo() == null
                    || !"java.util.stream.Collectors".equals(collector.methodInfo().typeInfo().fullyQualifiedName())) {
                    return Map.of();
                }
                List<Expression> cargs = collector.parameterExpressions();
                switch (collector.methodInfo().name()) {
                    case "toList", "toSet", "toUnmodifiableList", "toUnmodifiableSet", "toCollection" ->
                            elements = streamElements(mi, scope, mc.object());
                    case "toMap", "toUnmodifiableMap" -> {
                        if (cargs.size() < 2) return Map.of();
                        Map<Integer, List<Object>> slots = new LinkedHashMap<>();
                        List<Object> keys = mapped(mi, scope, mc.object(), cargs.get(0));
                        List<Object> values = mapped(mi, scope, mc.object(), cargs.get(1));
                        if (!keys.isEmpty()) slots.put(0, keys);
                        if (!values.isEmpty()) slots.put(1, values);
                        return slots;
                    }
                    default -> {
                        return Map.of();
                    }
                }
            }
            default -> {
                return Map.of();
            }
        }
        return elements.isEmpty() ? Map.of() : Map.of(0, elements);
    }

    // the nodes whose values are slot j of what an expression denotes: slotOf, or a stream terminal's elements
    private List<Object> slotSources(MethodInfo mi, Scope scope, Expression expression, int j) {
        Object slot = slotOf(mi, scope, expression, j);
        if (slot != null) return List.of(slot);
        return streamTerminal(mi, scope, expression).getOrDefault(j, List.of());
    }

    /*
     'new T[n]' (no initializer) holds nulls until filled: Kotlin's arrayOfNulls. With fewer dimension expressions
     than dimensions ('new T[n][]') the inner arrays are null; else the innermost reference elements are. Returns the
     depth of the Content that is null, 0 when the expression is not such a creation.
     */
    private static int arrayCreatedWithNulls(Expression value) {
        // the front end's array creation is a ConstructorCall of a synthetic constructor, with the array's type
        if (!(NonNullFacts.unwrap(value) instanceof ConstructorCall cc) || cc.arrayInitializer() != null) return 0;
        ParameterizedType type = cc.parameterizedType();
        if (type == null || type.arrays() == 0) return 0;
        List<Expression> sizes = cc.parameterExpressions().stream().filter(e -> !e.isEmpty()).toList();
        // 'new T[0]': no elements (guava's EMPTY_ARRAY)
        if (!sizes.isEmpty() && sizes.getFirst() instanceof io.codelaser.maddi.cst.api.expression.IntConstant ic
            && ic.constant() == 0) return 0;
        int dimensions = sizes.size();
        if (dimensions > 0 && dimensions < type.arrays()) return dimensions;
        boolean primitive = type.copyWithoutArrays().isPrimitiveExcludingVoid();
        return primitive ? 0 : type.arrays();
    }

    /*
     'T[] a = new T[n]; for (int i = 0; i < n; i++) { ... a[i] = v; ... }': every element is written before anything
     reads the array, so the creation leaves no null; each 'v' flows into the elements through the links. Only a
     one-dimensional creation, followed directly by the loop: from 0, while i < n (the same size expression) or
     i < a.length, one step at a time, with 'a[i] = ...' as a statement of the body itself, and no break, continue
     or return in the body, and no other assignment to i. Guava 2026-10-07: 'TypeResolver.resolveTypes' and the like.
     */
    private void indexFills(Block block) {
        List<Statement> statements = block.statements();
        for (int i = 0; i < statements.size(); i++) {
            Statement statement = statements.get(i);
            {
                Variable array = null;
                Expression init = null;
                if (statement instanceof LocalVariableCreation lvc) {
                    List<LocalVariable> locals = lvc.localVariableStream().toList();
                    if (locals.size() == 1) {
                        array = locals.getFirst();
                        init = locals.getFirst().assignmentExpression();
                    }
                } else if (!FIELD_FILLS_OFF.get() && statement instanceof ExpressionAsStatement eas
                           && eas.expression() instanceof Assignment a && a.assignmentOperator() == null
                           && (a.variableTarget() instanceof FieldReference || a.variableTarget() instanceof LocalVariable)) {
                    // `this.workers = new Worker[n]; for (...) workers[i] = new Worker();` in a constructor (nacos's
                    // NacosExecuteTaskExecuteEngine, CodeLaser/maddi-mod#22 gap 4), or a local assigned after its declaration
                    array = a.variableTarget();
                    init = a.value();
                }
                if (init != null && arrayCreatedWithNulls(init) == 1
                    && NonNullFacts.unwrap(init) instanceof ConstructorCall cc
                    && (i + 1 < statements.size() && statements.get(i + 1) instanceof ForStatement loop
                        && fills(loop, array, cc.parameterExpressions().getFirst())
                        || !COUNTED_FILLS_OFF.get() && countedFill(statements, i, array, cc.parameterExpressions().getFirst()))) {
                    filledCreations.add(init);
                }
            }
            statement.subBlockStream().forEach(this::indexFills);
            statement.visit(e -> {
                if (e instanceof SwitchExpression se) {
                    se.entries().forEach(entry -> {
                        if (entry.statement() instanceof Block arm) indexFills(arm);
                    });
                }
                return !(e instanceof Block) && !(e instanceof Lambda);
            });
        }
    }

    private static boolean fills(ForStatement loop, Variable array,
                                 Expression size) {
        if (loop.initializers().size() != 1 || !(loop.initializers().getFirst() instanceof LocalVariableCreation init)
            || init.localVariableStream().count() != 1) return false;
        LocalVariable index = init.localVariableStream().findFirst().orElseThrow();
        if (!(index.assignmentExpression() instanceof io.codelaser.maddi.cst.api.expression.IntConstant zero)
            || zero.constant() != 0) return false;
        if (!(NonNullFacts.unwrap(loop.expression()) instanceof BinaryOperator bo) || bo.operator() == null
            || !"<".equals(bo.operator().name()) || !isVariable(bo.lhs(), index)) return false;
        Expression bound = NonNullFacts.unwrap(bo.rhs());
        boolean sameBound = bound.equals(NonNullFacts.unwrap(size))
                            || bound instanceof io.codelaser.maddi.cst.api.expression.ArrayLength length
                               && isVariable(length.scope(), array);
        if (!sameBound || loop.updaters().size() != 1 || !(loop.updaters().getFirst() instanceof Assignment step)
            || !index.equals(step.variableTarget()) || !step.assignmentOperatorIsPlus()
            || step.prefixPrimitiveOperator() == null
               && !(step.value() instanceof io.codelaser.maddi.cst.api.expression.IntConstant one && one.constant() == 1)) {
            return false;
        }
        boolean[] escapes = {false};
        loop.block().visit(e -> {
            if (e instanceof Lambda) return false;
            // a return leaves a local array unread unless it hands the array out: 'if (x == null) return null;'
            // (timefold SolverMetricUtil.extractScore, CodeLaser/maddi-mod#27 1); a field keeps what was filled
            boolean returnsArray = e instanceof ReturnStatement rs
                                   && (!(array instanceof LocalVariable) || rs.expression() != null
                                                                            && mentions(rs.expression(), array));
            if (e instanceof BreakOrContinueStatement || returnsArray
                || e instanceof Assignment a && index.equals(a.variableTarget())) escapes[0] = true;
            return !escapes[0];
        });
        if (escapes[0]) return false;
        return loop.block().statements().stream().anyMatch(st -> st instanceof ExpressionAsStatement eas
                && eas.expression() instanceof Assignment a && a.assignmentOperator() == null
                && a.variableTarget() instanceof DependentVariable dv
                && isVariable(dv.arrayExpression(), array) && isVariable(dv.indexExpression(), index));
    }

    /*
     'T[] a = new T[c.size()]; ... int k = 0; ... for (X x : c) { ... a[k++] = v; ... }': one element per element of
     the collection, so the creation leaves no null (guava's RegularImmutableMultiset.create, whose entryArray became
     Array<ImmutableEntry<E>?> and with it IndexedImmutableSet.get's E?, worktree-maddi-all 2026-10-27). The size is
     'c.size()' or a local holding it; the statements between do not mention the array, except an
     'if (size == 0) return ...;' (no element to fill); the counter is a local declared there from 0, written only by
     the one 'a[k++] = v', a statement of the loop's own body; no break, continue, or return handing out the array.
     Gate NOCOUNTEDFILLS.
     */
    private static boolean countedFill(List<Statement> statements, int creation, Variable array, Expression size) {
        Expression sizeExpression = NonNullFacts.unwrap(size);
        Variable sizeLocal = null;
        if (sizeExpression instanceof VariableExpression sv && sv.variable() instanceof LocalVariable lv
            && lv.assignmentExpression() != null) {
            sizeLocal = lv;
            sizeExpression = NonNullFacts.unwrap(lv.assignmentExpression());
        }
        if (!(sizeExpression instanceof MethodCall mc) || !"size".equals(mc.methodInfo().name())
            || !mc.parameterExpressions().isEmpty()
            || !(NonNullFacts.unwrap(mc.object()) instanceof VariableExpression cv)) return false;
        Variable collection = cv.variable();
        Set<Variable> counters = new HashSet<>();
        for (int j = creation + 1; j < statements.size(); j++) {
            Statement st = statements.get(j);
            if (st instanceof ForEachStatement fe && isVariable(fe.expression(), collection)) {
                return countedLoop(fe, array, counters);
            }
            if (writes(st, collection) || sizeLocal != null && writes(st, sizeLocal)) return false;
            for (Variable counter : counters) if (writes(st, counter)) return false;
            if (st instanceof LocalVariableCreation lvc) {
                for (LocalVariable lv : lvc.localVariableStream().toList()) {
                    if (lv.parameterizedType().isInt() && lv.assignmentExpression() instanceof
                            io.codelaser.maddi.cst.api.expression.IntConstant zero && zero.constant() == 0) {
                        counters.add(lv);
                    }
                }
            }
            if (mentionsInStatement(st, array) && !emptyReturn(st, sizeLocal, sizeExpression)) return false;
        }
        return false;
    }

    // 'if (n == 0) return ...;' without else: the array has no element yet to fill
    private static boolean emptyReturn(Statement st, Variable sizeLocal, Expression sizeCall) {
        if (!(st instanceof io.codelaser.maddi.cst.api.statement.IfElseStatement ifs)
            || ifs.elseBlock() != null && !ifs.elseBlock().isEmpty()
            || ifs.block().statements().size() != 1 || !(ifs.block().statements().getFirst() instanceof ReturnStatement)
            || !(NonNullFacts.unwrap(ifs.expression()) instanceof BinaryOperator bo) || bo.operator() == null
            || !"==".equals(bo.operator().name())
            || !(NonNullFacts.unwrap(bo.rhs()) instanceof io.codelaser.maddi.cst.api.expression.IntConstant zero)
            || zero.constant() != 0) return false;
        Expression lhs = NonNullFacts.unwrap(bo.lhs());
        return sizeLocal != null ? isVariable(lhs, sizeLocal) : lhs.equals(sizeCall);
    }

    private static boolean countedLoop(ForEachStatement loop, Variable array, Set<Variable> counters) {
        Variable[] counter = {null};
        for (Statement st : loop.block().statements()) {
            if (st instanceof ExpressionAsStatement eas && eas.expression() instanceof Assignment a
                && a.assignmentOperator() == null && a.variableTarget() instanceof DependentVariable dv
                && isVariable(dv.arrayExpression(), array)
                && NonNullFacts.unwrap(dv.indexExpression()) instanceof Assignment step
                && Boolean.FALSE.equals(step.prefixPrimitiveOperator()) && step.assignmentOperatorIsPlus()
                && counters.contains(step.variableTarget())) {
                if (counter[0] != null) return false;
                counter[0] = step.variableTarget();
            }
        }
        if (counter[0] == null) return false;
        boolean[] escapes = {false};
        int[] steps = {0};
        loop.block().visit(e -> {
            if (e instanceof Lambda) return false;
            if (e instanceof BreakOrContinueStatement
                || e instanceof ReturnStatement rs && rs.expression() != null && mentions(rs.expression(), array)) {
                escapes[0] = true;
            }
            if (e instanceof Assignment a && counter[0].equals(a.variableTarget()) && ++steps[0] > 1) escapes[0] = true;
            return !escapes[0];
        });
        return !escapes[0];
    }

    private static boolean writes(Statement st, Variable v) {
        boolean[] found = {false};
        st.visit(e -> {
            if (e instanceof Assignment a && v.equals(a.variableTarget())) found[0] = true;
            return !found[0];
        });
        return found[0];
    }

    private static boolean mentionsInStatement(Statement st, Variable v) {
        boolean[] found = {false};
        st.visit(e -> {
            if (e instanceof VariableExpression ve && sameVariable(ve.variable(), v)) found[0] = true;
            return !found[0];
        });
        return found[0];
    }

    // gate NOCOUNTEDFILLS: a creation filled by a counted for-each keeps its null elements (A/B)
    private static final Switches.Gate COUNTED_FILLS_OFF = Switches.gate("NOCOUNTEDFILLS");

    private static boolean isVariable(Expression e, Variable v) {
        return NonNullFacts.unwrap(e) instanceof VariableExpression ve && ve.variable().equals(v);
    }

    private void seedCreated(Object target, Expression value, MethodInfo mi) {
        if (target == null || value == null) return;
        int depth = filledCreations.contains(value) ? 0 : arrayCreatedWithNulls(value);
        if (depth > 0) {
            Object content = target;
            for (int d = 0; d < depth; d++) content = new Content(content);
            seed(content, "array created with null elements in " + where(mi), writer(mi));
        }
        Expression unwrapped = NonNullFacts.unwrap(value);
        ArrayInitializer initializer = unwrapped instanceof ArrayInitializer ai ? ai
                : unwrapped instanceof ConstructorCall cc ? cc.arrayInitializer() : null;
        if (initializer != null) seedInitializer(new Content(target), initializer, mi);
    }

    /*
     An array initializer, at any depth: a null element seeds the elements, a nested initializer or creation is
     walked one level down. The links have this index-precisely for a method's own arrays, but not for a field
     initializer ('int[][][] t = {{null, null}, null}'), which no method's variable data holds.
     */
    private void seedInitializer(Content elements, ArrayInitializer initializer, MethodInfo mi) {
        for (Expression element : initializer.expressions()) {
            // also an alternative of the element: '{…, monitor ? "1" : null}' (fernflower FlattenStatementsHelper)
            for (Expression e : alternatives(element)) {
                if (NonNullFacts.unwrap(e) instanceof NullConstant) {
                    seed(elements, "null in an array initializer in " + where(mi), writer(mi));
                } else {
                    seedCreated(elements, e, mi);
                }
            }
        }
    }

    private static String where(MethodInfo mi) {
        return mi == null ? "a field initializer" : mi.fullyQualifiedName();
    }

    // a call's result used directly: a library method that may return null, or (Policy.contracts) an analysed one
    // annotated nullable, seeds. Neither leaves a null marker in the link summary. NOT a general edge from every
    // analysed callee's return: on guava that carried the flow-insensitivity of 'v = get(k); if (v == null) ...'
    // into the callers, +947 noise for -27 unsafe (2026-10-06); to be revisited with the use-site pass (M4).
    private void callResult(Object target, Expression value) {
        for (Expression alternative : alternatives(value)) callResultOf(target, alternative);
    }

    /*
     The values an expression may evaluate to: a conditional's branches, a switch expression's arms ('-> v', and each
     'yield v' of a block arm), recursively; else the expression itself. Fernflower's 'MatchEngine': 'value = switch
     (property) { case STATEMENT_TYPE -> stat_type.get(strValue); ... }'.
     */
    private static List<Expression> alternatives(Expression value) {
        List<Expression> out = new ArrayList<>();
        alternatives(value, out);
        return out;
    }

    private static void alternatives(Expression value, List<Expression> out) {
        if (value == null) return;
        switch (NonNullFacts.unwrap(value)) {
            case InlineConditional ic -> {
                alternatives(ic.ifTrue(), out);
                alternatives(ic.ifFalse(), out);
            }
            case SwitchExpression se -> se.entries().forEach(entry -> {
                Statement arm = entry.statement();
                if (arm instanceof Block block) {
                    block.visit(e -> {
                        if (e instanceof YieldStatement ys) alternatives(ys.expression(), out);
                        // a nested switch expression's yields are its own
                        return !(e instanceof Lambda) && !(e instanceof SwitchExpression);
                    });
                } else if (arm instanceof ExpressionAsStatement || arm instanceof YieldStatement) {
                    alternatives(arm.expression(), out);
                }
            });
            default -> out.add(value);
        }
    }

    private void callResultOf(Object target, Expression value) {
        Expression unwrapped = value instanceof Cast c ? c.expression() : value;
        if (target == null || !(unwrapped instanceof MethodCall mc) || mc.methodInfo() == null) return;
        MethodInfo callee = mc.methodInfo();
        if (readScope != null) factorySlots(target, value, readScope.mi());
        if (policy.callResults() && readScope != null) {
            for (Expression source : passedThrough(mc)) {
                Object node = argumentNode(readScope.mi(), readScope.scope(), source);
                if (node != null) flows.add(List.of(node, target)); // Kotlin infers one type: content slots tied
            }
            if (readScope != null) {
                callTargets.computeIfAbsent(mc, _ -> new ArrayList<>())
                        .add(new CallTarget(target, readScope.mi(), readScope.scope()));
            }
            typeArgumentsPassedThrough(mc).forEach((source, k) -> {
                // Kotlin: a lambda in a functional position whose result is T ('<T> T ifOverridden(Supplier<T>, …)')
                // returns the call's result (jenkins CrumbIssuer.kt:60, 2026-10-21). Gate NOLAMBDATYPEVARRESULTS.
                if (!LAMBDA_TYPE_VARIABLE_RESULTS_OFF.get() && NonNullFacts.unwrap(source) instanceof Lambda lambda
                    && lambda.methodInfo() != null && lambdaResultsPosition(lambda, k)) {
                    addEdge(lambda.methodInfo(), target);
                    return;
                }
                Object node = argumentNode(readScope.mi(), readScope.scope(), source);
                if (node != null) flows.add(List.of(new Arg(node, k), target));
                // Kotlin: from a '?' argument the result is the star projection's value, Any? ('Object v =
                // getUninterruptibly(future)' on a ListenableFuture<?>: guava AbstractFuture.getFutureValue then tests
                // 'v == null', AbstractFuture.kt:346, 2026-10-18). Gate NOSTARRESULTS.
                ParameterizedType st = node == null ? null : typeOf(node);
                if (!STAR_RESULTS_OFF.get() && st != null && k < st.parameters().size()
                    && st.parameters().get(k).wildcard() != null) {
                    addEdge(new Arg(node, k), target);
                }
            });
            arrayResultElements(target, mc);
            libraryView(target, mc, 0);
            if (!ARRAY_ELEMENTS_PASSED_OFF.get()) {
                List<Expression> calls = new ArrayList<>();
                calls.add(mc);
                for (Expression source : passedThrough(mc)) {
                    if (NonNullFacts.unwrap(source) instanceof MethodCall inner) calls.add(inner);
                }
                for (Expression c : calls) {
                    MethodCall arrayCall = (MethodCall) NonNullFacts.unwrap(c);
                    // a library method only: an analysed one's parameter elements are tied to every caller's
                    // array, and its body decides its result ('ImmutableList.copyOf(E[])' had carried the tests'
                    // nulls into Types.TypeVariableImpl.bounds, Files.FileByteSink.modes; guava 2026-10-17)
                    if (analysed.contains(arrayCall.methodInfo())) continue;
                    arrayElementsPassedThrough(arrayCall).forEach((source, k) -> {
                        Object node = argumentNode(readScope.mi(), readScope.scope(), source);
                        if (node == null) return;
                        Arg slot = new Arg(target, k);
                        addEdge(new Content(node), slot);
                        // a local array of the method's own type variable: its null is the slot's, not an
                        // instantiation's. Not a parameter's array, whose elements are each caller's: through
                        // 'ImmutableSet.copyOf(E[])' and 'Iterators.forArray(T...)' the tests' nulls had made
                        // their results ImmutableSet<E?> and UnmodifiableIterator<T?> (guava 2026-10-17)
                        ParameterizedType type = typeOf(node);
                        if (node instanceof Local && type != null && type.arrays() == 1
                            && isTypeVariable(type.copyWithOneFewerArrays())) {
                            typeVariableElementSlots.add(slot);
                        }
                    });
                }
            }
        }
        if (analysed.contains(callee) && !callee.isConstructor() && !callee.returnType().isVoid()) {
            ParameterizedType rt = callee.returnType();
            // the same object: its content slots are one (coupleContent); not where the callee's type arguments are
            // type variables, which each call instantiates anew. An array's elements are the callee's whatever its
            // component's type arguments ('Node<K, V>[] createTable(int n) { return new Node[n]; }': guava
            // HashBiMap.hashTableVToK, ImmutableMapEntry.createEntryArray), unless the component is the callee's own
            // type variable ('<T> T[] toArray(T[] a)'), which would tie the arrays of all its callers
            if (concreteArguments(rt) || rt.arrays() > 0 && !isMethodTypeVariable(rt.componentType())
                || policy.callResults() && readScope != null && sameInstantiation(mc, readScope.mi())) {
                flows.add(List.of(callee, target));
            } else if (policy.callResults() && !GENERIC_RESULT_SLOTS_OFF.get() && rt.arrays() == 0) {
                // Kotlin: a slot whose argument is not a type variable has its own nullability whatever the
                // instantiation: 'Map<Descriptor<T>, T> toMap(…)' with a nullable key makes 'return toMap(properties)'
                // a Map<Descriptor<UserProperty>?, UserProperty> (jenkins User.kt:143, 2026-10-21). Gate
                // NOGENERICRESULTSLOTS.
                ParameterizedType tt = typeOf(target);
                for (int j = 0; j < rt.parameters().size(); j++) {
                    ParameterizedType a = rt.parameters().get(j);
                    if (a.typeParameter() == null && a.wildcard() == null && tt != null && tt.arrays() == 0
                        && j < tt.parameters().size()) {
                        addEdge(new Arg(callee, j), new Arg(target, j));
                    }
                }
            }
            // a class type variable's own null ('E getWithKey(K k) { ... return null; }', E?) holds for every
            // instantiation; so does a method type variable's, unless a parameter of that very type can carry the
            // caller's null back out ('<X> X id(X x)'): '<T> T getAttribute(Key<T> key)' returns a map's lookup
            boolean ownNull = rt.arrays() == 0 && rt.typeParameter() != null
                              && (!rt.typeParameter().isMethodTypeParameter()
                                  || callee.parameters().stream().noneMatch(pi ->
                                         rt.typeParameter().equals(pi.parameterizedType().typeParameter())));
            if (policy.callResults() && (!isTypeVariable(rt) || ownNull && !parametricNullness(callee))) {
                if (GAP9_OFF.get() || callee.parameters().isEmpty()) addEdge(callee, target);
                else gatedSites.add(new GatedSite(callee, target, callId(mc, callee)));
            }
            // a result typed with the callee's class type variables inside a generic type ('FastFixedSet<E>
            // spawnEmptySet()'): those positions are the receiver's slots (instantiatedSlots)
            if (readScope != null && !callee.isStatic() && rt.arrays() == 0 && !rt.parameters().isEmpty()
                && !concreteArguments(rt)) {
                Object receiver = receiverNode(readScope.mi(), readScope.scope(), mc);
                ParameterizedType receiverType = receiver == null ? null : typeOf(receiver);
                if (receiverType != null) {
                    instantiatedSlots(target, typeOf(target), callee, rt, receiver, receiverType, callee.typeInfo(),
                            false);
                }
            }
        }
        if (readScope != null) {
            // a stream's terminal operation: its elements are values of the result's slot
            streamTerminal(readScope.mi(), readScope.scope(), unwrapped)
                    .forEach((j, nodes) -> nodes.forEach(n -> addEdge(n, new Arg(target, j))));
        }
        // Kotlin: 'collect(LinkedHashMap::new, (map, plugin) -> map.put(plugin, url), Map::putAll)' returns the
        // container its accumulator fills: the lambda's first parameter is the result, slots and all (jenkins
        // PluginManager.kt:1532, 2026-10-21). Gate NOCOLLECTCONTAINERS.
        if (policy.callResults() && !COLLECT_CONTAINERS_OFF.get() && isStreamType(callee) && "collect".equals(callee.name())
            && mc.parameterExpressions().size() == 3
            && NonNullFacts.unwrap(mc.parameterExpressions().get(1)) instanceof Lambda accumulator
            && accumulator.methodInfo() != null && !accumulator.methodInfo().parameters().isEmpty()) {
            flows.add(List.of(accumulator.methodInfo().parameters().getFirst(), target));
        }
        if (readScope != null && argumentNode(readScope.mi(), readScope.scope(), unwrapped) instanceof Arg slot) {
            if (!analysed.contains(callee)) {
                flows.add(List.of(slot, target)); // 'x = map.get(k)': x is the map's value, its slots are the value's
            }
            // Kotlin: the value read is the receiver's slot ('block = blocks.getWithKey(j)', 'st = stats.get(0)' on a
            // VBStyleCollection<Statement?, ...>): a nullable slot gives a nullable value
            if (policy.callResults()) addEdge(slot, target);
        }
        // 'x = map.get(k)' with k known present (NonNullFacts, CodeLaser/maddi-mod#22 gap 1): not the absent key's null; what
        // the map holds for k still flows through its value slot above
        String lib = libraryNullableCall(mc);
        if (lib != null) {
            // not a member Kotlin itself types nullable ('Map.get(k): V?'): the local must then be declared so
            if (!(target instanceof Local && seedStatement != null && deadNullAssignments.contains(seedStatement)
                  && java.util.stream.Stream.concat(java.util.stream.Stream.of(callee), overrides(callee).stream())
                          .noneMatch(m -> KOTLIN_COLLECTIONS.contains(m.typeInfo().fullyQualifiedName())))) {
                seedIndirect(target, "assigned " + lib);
            } else if (!GUARDED_LOCALS_OFF.get()) {
                guardedLocals.add(target);
            }
        } else if (policy.contracts() && analysed.contains(callee)
                   && explicitState(callee) == NullableState.NULLABLE) {
            seed(target, "assigned " + callee.fullyQualifiedName() + ", annotated nullable");
        }
    }

    /*
     Kotlin: an argument to a method's own type variable ('<E> Entry<E> immutableEntry(E e, int n)'). Kotlin's
     declaration decides what a null argument does. A parameter the method passes on unchecked, into its result or
     into another class's type variable ('new ImmutableEntry<>(e)'), is declared 'e: E': a null argument instantiates
     E for that call, so the call's result (or the result's slot E) is nullable, not the parameter. Through the
     parameter, MultimapKeysTester's 'immutableEntry(null, 3)' had made ImmutableEntry.element, getElement() and
     Multiset.Entry.getElement() 'E?' (guava Multisets.kt:547, 2026-10-17). A parameter the method checks
     ('ensureNotNull(T object, ...)', 'getOrDefault(T value, T defaultValue)'s value) is declared 'T?' and takes the
     argument as before. Decided once the graph is built, from the edges that leave the parameter.
     Gate NOMETHODTYPEVARARGS.
     */
    private record TypeVariableArgument(ParameterInfo parameter, Expression call, int index, MethodInfo mi,
                                        Scope scope, Runnable asBefore) {
    }

    private record CallTarget(Object target, MethodInfo mi, Scope scope) {
    }

    private final List<TypeVariableArgument> typeVariableArguments = new ArrayList<>();
    private final Map<Expression, List<CallTarget>> callTargets = new IdentityHashMap<>();
    private final Map<ParameterInfo, Boolean> passedOnUnchecked = new HashMap<>();

    private void resolveTypeVariableArguments() {
        for (TypeVariableArgument tva : typeVariableArguments) {
            if (!passedOnUnchecked.computeIfAbsent(tva.parameter(), this::passedOnUnchecked)
                && !parametricParameter(tva.parameter())) {
                tva.asBefore().run();
                continue;
            }
            MethodCall mc = (MethodCall) tva.call();
            ParameterizedType rt = mc.methodInfo().returnType();
            var typeParameter = tva.parameter().parameterizedType().typeParameter();
            for (CallTarget ct : callTargets.getOrDefault(mc, List.of())) {
                if (rt.arrays() == 0 && typeParameter.equals(rt.typeParameter())) {
                    inferredNull(ct.target(), ct, mc, tva.index());
                } else if (rt.arrays() == 0) {
                    for (int k = 0; k < rt.parameters().size(); k++) {
                        ParameterizedType slot = rt.parameters().get(k);
                        if (slot.wildcard() == null && typeParameter.equals(slot.typeParameter())) {
                            inferredNull(new Arg(ct.target(), k), ct, mc, tva.index());
                        }
                    }
                }
            }
        }
        typeVariableArguments.clear();
    }

    /*
     Kotlin: a '@ParametricNullness V' parameter is a plain 'V': a caller's null instantiates V, as for a parameter passed
     on unchecked. guava's TrustedListenableFutureTask.create(Runnable, @ParametricNullness V result), given null by
     Futures.submit(Runnable, Executor), printed 'result: V?', and 'Executors.callable(runnable, result)' then inferred
     a Callable<V?> (TrustedListenableFutureTask.kt:68, 2026-10-18). Gate NOPARAMETRICPARAMETERS.
     */
    private static boolean parametricParameter(ParameterInfo pi) {
        return !PARAMETRIC_PARAMETERS_OFF.get() && pi.annotations().stream().anyMatch(a -> a.typeInfo() != null
                && a.typeInfo().simpleName().equals("ParametricNullness"));
    }

    private static final Switches.Gate PARAMETRIC_PARAMETERS_OFF = Switches.gate("NOPARAMETRICPARAMETERS");

    // does the parameter's value leave the method unchecked: into its return, or into another class's type variable
    private boolean passedOnUnchecked(ParameterInfo pi) {
        MethodInfo method = pi.methodInfo();
        Set<Object> seen = new HashSet<>();
        Deque<Object> todo = new ArrayDeque<>(successors.getOrDefault(pi, Set.of()));
        while (!todo.isEmpty() && seen.size() < 2000) {
            Object n = todo.removeFirst();
            if (!seen.add(n)) continue;
            if (n.equals(method)) return true;
            ParameterizedType type = typeOf(n);
            if (type != null && isClassTypeVariable(type)) return true;
            if (n instanceof Local || n instanceof ParameterInfo p && p.methodInfo() == method) {
                todo.addAll(successors.getOrDefault(n, Set.of()));
            }
        }
        return false;
    }

    private void inferredNull(Object target, CallTarget ct, MethodCall mc, int i) {
        Expression argument = NonNullFacts.unwrap(mc.parameterExpressions().get(i));
        if (argument instanceof NullConstant) {
            seed(target, NULL_ARGUMENT + ct.mi().fullyQualifiedName(), ct.mi().typeInfo());
        } else if (argument instanceof VariableExpression ve) {
            if (facts.nonNullWhenCalled(mc, ve.variable())) return;
            Object node = node(ct.mi(), ct.scope(), ve.variable());
            if (node != null) addEdge(node, target);
        } else if (argument instanceof MethodCall inner && inner.methodInfo() != null) {
            Object node = argumentNode(ct.mi(), ct.scope(), inner);
            if (node != null) addEdge(node, target);
            String lib = libraryNullableCall(inner);
            if (lib != null) seedIndirect(target, "assigned " + lib);
        }
    }

    /*
     Kotlin: a generic method that hands an argument's type on ('<T> T ensureNotNull(T, String)', '<T> List<T>
     copy(List<? extends T>)', '<T> T getOrDefault(T, T)') gives its result the argument's type, type arguments
     included: Kotlin infers the method's type variable from the argument, and generic types are invariant. Those
     arguments (through nested such calls) are the values whose content slots the target's must equal (langchain4j
     EmbeddingModelRequestContext: 'textSegments = copy(ensureNotNull(builder.textSegments, ...))' is a
     List<TextSegment?> where the builder's is; DefaultContent: 'metadata = copy(metadata)').
     */
    private static List<Expression> passedThrough(MethodCall mc) {
        MethodInfo callee = mc.methodInfo();
        if (callee == null) return List.of();
        ParameterizedType rt = callee.returnType();
        List<ParameterInfo> parameters = callee.parameters();
        List<Expression> arguments = mc.parameterExpressions();
        List<Expression> out = new ArrayList<>();
        for (int i = 0; i < parameters.size() && i < arguments.size(); i++) {
            if (parameters.get(i).isVarArgs()) break;
            if (!sameInstantiation(rt, parameters.get(i).parameterizedType())) continue;
            Expression argument = arguments.get(i);
            List<Expression> nested = NonNullFacts.unwrap(argument) instanceof MethodCall inner
                    ? passedThrough(inner) : List.of();
            if (nested.isEmpty()) out.add(argument);
            else out.addAll(nested);
        }
        return out;
    }

    /*
     Kotlin: a generic method whose result is its type variable T, with a parameter that carries T as a type argument
     ('<T> T readValue(String, TypeReference<T>)', '<T> T convert(Object, Class<T>)'), infers T from that argument:
     the result's type is the argument's type argument k, and generic types are invariant, so their content slots are
     one (langchain4j DefaultStructuredPromptFactory: 'return OBJECT_MAPPER.readValue(json, MAP_TYPE)' with a
     'TypeReference<Map<String, Object>> MAP_TYPE' returned a MutableMap<String, Any?>, maddi-mod#38). Per argument,
     the position k.
     */
    private static Map<Expression, Integer> typeArgumentsPassedThrough(MethodCall mc) {
        MethodInfo callee = mc.methodInfo();
        if (callee == null || !isMethodTypeVariable(callee.returnType())) return Map.of();
        var typeVariable = callee.returnType().typeParameter();
        List<ParameterInfo> parameters = callee.parameters();
        List<Expression> arguments = mc.parameterExpressions();
        Map<Expression, Integer> out = new LinkedHashMap<>();
        for (int i = 0; i < parameters.size() && i < arguments.size(); i++) {
            if (parameters.get(i).isVarArgs()) break;
            ParameterizedType formal = parameters.get(i).parameterizedType();
            if (formal.arrays() > 0) continue;
            List<ParameterizedType> typeArguments = formal.parameters();
            for (int k = 0; k < typeArguments.size(); k++) {
                ParameterizedType a = typeArguments.get(k);
                if (a.arrays() == 0 && a.wildcard() == null && typeVariable.equals(a.typeParameter())) {
                    out.put(arguments.get(i), k);
                    break;
                }
            }
        }
        return out;
    }

    /*
     Kotlin: a generic method given an array of its type variable ('<T> List<T> asList(T... a)' passed the array
     itself) infers T from the array's elements: the result's slot of T holds them. guava Iterators.partitionImpl:
     'List<@Nullable T> list = unmodifiableList(asList(array))' with nulls written into 'array' printed a
     MutableList<T> (Iterators.kt:397, 2026-10-17). Per array argument, the result's position k.
     */
    private static Map<Expression, Integer> arrayElementsPassedThrough(MethodCall mc) {
        MethodInfo callee = mc.methodInfo();
        if (callee == null) return Map.of();
        ParameterizedType rt = callee.returnType();
        if (rt.arrays() > 0 || rt.parameters().isEmpty()) return Map.of();
        List<ParameterInfo> parameters = callee.parameters();
        List<Expression> arguments = mc.parameterExpressions();
        Map<Expression, Integer> out = new LinkedHashMap<>();
        for (int i = 0; i < parameters.size() && i < arguments.size(); i++) {
            ParameterizedType formal = parameters.get(i).parameterizedType();
            if (formal.arrays() != 1 || !isMethodTypeVariable(formal)) continue;
            if (parameters.get(i).isVarArgs() && !passesTheArray(arguments, parameters, i)) continue;
            for (int k = 0; k < rt.parameters().size(); k++) {
                ParameterizedType a = rt.parameters().get(k);
                if (a.arrays() == 0 && a.wildcard() == null && formal.typeParameter().equals(a.typeParameter())) {
                    out.put(arguments.get(i), k);
                    break;
                }
            }
        }
        return out;
    }

    /*
     Kotlin: a generic method returning its type variable's array, given an array of it (guava's
     'ObjectArrays.newArray(T[] reference, int length)'), infers T from that array: the
     result's elements are the argument's. guava ObjectArrays.concat: 'T[] result = newArray(array, n)' with an
     'array: Array<out T?>' returned an Array<T> (ObjectArrays.kt:30, 2026-10-17). From the caller's own array to its
     own target only, so no two callers are tied. Gate NOARRAYRESULTS.
     */
    private void arrayResultElements(Object target, MethodCall mc) {
        if (ARRAY_RESULTS_OFF.get() || readScope == null) return;
        MethodInfo callee = mc.methodInfo();
        // an analysed callee only: through 'Arrays.copyOf(castBuffer, n)' guava's TopKSelector.topK() returned a
        // List<T?>, where 'T[] castBuffer = buffer' is guava's own @SuppressWarnings("nullness") assertion. Nor in a
        // test: OrderingTest's 'newArray(strictlyOrderedElements, 0)' reached Ordering.max's 'E... rest', shared by
        // every caller, and made the min/max family's results nullable (guava 2026-10-17)
        if (!analysed.contains(callee) || testCode(readScope.mi())) return;
        ParameterizedType rt = callee.returnType();
        if (rt.arrays() != 1 || !isMethodTypeVariable(rt)) return;
        List<ParameterInfo> parameters = callee.parameters();
        List<Expression> arguments = mc.parameterExpressions();
        for (int i = 0; i < parameters.size() && i < arguments.size(); i++) {
            ParameterizedType formal = parameters.get(i).parameterizedType();
            if (formal.arrays() != 1 || !rt.typeParameter().equals(formal.typeParameter())) continue;
            if (parameters.get(i).isVarArgs() && !passesTheArray(arguments, parameters, i)) continue;
            Object node = argumentNode(readScope.mi(), readScope.scope(), arguments.get(i));
            if (node != null) addEdge(new Content(node), new Content(target));
        }
    }

    private static final Switches.Gate ARRAY_RESULTS_OFF = Switches.gate("NOARRAYRESULTS");

    // gate NOARRAYELEMENTSPASSED: an array argument's elements do not reach the slot of a generic call's result (A/B)
    private static final Switches.Gate ARRAY_ELEMENTS_PASSED_OFF = Switches.gate("NOARRAYELEMENTSPASSED");

    // is the return type the parameter's, in the method's own type variables: 'T' and 'T', 'List<T>' and
    // 'List<? extends T>', 'Map<K, V>' and 'Map<K, V>'
    private static boolean sameInstantiation(ParameterizedType returnType, ParameterizedType parameterType) {
        if (returnType.arrays() > 0 || parameterType.arrays() > 0) return false;
        if (isMethodTypeVariable(returnType)) {
            return returnType.typeParameter().equals(parameterType.typeParameter()) && parameterType.wildcard() == null;
        }
        if (returnType.parameters().isEmpty() || returnType.typeInfo() == null
            || !returnType.typeInfo().equals(parameterType.typeInfo())
            || returnType.parameters().size() != parameterType.parameters().size()) {
            return false;
        }
        for (int i = 0; i < returnType.parameters().size(); i++) {
            ParameterizedType r = returnType.parameters().get(i);
            ParameterizedType a = parameterType.parameters().get(i);
            if (!isMethodTypeVariable(r) || r.arrays() > 0 || a.arrays() > 0
                || !r.typeParameter().equals(a.typeParameter())) {
                return false;
            }
        }
        return true;
    }

    /*
     A library factory: the return type's argument j is a method type variable T ('Map.of(K, V, ...)' returns
     Map<K, V>, 'List.of(E...)', 'Arrays.asList(T...)', 'Optional.of(T)'). Each argument passed for a T (or as an
     element of a 'T...') is a value of the result's slot j, which is the target's ('Map<Integer, Integer[]> m =
     Map.of(k, new Integer[]{null})': the target's value elements are nullable). An analysed callee's result is
     tied by callResult instead.
     */
    private void factorySlots(Object target, Expression value, MethodInfo mi) {
        if (target == null || !(NonNullFacts.unwrap(value) instanceof MethodCall mc) || mc.methodInfo() == null) {
            return;
        }
        MethodInfo callee = mc.methodInfo();
        if (analysed.contains(callee)) return;
        ParameterizedType returnType = callee.returnType();
        ParameterizedType targetType = typeOf(target);
        if (returnType.arrays() > 0 || returnType.parameters().isEmpty() || targetType == null
            || targetType.arrays() > 0 || targetType.parameters().size() != returnType.parameters().size()) {
            return;
        }
        List<ParameterInfo> parameters = callee.parameters();
        List<Expression> arguments = mc.parameterExpressions();
        for (int j = 0; j < returnType.parameters().size(); j++) {
            ParameterizedType r = returnType.parameters().get(j);
            if (r.arrays() > 0 || r.typeParameter() == null || !r.typeParameter().isMethodTypeParameter()) continue;
            Arg slot = new Arg(target, j);
            for (int a = 0; a < arguments.size() && !parameters.isEmpty(); a++) {
                ParameterInfo p = parameters.get(Math.min(a, parameters.size() - 1));
                ParameterizedType pt = p.parameterizedType();
                boolean element = p.isVarArgs() && !passesTheArray(arguments, parameters, a);
                if (!r.typeParameter().equals(pt.typeParameter()) || pt.arrays() != (element ? 1 : 0)) continue;
                // a parameter the library declares nullable accepts null; that is not storing it as a T
                if (stateOf(p, PropertyImpl.NULLABILITY_PARAMETER) == NullableState.NULLABLE) continue;
                Expression argument = arguments.get(a);
                if (rejectsNull(callee)) {
                    seedCreated(slot, argument, mi); // the elements of an array stored as one ('new Integer[]{null}')
                    Object source = readScope == null ? null : argumentNode(readScope.mi(), readScope.scope(), argument);
                    if (source != null) flows.add(List.of(source, slot)); // its content slots still are the slot's
                    continue;
                }
                if (NonNullFacts.unwrap(argument) instanceof NullConstant) {
                    seed(slot, "null passed to " + callee.fullyQualifiedName() + " in " + where(mi), writer(mi));
                    continue;
                }
                seedCreated(slot, argument, mi);
                Object source = readScope == null ? null : argumentNode(readScope.mi(), readScope.scope(), argument);
                if (source != null) {
                    // M4: a variable known non-null at the call carries no null into the slot ('textSegment == null
                    // ? List.of() : List.of(textSegment)', langchain4j ListeningEmbeddingModel.embed)
                    if (!(NonNullFacts.unwrap(argument) instanceof VariableExpression ve
                          && facts.nonNullAt(mc, ve.variable()))) {
                        addEdge(source, slot);
                    }
                    flows.add(List.of(source, slot)); // the same object: its content slots are the slot's
                }
            }
        }
    }

    /*
     An immutable collection factory throws on a null element: 'List.of(x)' holds no null, whatever x may be
     (timefold DefaultExhaustiveSearchPhaseFactory: 'return List.of(solutionDescriptor.getListVariableDescriptor())',
     whose null is the caller's NullPointerException, not an element). The JDK's 'of', 'copyOf', 'entry' and
     'ofEntries' on List, Set and Map; guava's Immutable* collections.
     */
    private static boolean rejectsNull(MethodInfo callee) {
        String type = callee.typeInfo().fullyQualifiedName();
        String name = callee.name();
        boolean factory = "of".equals(name) || "copyOf".equals(name) || "entry".equals(name) || "ofEntries".equals(name);
        return factory && callee.isStatic()
               && ("java.util.List".equals(type) || "java.util.Set".equals(type) || "java.util.Map".equals(type)
                   || type.startsWith("com.google.common.collect.Immutable"));
    }

    private String nullableLibraryCall(Expression e) {
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
    private String libraryNullableCall(MethodCall mc) {
        if (mc.methodInfo() != null && !analysed.contains(mc.methodInfo())) lookups.add(mc);
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
        return analysed.stream().anyMatch(m -> m.typeInfo().primaryType().equals(primary));
    }

    // in Java only == and != take a null operand
    private void nullTest(MethodInfo mi, Scope scope, BinaryOperator bo) {
        // NOT excluded: 'if (p == null) throw ...'. Guava annotates such checking parameters @Nullable (the method's
        // job is to accept null and throw); treating the test as a non-null precondition cost 28 agreements, +3
        // unsafe (2026-10-06)
        Expression other = bo.lhs() instanceof NullConstant ? bo.rhs() : bo.rhs() instanceof NullConstant ? bo.lhs() : null;
        if (other instanceof VariableExpression ve) {
            Object node = node(mi, scope, ve.variable());
            if (node instanceof ParameterInfo || node instanceof FieldInfo) {
                seedIndirect(node, "compared with null in " + mi.fullyQualifiedName(), mi.typeInfo());
            }
        }
    }

    // ------------------------------------------------------------------ keyed maps (CodeLaser/maddi-mod#22 gap 2)

    // gate NOKEYEDMAPS: a map field's values stay one slot for all keys (A/B)
    private static final Switches.Gate KEYED_MAPS_OFF = Switches.gate("NOKEYEDMAPS");

    /**
     * The values a keyed map field holds under one constant key: nacos's {@code MapperContext.whereParamMap}, a
     * {@code Map<String, Object>} written and read through {@code putWhereParameter(KEY, value)} and
     * {@code getWhereParameter(KEY)}, where a null {@code dataId} put under one key made every key's value nullable.
     * The key is the constant's value ({@link #keyOf}); a null key stands for the values put under a key not known,
     * which every keyed node receives. A keyed node flows into the field's value slot, which keeps every value.
     */
    public record Keyed(Arg slot, Object key) {
        @Override
        public String toString() {
            return Report.label(this);
        }
    }

    /** An accessor of a keyed map: {@code get(key)} returning the value, or {@code put(key, value)}. */
    record KeyedAccessor(FieldInfo field, int keyIndex, int valueIndex) {
    }

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
    private void indexKeyedMaps(List<MethodInfo> methods, List<FieldInfo> fields) {
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

    private static int ownParameter(MethodInfo mi, Expression e) {
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

    private static Object keyOf(Expression e, int depth) {
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

    private Keyed keyed(Arg slot, Object key) {
        keysUsed.computeIfAbsent(slot, _ -> new LinkedHashSet<>()).add(key == null ? NO_KEY : key);
        return new Keyed(slot, key);
    }

    private static final Object NO_KEY = new Object();

    /*
     A write into a keyed map: through a keyed putter, the key its caller passes; directly ('field.put(k, v)'), the
     key k, except in a keyed putter itself, whose callers write. A key not known writes the values every key reads.
     The write into the field's whole value slot is made as before, by the parameter or the receiver's slot.
     */
    private void keyedWrite(MethodInfo mi, Scope scope, Statement statement, Expression call, MethodInfo callee,
                            LinkComputer.ListOfLinks list, List<Expression> arguments) {
        if (keyedSlots.isEmpty()) return;
        KeyedAccessor putter = keyedPutters.get(callee);
        if (putter != null && arguments.size() > Math.max(putter.keyIndex(), putter.valueIndex())) {
            Arg slot = keyedSlots.get(putter.field());
            argument(mi, scope, statement, call, list, arguments, putter.valueIndex(),
                    keyed(slot, keyOf(arguments.get(putter.keyIndex()))));
            return;
        }
        if (!(call instanceof MethodCall mc) || !"put".equals(callee.name()) || arguments.size() != 2
            || mc.object() == null || !(NonNullFacts.unwrap(mc.object()) instanceof VariableExpression ve)
            || !(ve.variable() instanceof FieldReference fr)) return;
        Arg slot = keyedSlots.get(fr.fieldInfo());
        if (slot == null) return;
        KeyedAccessor own = keyedPutters.get(mi);
        if (own != null && own.field() == fr.fieldInfo() && ownParameter(mi, arguments.getFirst()) == own.keyIndex()) {
            return;
        }
        argument(mi, scope, statement, call, list, arguments, 1, keyed(slot, keyOf(arguments.getFirst())));
    }

    // the keyed node a statement reads: 'x = ctx.get(KEY)' through a keyed getter, or 'x = field.get(KEY)'
    private Keyed keyedRead(Statement statement, Variable recipient) {
        if (keyedSlots.isEmpty()) return null;
        Expression value = assignedValue(statement, recipient);
        if (!(NonNullFacts.unwrap(value) instanceof MethodCall mc) || mc.methodInfo() == null) return null;
        KeyedAccessor getter = keyedGetters.get(mc.methodInfo());
        if (getter != null && getter.keyIndex() < mc.parameterExpressions().size()) {
            Object key = keyOf(mc.parameterExpressions().get(getter.keyIndex()));
            return key == null ? null : keyed(keyedSlots.get(getter.field()), key);
        }
        if ("get".equals(mc.methodInfo().name()) && mc.parameterExpressions().size() == 1 && mc.object() != null
            && NonNullFacts.unwrap(mc.object()) instanceof VariableExpression ve
            && ve.variable() instanceof FieldReference fr && keyedSlots.containsKey(fr.fieldInfo())) {
            Object key = keyOf(mc.parameterExpressions().getFirst());
            return key == null ? null : keyed(keyedSlots.get(fr.fieldInfo()), key);
        }
        return null;
    }

    // the node a read takes its value from: the key's node where it would be the field's value slot
    private static Object keyedSource(Keyed keyed, Object node) {
        return keyed != null && keyed.slot().equals(node) ? keyed : node;
    }

    /*
     After all writes are seen: the values under a key not known reach every key; every key's values are the field's
     values too (and their own slots are tied to the field's, coupleContent). In an open world an outside caller of a
     keyed putter writes under any key.
     */
    private void keyedEdges(List<MethodInfo> methods) {
        keysUsed.forEach((slot, keys) -> {
            Keyed unknown = new Keyed(slot, null);
            for (Object key : keys) {
                if (key == NO_KEY) continue;
                Keyed k = new Keyed(slot, key);
                addEdge(unknown, k);
                addEdge(k, slot);
            }
        });
        if (policy.world() == World.CLOSED) return;
        keyedPutters.forEach((mi, putter) -> {
            if (externallyCallable(mi)) {
                addEdge(mi.parameters().get(putter.valueIndex()), new Keyed(keyedSlots.get(putter.field()), null));
                for (Object key : keysUsed.getOrDefault(keyedSlots.get(putter.field()), Set.of())) {
                    if (key != NO_KEY) addEdge(new Keyed(keyedSlots.get(putter.field()), null),
                            new Keyed(keyedSlots.get(putter.field()), key));
                }
            }
        });
    }

    /*
     Kotlin: 'new SplittingIterator(splitter, toSplit) { ... }' passes its arguments to the superclass's constructor;
     the anonymous class's own has none to take them (guava Splitter.kt:137: a nullable 'toSplit' into
     SplittingIterator's non-null parameter, worktree-maddi-all 2026-10-17). The one superclass constructor of that
     arity, when there is exactly one.
     */
    private void anonymousSuperArguments(MethodInfo mi, Scope scope, Statement statement, ConstructorCall cc) {
        TypeInfo anonymous = cc.anonymousClass();
        if (!policy.callResults() || ANONYMOUS_SUPER_OFF.get() || anonymous == null || anonymous.parentClass() == null
            || anonymous.parentClass().typeInfo() == null) {
            return;
        }
        List<Expression> arguments = cc.parameterExpressions();
        int n = arguments.size();
        MethodInfo constructor = anonymousSuperConstructor(cc);
        if (constructor == null) return;
        for (int i = 0; i < n; i++) {
            ParameterInfo pi = constructor.parameters().get(i);
            Expression argument = NonNullFacts.unwrap(arguments.get(i));
            if (argument instanceof NullConstant) {
                seed(pi, NULL_ARGUMENT + mi.fullyQualifiedName(), mi.typeInfo());
            } else if (argument instanceof VariableExpression ve) {
                Object source = node(mi, scope, ve.variable());
                if (source == null) continue;
                if (facts.nonNullWhenCalled(cc, ve.variable()) || facts.nonNullAt(statement, ve.variable())) {
                    flows.add(List.of(source, pi));
                } else {
                    addEdge(source, pi);
                }
            }
        }
    }

    // the superclass constructor an anonymous class's creation calls: the one of that arity, when there is exactly one
    private MethodInfo anonymousSuperConstructor(ConstructorCall cc) {
        TypeInfo anonymous = cc.anonymousClass();
        if (anonymous == null || anonymous.parentClass() == null || anonymous.parentClass().typeInfo() == null) return null;
        int n = cc.parameterExpressions().size();
        List<MethodInfo> candidates = anonymous.parentClass().typeInfo().constructors().stream()
                .filter(c -> c.parameters().size() == n && c.parameters().stream().noneMatch(ParameterInfo::isVarArgs))
                .toList();
        return candidates.size() == 1 && analysed.contains(candidates.getFirst()) ? candidates.getFirst() : null;
    }

    /*
     Kotlin: a double-brace initialization ('new HashMap<String, String>(8) {{ put(APPNAME, labels.get(APPNAME)); }}')
     writes the new object's slots through its implicit 'this': the anonymous class's 'this' is a node typed by its
     generic superclass, whose slots flow into what the creation is assigned to (nacos RequestMeta.java:103,
     ConnectionMeta.java:164, 2026-10-19: a HashMap<String, String> given nulls). Gate NOANONYMOUSTHIS.
     */
    public record AnonymousThis(TypeInfo type) {
        @Override
        public String toString() {
            return "this of " + type.fullyQualifiedName();
        }
    }

    private static final Switches.Gate ANONYMOUS_THIS_OFF = Switches.gate("NOANONYMOUSTHIS");

    private AnonymousThis anonymousThis(Variable v) {
        if (ANONYMOUS_THIS_OFF.get() || !policy.callResults() || !(v instanceof This t)) return null;
        TypeInfo type = t.typeInfo();
        if (type == null || type.parentClass() == null || type.parentClass().parameters().isEmpty()) return null;
        // a named class too, whose superclass's slots its own code writes (parentClassVerdicts)
        if (!type.isAnonymous() && (SUPERTYPE_SLOTS_OFF.get() || !thisTypedBySuperclass(type))) return null;
        return new AnonymousThis(type);
    }

    private void anonymousThisTarget(MethodInfo mi, Scope scope, Statement statement, ConstructorCall cc) {
        TypeInfo anonymous = cc.anonymousClass();
        if (ANONYMOUS_THIS_OFF.get() || !policy.callResults() || anonymous == null || anonymous.parentClass() == null
            || anonymous.parentClass().parameters().isEmpty()) return;
        Object target = switch (statement) {
            case LocalVariableCreation lvc -> lvc.localVariableStream()
                    .filter(lv -> lv.assignmentExpression() != null && NonNullFacts.unwrap(lv.assignmentExpression()) == cc)
                    .map(lv -> (Object) local(mi, scope, lv.simpleName())).findFirst().orElse(null);
            case ExpressionAsStatement eas when NonNullFacts.unwrap(eas.expression()) instanceof Assignment a
                                                && NonNullFacts.unwrap(a.value()) == cc -> node(mi, scope, a.variableTarget());
            case ReturnStatement rs when rs.expression() != null && NonNullFacts.unwrap(rs.expression()) == cc -> mi;
            default -> null;
        };
        if (target == null) return;
        AnonymousThis at = new AnonymousThis(anonymous);
        for (int i = 0; i < anonymous.parentClass().parameters().size(); i++) addEdge(new Arg(at, i), new Arg(target, i));
    }

    /*
     Kotlin: a library view of a generic receiver holds the receiver's values: 'namespaceSingletonMaps.keySet()' is a
     Set of the map's keys, 'header.entrySet().iterator()' an Iterator of its entries. The result's slots are the
     receiver's where the callee's declared result uses its class's type variables, lifted to the target's class
     ('KeySetView<K, V>' as a 'Set<K>'), nested too ('Entry<K, V>'); both ways, a view being the same data. A library
     call in between ('header.entrySet()') is a node of its own, typed by its inferred type (nacos
     ServiceManager.java:92, Header.java:85, 2026-10-19). Gate NOLIBRARYVIEWS.
     */
    public record CallResult(MethodCall call) {
        @Override
        public boolean equals(Object o) {
            return o instanceof CallResult(MethodCall c) && c == call;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(call);
        }

        @Override
        public String toString() {
            return "result of " + call;
        }
    }

    private static final Switches.Gate LIBRARY_VIEWS_OFF = Switches.gate("NOLIBRARYVIEWS");

    private void libraryView(Object target, MethodCall mc, int depth) {
        if (LIBRARY_VIEWS_OFF.get() || readScope == null || depth > 3) return;
        MethodInfo callee = mc.methodInfo();
        ParameterizedType tt = typeOf(target);
        if (callee == null || analysed.contains(callee) || callee.isStatic() || mc.object() == null || tt == null
            || tt.arrays() > 0 || tt.parameters().isEmpty() || tt.typeInfo() == null) return;
        ParameterizedType rt = callee.returnType();
        if (rt.arrays() > 0 || rt.parameters().isEmpty() || rt.typeInfo() == null) return;
        ParameterizedType lifted = rt.typeInfo() == tt.typeInfo() ? rt
                : rt.concreteSuperType(tt.typeInfo().asParameterizedType());
        if (lifted == null || lifted.parameters().size() != tt.parameters().size()) return;
        Expression object = NonNullFacts.unwrap(mc.object());
        Object receiver;
        if (object instanceof MethodCall inner && inner.methodInfo() != null && !analysed.contains(inner.methodInfo())
            && !inner.methodInfo().isStatic() && inner.concreteReturnType() != null) {
            receiver = new CallResult(inner);
            libraryView(receiver, inner, depth + 1);
        } else {
            receiver = receiverNode(readScope.mi(), readScope.scope(), mc);
        }
        ParameterizedType receiverType = receiver == null ? null : typeOf(receiver);
        if (receiverType == null || receiverType.arrays() > 0) return;
        for (int j = 0; j < lifted.parameters().size(); j++) {
            viewSlot(new Arg(target, j), lifted.parameters().get(j), receiver, receiverType, callee.typeInfo());
        }
    }

    private void viewSlot(Arg slot, ParameterizedType formal, Object receiver, ParameterizedType receiverType,
                          TypeInfo calleeClass) {
        if (formal.arrays() > 0 || formal.wildcard() != null) return;
        var tp = formal.typeParameter();
        if (tp != null) {
            if (tp.isMethodTypeParameter() || !tp.getOwner().isLeft() || tp.getOwner().getLeft() != calleeClass) return;
            int index = slotIndex(receiverType, calleeClass, tp.getIndex());
            if (index < 0) return;
            Arg own = new Arg(receiver, index);
            addEdge(own, slot);
            addEdge(slot, own);
            return;
        }
        for (int m = 0; m < formal.parameters().size(); m++) {
            viewSlot(new Arg(slot, m), formal.parameters().get(m), receiver, receiverType, calleeClass);
        }
    }

    // gate NOCONSUMERSLOTS: a caller's null into an abstract type-variable parameter reaches the implementations (A/B)
    private static final Switches.Gate CONSUMER_SLOTS_OFF = Switches.gate("NOCONSUMERSLOTS");
    private static final Switches.Gate METHOD_TYPE_VARIABLE_ARGUMENTS_OFF = Switches.gate("NOMETHODTYPEVARARGS");
    private static final Switches.Gate EXPLICIT_BOUNDED_OFF = Switches.gate("NOEXPLICITBOUNDED");
    // gate NOANONYMOUSSUPER: an anonymous class's arguments do not reach its superclass's constructor (A/B)
    private static final Switches.Gate ANONYMOUS_SUPER_OFF = Switches.gate("NOANONYMOUSSUPER");

    private void callSite(MethodInfo mi, Scope scope, Statement statement, Expression call, MethodInfo callee,
                          io.codelaser.maddi.cst.api.analysis.PropertyValueMap analysis, List<Expression> arguments) {
        List<ParameterInfo> parameters = callee.parameters();
        if (parameters.isEmpty()) return;
        LinkComputer.ListOfLinks list = analysis.getOrNull(LinkComputerImpl.LINKED_VARIABLES_ARGUMENTS,
                LinkComputerImpl.ListOfLinksImpl.class);
        receiverSlots(mi, scope, statement, call, callee, list, arguments);
        instantiatedArguments(mi, scope, call, callee, arguments);
        if (call instanceof MethodCall && analysed.contains(callee) && !callee.returnType().isVoid()) {
            int id = callId(call, callee);
            argumentsSeen.add(id);
            for (int i = 0; i < arguments.size() && i < parameters.size(); i++) {
                if (parameters.get(i).isVarArgs()) break;
                siteArgument(mi, scope, statement, call, list, arguments, i, new SiteArg(id, i));
            }
        }
        for (int i = 0; i < arguments.size(); i++) {
            ParameterInfo parameter = parameters.get(Math.min(i, parameters.size() - 1)); // varargs: the last one
            if (parameter.isVarArgs() && !passesTheArray(arguments, parameters, i)) {
                // an element of the array the call creates: the array is never null, the argument is an element
                argument(mi, scope, statement, call, list, arguments, i, new Content(parameter));
                continue;
            }
            if (parameter.isVarArgs() && policy.callResults() && !VARARG_COPIES_OFF.get()) {
                // an array spread into varargs ('*array'): the callee holds a copy (varargsCopy)
                Object source = argumentNode(mi, scope, arguments.get(i));
                if (source != null) varargsPasses.add(List.of(source, parameter));
            }
            if (preconditions.contains(parameter)) {
                preconditionArgument(mi, scope, statement, call, parameter, arguments.get(i));
            }
            // a library parameter typed by its class's type variable ('Map.put(K, V)') does not take the argument: the
            // write is the receiver's slot (receiverSlots), and the parameter would carry it down into every analysed
            // implementation of the library type
            // The same for an analysed generic class, where the receiver's slot takes the write: 'stack.push(null)' on a
            // 'ListStack<T>' makes the stack a 'ListStack<X?>', not 'push(T?)' (JSpecify: 'T extends @Nullable
            // Object'; Kotlin: 'push(item: T)' and a 'ListStack<X?>'). Only for a concrete method nothing overrides: an
            // abstract one is not known to store ('Function.apply(F)' is a consumer, and its null must reach the
            // implementations; guava 2026-10-07: +5 unsafe without this). A call on 'this' or a consumer method
            // still makes the parameter nullable.
            // Kotlin: an abstract one too. An implementation's parameter takes the declaration's state
            // (clampTypeVariableOverrides), so a caller's null belongs to its receiver's instantiation
            // ('predicate.apply(null)' on a 'Predicate<in Int?>'): through 'Predicate.apply(T input)' PredicatesTest's
            // null reached every implementation, AbstractNetwork's 'edge -> incidentNodes(edge)' and from there
            // Network.incidentNodes()'s return (guava AbstractNetwork.kt:21/155, 2026-10-17).
            if (parameter.parameterizedType().typeParameter() != null
                && !parameter.parameterizedType().typeParameter().isMethodTypeParameter()
                && parameter.parameterizedType().arrays() == 0
                && (!analysed.contains(callee)
                    || (policy.callResults() && !CONSUMER_SLOTS_OFF.get()
                        || !callee.isAbstract() && !implementations.containsKey(callee))
                       && slotReceiver(mi, scope, call, callee) != null)) {
                continue;
            }
            if (projectedReceiver(call, callee, parameter)) continue;
            // Kotlin: an argument to the callee's own type variable is decided once the graph is built
            // (resolveTypeVariableArguments)
            if (policy.callResults() && !METHOD_TYPE_VARIABLE_ARGUMENTS_OFF.get() && call instanceof MethodCall
                && analysed.contains(callee) && isMethodTypeVariable(parameter.parameterizedType())
                && !parameter.isVarArgs() && explicitState(parameter) != NullableState.NULLABLE) {
                int index = i;
                typeVariableArguments.add(new TypeVariableArgument(parameter, call, index, mi, scope, () -> {
                    for (ParameterInfo pi : typedDispatch(call, callee, parameter)) {
                        argument(mi, scope, statement, call, list, arguments, index, pi);
                    }
                }));
                continue;
            }
            for (ParameterInfo pi : typedDispatch(call, callee, parameter)) {
                argument(mi, scope, statement, call, list, arguments, i, pi);
            }
        }
        keyedWrite(mi, scope, statement, call, callee, list, arguments);
    }

    /*
     A write into a generic receiver: an argument to a parameter typed by a type variable of the callee's class
     ('add(E)', 'put(K, V)', 'set(T)') flows into that type argument's slot of the receiver ('list', 'this.names').
     The receiver's type arguments are matched to the class's by position (ArrayList<E> -> List<E>), so only when the
     receiver's declared type has as many as the callee's class.
     */
    private void receiverSlots(MethodInfo mi, Scope scope, Statement statement, Expression call, MethodInfo callee,
                               LinkComputer.ListOfLinks list, List<Expression> arguments) {
        Object receiver = slotReceiver(mi, scope, call, callee);
        if (receiver == null) return;
        ParameterizedType type = typeOf(receiver);
        List<ParameterInfo> parameters = callee.parameters();
        for (int i = 0; i < arguments.size() && i < parameters.size(); i++) {
            ParameterizedType pt = parameters.get(i).parameterizedType();
            if (pt.arrays() > 0 || pt.typeParameter() == null || pt.typeParameter().isMethodTypeParameter()) continue;
            // Kotlin: a parameter declared nullable takes the null itself ('convert(a: A?)'); the receiver's
            // instantiation stays. guava's tests' 'Ints.stringConverter().convert(null)' had made
            // IntConverter.INSTANCE a Converter<String?, Int>, which an IntConverter (a Converter<String, Int>) is
            // not; likewise 'Equivalence.equals().equivalentTo(null)' (Ints.kt:101, Equivalence.kt:98, 2026-10-18)
            if (policy.callResults() && !NULLABLE_PARAMETER_SLOTS_OFF.get()
                && explicitState(parameters.get(i)) == NullableState.NULLABLE) continue;
            int index = slotIndex(type, callee.typeInfo(), pt.typeParameter().getIndex());
            if (index >= 0) argument(mi, scope, statement, call, list, arguments, i, new Arg(receiver, index));
        }
        contentCopies(mi, scope, receiver, callee, arguments);
    }

    // gate NONULLABLEPARAMETERSLOTS: a null into a parameter declared nullable writes the receiver's slot too (A/B)
    private static final Switches.Gate NULLABLE_PARAMETER_SLOTS_OFF = Switches.gate("NONULLABLEPARAMETERSLOTS");

    // the receiver whose slots a call writes: a variable of the callee's generic class, with as many type arguments;
    // or an analysed method's result, whose slots are those of what it returns ('wrapper.getInits().addWithKey(v, k)'
    // writes the field 'inits' returns: fernflower InitializerProcessor)
    /*
     Kotlin: a receiver projected at the type variable of the parameter ('Funnel<?>', 'Funnel<? extends T>') takes
     nothing in that position: Kotlin's 'Funnel<*>.funnel(from: Nothing, …)'. A null passed there belongs to no
     implementation. Once the reachability pass completed on guava (5512762c), 'Funnel.funnel' was known
     non-modifying, so slotReceiver no longer took FunnelsTest's 'funnel.funnel(null, sink)', and the null made every
     implementation's 'from' nullable (BloomFilterTest.kt:557, HashTestUtils.kt:139, 2026-10-20). Gate
     NOCONSUMERRECEIVERS.
     */
    private boolean projectedReceiver(Expression call, MethodInfo callee, ParameterInfo parameter) {
        if (CONSUMER_RECEIVERS_OFF.get() || !policy.callResults() || !(call instanceof MethodCall mc) || mc.object() == null
            || callee.isStatic()) return false;
        var tp = parameter.parameterizedType().typeParameter();
        ParameterizedType type = mc.object().parameterizedType();
        if (tp == null || type == null || type.arrays() > 0 || !tp.getOwner().isLeft()
            || tp.getOwner().getLeft() != callee.typeInfo()) return false;
        int index = slotIndex(type, callee.typeInfo(), tp.getIndex());
        if (index < 0 || index >= type.parameters().size()) return false;
        var wildcard = type.parameters().get(index).wildcard();
        return wildcard != null && !wildcard.isSuper();
    }

    private static final Switches.Gate CONSUMER_RECEIVERS_OFF = Switches.gate("NOCONSUMERRECEIVERS");

    /*
     Kotlin: an analysed consumer that overrides replace ('Ordering.compare(T, T)', abstract) takes a null into its
     class's type variable as its receiver's instantiation, as a modifying method does: the overrides' parameters
     are the declaration's T (clampTypeVariableOverrides), and Kotlin passes null only to an 'Ordering<X?>'. Seeded
     on the parameter instead, the null reached every override and flowed on from its clamped parameter: guava
     OrderingTest's 'compare(null, null)' on an Ordering<@Nullable Object> made ExplicitOrdering's 'rank(value: T?)'
     and IncomparableValueException's 'value: Any?', once Ordering.compare was known non-modifying (maddi
     afb4537bf's JDK hints; ExplicitOrdering.kt:14, 2026-10-25). A concrete consumer nothing overrides keeps its own
     nullable parameter. Gate NOFAMILYCONSUMERS.
     */
    private boolean familyConsumer(MethodInfo callee) {
        return policy.callResults() && !FAMILY_CONSUMERS_OFF.get() && analysed.contains(callee)
               && (callee.isAbstract() || implementations.containsKey(callee));
    }

    private static final Switches.Gate FAMILY_CONSUMERS_OFF = Switches.gate("NOFAMILYCONSUMERS");

    private Object slotReceiver(MethodInfo mi, Scope scope, Expression call, MethodInfo callee) {
        if (!(call instanceof MethodCall mc) || mc.object() == null || callee.isStatic()) return null;
        Expression object = NonNullFacts.unwrap(mc.object());
        Object receiver;
        if (object instanceof VariableExpression ve && anonymousThis(ve.variable()) instanceof AnonymousThis at) {
            receiver = at;
        } else if (object instanceof VariableExpression ve) {
            receiver = node(mi, scope, ve.variable());
        } else if (object instanceof MethodCall getter && getter.methodInfo() != null
                   && analysed.contains(getter.methodInfo()) && !getter.methodInfo().isConstructor()
                   && concreteArguments(getter.methodInfo().returnType())) {
            receiver = getter.methodInfo();
        } else if (object instanceof MethodCall lookup && !analysed.contains(callee)
                   && argumentNode(mi, scope, lookup) instanceof Arg slot) {
            // a library write into a generic receiver's content read ('ranges.computeIfAbsent(id, k -> new
            // ArrayList<>()).add(new String[]{…, c ? "1" : null})' writes the map's value: fernflower
            // FlattenStatementsHelper.saveEdge, a Kotlin NPE without it). Library callees only: through an analysed
            // generic class (FastFixedSet) the same receiver made fernflower's type variables disagree (32f0a688)
            receiver = slot;
        } else {
            return null;
        }
        // only a method that modifies its receiver stores what it is given: 'add', 'put', 'set'; not a consumer
        // such as 'Comparator.compare(T, T)', 'Equivalence.equivalent', 'Predicate.test'
        if (callee.analysis().getOrDefault(PropertyImpl.NON_MODIFYING_METHOD, ValueImpl.BoolImpl.FALSE).isTrue()
            && !familyConsumer(callee)) {
            return null;
        }
        ParameterizedType type = receiver == null ? null : typeOf(receiver);
        if (type == null || type.arrays() > 0 || callee.typeInfo().typeParameters().isEmpty()) return null;
        boolean anySlot = false;
        for (int i = 0; i < callee.typeInfo().typeParameters().size(); i++) {
            anySlot |= slotIndex(type, callee.typeInfo(), i) >= 0;
        }
        return anySlot ? receiver : null;
    }

    /*
     The receiver's type argument that the callee's class type variable 'index' stands for. The same class (or one
     with as many type parameters, ArrayList<E> -> List<E>): the same position. Otherwise through the receiver's
     supertype: on a 'VBStyleCollection<Statement, Integer>', which extends ArrayList<E>, 'get' returns ArrayList's E,
     the receiver's argument 0 (fernflower 'stat.getStats().get(0)'). -1 when it maps to no argument of the receiver.
     */
    private static int slotIndex(ParameterizedType receiverType, TypeInfo calleeClass, int index) {
        int arity = calleeClass.typeParameters().size();
        if (receiverType.arrays() > 0 || index >= arity) return -1;
        TypeInfo receiverClass = receiverType.typeInfo();
        if (receiverClass == calleeClass || receiverClass == null || receiverType.parameters().size() == arity) {
            return index < receiverType.parameters().size() ? index : -1;
        }
        ParameterizedType formal = receiverClass.asParameterizedType();
        ParameterizedType asSuper = formal.concreteSuperType(calleeClass.asParameterizedType());
        if (asSuper == null || index >= asSuper.parameters().size()) return -1;
        ParameterizedType argument = asSuper.parameters().get(index);
        if (argument.arrays() > 0 || argument.typeParameter() == null || argument.typeParameter().isMethodTypeParameter()
            || argument.typeParameter().getOwner().getLeft() != receiverClass) return -1;
        int j = argument.typeParameter().getIndex();
        return j < receiverType.parameters().size() ? j : -1;
    }

    // 'm(array)' for 'm(String... xs)': the one argument in the varargs position is itself the array
    private static boolean passesTheArray(List<Expression> arguments, List<ParameterInfo> parameters, int i) {
        if (arguments.size() != parameters.size()) return false;
        if (arguments.get(i) instanceof NullConstant) return true; // 'm((String[]) null)' would be a cast; javac warns
        ParameterizedType type = arguments.get(i).parameterizedType();
        return type != null && type.arrays() == parameters.get(i).parameterizedType().arrays();
    }

    /**
     * The parameter, and, where the override chain stops at a type variable (buildForMethod), the parameters of the
     * implementations the receiver's type selects: for {@code Fn<String, String> f; f.apply(x)}, every
     * {@code apply(String)} (and {@code apply(Object)}, which the chain still reaches).
     */
    private List<ParameterInfo> typedDispatch(Expression call, MethodInfo callee, ParameterInfo pi) {
        if (!isTypeVariable(pi.parameterizedType()) || pi.parameterizedType().typeParameter().isMethodTypeParameter()
            || !(call instanceof MethodCall mc) || mc.object() == null) {
            return List.of(pi);
        }
        ParameterizedType receiver = mc.object().parameterizedType();
        int index = pi.parameterizedType().typeParameter().getIndex();
        if (receiver == null || receiver.typeInfo() != callee.typeInfo() || index >= receiver.parameters().size()) {
            return List.of(pi);
        }
        ParameterizedType argumentType = receiver.parameters().get(index);
        if (argumentType.typeInfo() == null || argumentType.typeInfo().isJavaLangObject()) return List.of(pi);
        List<ParameterInfo> targets = new java.util.ArrayList<>(List.of(pi));
        for (MethodInfo implementation : implementations.getOrDefault(callee, List.of())) {
            ParameterizedType type = implementation.parameters().get(pi.index()).parameterizedType();
            if (type.typeInfo() == argumentType.typeInfo() && type.arrays() == argumentType.arrays()) {
                targets.add(implementation.parameters().get(pi.index()));
            }
        }
        return targets;
    }

    private int callId(Expression call, MethodInfo callee) {
        Integer id = callIds.get(call);
        if (id == null) {
            id = callIds.size();
            callIds.put(call, id);
            callees.put(id, callee);
        }
        return id;
    }

    /*
     Gap 9: the null-carrying half of argument(), into this call's own SiteArg instead of the shared parameter (no
     slots, no content: only whether the argument itself may be null here).
     */
    private void siteArgument(MethodInfo mi, Scope scope, Statement statement, Expression call,
                              LinkComputer.ListOfLinks list, List<Expression> arguments, int i, SiteArg site) {
        Expression argument = arguments.get(i);
        Expression unwrapped = NonNullFacts.unwrap(argument);
        if (unwrapped instanceof NullConstant) {
            seed(site, "null argument in " + mi.fullyQualifiedName());
            return;
        }
        if (unwrapped instanceof VariableExpression ve
            && (facts.nonNullWhenCalled(call, ve.variable()) || facts.nonNullAt(statement, ve.variable()))) {
            return;
        }
        String lib = nullableLibraryCall(argument);
        if (lib != null) {
            seedIndirect(site, "argument " + lib + " in " + mi.fullyQualifiedName());
            return;
        }
        if (unwrapped instanceof MethodCall amc && amc.methodInfo() != null && analysed.contains(amc.methodInfo())
            && !amc.methodInfo().isConstructor() && !amc.methodInfo().returnType().isVoid()) {
            addEdge(amc.methodInfo(), site); // conservative: the nested call's result, ungated
        }
        if (list == null || i >= list.list().size()) {
            if (!(unwrapped instanceof MethodCall)) addEdge(argumentNode(mi, scope, argument), site);
            return;
        }
        Links links = list.list().get(i);
        Variable primary = links.primary();
        if (primary == null) return;
        if (isNullMarker(primary)) {
            nullMarker(mi, primary, site, "null argument in " + mi.fullyQualifiedName());
            return;
        }
        Object node = node(mi, scope, primary);
        if (node != null) addEdge(node, site);
        sourcesOf(mi, scope, primary, links, site);
    }

    /*
     Kotlin: what a lambda returns is a value of the type argument its interface method returns, in the parameter it
     is passed to: '(query) -> maxResults' for a 'Function<Query, Integer>' makes it a 'Function<Query, Int?>' when
     maxResults may be null (langchain4j EmbeddingStoreContentRetriever, whose lambda returned an Int? where the
     constructor declared Int).
     */
    private void lambdaResult(Lambda lambda, Object pi) {
        MethodInfo body = lambda.methodInfo();
        if (body == null) return;
        ParameterizedType formal;
        if (pi instanceof ParameterInfo parameter && analysed.contains(parameter.methodInfo())) {
            formal = parameter.parameterizedType();
        } else if (!LAMBDA_SLOTS_OFF.get() && policy.callResults() && pi instanceof Arg slot) {
            // a receiver's slot: 'chain.add(input -> states.get(current))' on a List<Function<Provider<S>, S>> makes
            // it a List<Function<Provider<S>, S?>> (jenkins InstallUtil.kt:48/60, 2026-10-20). Gate NOLAMBDASLOTS.
            formal = typeOf(slot);
            if (formal == null) return;
        } else {
            return;
        }
        if (formal.arrays() > 0 || formal.typeInfo() == null || formal.parameters().isEmpty()) return;
        MethodInfo sam = formal.typeInfo().singleAbstractMethod();
        if (sam == null || !isClassTypeVariable(sam.returnType())) return;
        int j = formal.typeInfo().typeParameters().indexOf(sam.returnType().typeParameter());
        // not into a type variable ('<T> T withRetry(Callable<T> action)'): each call instantiates it anew
        if (j >= 0 && j < formal.parameters().size() && formal.parameters().get(j).typeParameter() == null) {
            addEdge(body, new Arg(pi, j));
        }
    }

    private static final Switches.Gate LAMBDA_SLOTS_OFF = Switches.gate("NOLAMBDASLOTS");

    /*
     Kotlin: a method reference returns what its method returns, as a lambda its body: 'new
     JenkinsANTLRErrorListener(parser::getErrorMessage)' with a nullable getErrorMessage() makes the parameter a
     Supplier<String?> (jenkins CronTab.kt:30, 2026-10-21). Not a constructor reference, whose result is never null.
     Gate NOMETHODREFRESULTS.
     */
    private void methodReferenceResult(MethodReference mr, Object pi) {
        MethodInfo method = mr.methodInfo();
        if (METHOD_REF_RESULTS_OFF.get() || method == null || method.isConstructor() || method.returnType().isVoid()
            || !analysed.contains(method)) return;
        ParameterizedType formal;
        if (pi instanceof ParameterInfo parameter && analysed.contains(parameter.methodInfo())) {
            formal = parameter.parameterizedType();
        } else if (pi instanceof Arg slot) {
            formal = typeOf(slot);
            if (formal == null) return;
        } else {
            return;
        }
        if (formal.arrays() > 0 || formal.typeInfo() == null || formal.parameters().isEmpty()) return;
        MethodInfo sam = formal.typeInfo().singleAbstractMethod();
        if (sam == null || !isClassTypeVariable(sam.returnType())) return;
        int j = formal.typeInfo().typeParameters().indexOf(sam.returnType().typeParameter());
        if (j >= 0 && j < formal.parameters().size() && formal.parameters().get(j).typeParameter() == null) {
            addEdge(method, new Arg(pi, j));
        }
    }

    private static final Switches.Gate METHOD_REF_RESULTS_OFF = Switches.gate("NOMETHODREFRESULTS");
    /*
     Kotlin: a lambda given to 'computeIfAbsent(K, Function<? super K, ? extends V>)', whose functional parameter
     returns the callee class's type variable, returns a value of the receiver's slot for it: 'cache
     .computeIfAbsent(t, unused -> computeConverter(t))' with a nullable computeConverter makes the cache's values
     nullable (jenkins XStream2.kt:224, 2026-10-21). Gate NORECEIVERLAMBDAS.
     */
    private void receiverLambdaResult(MethodInfo mi, Scope scope, Expression call, Lambda lambda, Object pi) {
        if (RECEIVER_LAMBDAS_OFF.get() || lambda.methodInfo() == null || !(pi instanceof ParameterInfo parameter)
            || analysed.contains(parameter.methodInfo()) || !(call instanceof MethodCall mc)) return;
        MethodInfo callee = parameter.methodInfo();
        ParameterizedType formal = parameter.parameterizedType();
        // only ConcurrentHashMap's own 'computeIfAbsent', whose function Kotlin types '(K) -> V': Map's (and
        // ConcurrentMap's) is enhanced to 'Function<in K, out V?>', so jenkins AbstractCIBase's
        // 'computers.computeIfAbsent(n, node -> node.createComputer())' on a ConcurrentMap compiles as it is. For
        // 'compute', 'computeIfPresent' and 'merge' a null result removes the entry (guava MapsTest, nacos
        // SelectorManager: their maps' values had become nullable)
        if (!"computeIfAbsent".equals(callee.name())
            || !"java.util.concurrent.ConcurrentHashMap".equals(callee.typeInfo().fullyQualifiedName())) return;
        if (callee.isStatic() || formal.arrays() > 0 || formal.typeInfo() == null || formal.parameters().isEmpty()) return;
        MethodInfo sam = formal.typeInfo().singleAbstractMethod();
        if (sam == null || !isClassTypeVariable(sam.returnType())) return;
        int j = formal.typeInfo().typeParameters().indexOf(sam.returnType().typeParameter());
        if (j < 0 || j >= formal.parameters().size()) return;
        ParameterizedType result = formal.parameters().get(j); // '? extends V'
        var tp = result.typeParameter();
        if (tp == null || tp.isMethodTypeParameter() || !tp.getOwner().isLeft()
            || tp.getOwner().getLeft() != callee.typeInfo() || result.wildcard() != null && result.wildcard().isSuper()) {
            return;
        }
        Object receiver = receiverNode(mi, scope, mc);
        ParameterizedType receiverType = receiver == null ? null : typeOf(receiver);
        if (receiverType == null || receiverType.arrays() > 0) return;
        int index = slotIndex(receiverType, callee.typeInfo(), tp.getIndex());
        if (index >= 0) addEdge(lambda.methodInfo(), new Arg(receiver, index));
    }

    private static final Switches.Gate RECEIVER_LAMBDAS_OFF = Switches.gate("NORECEIVERLAMBDAS");
    private static final Switches.Gate COLLECT_CONTAINERS_OFF = Switches.gate("NOCOLLECTCONTAINERS");
    private static final Switches.Gate RETURN_NULL_LITERALS_OFF = Switches.gate("NORETURNNULLLITERALS");
    private static final Switches.Gate ASSIGNED_NON_NULL_OFF = Switches.gate("NOASSIGNEDNONNULL");

    /*
     Kotlin: a method that returns its parameter filtered of nulls ('<T> Iterator<T> removeNull(Iterator<T> itr) {
     return Iterators.filter(itr, Objects::nonNull); }') takes nullable elements and returns non-null ones:
     'removeNull(itr: Iterator<T?>): Iterator<T>', from which Kotlin infers a non-null T for an Iterator<Int?>
     argument (jenkins BuildReferenceMapAdapter.kt:111, 2026-10-21). Gate NONULLFILTEREDPARAMETERS.
     */
    private void nullFilteredParameters(List<MethodInfo> methods) {
        if (NULL_FILTERED_PARAMETERS_OFF.get() || !policy.callResults()) return;
        for (MethodInfo mi : methods) {
            if (mi.methodBody() == null || mi.methodBody().statements().size() != 1
                || !(mi.methodBody().statements().getFirst() instanceof ReturnStatement rs)
                || !(NonNullFacts.unwrap(rs.expression()) instanceof MethodCall mc) || mc.methodInfo() == null
                || !"filter".equals(mc.methodInfo().name()) || mc.parameterExpressions().size() != 2
                || !nullFilter(mc.parameterExpressions().get(1))
                || !(NonNullFacts.unwrap(mc.parameterExpressions().getFirst()) instanceof VariableExpression ve)
                || !(ve.variable() instanceof ParameterInfo pi) || !mi.equals(pi.methodInfo())) continue;
            ParameterizedType type = pi.parameterizedType();
            // only a covariant container ('Iterator<out T>', 'Iterable<out T>'): every caller's argument fits T?
            String container = type.typeInfo() == null ? "" : type.typeInfo().fullyQualifiedName();
            if (type.arrays() == 0 && type.parameters().size() == 1 && isTypeVariable(type.parameters().getFirst())
                && ("java.util.Iterator".equals(container) || "java.lang.Iterable".equals(container))) {
                Arg slot = new Arg(pi, 0);
                typeVariableElementSlots.add(slot); // a T? slot, not held at T (boundedSlot)
                seed(slot, "filtered of nulls in " + mi.fullyQualifiedName(), mi.typeInfo());
            }
        }
    }

    private static final Switches.Gate NULL_FILTERED_PARAMETERS_OFF = Switches.gate("NONULLFILTEREDPARAMETERS");

    // whether a lambda's functional type returns its type argument k ('Supplier<T>': get() returns argument 0)
    private static boolean lambdaResultsPosition(Lambda lambda, int k) {
        ParameterizedType type = lambda.concreteFunctionalType();
        if (type == null || type.typeInfo() == null) return false;
        MethodInfo sam = type.typeInfo().singleAbstractMethod();
        if (sam == null || !isClassTypeVariable(sam.returnType())) return false;
        return type.typeInfo().typeParameters().indexOf(sam.returnType().typeParameter()) == k;
    }

    private static final Switches.Gate LAMBDA_TYPE_VARIABLE_RESULTS_OFF = Switches.gate("NOLAMBDATYPEVARRESULTS");
    private static final Switches.Gate GENERIC_RESULT_SLOTS_OFF = Switches.gate("NOGENERICRESULTSLOTS");

    /*
     Kotlin: a lambda's parameters are values of the type arguments its interface method takes, in the parameter it
     is passed to: 's -> Json.fromJson(s, type)' for a 'ThrowingFunction<String?, T>' has 's: String?'. Since a
     caller's null into 'parser.apply(text)' goes to the receiver's instantiation (6494045c), it reaches the lambda
     only this way (langchain4j JsonParsingUtils.kt:9, worktree-maddi-all 2026-10-17). Not from a type variable
     argument, which each call instantiates anew. Gate NOLAMBDAPARAMETERS.
     */
    private void lambdaParameters(Lambda lambda, Object pi) {
        MethodInfo body = lambda.methodInfo();
        if (LAMBDA_PARAMETERS_OFF.get() || body == null) return;
        ParameterizedType formal;
        if (pi instanceof ParameterInfo parameter && analysed.contains(parameter.methodInfo())) {
            formal = parameter.parameterizedType();
        } else if (!LAMBDA_PARAMETER_SLOTS_OFF.get() && pi instanceof Arg slot) {
            // a receiver's slot, as in lambdaResult: 'chain.add(next -> filter.getNextInstallState(current, next))'
            // on a List<Function<Provider<S?>, S?>> makes 'next' a Provider<S?>, which it passes on (jenkins
            // InstallUtil.kt:44, 2026-10-20). Gate NOLAMBDAPARAMETERSLOTS.
            formal = typeOf(slot);
            if (formal == null) return;
        } else {
            return;
        }
        if (formal.arrays() > 0 || formal.typeInfo() == null || formal.parameters().isEmpty()) return;
        MethodInfo sam = formal.typeInfo().singleAbstractMethod();
        if (sam == null || sam.typeInfo() != formal.typeInfo() || body.parameters().size() != sam.parameters().size()) {
            return;
        }
        for (ParameterInfo samParameter : sam.parameters()) {
            ParameterizedType t = samParameter.parameterizedType();
            if (!isClassTypeVariable(t) || t.wildcard() != null) continue;
            int j = formal.typeInfo().typeParameters().indexOf(t.typeParameter());
            if (j >= 0 && j < formal.parameters().size() && formal.parameters().get(j).typeParameter() == null) {
                Object lambdaParameter = body.parameters().get(samParameter.index());
                addEdge(new Arg(pi, j), lambdaParameter);
                // a generic argument ('Provider<S>'): the lambda's parameter is that object, its slots tied both ways
                if (pi instanceof Arg && !formal.parameters().get(j).parameters().isEmpty()) {
                    flows.add(List.of(new Arg(pi, j), lambdaParameter));
                }
            }
        }
    }

    private static final Switches.Gate LAMBDA_PARAMETER_SLOTS_OFF = Switches.gate("NOLAMBDAPARAMETERSLOTS");

    private static final Switches.Gate LAMBDA_PARAMETERS_OFF = Switches.gate("NOLAMBDAPARAMETERS");

    /*
     A lambda handed to a library parameter whose functional type has a nullable type argument (the hints'
     nullableArguments, from JSpecify): the library calls the lambda with null in each SAM parameter typed by that type
     argument. 'map.compute(k, (key, old) -> ...)' ('BiFunction<? super K, ? super @Nullable V, ? extends @Nullable
     V>'): 'old' is null for an absent key. Only where the SAM is declared by the parameter's own functional type, so
     that its type variables are the ones the positions name.
     */
    private void libraryCallback(Lambda lambda, Object pi) {
        if (!(pi instanceof ParameterInfo parameter) || analysed.contains(parameter.methodInfo())
            || lambda.methodInfo() == null) return;
        List<? extends Value.Nullability> arguments = parameter.analysis()
                .getOrDefault(PropertyImpl.NULLABILITY_PARAMETER, ValueImpl.NullabilityImpl.UNSPECIFIED).arguments();
        ParameterizedType formal = parameter.parameterizedType();
        if (arguments.isEmpty() || formal.arrays() > 0 || formal.typeInfo() == null) return;
        MethodInfo sam = formal.typeInfo().singleAbstractMethod();
        if (sam == null || sam.typeInfo() != formal.typeInfo()) return;
        List<ParameterInfo> lambdaParameters = lambda.methodInfo().parameters();
        if (lambdaParameters.size() != sam.parameters().size()) return;
        for (int j = 0; j < arguments.size() && j < formal.typeInfo().typeParameters().size(); j++) {
            if (arguments.get(j).state() != NullableState.NULLABLE) continue;
            var typeParameter = formal.typeInfo().typeParameters().get(j);
            for (ParameterInfo samParameter : sam.parameters()) {
                ParameterizedType t = samParameter.parameterizedType();
                if (t.arrays() == 0 && t.typeParameter() == typeParameter) {
                    seed(lambdaParameters.get(samParameter.index()), "called back with null by "
                                                                     + parameter.methodInfo().fullyQualifiedName());
                }
            }
        }
    }

    // pi: the parameter, or the Content of a varargs parameter
    private void argument(MethodInfo mi, Scope scope, Statement statement, Expression call,
                          LinkComputer.ListOfLinks list, List<Expression> arguments, int i, Object pi) {
        // Kotlin: in 'assertThrows(NullPointerException.class, () -> copyOf(nullKey))' the arguments are the test's
        // rejected input, whether a literal null (seed) or a variable holding one: they carry nothing into the
        // callee. guava's ImmutableClassToInstanceMapTest had made Primitives.wrap(type), which starts with
        // checkNotNull(type), take a Class<T>? (worktree-maddi-all 2026-10-22). Gate NOREJECTEDFLOWS.
        if (policy.callResults() && !REJECTED_FLOWS_OFF.get() && nullRejectingBodies.contains(mi.fullyQualifiedName())) {
            return;
        }
        // also a cast null: '(AiMessage) null' choosing between overloads (langchain4j OutputGuardrailResult)
        if (NonNullFacts.unwrap(arguments.get(i)) instanceof NullConstant) {
            seed(pi, "null argument in " + mi.fullyQualifiedName(), mi.typeInfo());
            return;
        }
        // M4: a variable argument known non-null at the call (or when its statement starts) carries no null
        if (NonNullFacts.unwrap(arguments.get(i)) instanceof VariableExpression ve
            && (call != null && facts.nonNullWhenCalled(call, ve.variable())
                || facts.nonNullAt(statement, ve.variable()))) {
            Object node = node(mi, scope, ve.variable());
            if (node != null) flows.add(List.of(node, pi)); // an array known non-null: its elements still flow
            return;
        }
        seedCreated(pi, arguments.get(i), mi);
        Expression unwrappedArgument = NonNullFacts.unwrap(arguments.get(i));
        if (policy.callResults() && unwrappedArgument instanceof Lambda lambda) {
            lambdaResult(lambda, pi);
            lambdaParameters(lambda, pi);
            receiverLambdaResult(mi, scope, call, lambda, pi);
        }
        if (policy.callResults() && unwrappedArgument instanceof MethodReference mr) methodReferenceResult(mr, pi);
        if (unwrappedArgument instanceof Lambda lambda) libraryCallback(lambda, pi);
        // 'getUniqueNext(graph, new HashSet<>(mapNext.values()))': a library copy constructor as the argument copies
        // its argument's content into the parameter's slots (fernflower FinallyProcessor)
        if (unwrappedArgument instanceof ConstructorCall copy && copy.constructor() != null
            && !analysed.contains(copy.constructor()) && copy.parameterizedType() != null
            && copy.parameterizedType().arrays() == 0) {
            contentCopies(mi, scope, pi, copy.constructor(), copy.parameterExpressions());
        }
        if (unwrappedArgument instanceof MethodCall amc && amc.methodInfo() != null) {
            Object source = argumentNode(mi, scope, unwrappedArgument);
            if (source instanceof Arg || source instanceof MethodInfo m && concreteArguments(m.returnType())) {
                flows.add(List.of(source, pi)); // the same object: content slots tied (coupleContent)
            }
            // Kotlin: a library view ('main(args.subList(1, args.size()))') is the receiver's data: the parameter's
            // slots are the receiver's (jenkins CLIAction.kt:232, 2026-10-21). Gate NOVIEWMETHODARGUMENTS.
            if (source == null && policy.callResults() && !VIEW_METHOD_ARGUMENTS_OFF.get() && pi instanceof ParameterInfo) {
                Object view = viewArgument(mi, scope, unwrappedArgument);
                if (view != null) flows.add(List.of(view, pi));
            }
            // Kotlin: a slot read passed on ('new Pair(cv.get(i), i)') gives the parameter the slot's value, as
            // callResult does for an assignment; the Java policies leave this to the links
            if (policy.callResults() && source instanceof Arg) addEdge(source, pi);
            // a stream's terminal operation as the argument: its elements are values of the parameter's slot
            streamTerminal(mi, scope, unwrappedArgument)
                    .forEach((j, nodes) -> nodes.forEach(n -> addEdge(n, new Arg(pi, j))));
        }
        String lib = nullableLibraryCall(arguments.get(i));
        if (lib != null) {
            seedIndirect(pi, "argument " + lib + " in " + mi.fullyQualifiedName(), mi.typeInfo());
            return;
        }
        if (list == null || i >= list.list().size()) {
            // no argument links ('super(attributes)', 'this(...)' carry none): each value the argument may take, its
            // own node; a null branch seeds ('this(r, text == null ? null : AiMessage.from(text), …)', langchain4j
            // OutputGuardrailResult, whose translated SUCCESS threw an NPE when this was dropped)
            for (Expression alternative : alternatives(arguments.get(i))) {
                Expression unwrappedAlternative = NonNullFacts.unwrap(alternative);
                if (unwrappedAlternative instanceof NullConstant) {
                    seed(pi, "null argument in " + mi.fullyQualifiedName(), mi.typeInfo());
                } else if (!(unwrappedAlternative instanceof MethodCall)) {
                    addEdge(argumentNode(mi, scope, alternative), pi);
                } else {
                    callResultArgument(unwrappedAlternative, pi);
                }
            }
            return;
        }
        Links links = list.list().get(i);
        Variable primary = links.primary();
        if (primary == null) {
            callResultArgument(unwrappedArgument, pi);
            return;
        }
        if (isNullMarker(primary)) {
            nullMarker(mi, primary, pi, "null argument in " + mi.fullyQualifiedName());
            return;
        }
        Object node = node(mi, scope, primary);
        if (node != null) addEdge(node, pi);
        // an intermediate (or a local assigned in this very statement): its sources, in the argument's own links
        sourcesOf(mi, scope, primary, links, pi);
    }

    /*
     An analysed method's result as the argument, where the links say nothing: the link engine links no value of an
     immutable type ('String' per the JDK hints), so 'builder.description(optionalString(map, "description"))' had
     no edge, and the setter's parameter stayed non-null although optionalString returns null (langchain4j's
     JsonSchemaElementJsonUtils.fromMap; the translated tests pass null there). The same rule as callResultOf: not a
     type-variable result, unless the callee's own null; and, like it, Kotlin only (Policy.callResults): guava
     measured -13 agree / +14 noise under NULL_MARKED with it on for every policy (2026-10-09).
     */
    private void callResultArgument(Expression argument, Object pi) {
        if (!policy.callResults() || !(argument instanceof MethodCall mc) || mc.methodInfo() == null) return;
        MethodInfo callee = mc.methodInfo();
        if (!analysed.contains(callee) || callee.isConstructor() || callee.returnType().isVoid()) return;
        ParameterizedType rt = callee.returnType();
        boolean ownNull = rt.arrays() == 0 && rt.typeParameter() != null
                          && (!rt.typeParameter().isMethodTypeParameter()
                              || callee.parameters().stream().noneMatch(p ->
                                     rt.typeParameter().equals(p.parameterizedType().typeParameter())));
        if (!isTypeVariable(rt) || ownNull) addEdge(callee, pi);
    }

    private void sourcesOf(MethodInfo mi, Scope scope, Variable primary, Links links, Object pi) {
        for (Link link : links) {
            if (!link.from().equals(primary)) continue;
            if (!(link.linkNature().isIdenticalTo() || link.linkNature().isAssignedFrom())) continue;
            if (isNullMarker(link.to())) {
                nullMarker(mi, link.to(), pi,
                        "null argument (via " + primary.simpleName() + ") in " + mi.fullyQualifiedName());
            } else {
                addEdge(node(mi, scope, link.to()), pi);
            }
        }
    }

    // Java's default value: a non-final reference field without an initializer, which some constructor leaves alone
    private void seedDefaultValue(FieldInfo fi) {
        if (fi.type().isPrimitiveExcludingVoid() && fi.type().arrays() == 0) return;
        Expression initializer = fi.initializer();
        if (initializer != null) {
            seedCreated(fi, initializer, null);
            factorySlots(fi, initializer, null);
            initializerContent(fi, initializer);
        }
        if (initializer instanceof NullConstant) {
            seed(fi, "initializer null", fi.owner());
            return;
        }
        if (fi.isFinal() || initializer != null && !initializer.isEmpty()) return;
        List<MethodInfo> constructors = fi.owner().constructors();
        if (constructors.isEmpty()) {
            seedIndirect(fi, "default value: no constructor assigns it", fi.owner());
            return;
        }
        for (MethodInfo constructor : constructors) {
            if (!assigns(constructor, fi)) {
                seedIndirect(fi, "default value: not assigned in " + constructor.fullyQualifiedName(), fi.owner());
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
        } else if (e instanceof MethodCall mc && mc.methodInfo() != null && analysed.contains(mc.methodInfo())
                   && !mc.methodInfo().returnType().isVoid()) {
            source = mc.methodInfo();
        }
        if (source != null && source != fi) flows.add(List.of(source, fi));
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
}

