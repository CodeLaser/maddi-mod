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

import static io.codelaser.maddi.modification.analyzer.nullability.Types.*;

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
    // NULLWHY: every print of the pass goes through it
    private final Trace trace;
    // analysed methods whose return an earlier round found unreached by null (see go)
    private final Set<MethodInfo> trustedReturns;
    // analysed parameters an earlier round found non-null on every normal exit of their method: passing null throws
    private final Set<ParameterInfo> preconditions;
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
    // gate NOROUNDKEYS: no key presence from an earlier round (A/B for #48 B6)
    private static final Switches.Gate ROUND_KEYS_OFF = Switches.gate("NOROUNDKEYS");
    // gate NOENTRYFIELDS: no field of this is non-null at a private method's entry by its calls (A/B for #48 A5)
    private static final Switches.Gate ENTRY_FIELDS_OFF = Switches.gate("NOENTRYFIELDS");
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
    // Culprits: the statement the walk is at (WalkSite)
    private final WalkSite site = new WalkSite();
    // the parts of this round, in the order they are built (see once)
    private final Index index;
    private final TypeUses typeUses;
    private final Seeds seeds;
    private final Contracts contracts;
    // the nodes and edges of this round
    private final NullGraph graph;
    private static final int CULPRIT_STEPS = 12;

    // (method, null marker): a return statement guarded the marker's link into the method's return (linksOf)
    private final Set<List<Object>> guardedReturnMarkers = new HashSet<>();
    // the same for a local: (method, marker, local) whose every assignment carrying the marker was guarded
    private final Set<List<Object>> guardedLocalMarkers = new HashSet<>();
    // (method, null marker): a statement carried the marker's link into the method's return (linksOf)
    private final Set<List<Object>> returnMarkersSeen = new HashSet<>();
    private MethodInfo owner;
    private NonNullFacts facts;
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
    private final Set<ParameterInfo> shadowed = new HashSet<>(); // walk state: reads resolve to the shadow from here on
    // per method (a lambda is its own), the declarations of each name: the fallback when scopes do not resolve one
    private final Map<MethodInfo, Map<String, List<Local>>> declaredByName = new HashMap<>();

    public NullabilityPass(Policy policy) {
        this(policy, Trace.NULL_WHY, Set.of(), Set.of(), NarrowedReads.NONE, Map.of(), Set.of());
    }

    private NullabilityPass(Policy policy, Trace trace, Set<MethodInfo> trustedReturns,
                            Set<ParameterInfo> preconditions, NarrowedReads narrowed,
                            Map<MethodInfo, Set<Variable>> fieldsAtEntry, Set<Expression> presentLookups) {
        this.policy = policy;
        this.trace = trace;
        this.index = new Index(policy);
        this.typeUses = new TypeUses(policy, index);
        this.seeds = new Seeds(trace, index, site);
        this.contracts = new Contracts(policy, trace, index, seeds, trustedReturns, preconditions, presentLookups);
        this.graph = new NullGraph(policy, trace, site);
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
            NullabilityPass pass = new NullabilityPass(policy, trace, trusted, nonNullAtExit, narrowedReads, atEntry, present);
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
                if (index.analysed.contains(callee)) {
                    yield reached.contains(callee) || external.contains(callee) || contracts.degraded.contains(callee);
                }
                yield contracts.libraryNullableCall(mc) != null;
            }
            default -> true;
        };
    }

    // gate NOFAMILYPRECONDITIONS: only methods no override replaces have preconditions, as before CodeLaser/maddi-mod#41
    private static final Switches.Gate FAMILY_PRECONDITIONS_OFF = Switches.gate("NOFAMILYPRECONDITIONS");

    private Set<ParameterInfo> preconditions() {
        Set<ParameterInfo> set = new HashSet<>();
        Map<MethodInfo, Set<MethodInfo>> families = policy.world() == World.CLOSED && !FAMILY_PRECONDITIONS_OFF.get()
                ? index.families() : Map.of();
        for (MethodInfo mi : index.analysed) {
            if (families.containsKey(mi)) continue;
            // a call runs this body only when no override replaces it: an analysed one; in an open world any
            boolean overridable = !mi.isConstructor() && !mi.isStatic() && !mi.access().isPrivate()
                                  && !mi.isFinal() && !mi.typeInfo().isFinal();
            if (overridable && (policy.world() != World.CLOSED || index.implementations.containsKey(mi))) continue;
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
            if (m.parameters().size() != n || this.index.referencedSams.contains(m)) {
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
                ? index.families() : Map.of();
        Set<ParameterInfo> declared = new HashSet<>();
        for (ParameterInfo pi : preconditions) {
            Set<MethodInfo> family = families.getOrDefault(pi.methodInfo(), Set.of(pi.methodInfo()));
            boolean excluded = family.stream().anyMatch(m -> m.parameters().size() <= pi.index()
                    || nullLiteralParameters.contains(m.parameters().get(pi.index()))
                    || index.referencedMethods.contains(m) || junitNullSource(m)
                    || hasBody(m) && comparedWithNull(m, m.parameters().get(pi.index()), new HashSet<>())
                    || overrides(m).stream().anyMatch(o -> !index.analysed.contains(o)));
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
                if (callee != null && index.analysed.contains(callee) && hasBody(callee)
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
        for (MethodInfo mi : index.analysed) {
            ParameterizedType rt = mi.returnType();
            if (mi.isConstructor() || rt.isVoid() || rt.isPrimitiveExcludingVoid() && rt.arrays() == 0
                || contracts.degraded.contains(mi) || reached.contains(mi) || external.contains(mi)) continue;
            set.add(mi);
        }
        return Set.copyOf(set);
    }

    private Report once(List<Info> analysisOrder) {
        List<MethodInfo> methods = analysisOrder.stream()
                .filter(i -> i instanceof MethodInfo).map(i -> (MethodInfo) i).toList();
        List<FieldInfo> fields = analysisOrder.stream()
                .filter(i -> i instanceof FieldInfo).map(i -> (FieldInfo) i).toList();
        index.analysed.addAll(methods);
        index.analysedFields = fields;
        typeUses.nonNullBounds(analysisOrder, methods, fields);
        methods.forEach(seeds::indexNullLiterals);
        for (MethodInfo mi : methods) if (mi.methodBody() != null) seeds.indexFills(mi.methodBody());
        for (MethodInfo mi : methods) {
            for (MethodInfo overridden : overrides(mi)) {
                index.implementations.computeIfAbsent(overridden, _ -> new java.util.ArrayList<>()).add(mi);
            }
        }
        index.indexFunctionals(methods);
        index.indexKeyedMaps(methods, fields);
        predicates = NullPredicates.infer(methods);
        narrowedReads = narrowed.isEmpty() ? null : narrowed::nonNull;
        facts = new NonNullFacts(contracts::parameterContract, contracts::returnContract)
                .withPredicates(predicates)
                .withNarrowedReads(narrowedReads)
                .withNarrowedInMethod(narrowedReads == null ? null : this::narrowedInMethod)
                .withFieldsAtEntry(fieldsAtEntry)
                .withSuperCalls(policy.callResults() && !Contracts.SUPER_CALLS_OFF.get() ? contracts::ownReturnsNonNull : null);
        contracts.facts = facts;
        if (policy.contracts()) {
            for (FieldInfo fi : fields) contracts.contract(fi, fi);
            for (MethodInfo mi : methods) {
                for (ParameterInfo pi : mi.parameters()) contracts.contract(pi, pi);
                if (!mi.isConstructor() && !mi.returnType().isVoid()) contracts.contract(mi, mi);
            }
            for (FieldInfo fi : fields) contracts.elementContract(fi.type(), fi);
            for (MethodInfo mi : methods) {
                for (ParameterInfo pi : mi.parameters()) contracts.elementContract(pi.parameterizedType(), pi);
                if (!mi.isConstructor() && !mi.returnType().isVoid()) contracts.elementContract(mi.returnType(), mi);
            }
        }
        if (policy.callResults()) {
            for (MethodInfo mi : methods) {
                if (Contracts.boundedTypeVariableOverride(mi) || contracts.overridesKotlinNonNullReturn(mi)
                    || contracts.overridesLibraryNonNullReturn(mi) || contracts.testOverrideOfInstantiatedReturn(mi)) contracts.add(mi);
            }
            contracts.nonNullOverrides(methods);
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
        graph.linkEdges.forEach((edge, count) -> {
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
                graph.addEdge(edge.get(0), edge.get(1));
            } else {
                graph.flows.add(List.of(edge.get(0), edge.get(1))); // the value is not null there; its elements may be
            }
        });
        holderFields(methods, fields);
        fieldInitializerNullArguments(fields);
        assignedParameterSlots(methods);
        enumValueOfContracts(methods);
        nullFilteredParameters(methods);
        resolveTypeVariableArguments();
        coupleContent();
        if (!graph.invariantTies.isEmpty()) {
            // a class type variable's slot is reached only by the class's own nulls (coupleContent never ties it
            // to an instantiation's slot), so that is known before the invariance edges are added
            Set<Object> own = closure(new HashMap<>());
            boolean added = false;
            for (Object[] tie : graph.invariantTies) {
                if (!own.contains(tie[2])) {
                    graph.addEdge(tie[0], tie[1]);
                    added = true;
                }
            }
            if (added) coupleContent(); // the nested slots of the new flows
        }

        contracts.libraryParameterContracts(graph.successors);
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
                contracts.addAll(declared);
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
                contracts.addAll(asserted);
                cause.clear();
                reached = closure(cause);
            }
        }
        if (policy.world() != World.CLOSED) external = externalClosure(methods);
        if (policy.callResults()) reached = storedOnlyIntoNullableFields(methods, reached, cause);

        Map<Info, ParameterizedType> verdicts = new LinkedHashMap<>();
        Map<Local, ParameterizedType> locals = new LinkedHashMap<>();
        // an unreached local of a degraded method: its links are missing, so "no null reaches it" is not known
        graph.declaredLocals.forEach((local, lv) -> {
            ParameterizedType v = verdict(lv.parameterizedType(), local, contracts.degraded.contains(graph.localOwner.get(local)));
            locals.put(local, guardedLocals.contains(local) && !(v.isPrimitiveExcludingVoid() && v.arrays() == 0)
                    ? v.withNullable(NullableState.NULLABLE) : v);
        });
        graph.shadows.forEach((local, pi) -> locals.put(local, verdict(pi.parameterizedType(), local,
                contracts.degraded.contains(pi.methodInfo()))));
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
        NonNullFacts smartCasts = facts.kotlinSmartCasts();
        methods.forEach(smartCasts::walk);
        Set<Expression> nonNullCallResults = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        Map<MethodInfo, Boolean> intrinsic = new HashMap<>();
        Set<Object> finalReached = reached;
        graph.callIds.forEach((call, id) -> {
            MethodInfo callee = graph.callees.get(id);
            if (finalReached.contains(callee)
                && !callResultNullable(callee, id, finalReached, finalReached::contains, intrinsic)) {
                nonNullCallResults.add(call);
            }
        });
        // gap 7: the facts handed out know what the closure decided; the walk that built the graph could not
        NonNullFacts useSites = new NonNullFacts(contracts::parameterContract, contracts::returnContract)
                .withPredicates(predicates)
                .withNarrowedReads(narrowedReads)
                .withNarrowedInMethod(narrowedReads == null ? null : this::narrowedInMethod)
                .withNotNullable(e -> notNullable(e, nonNullCallResults))
                .withFieldsAtEntry(fieldsAtEntry);
        methods.forEach(useSites::walk);
        nextFieldsAtEntry = fieldsAtEntry(useSites, methods, fields);
        if (!ROUND_KEYS_OFF.get()) {
            Set<Expression> present = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
            for (MethodCall lookup : contracts.lookups) if (useSites.keyPresentWhenCalled(lookup)) present.add(lookup);
            nextPresentLookups = present;
        }
        return new Report(verdicts, locals, cause, seeds.seedOrigin, useSites, smartCasts, Set.copyOf(asserted),
                Set.copyOf(unobservedCandidates), nonNullCallResults, preconditions, nullArguments, preconditionsNonNull,
                rejectsNull(), writtenReach, Set.copyOf(typeUses.nonNullTypeParameters), parentClassVerdicts(methods));
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
                Site site = graph.edgeSites.getOrDefault(from, Map.of()).get(at);
                if (site != null) {
                    io.codelaser.maddi.modification.common.util.Culprits.add(steps,
                            io.codelaser.maddi.modification.common.util.Culprits.step(site.in(), site.element(),
                                    Trace.culpritText(site.element())));
                }
                at = from;
            }
            String origin = report.seedOrigin().get(at);
            Site seed = seeds.seedSites.get(at);
            if (seed != null) {
                io.codelaser.maddi.modification.common.util.Culprits.add(steps,
                        io.codelaser.maddi.modification.common.util.Culprits.step(seed.in(), seed.element(),
                                Trace.culpritText(seed.element()) + (origin == null ? "" : " (" + Trace.shorten(origin) + ")")));
            } else if (origin != null) {
                steps.add(new Value.Culprit.Step(null, 0, 0, Trace.shorten(origin)));
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
     Kotlin: type arguments are invariant, so the type-argument slots of an override's return or parameter equal the
     analysed overridden member's, where both declare the same class ('List<String>' over 'List<E>'): edges both ways
     (guava: AbstractMapBasedMultimap's remove, MutableCollection<V?>? over MutableCollection<V>?).
     */
    private void invariantSlots(ParameterizedType ours, Object node, ParameterizedType theirs, Object theirNode,
                                boolean upward) {
        if (ours == null || theirs == null || ours.arrays() != 0 || theirs.arrays() != 0 || ours.typeInfo() == null
            || ours.typeInfo() != theirs.typeInfo() || ours.parameters().size() != theirs.parameters().size()) return;
        for (int j = 0; j < ours.parameters().size(); j++) {
            if (upward) graph.addEdge(new Arg(node, j), new Arg(theirNode, j));
            graph.addEdge(new Arg(theirNode, j), new Arg(node, j));
        }
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
                if (primitive(type) || isTypeVariable(type) || before.contains(pi) || contracts.nonNullContracts.contains(pi)
                    || contracts.explicitState(pi) == NullableState.NONNULL || contracts.libraryNonNullParameter(mi, pi.index())
                    || hook(mi, pi) || overloadedAt(mi, pi) || overridesAnalysed(mi)) continue;
                if (tolerates(pi, before)) {
                    seeds.seedIndirect(pi, mi.fullyQualifiedName() + " tolerates null in " + pi.name()
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
        return index.implementations.containsKey(mi) && !read(mi, pi);
    }

    /*
     An override of an analysed method takes its parameters' nullability from the family, which Kotlin wants as one: its
     own tolerance would raise the declaration's (guava's ServiceManager listener logs 'failure', which Logger.log
     accepts null for, and Service.Listener.failed's empty hook became 'failure: Throwable?'). The declaration's own
     body decides. An override of a library method only (a Comparator's compare) is #68's case.
     */
    private boolean overridesAnalysed(MethodInfo mi) {
        return mi.overrides().stream().anyMatch(index.analysed::contains);
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
        // NULLWHY: the node the walk reached each node from, to print the paths of a traced parameter
        Map<Object, Object> from = trace.why(start) ? new HashMap<>() : null;
        Set<Object> seen = new HashSet<>();
        Deque<Object> queue = new ArrayDeque<>();
        seen.add(start);
        queue.add(start);
        while (!queue.isEmpty()) {
            Object n = queue.removeFirst();
            if (seen.size() > MAX_TOLERANCE_SEARCH || contracts.nonNullContracts.contains(n)) {
                return trace.tolerance(from, n, seen.size() > MAX_TOLERANCE_SEARCH ? "search too large" : "contract");
            }
            switch (n) {
                case ParameterInfo pi -> {
                    MethodInfo m = pi.methodInfo();
                    if (!hasBody(m) || demanded(m, v -> pi.equals(v))) return trace.tolerance(from, n, "demanded");
                }
                case Local local -> {
                    // by name, as NonNullFacts compares locals; a reassigned parameter's shadow is the parameter
                    if (demanded(local.methodInfo(), v -> (v instanceof LocalVariable || v instanceof ParameterInfo)
                                                          && v.simpleName().equals(local.name()))) {
                        return trace.tolerance(from, n, "demanded");
                    }
                }
                case FieldInfo _, MethodInfo _ -> {
                    if (!before.contains(n)) return trace.tolerance(from, n, "non-null sink");
                    trace.tolerance(from, n, "nullable anyway");
                    continue; // nullable anyway: what it flows into is decided without this parameter
                }
                case Content _, Arg _ when declaredSlot(n) && !TOLERANT_SLOTS_OFF.get() -> {
                    if (!before.contains(n)) return trace.tolerance(from, n, "non-null slot");
                    trace.tolerance(from, n, "nullable anyway");
                    continue;
                }
                default -> {
                }
            }
            for (Object s : graph.successors.getOrDefault(n, Set.of())) {
                if (seen.add(s)) {
                    queue.addLast(s);
                    if (from != null) from.put(s, n);
                }
            }
        }
        if (from != null) trace.print("NULLWHY tolerates " + Trace.name(start) + " visited=" + seen.size());
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

    /*
     a demand in m's body, or in the body of the method that encloses m's lambda or local class; or in a method of an
     anonymous or local class m encloses, which captures m's variables: langchain4j LambdaStreamingResponseHandler
     hands 'onPartialResponse' to an anonymous handler whose onPartialResponse(s) calls 'onPartialResponse.accept(s)'
     (#68: 5 C# warnings, test-manager 2026-10-30). A lambda's body is m's own (NonNullFacts.demands). Gate
     NOENCLOSEDDEMANDS.
     */
    private boolean demanded(MethodInfo m, java.util.function.Predicate<Variable> variable) {
        for (MethodInfo mi = m; mi != null; mi = mi.typeInfo().enclosingMethod()) {
            if (facts.demands(mi).stream().anyMatch(variable)) return true;
        }
        if (!ENCLOSED_DEMANDS_OFF.get()) {
            for (MethodInfo inner : index.enclosedMethods().getOrDefault(m, List.of())) {
                if (facts.demands(inner).stream().anyMatch(variable)) return true;
            }
        }
        return false;
    }

    private static final Switches.Gate ENCLOSED_DEMANDS_OFF = Switches.gate("NOENCLOSEDDEMANDS");

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
                if (reached.contains(pi) || contracts.nonNullContracts.contains(pi)
                    || policy.world() == World.OPEN && facts.nonNullAtExit(mi, pi)
                    || contracts.libraryNonNullParameter(mi, pi.index())) {
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
            for (Object s : graph.successors.getOrDefault(n, Set.of())) {
                if (!contracts.nonNullContracts.contains(s) && !reached.contains(s) && set.add(s)) queue.add(s);
            }
        }
        return set;
    }

    // an outside caller chooses the elements of an array it passes, whatever it does with the array itself
    private void addContent(Set<Object> set, Object node, ParameterizedType type) {
        if (type.arrays() > 0) {
            Content content = new Content(node);
            if (!reached.contains(content) && !contracts.nonNullContracts.contains(content)) set.add(content);
            addContent(set, content, type.componentType());
            return;
        }
        for (int i = 0; i < type.parameters().size(); i++) {
            Arg arg = new Arg(node, i);
            if (!reached.contains(arg) && !contracts.nonNullContracts.contains(arg)) set.add(arg);
            addContent(set, arg, type.parameters().get(i));
        }
    }

    private boolean externallyCallable(MethodInfo mi) {
        if (visibleOutside(mi)) return true;
        // an override is called through what it overrides: from outside, or by the library itself
        return overrides(mi).stream().anyMatch(o -> !index.analysed.contains(o) || visibleOutside(o));
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

    private Set<Object> closure(Map<Object, Object> cause) {
        if (!policy.assertContentWrites()) {
            // the strict closure only for the Report (CodeLaser/maddi-mod#49): the verdicts are the full closure's
            writtenReach = Set.copyOf(closure(new HashMap<>(), this.seeds.strictSeeds, null));
            return closure(cause, this.seeds.seedOrigin.keySet(), null);
        }
        // Policy.assertContentWrites: first what null reaches without the library contracts; a value outside it
        // enters a slot only as an asserted write, so that edge is not followed, and a library seed on a slot itself
        // is an asserted write too
        Map<Object, Object> strictCause = new HashMap<>();
        Set<Object> strict = closure(strictCause, this.seeds.strictSeeds, null);
        writtenReach = Set.copyOf(strict);
        Set<Object> seeds = new LinkedHashSet<>(this.seeds.seedOrigin.keySet());
        seeds.removeIf(n -> isSlot(n) && !this.seeds.strictSeeds.contains(n));
        Set<Object> reached = closure(cause, seeds, strict);
        // explain a node null reaches by a written null with that chain: it is the one that lets the node's value
        // into a slot, where the first chain found may begin at a library contract or a default value
        strict.forEach(n -> {
            if (this.seeds.strictSeeds.contains(n)) cause.remove(n);
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
        ParameterizedType type = graph.typeOf(arg.of());
        return type != null && type.arrays() == 0 && type.typeInfo() != null
               && "java.util.Optional".equals(type.typeInfo().fullyQualifiedName());
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
        ParameterizedType outer = graph.typeOf(a.of());
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
        if (typeUses.nonNullTypeParameters.isEmpty()) return false;
        ParameterizedType outer = graph.typeOf(a.of());
        if (outer == null || outer.arrays() > 0) return false;
        TypeInfo ti = outer.typeInfo();
        if (ti != null && a.index() < ti.typeParameters().size()
            && typeUses.nonNullTypeParameters.contains(ti.typeParameters().get(a.index()))) return true;
        if (a.index() >= outer.parameters().size()) return false;
        ParameterizedType p = outer.parameters().get(a.index());
        return p.typeParameter() != null && (p.wildcard() == null || !p.wildcard().isSuper())
               && typeUses.nonNullTypeParameters.contains(p.typeParameter());
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
        if (TIED_BOUNDS_OFF.get() || typeUses.nonNullTypeParameters.isEmpty()) return tiedToNonNull;
        List<String> why = TypeUses.NULL_MARKED_DEBUG == null ? null : new ArrayList<>();
        // the container itself given to a null-marked slot that the graph does not tie, a method type variable's:
        // 'copyOf(multiset)'. Not along the slot edges: those carry values ('accessQueue.add(table.get(i))'), which
        // Kotlin asserts at the write, and had made Segment.table's elements non-null through
        // ImmutableList.copyOf(accessQueue), where Segment.clear() writes null (guava, 2026-10-19)
        for (List<Object> flow : graph.flows) {
            ParameterizedType t0 = graph.typeOf(flow.get(0));
            ParameterizedType t1 = graph.typeOf(flow.get(1));
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
                java.nio.file.Files.write(java.nio.file.Path.of(TypeUses.NULL_MARKED_DEBUG + ".tied"), why);
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
    private static final Switches.Gate REJECTED_FLOWS_OFF = Switches.gate("NOREJECTEDFLOWS");
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
        reached.removeAll(contracts.nonNullContracts);
        reached.removeIf(this::optionalValue);
        reached.removeIf(this::boundedSlot);
        Deque<Object> queue = new ArrayDeque<>(reached);
        while (true) {
            while (!queue.isEmpty()) {
                Object n = queue.removeFirst();
                for (Object s : graph.successors.getOrDefault(n, Set.of())) {
                    if (strict != null && isSlot(s) && !isSlot(n) && !strict.contains(n) && !isVarargElements(s)) continue;
                    if (optionalValue(s) || boundedSlot(s)) continue;
                    if (!contracts.nonNullContracts.contains(s) && reached.add(s)) {
                        cause.put(s, n);
                        queue.addLast(s);
                    }
                }
            }
            // gap 9: the gated call results, once nothing else moves; monotone, so repeated until none opens
            Map<MethodInfo, Boolean> intrinsic = new HashMap<>();
            for (GatedSite site : graph.gatedSites) {
                if (!reached.contains(site.callee()) || reached.contains(site.target())
                    || contracts.nonNullContracts.contains(site.target())
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
        if (GAP9_OFF.get() || !graph.argumentsSeen.contains(call)) return true;
        Set<ParameterInfo> through = graph.passThrough(callee);
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

    /* whether null reaches 'callee' backwards from a seed, without passing through one of 'through' */
    private boolean reachedWithout(MethodInfo callee, Set<ParameterInfo> through, Set<Object> reached) {
        Map<Object, Set<Object>> pred = graph.predecessors();
        Set<Object> seen = new HashSet<>();
        Deque<Object> todo = new ArrayDeque<>(List.of(callee));
        while (!todo.isEmpty()) {
            Object n = todo.removeFirst();
            if (!seen.add(n) || through.contains(n) || !reached.contains(n)) continue;
            if (seeds.seedOrigin.containsKey(n)) return true;
            todo.addAll(pred.getOrDefault(n, Set.of()));
        }
        return false;
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
                    case ConstructorCall cc when anonymous -> index.anonymousSuperConstructor(cc);
                    case ConstructorCall cc -> cc.constructor();
                    default -> null;
                };
                if (anonymous && callee == null) return false;
                if (callee == null || !index.analysed.contains(callee)) return true;
                List<Expression> args = e instanceof MethodCall mc ? mc.parameterExpressions()
                        : ((ConstructorCall) e).parameterExpressions();
                for (int i = 0; i < args.size() && i < callee.parameters().size(); i++) {
                    ParameterInfo pi = callee.parameters().get(i);
                    // not a type-variable parameter: the null instantiates the variable for that call, as at any call
                    // site ('static final ImmediateFuture<?> NULL = new ImmediateFuture<>(null)', guava)
                    if (!pi.isVarArgs() && !isTypeVariable(pi.parameterizedType())
                        && NonNullFacts.unwrap(args.get(i)) instanceof NullConstant) {
                        seeds.seed(pi, Seeds.NULL_ARGUMENT + fi.fullyQualifiedName(), fi.owner());
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
                    graph.flows.add(List.of(pi, fr.fieldInfo()));
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
                contracts.add(mi.parameters().getFirst());
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
        nodes.addAll(graph.declaredLocals.keySet());
        for (Object n : nodes) {
            ParameterizedType type = graph.typeOf(n);
            if (type == null || type.arrays() > 0 || type.typeInfo() == null) continue;
            for (FieldInfo fi : byOwner.getOrDefault(type.typeInfo(), List.of())) {
                int index = classTypeVariable(fi);
                if (index >= type.parameters().size()) continue;
                if (policy.assertContentWrites()) {
                    graph.pendingHolders.computeIfAbsent(fi, _ -> new ArrayList<>()).add(new Arg(n, index));
                } else {
                    graph.addEdge(fi, new Arg(n, index));
                }
            }
        }
        if (!graph.pendingHolders.isEmpty()) holdersReachedFromOutside();
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
        graph.pendingHolders.keySet().forEach(fi -> byOwner.computeIfAbsent(fi.owner(), _ -> new ArrayList<>()).add(fi));
        byOwner.forEach((holder, holders) -> {
            Set<Object> outside = new LinkedHashSet<>();
            seeds.seedOrigin.keySet().forEach(seed -> {
                List<TypeInfo> writers = seeds.seedWriters.getOrDefault(seed, List.of());
                if (writers.isEmpty() || writers.stream().anyMatch(w -> !within(w, holder))) outside.add(seed);
            });
            Set<Object> reachedFromOutside = closure(new HashMap<>(), outside, null);
            for (FieldInfo fi : holders) {
                if (reachedFromOutside.contains(fi)) graph.pendingHolders.get(fi).forEach(arg -> graph.addEdge(fi, arg));
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
        Deque<List<Object>> queue = new ArrayDeque<>(graph.flows);
        Set<List<Object>> seen = new HashSet<>();
        while (!queue.isEmpty()) {
            List<Object> flow = queue.removeFirst();
            if (!seen.add(flow)) continue;
            // not through a library method: its parameter would tie together the arrays of all its callers
            if (index.library(flow.get(0)) || index.library(flow.get(1))) continue;
            ParameterizedType t0 = graph.typeOf(flow.get(0));
            ParameterizedType t1 = graph.typeOf(flow.get(1));
            if (t0 == null || t1 == null) continue;
            if (t0.arrays() > 0 && t1.arrays() > 0) {
                Content c0 = new Content(flow.get(0));
                Content c1 = new Content(flow.get(1));
                // the links run both ways: 'join(upperBounds)' also gives a flow from join's 'parts' to the caller's
                // array. A read-only parameter flows on only into another or a library's (readOnlyArrayParameters);
                // anything else is that reversed link
                if ((!readOnlyArrayParameter(flow.get(0), readOnlyArrays)
                     || readOnlyArrayParameter(flow.get(1), readOnlyArrays))
                    && !graph.varargsPasses.contains(List.of(flow.get(1), flow.get(0)))
                    && !(narrowedArray(t0, t1) && !written.contains(flow.get(0)))) {
                    graph.successors.computeIfAbsent(c0, _ -> new LinkedHashSet<>()).add(c1);
                    if (trace.why(c1.of())) trace.log("coupled forward", c1.of(), "from " + Trace.name(c0.of()) + " " + t0 + " -> " + t1);
                }
                if (!readOnlyArrayParameter(flow.get(1), readOnlyArrays) && !graph.varargsPasses.contains(flow)
                    && (narrowedArray(t1, t0) ? written.contains(flow.get(1))
                        : policy.assertContentWrites() || writtenThrough.contains(flow.get(1)))) {
                    graph.successors.computeIfAbsent(c1, _ -> new LinkedHashSet<>()).add(c0);
                    if (trace.why(c0.of())) trace.log("coupled back", c0.of(), "from " + Trace.name(c1.of()) + " " + t1 + " -> " + t0);
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
                    if (!class1 || class0) graph.successors.computeIfAbsent(a0, _ -> new LinkedHashSet<>()).add(a1);
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
                    boolean read = graph.viewLoopReads.contains(flow);
                    if (!isExtendsWildcard(p1) && !unbound && !declaredNullable && (!class0 || class1) && !read) {
                        graph.successors.computeIfAbsent(a1, _ -> new LinkedHashSet<>()).add(a0);
                    }
                    if (read) graph.viewLoopReads.add(List.of(a0, a1));
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
        for (List<Object> flow : graph.flows) {
            if (flow.get(0) instanceof ParameterInfo pi) out.computeIfAbsent(pi, _ -> new HashSet<>()).add(flow.get(1));
        }
        for (MethodInfo mi : index.analysed) {
            if (!overrides(mi).isEmpty() || index.implementations.containsKey(mi)) continue;
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
                    if (to instanceof ParameterInfo target && (candidates.contains(target) || index.library(target))) continue;
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
        Map<Object, String> because = !trace.enabled() ? null : new HashMap<>();
        graph.successors.forEach((from, tos) -> {
            // narrowedArray: nor is an element "written" from a lifted field ('RegularImmutableList.array ∋
            // RegularImmutableList.element'), the links' stand-in for any element, not code writing into the array
            if (from instanceof Content || !annotations && liftedField(from)) return;
            for (Object to : tos) {
                if (to instanceof Content c && written.add(c.of()) && because != null) {
                    because.put(c.of(), "written from " + Trace.name(from));
                }
            }
        });
        seeds.seedOrigin.forEach((node, origin) -> {
            if (node instanceof Content c && !origin.startsWith("array created") && !origin.startsWith("null in an array initializer")
                && (annotations || !origin.startsWith("annotated"))) {
                if (written.add(c.of()) && because != null) because.put(c.of(), "seed " + origin);
            }
        });
        Map<Object, List<Object>> upstream = new HashMap<>();
        for (List<Object> flow : graph.flows) upstream.computeIfAbsent(flow.get(1), _ -> new ArrayList<>()).add(flow.get(0));
        Deque<Object> queue = new ArrayDeque<>(written);
        while (!queue.isEmpty()) {
            Object down = queue.removeFirst();
            if (!annotations && (liftedField(down) || index.library(down))) {
                if (because != null && trace.why(down)) trace.print("NULLWHY written(false) stops at " + Trace.name(down));
                continue;
            }
            for (Object up : upstream.getOrDefault(down, List.of())) {
                // narrowedArray: an array is written through arrays only, not through a type argument's slot, a
                // return value or a call's argument that happens to link to it
                if (!annotations && !LIFTED_WRITES_OFF.get() && !(isArray(up) && isArray(down))) continue;
                if (written.add(up)) {
                    queue.add(up);
                    if (because != null) because.put(up, "flows into " + Trace.name(down));
                }
            }
        }
        // narrowedArray: nor is the lifted field itself written, nor does it pass its elements on as if it were
        if (!annotations) written.removeIf(NullabilityPass::liftedField);
        if (because != null) {
            for (Object node : written) {
                if (!trace.why(node)) continue;
                StringBuilder sb = new StringBuilder("NULLWHY written(" + annotations + ") " + Trace.name(node)
                        + (liftedField(node) ? " (lifted)" : ""));
                Object n = node;
                for (int i = 0; i < 40 && because.get(n) != null; i++) {
                    String b = because.get(n);
                    sb.append("\n    ").append(b);
                    if (!b.startsWith("flows into ")) break;
                    n = written.stream().filter(w -> Trace.name(w).equals(b.substring(11))).findFirst().orElse(null);
                    if (n == null) break;
                }
                trace.print(sb);
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
        ParameterizedType t = graph.typeOf(node);
        return t != null && t.arrays() > 0;
    }

    // the elements of a lifted field (liftedField) linked into a narrower array: what other arrays of the type hold
    private boolean liftedIntoNarrower(Object from, Object to) {
        if (!(from instanceof Content cf) || !(to instanceof Content ct) || !liftedField(cf)) return false;
        ParameterizedType tf = graph.typeOf(cf.of());
        ParameterizedType tt = graph.typeOf(ct.of());
        return tf != null && tt != null && narrowedArray(tf, tt);
    }

    private static final Switches.Gate LIFTED_WRITES_OFF = Switches.gate("NOLIFTEDWRITES");

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
                seeds.seed(new Arg(pi, i), "read from '*' in " + pi.methodInfo().fullyQualifiedName());
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
        ParameterizedType t0 = graph.typeOf(from);
        ParameterizedType t1 = graph.typeOf(to);
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

    // the shadow local of a reassigned parameter; created once, the caller's value flows into it
    private Local shadow(ParameterInfo pi) {
        Local local = new Local(pi.methodInfo(), null, pi.name());
        if (graph.shadows.putIfAbsent(local, pi) == null) {
            graph.localOwner.put(local, pi.methodInfo());
            graph.addEdge(pi, local);
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
        scope.names().put(lv.simpleName(), local);
        graph.declaredLocals.put(local, lv);
        graph.localOwner.put(local, owner);
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
                ParameterizedType type = base == null ? null : graph.typeOf(base);
                yield type == null || type.arrays() > 0 || type.parameters().size() != 1 ? null : argSlot(base, 0);
            }
            case FieldReference fr when !fr.scopeIsRecursivelyThis() && fr.scopeVariable() != null
                                        && classTypeVariable(fr.fieldInfo()) >= 0 -> {
                // 'b.t' of a generic holder 'Box<String> b': its type argument, not the field shared by all boxes
                Object base = node(mi, scope, fr.scopeVariable());
                ParameterizedType type = base == null ? null : graph.typeOf(base);
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

    // the slot of type argument 'index' of 'base', when its declared type has one there
    private Object argSlot(Object base, int index) {
        ParameterizedType type = graph.typeOf(base);
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
        MethodInfo owner = seeds.nullOwner(((LocalVariable) marker).assignmentExpression());
        if (owner != null && !owner.equals(mi) && index.analysed.contains(owner) && !owner.returnType().isVoid()) {
            graph.addEdge(owner, target);
        } else {
            seeds.seed(target, origin, writer(mi));
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

    // gate NOPARAMETRIC: a '@ParametricNullness' type-variable return's own null reaches every call (A/B)
    private static final Switches.Gate PARAMETRIC_OFF = Switches.gate("NOPARAMETRIC");

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
                    || policy.callResults() && this.index.analysed.contains(overridden) && !Contracts.nullableInstantiation(mi, overridden)) {
                    graph.addEdge(mi, overridden);
                }
                if (policy.callResults() && this.index.analysed.contains(overridden)) {
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
                        graph.addEdge(overridden.parameters().get(i), mi.parameters().get(i));
                    }
                    if (upward && !isTypeVariable(overridden.parameters().get(i).parameterizedType())) {
                        graph.addEdge(mi.parameters().get(i), overridden.parameters().get(i));
                    }
                }
            }
        }
        if (!mi.isConstructor()) {
            String lib = contracts.libraryNullableReturn(mi);
            if (lib != null && !mi.returnType().isVoid()) seeds.seed(mi, "overrides " + lib);
            contracts.libraryNullableContent(mi, mi.returnType(), m -> m.analysis().getOrDefault(PropertyImpl.NULLABILITY_METHOD,
                    ValueImpl.NullabilityImpl.UNSPECIFIED), MethodInfo::returnType);
            for (ParameterInfo pi : mi.parameters()) {
                int index = pi.index();
                starSlots(pi);
                contracts.libraryNullableContent(pi, pi.parameterizedType(), m -> index < m.parameters().size()
                        ? m.parameters().get(index).analysis().getOrDefault(PropertyImpl.NULLABILITY_PARAMETER,
                        ValueImpl.NullabilityImpl.UNSPECIFIED) : ValueImpl.NullabilityImpl.UNSPECIFIED,
                        m -> index < m.parameters().size() ? m.parameters().get(index).parameterizedType() : null);
                String libParam = contracts.libraryNullableParameter(mi, pi.index());
                if (libParam != null) {
                    seeds.seed(pi, "overrides " + libParam);
                } else if (policy.libraryCallbacks()) {
                    String unhinted = contracts.libraryUnhintedParameter(mi, pi.index());
                    if (unhinted != null) seeds.seed(pi, "overrides " + unhinted + ", which does not declare it non-null");
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
                    seeds.seed(pi, "JUnit null source of " + mi.fullyQualifiedName());
                }
            }
        }
        // Policy.rejectionsNullable (#28): a Java caller's null should reach the method's own check; constructors too
        if (policy.rejectionsNullable()) {
            for (ParameterInfo pi : mi.parameters()) {
                if (rejects(pi)) seeds.seed(pi, mi.fullyQualifiedName() + " rejects null with its own exception");
            }
        }
        if (mi.analysis().getOrDefault(PropertyImpl.DEGRADED_ANALYSIS_METHOD,
                ValueImpl.BoolImpl.FALSE).isTrue()) {
            contracts.degraded.add(mi);
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
                graph.addEdge(getter.field(), mi);
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
                    if (nature == LinkNatureImpl.IS_SUBSET_OF) graph.addEdge(other, from);
                    else graph.addEdge(from, other);
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
                    if (trace.why(mi)) {
                        trace.log("marker", mi, "from " + from + " to " + link.to() + " statement "
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
                    if (slot && deepFace(link.from()) && !mi.equals(seeds.nullOwner(((LocalVariable) link.to()).assignmentExpression()))) {
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
            case ReturnVariable _ -> graph.addEdge(container, member);
            default -> {
                if (statement != null && assignsHere(statement, memberVar)) graph.addEdge(container, member);
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
        int[] count = graph.linkEdges.computeIfAbsent(List.of(from, to), _ -> new int[4]);
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
            site.element = statement;
            site.method = mi;
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
                    site.element = e;
                    site.method = mi;
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
            site.element = statement;
            site.method = mi;
            syntacticSeeds(mi, own, statement);
            if (statement instanceof ExplicitConstructorInvocation eci && eci.methodInfo() != null) {
                callSite(mi, own, statement, null, eci.methodInfo(), eci.analysis(), eci.parameterExpressions());
            }
            // from here on a read of a parameter the statement assigned is a read of the shadow
            shadowed.addAll(shadowedBySubBlocks);
            shadowed.addAll(parametersAssignedIn(statement, mi));
            site.element = null;
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
            seeds.seedCreated(mi, rs.expression(), mi);
            constructorCopies(mi, scope, mi, rs.expression());
        } else if (statement instanceof LocalVariableCreation lvc) {
            lvc.localVariableStream().forEach(lv -> {
                Object local = local(mi, scope, lv.simpleName());
                if (lv.assignmentExpression() instanceof NullConstant) {
                    seeds.seed(local, "initialized null in " + mi.fullyQualifiedName(), mi.typeInfo());
                }
                callResult(local, lv.assignmentExpression());
                seeds.seedCreated(local, lv.assignmentExpression(), mi);
                constructorCopies(mi, scope, local, lv.assignmentExpression());
            });
        } else if (statement.expression() instanceof Assignment a && a.variableTarget() != null) {
            Object target = assignmentTarget(mi, scope, a.variableTarget());
            if (a.value() instanceof NullConstant) seeds.seed(target, "assigned null in " + mi.fullyQualifiedName(), mi.typeInfo());
            callResult(target, a.value());
            seeds.seedCreated(target, a.value(), mi);
            constructorCopies(mi, scope, target, a.value());
        } else if (statement instanceof ForEachStatement fe && fe.initializer() != null) {
            // for (T x : array): the elements flow into x
            Expression iterated = NonNullFacts.unwrap(fe.expression());
            Object array = iterated instanceof VariableExpression ve ? node(mi, scope, ve.variable())
                    : iterated instanceof MethodCall mc && mc.methodInfo() != null && index.analysed.contains(mc.methodInfo())
                    ? mc.methodInfo() : null;
            // Kotlin: a library view ('for (Map.Entry<K, V> e : data.entrySet())') holds the receiver's data: the loop
            // variable's slots are the map's, so 'e.setValue(null)' writes it (jenkins DiskSpaceMonitorDescriptor.kt:38,
            // 2026-10-22). Gate NOVIEWLOOPS.
            if (array == null && policy.callResults() && !VIEW_LOOPS_OFF.get()) array = viewArgument(mi, scope, iterated);
            ParameterizedType type = array == null ? null : graph.typeOf(array);
            if (type != null && (type.arrays() > 0 || type.parameters().size() == 1)) {
                // over an array its elements, over an Iterable<T> its one type argument; the statement's own scope
                // declares the loop variable
                Object elements = type.arrays() > 0 ? new Content(array) : new Arg(array, 0);
                boolean view = !(iterated instanceof VariableExpression) && !(array instanceof MethodInfo);
                fe.initializer().localVariableStream().forEach(lv -> {
                    Object local = local(mi, scope, lv.simpleName());
                    graph.addEdge(elements, local);
                    if (view && !VIEW_LOOP_WRITES_OFF.get()) graph.viewLoopReads.add(List.of(elements, local));
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
                if (a.value() instanceof NullConstant) seeds.seed(target, "assigned null in " + mi.fullyQualifiedName(), mi.typeInfo());
                callResult(target, a.value());
                seeds.seedCreated(target, a.value(), mi);
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
        if (facts.nonNull(value, facts.before(statement))) graph.flows.add(List.of(source, target));
        else graph.addEdge(source, target);
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
        ParameterizedType targetType = graph.typeOf(target);
        if (!index.analysed.contains(constructor) || targetType == null || constructor.typeInfo().typeParameters().isEmpty()) {
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
            ParameterizedType type = graph.typeOf(argument);
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
            || index.analysed.contains(mc.methodInfo()) || mc.methodInfo().isStatic() || mc.concreteReturnType() == null) {
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
        ParameterizedType type = graph.typeOf(receiver);
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
                    graph.addEdge(source, new Arg(receiver, index));
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
            || mc.methodInfo().isStatic() || mc.object() == null || this.index.analysed.contains(mc.methodInfo())) return null;
        ParameterizedType rt = mc.methodInfo().returnType();
        if (rt.arrays() > 0 || j >= rt.parameters().size()) return null;
        ParameterizedType a = rt.parameters().get(j);
        if (!isTypeVariable(a) || a.typeParameter().isMethodTypeParameter()
            || a.typeParameter().getOwner().getLeft() != mc.methodInfo().typeInfo()) return null;
        Object receiver = argumentNode(mi, scope, mc.object());
        ParameterizedType receiverType = receiver == null ? null : graph.typeOf(receiver);
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
            ParameterizedType type = receiver == null ? null : graph.typeOf(receiver);
            int index = type == null ? -1 : slotIndex(type, callee.typeInfo(), rt.typeParameter().getIndex());
            return index < 0 ? null : new Arg(receiver, index);
        }
        if (this.index.analysed.contains(callee) && !rt.isVoid()) return callee;
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
            && index.analysed.contains(getter.methodInfo()) && !getter.methodInfo().isConstructor()
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
                if (policy.callResults()) graph.addEdge(formalSlot, slot);
                if (intoReceiver) {
                    if (consumes) {
                        if (policy.callResults()) graph.addEdge(receiverSlot, slot);
                    } else {
                        graph.addEdge(slot, receiverSlot);
                        if (policy.assertContentWrites() && !receivesOnly) {
                            graph.invariantTies.add(new Object[]{receiverSlot, slot, formalSlot});
                        }
                    }
                } else {
                    if (policy.callResults()) graph.addEdge(receiverSlot, slot);
                    if (policy.assertContentWrites() && !receivesOnly) {
                        graph.invariantTies.add(new Object[]{slot, receiverSlot, formalSlot});
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
        ParameterizedType receiverType = receiver == null ? null : graph.typeOf(receiver);
        if (receiverType == null) return;
        List<ParameterInfo> parameters = callee.parameters();
        for (int i = 0; i < arguments.size() && i < parameters.size(); i++) {
            ParameterizedType formal = parameters.get(i).parameterizedType();
            if (formal.arrays() > 0 || formal.parameters().isEmpty()) continue;
            Object argument = argumentNode(mi, scope, arguments.get(i));
            if (argument == null) continue;
            ParameterizedType type = graph.typeOf(argument);
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
            return !m.isConstructor() && !m.returnType().isVoid() && index.analysed.contains(m) ? List.of(m) : List.of();
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

    // a call's result used directly: a library method that may return null, or (Policy.contracts) an analysed one
    // annotated nullable, seeds. Neither leaves a null marker in the link summary. NOT a general edge from every
    // analysed callee's return: on guava that carried the flow-insensitivity of 'v = get(k); if (v == null) ...'
    // into the callers, +947 noise for -27 unsafe (2026-10-06); to be revisited with the use-site pass (M4).
    private void callResult(Object target, Expression value) {
        for (Expression alternative : alternatives(value)) callResultOf(target, alternative);
    }

    private void callResultOf(Object target, Expression value) {
        Expression unwrapped = value instanceof Cast c ? c.expression() : value;
        if (target == null || !(unwrapped instanceof MethodCall mc) || mc.methodInfo() == null) return;
        MethodInfo callee = mc.methodInfo();
        if (readScope != null) factorySlots(target, value, readScope.mi());
        if (policy.callResults() && readScope != null) {
            for (Expression source : passedThrough(mc)) {
                Object node = argumentNode(readScope.mi(), readScope.scope(), source);
                if (node != null) graph.flows.add(List.of(node, target)); // Kotlin infers one type: content slots tied
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
                    graph.addEdge(lambda.methodInfo(), target);
                    return;
                }
                Object node = argumentNode(readScope.mi(), readScope.scope(), source);
                if (node != null) graph.flows.add(List.of(new Arg(node, k), target));
                // Kotlin: from a '?' argument the result is the star projection's value, Any? ('Object v =
                // getUninterruptibly(future)' on a ListenableFuture<?>: guava AbstractFuture.getFutureValue then tests
                // 'v == null', AbstractFuture.kt:346, 2026-10-18). Gate NOSTARRESULTS.
                ParameterizedType st = node == null ? null : graph.typeOf(node);
                if (!STAR_RESULTS_OFF.get() && st != null && k < st.parameters().size()
                    && st.parameters().get(k).wildcard() != null) {
                    graph.addEdge(new Arg(node, k), target);
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
                    if (index.analysed.contains(arrayCall.methodInfo())) continue;
                    arrayElementsPassedThrough(arrayCall).forEach((source, k) -> {
                        Object node = argumentNode(readScope.mi(), readScope.scope(), source);
                        if (node == null) return;
                        Arg slot = new Arg(target, k);
                        graph.addEdge(new Content(node), slot);
                        // a local array of the method's own type variable: its null is the slot's, not an
                        // instantiation's. Not a parameter's array, whose elements are each caller's: through
                        // 'ImmutableSet.copyOf(E[])' and 'Iterators.forArray(T...)' the tests' nulls had made
                        // their results ImmutableSet<E?> and UnmodifiableIterator<T?> (guava 2026-10-17)
                        ParameterizedType type = graph.typeOf(node);
                        if (node instanceof Local && type != null && type.arrays() == 1
                            && isTypeVariable(type.copyWithOneFewerArrays())) {
                            typeVariableElementSlots.add(slot);
                        }
                    });
                }
            }
        }
        if (index.analysed.contains(callee) && !callee.isConstructor() && !callee.returnType().isVoid()) {
            ParameterizedType rt = callee.returnType();
            // the same object: its content slots are one (coupleContent); not where the callee's type arguments are
            // type variables, which each call instantiates anew. An array's elements are the callee's whatever its
            // component's type arguments ('Node<K, V>[] createTable(int n) { return new Node[n]; }': guava
            // HashBiMap.hashTableVToK, ImmutableMapEntry.createEntryArray), unless the component is the callee's own
            // type variable ('<T> T[] toArray(T[] a)'), which would tie the arrays of all its callers
            if (concreteArguments(rt) || rt.arrays() > 0 && !isMethodTypeVariable(rt.componentType())
                || policy.callResults() && readScope != null && sameInstantiation(mc, readScope.mi())) {
                graph.flows.add(List.of(callee, target));
            } else if (policy.callResults() && !GENERIC_RESULT_SLOTS_OFF.get() && rt.arrays() == 0) {
                // Kotlin: a slot whose argument is not a type variable has its own nullability whatever the
                // instantiation: 'Map<Descriptor<T>, T> toMap(…)' with a nullable key makes 'return toMap(properties)'
                // a Map<Descriptor<UserProperty>?, UserProperty> (jenkins User.kt:143, 2026-10-21). Gate
                // NOGENERICRESULTSLOTS.
                ParameterizedType tt = graph.typeOf(target);
                for (int j = 0; j < rt.parameters().size(); j++) {
                    ParameterizedType a = rt.parameters().get(j);
                    if (a.typeParameter() == null && a.wildcard() == null && tt != null && tt.arrays() == 0
                        && j < tt.parameters().size()) {
                        graph.addEdge(new Arg(callee, j), new Arg(target, j));
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
                if (GAP9_OFF.get() || callee.parameters().isEmpty()) graph.addEdge(callee, target);
                else graph.gatedSites.add(new GatedSite(callee, target, graph.callId(mc, callee)));
            }
            // a result typed with the callee's class type variables inside a generic type ('FastFixedSet<E>
            // spawnEmptySet()'): those positions are the receiver's slots (instantiatedSlots)
            if (readScope != null && !callee.isStatic() && rt.arrays() == 0 && !rt.parameters().isEmpty()
                && !concreteArguments(rt)) {
                Object receiver = receiverNode(readScope.mi(), readScope.scope(), mc);
                ParameterizedType receiverType = receiver == null ? null : graph.typeOf(receiver);
                if (receiverType != null) {
                    instantiatedSlots(target, graph.typeOf(target), callee, rt, receiver, receiverType, callee.typeInfo(),
                            false);
                }
            }
        }
        if (readScope != null) {
            // a stream's terminal operation: its elements are values of the result's slot
            streamTerminal(readScope.mi(), readScope.scope(), unwrapped)
                    .forEach((j, nodes) -> nodes.forEach(n -> graph.addEdge(n, new Arg(target, j))));
        }
        // Kotlin: 'collect(LinkedHashMap::new, (map, plugin) -> map.put(plugin, url), Map::putAll)' returns the
        // container its accumulator fills: the lambda's first parameter is the result, slots and all (jenkins
        // PluginManager.kt:1532, 2026-10-21). Gate NOCOLLECTCONTAINERS.
        if (policy.callResults() && !COLLECT_CONTAINERS_OFF.get() && isStreamType(callee) && "collect".equals(callee.name())
            && mc.parameterExpressions().size() == 3
            && NonNullFacts.unwrap(mc.parameterExpressions().get(1)) instanceof Lambda accumulator
            && accumulator.methodInfo() != null && !accumulator.methodInfo().parameters().isEmpty()) {
            graph.flows.add(List.of(accumulator.methodInfo().parameters().getFirst(), target));
        }
        if (readScope != null && argumentNode(readScope.mi(), readScope.scope(), unwrapped) instanceof Arg slot) {
            if (!index.analysed.contains(callee)) {
                graph.flows.add(List.of(slot, target)); // 'x = map.get(k)': x is the map's value, its slots are the value's
            }
            // Kotlin: the value read is the receiver's slot ('block = blocks.getWithKey(j)', 'st = stats.get(0)' on a
            // VBStyleCollection<Statement?, ...>): a nullable slot gives a nullable value
            if (policy.callResults()) graph.addEdge(slot, target);
        }
        // 'x = map.get(k)' with k known present (NonNullFacts, CodeLaser/maddi-mod#22 gap 1): not the absent key's null; what
        // the map holds for k still flows through its value slot above
        String lib = contracts.libraryNullableCall(mc);
        if (lib != null) {
            // not a member Kotlin itself types nullable ('Map.get(k): V?'): the local must then be declared so
            if (!(target instanceof Local && seedStatement != null && deadNullAssignments.contains(seedStatement)
                  && java.util.stream.Stream.concat(java.util.stream.Stream.of(callee), overrides(callee).stream())
                          .noneMatch(m -> Contracts.KOTLIN_COLLECTIONS.contains(m.typeInfo().fullyQualifiedName())))) {
                seeds.seedIndirect(target, "assigned " + lib);
            } else if (!GUARDED_LOCALS_OFF.get()) {
                guardedLocals.add(target);
            }
        } else if (policy.contracts() && index.analysed.contains(callee)
                   && contracts.explicitState(callee) == NullableState.NULLABLE) {
            seeds.seed(target, "assigned " + callee.fullyQualifiedName() + ", annotated nullable");
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
        Deque<Object> todo = new ArrayDeque<>(graph.successors.getOrDefault(pi, Set.of()));
        while (!todo.isEmpty() && seen.size() < 2000) {
            Object n = todo.removeFirst();
            if (!seen.add(n)) continue;
            if (n.equals(method)) return true;
            ParameterizedType type = graph.typeOf(n);
            if (type != null && isClassTypeVariable(type)) return true;
            if (n instanceof Local || n instanceof ParameterInfo p && p.methodInfo() == method) {
                todo.addAll(graph.successors.getOrDefault(n, Set.of()));
            }
        }
        return false;
    }

    private void inferredNull(Object target, CallTarget ct, MethodCall mc, int i) {
        Expression argument = NonNullFacts.unwrap(mc.parameterExpressions().get(i));
        if (argument instanceof NullConstant) {
            seeds.seed(target, Seeds.NULL_ARGUMENT + ct.mi().fullyQualifiedName(), ct.mi().typeInfo());
        } else if (argument instanceof VariableExpression ve) {
            if (facts.nonNullWhenCalled(mc, ve.variable())) return;
            Object node = node(ct.mi(), ct.scope(), ve.variable());
            if (node != null) graph.addEdge(node, target);
        } else if (argument instanceof MethodCall inner && inner.methodInfo() != null) {
            Object node = argumentNode(ct.mi(), ct.scope(), inner);
            if (node != null) graph.addEdge(node, target);
            String lib = contracts.libraryNullableCall(inner);
            if (lib != null) seeds.seedIndirect(target, "assigned " + lib);
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
        if (!index.analysed.contains(callee) || testCode(readScope.mi())) return;
        ParameterizedType rt = callee.returnType();
        if (rt.arrays() != 1 || !isMethodTypeVariable(rt)) return;
        List<ParameterInfo> parameters = callee.parameters();
        List<Expression> arguments = mc.parameterExpressions();
        for (int i = 0; i < parameters.size() && i < arguments.size(); i++) {
            ParameterizedType formal = parameters.get(i).parameterizedType();
            if (formal.arrays() != 1 || !rt.typeParameter().equals(formal.typeParameter())) continue;
            if (parameters.get(i).isVarArgs() && !passesTheArray(arguments, parameters, i)) continue;
            Object node = argumentNode(readScope.mi(), readScope.scope(), arguments.get(i));
            if (node != null) graph.addEdge(new Content(node), new Content(target));
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
        if (index.analysed.contains(callee)) return;
        ParameterizedType returnType = callee.returnType();
        ParameterizedType targetType = graph.typeOf(target);
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
                    seeds.seedCreated(slot, argument, mi); // the elements of an array stored as one ('new Integer[]{null}')
                    Object source = readScope == null ? null : argumentNode(readScope.mi(), readScope.scope(), argument);
                    if (source != null) graph.flows.add(List.of(source, slot)); // its content slots still are the slot's
                    continue;
                }
                if (NonNullFacts.unwrap(argument) instanceof NullConstant) {
                    seeds.seed(slot, "null passed to " + callee.fullyQualifiedName() + " in " + where(mi), writer(mi));
                    continue;
                }
                seeds.seedCreated(slot, argument, mi);
                Object source = readScope == null ? null : argumentNode(readScope.mi(), readScope.scope(), argument);
                if (source != null) {
                    // M4: a variable known non-null at the call carries no null into the slot ('textSegment == null
                    // ? List.of() : List.of(textSegment)', langchain4j ListeningEmbeddingModel.embed)
                    if (!(NonNullFacts.unwrap(argument) instanceof VariableExpression ve
                          && facts.nonNullAt(mc, ve.variable()))) {
                        graph.addEdge(source, slot);
                    }
                    graph.flows.add(List.of(source, slot)); // the same object: its content slots are the slot's
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

    // in Java only == and != take a null operand
    private void nullTest(MethodInfo mi, Scope scope, BinaryOperator bo) {
        // NOT excluded: 'if (p == null) throw ...'. Guava annotates such checking parameters @Nullable (the method's
        // job is to accept null and throw); treating the test as a non-null precondition cost 28 agreements, +3
        // unsafe (2026-10-06)
        Expression other = bo.lhs() instanceof NullConstant ? bo.rhs() : bo.rhs() instanceof NullConstant ? bo.lhs() : null;
        if (other instanceof VariableExpression ve) {
            Object node = node(mi, scope, ve.variable());
            if (node instanceof ParameterInfo || node instanceof FieldInfo) {
                seeds.seedIndirect(node, "compared with null in " + mi.fullyQualifiedName(), mi.typeInfo());
            }
        }
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
        if (index.keyedSlots.isEmpty()) return;
        KeyedAccessor putter = index.keyedPutters.get(callee);
        if (putter != null && arguments.size() > Math.max(putter.keyIndex(), putter.valueIndex())) {
            Arg slot = index.keyedSlots.get(putter.field());
            argument(mi, scope, statement, call, list, arguments, putter.valueIndex(),
                    keyed(slot, Index.keyOf(arguments.get(putter.keyIndex()))));
            return;
        }
        if (!(call instanceof MethodCall mc) || !"put".equals(callee.name()) || arguments.size() != 2
            || mc.object() == null || !(NonNullFacts.unwrap(mc.object()) instanceof VariableExpression ve)
            || !(ve.variable() instanceof FieldReference fr)) return;
        Arg slot = index.keyedSlots.get(fr.fieldInfo());
        if (slot == null) return;
        KeyedAccessor own = index.keyedPutters.get(mi);
        if (own != null && own.field() == fr.fieldInfo() && Index.ownParameter(mi, arguments.getFirst()) == own.keyIndex()) {
            return;
        }
        argument(mi, scope, statement, call, list, arguments, 1, keyed(slot, Index.keyOf(arguments.getFirst())));
    }

    // the keyed node a statement reads: 'x = ctx.get(KEY)' through a keyed getter, or 'x = field.get(KEY)'
    private Keyed keyedRead(Statement statement, Variable recipient) {
        if (index.keyedSlots.isEmpty()) return null;
        Expression value = assignedValue(statement, recipient);
        if (!(NonNullFacts.unwrap(value) instanceof MethodCall mc) || mc.methodInfo() == null) return null;
        KeyedAccessor getter = index.keyedGetters.get(mc.methodInfo());
        if (getter != null && getter.keyIndex() < mc.parameterExpressions().size()) {
            Object key = Index.keyOf(mc.parameterExpressions().get(getter.keyIndex()));
            return key == null ? null : keyed(index.keyedSlots.get(getter.field()), key);
        }
        if ("get".equals(mc.methodInfo().name()) && mc.parameterExpressions().size() == 1 && mc.object() != null
            && NonNullFacts.unwrap(mc.object()) instanceof VariableExpression ve
            && ve.variable() instanceof FieldReference fr && index.keyedSlots.containsKey(fr.fieldInfo())) {
            Object key = Index.keyOf(mc.parameterExpressions().getFirst());
            return key == null ? null : keyed(index.keyedSlots.get(fr.fieldInfo()), key);
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
                graph.addEdge(unknown, k);
                graph.addEdge(k, slot);
            }
        });
        if (policy.world() == World.CLOSED) return;
        index.keyedPutters.forEach((mi, putter) -> {
            if (externallyCallable(mi)) {
                graph.addEdge(mi.parameters().get(putter.valueIndex()), new Keyed(index.keyedSlots.get(putter.field()), null));
                for (Object key : keysUsed.getOrDefault(index.keyedSlots.get(putter.field()), Set.of())) {
                    if (key != NO_KEY) graph.addEdge(new Keyed(index.keyedSlots.get(putter.field()), null),
                            new Keyed(index.keyedSlots.get(putter.field()), key));
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
        MethodInfo constructor = index.anonymousSuperConstructor(cc);
        if (constructor == null) return;
        for (int i = 0; i < n; i++) {
            ParameterInfo pi = constructor.parameters().get(i);
            Expression argument = NonNullFacts.unwrap(arguments.get(i));
            if (argument instanceof NullConstant) {
                seeds.seed(pi, Seeds.NULL_ARGUMENT + mi.fullyQualifiedName(), mi.typeInfo());
            } else if (argument instanceof VariableExpression ve) {
                Object source = node(mi, scope, ve.variable());
                if (source == null) continue;
                if (facts.nonNullWhenCalled(cc, ve.variable()) || facts.nonNullAt(statement, ve.variable())) {
                    graph.flows.add(List.of(source, pi));
                } else {
                    graph.addEdge(source, pi);
                }
            }
        }
    }

    /*
     Kotlin: a double-brace initialization ('new HashMap<String, String>(8) {{ put(APPNAME, labels.get(APPNAME)); }}')
     writes the new object's slots through its implicit 'this': the anonymous class's 'this' is a node typed by its
     generic superclass, whose slots flow into what the creation is assigned to (nacos RequestMeta.java:103,
     ConnectionMeta.java:164, 2026-10-19: a HashMap<String, String> given nulls). Gate NOANONYMOUSTHIS.
     */
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
        for (int i = 0; i < anonymous.parentClass().parameters().size(); i++) graph.addEdge(new Arg(at, i), new Arg(target, i));
    }

    /*
     Kotlin: a library view of a generic receiver holds the receiver's values: 'namespaceSingletonMaps.keySet()' is a
     Set of the map's keys, 'header.entrySet().iterator()' an Iterator of its entries. The result's slots are the
     receiver's where the callee's declared result uses its class's type variables, lifted to the target's class
     ('KeySetView<K, V>' as a 'Set<K>'), nested too ('Entry<K, V>'); both ways, a view being the same data. A library
     call in between ('header.entrySet()') is a node of its own, typed by its inferred type (nacos
     ServiceManager.java:92, Header.java:85, 2026-10-19). Gate NOLIBRARYVIEWS.
     */
    private static final Switches.Gate LIBRARY_VIEWS_OFF = Switches.gate("NOLIBRARYVIEWS");

    private void libraryView(Object target, MethodCall mc, int depth) {
        if (LIBRARY_VIEWS_OFF.get() || readScope == null || depth > 3) return;
        MethodInfo callee = mc.methodInfo();
        ParameterizedType tt = graph.typeOf(target);
        if (callee == null || index.analysed.contains(callee) || callee.isStatic() || mc.object() == null || tt == null
            || tt.arrays() > 0 || tt.parameters().isEmpty() || tt.typeInfo() == null) return;
        ParameterizedType rt = callee.returnType();
        if (rt.arrays() > 0 || rt.parameters().isEmpty() || rt.typeInfo() == null) return;
        ParameterizedType lifted = rt.typeInfo() == tt.typeInfo() ? rt
                : rt.concreteSuperType(tt.typeInfo().asParameterizedType());
        if (lifted == null || lifted.parameters().size() != tt.parameters().size()) return;
        Expression object = NonNullFacts.unwrap(mc.object());
        Object receiver;
        if (object instanceof MethodCall inner && inner.methodInfo() != null && !index.analysed.contains(inner.methodInfo())
            && !inner.methodInfo().isStatic() && inner.concreteReturnType() != null) {
            receiver = new CallResult(inner);
            libraryView(receiver, inner, depth + 1);
        } else {
            receiver = receiverNode(readScope.mi(), readScope.scope(), mc);
        }
        ParameterizedType receiverType = receiver == null ? null : graph.typeOf(receiver);
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
            graph.addEdge(own, slot);
            graph.addEdge(slot, own);
            return;
        }
        for (int m = 0; m < formal.parameters().size(); m++) {
            viewSlot(new Arg(slot, m), formal.parameters().get(m), receiver, receiverType, calleeClass);
        }
    }

    // gate NOCONSUMERSLOTS: a caller's null into an abstract type-variable parameter reaches the implementations (A/B)
    private static final Switches.Gate CONSUMER_SLOTS_OFF = Switches.gate("NOCONSUMERSLOTS");
    private static final Switches.Gate METHOD_TYPE_VARIABLE_ARGUMENTS_OFF = Switches.gate("NOMETHODTYPEVARARGS");
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
        if (call instanceof MethodCall && this.index.analysed.contains(callee) && !callee.returnType().isVoid()) {
            int id = graph.callId(call, callee);
            graph.argumentsSeen.add(id);
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
                if (source != null) graph.varargsPasses.add(List.of(source, parameter));
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
                && (!this.index.analysed.contains(callee)
                    || (policy.callResults() && !CONSUMER_SLOTS_OFF.get()
                        || !callee.isAbstract() && !this.index.implementations.containsKey(callee))
                       && slotReceiver(mi, scope, call, callee) != null)) {
                continue;
            }
            if (projectedReceiver(call, callee, parameter)) continue;
            // Kotlin: an argument to the callee's own type variable is decided once the graph is built
            // (resolveTypeVariableArguments)
            if (policy.callResults() && !METHOD_TYPE_VARIABLE_ARGUMENTS_OFF.get() && call instanceof MethodCall
                && this.index.analysed.contains(callee) && isMethodTypeVariable(parameter.parameterizedType())
                && !parameter.isVarArgs() && contracts.explicitState(parameter) != NullableState.NULLABLE) {
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
        ParameterizedType type = graph.typeOf(receiver);
        List<ParameterInfo> parameters = callee.parameters();
        for (int i = 0; i < arguments.size() && i < parameters.size(); i++) {
            ParameterizedType pt = parameters.get(i).parameterizedType();
            if (pt.arrays() > 0 || pt.typeParameter() == null || pt.typeParameter().isMethodTypeParameter()) continue;
            // Kotlin: a parameter declared nullable takes the null itself ('convert(a: A?)'); the receiver's
            // instantiation stays. guava's tests' 'Ints.stringConverter().convert(null)' had made
            // IntConverter.INSTANCE a Converter<String?, Int>, which an IntConverter (a Converter<String, Int>) is
            // not; likewise 'Equivalence.equals().equivalentTo(null)' (Ints.kt:101, Equivalence.kt:98, 2026-10-18)
            if (policy.callResults() && !NULLABLE_PARAMETER_SLOTS_OFF.get()
                && contracts.explicitState(parameters.get(i)) == NullableState.NULLABLE) continue;
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
        return policy.callResults() && !FAMILY_CONSUMERS_OFF.get() && index.analysed.contains(callee)
               && (callee.isAbstract() || index.implementations.containsKey(callee));
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
                   && index.analysed.contains(getter.methodInfo()) && !getter.methodInfo().isConstructor()
                   && concreteArguments(getter.methodInfo().returnType())) {
            receiver = getter.methodInfo();
        } else if (object instanceof MethodCall lookup && !index.analysed.contains(callee)
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
        ParameterizedType type = receiver == null ? null : graph.typeOf(receiver);
        if (type == null || type.arrays() > 0 || callee.typeInfo().typeParameters().isEmpty()) return null;
        boolean anySlot = false;
        for (int i = 0; i < callee.typeInfo().typeParameters().size(); i++) {
            anySlot |= slotIndex(type, callee.typeInfo(), i) >= 0;
        }
        return anySlot ? receiver : null;
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
        for (MethodInfo implementation : this.index.implementations.getOrDefault(callee, List.of())) {
            ParameterizedType type = implementation.parameters().get(pi.index()).parameterizedType();
            if (type.typeInfo() == argumentType.typeInfo() && type.arrays() == argumentType.arrays()) {
                targets.add(implementation.parameters().get(pi.index()));
            }
        }
        return targets;
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
            seeds.seed(site, "null argument in " + mi.fullyQualifiedName());
            return;
        }
        if (unwrapped instanceof VariableExpression ve
            && (facts.nonNullWhenCalled(call, ve.variable()) || facts.nonNullAt(statement, ve.variable()))) {
            return;
        }
        String lib = contracts.nullableLibraryCall(argument);
        if (lib != null) {
            seeds.seedIndirect(site, "argument " + lib + " in " + mi.fullyQualifiedName());
            return;
        }
        if (unwrapped instanceof MethodCall amc && amc.methodInfo() != null && index.analysed.contains(amc.methodInfo())
            && !amc.methodInfo().isConstructor() && !amc.methodInfo().returnType().isVoid()) {
            graph.addEdge(amc.methodInfo(), site); // conservative: the nested call's result, ungated
        }
        if (list == null || i >= list.list().size()) {
            if (!(unwrapped instanceof MethodCall)) graph.addEdge(argumentNode(mi, scope, argument), site);
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
        if (node != null) graph.addEdge(node, site);
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
        if (pi instanceof ParameterInfo parameter && index.analysed.contains(parameter.methodInfo())) {
            formal = parameter.parameterizedType();
        } else if (!LAMBDA_SLOTS_OFF.get() && policy.callResults() && pi instanceof Arg slot) {
            // a receiver's slot: 'chain.add(input -> states.get(current))' on a List<Function<Provider<S>, S>> makes
            // it a List<Function<Provider<S>, S?>> (jenkins InstallUtil.kt:48/60, 2026-10-20). Gate NOLAMBDASLOTS.
            formal = graph.typeOf(slot);
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
            graph.addEdge(body, new Arg(pi, j));
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
            || !index.analysed.contains(method)) return;
        ParameterizedType formal;
        if (pi instanceof ParameterInfo parameter && index.analysed.contains(parameter.methodInfo())) {
            formal = parameter.parameterizedType();
        } else if (pi instanceof Arg slot) {
            formal = graph.typeOf(slot);
            if (formal == null) return;
        } else {
            return;
        }
        if (formal.arrays() > 0 || formal.typeInfo() == null || formal.parameters().isEmpty()) return;
        MethodInfo sam = formal.typeInfo().singleAbstractMethod();
        if (sam == null || !isClassTypeVariable(sam.returnType())) return;
        int j = formal.typeInfo().typeParameters().indexOf(sam.returnType().typeParameter());
        if (j >= 0 && j < formal.parameters().size() && formal.parameters().get(j).typeParameter() == null) {
            graph.addEdge(method, new Arg(pi, j));
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
            || this.index.analysed.contains(parameter.methodInfo()) || !(call instanceof MethodCall mc)) return;
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
        ParameterizedType receiverType = receiver == null ? null : graph.typeOf(receiver);
        if (receiverType == null || receiverType.arrays() > 0) return;
        int index = slotIndex(receiverType, callee.typeInfo(), tp.getIndex());
        if (index >= 0) graph.addEdge(lambda.methodInfo(), new Arg(receiver, index));
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
                seeds.seed(slot, "filtered of nulls in " + mi.fullyQualifiedName(), mi.typeInfo());
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
        if (pi instanceof ParameterInfo parameter && index.analysed.contains(parameter.methodInfo())) {
            formal = parameter.parameterizedType();
        } else if (!LAMBDA_PARAMETER_SLOTS_OFF.get() && pi instanceof Arg slot) {
            // a receiver's slot, as in lambdaResult: 'chain.add(next -> filter.getNextInstallState(current, next))'
            // on a List<Function<Provider<S?>, S?>> makes 'next' a Provider<S?>, which it passes on (jenkins
            // InstallUtil.kt:44, 2026-10-20). Gate NOLAMBDAPARAMETERSLOTS.
            formal = graph.typeOf(slot);
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
                graph.addEdge(new Arg(pi, j), lambdaParameter);
                // a generic argument ('Provider<S>'): the lambda's parameter is that object, its slots tied both ways
                if (pi instanceof Arg && !formal.parameters().get(j).parameters().isEmpty()) {
                    graph.flows.add(List.of(new Arg(pi, j), lambdaParameter));
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
        if (!(pi instanceof ParameterInfo parameter) || index.analysed.contains(parameter.methodInfo())
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
                    seeds.seed(lambdaParameters.get(samParameter.index()), "called back with null by "
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
        if (policy.callResults() && !REJECTED_FLOWS_OFF.get() && index.nullRejectingBodies.contains(mi.fullyQualifiedName())) {
            return;
        }
        // also a cast null: '(AiMessage) null' choosing between overloads (langchain4j OutputGuardrailResult)
        if (NonNullFacts.unwrap(arguments.get(i)) instanceof NullConstant) {
            seeds.seed(pi, "null argument in " + mi.fullyQualifiedName(), mi.typeInfo());
            return;
        }
        // M4: a variable argument known non-null at the call (or when its statement starts) carries no null
        if (NonNullFacts.unwrap(arguments.get(i)) instanceof VariableExpression ve
            && (call != null && facts.nonNullWhenCalled(call, ve.variable())
                || facts.nonNullAt(statement, ve.variable()))) {
            Object node = node(mi, scope, ve.variable());
            if (node != null) graph.flows.add(List.of(node, pi)); // an array known non-null: its elements still flow
            return;
        }
        seeds.seedCreated(pi, arguments.get(i), mi);
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
            && !index.analysed.contains(copy.constructor()) && copy.parameterizedType() != null
            && copy.parameterizedType().arrays() == 0) {
            contentCopies(mi, scope, pi, copy.constructor(), copy.parameterExpressions());
        }
        if (unwrappedArgument instanceof MethodCall amc && amc.methodInfo() != null) {
            Object source = argumentNode(mi, scope, unwrappedArgument);
            if (source instanceof Arg || source instanceof MethodInfo m && concreteArguments(m.returnType())) {
                graph.flows.add(List.of(source, pi)); // the same object: content slots tied (coupleContent)
            }
            // Kotlin: a library view ('main(args.subList(1, args.size()))') is the receiver's data: the parameter's
            // slots are the receiver's (jenkins CLIAction.kt:232, 2026-10-21). Gate NOVIEWMETHODARGUMENTS.
            if (source == null && policy.callResults() && !VIEW_METHOD_ARGUMENTS_OFF.get() && pi instanceof ParameterInfo) {
                Object view = viewArgument(mi, scope, unwrappedArgument);
                if (view != null) graph.flows.add(List.of(view, pi));
            }
            // Kotlin: a slot read passed on ('new Pair(cv.get(i), i)') gives the parameter the slot's value, as
            // callResult does for an assignment; the Java policies leave this to the links
            if (policy.callResults() && source instanceof Arg) graph.addEdge(source, pi);
            // a stream's terminal operation as the argument: its elements are values of the parameter's slot
            streamTerminal(mi, scope, unwrappedArgument)
                    .forEach((j, nodes) -> nodes.forEach(n -> graph.addEdge(n, new Arg(pi, j))));
        }
        String lib = contracts.nullableLibraryCall(arguments.get(i));
        if (lib != null) {
            seeds.seedIndirect(pi, "argument " + lib + " in " + mi.fullyQualifiedName(), mi.typeInfo());
            return;
        }
        if (list == null || i >= list.list().size()) {
            // no argument links ('super(attributes)', 'this(...)' carry none): each value the argument may take, its
            // own node; a null branch seeds ('this(r, text == null ? null : AiMessage.from(text), …)', langchain4j
            // OutputGuardrailResult, whose translated SUCCESS threw an NPE when this was dropped)
            for (Expression alternative : alternatives(arguments.get(i))) {
                Expression unwrappedAlternative = NonNullFacts.unwrap(alternative);
                if (unwrappedAlternative instanceof NullConstant) {
                    seeds.seed(pi, "null argument in " + mi.fullyQualifiedName(), mi.typeInfo());
                } else if (!(unwrappedAlternative instanceof MethodCall)) {
                    graph.addEdge(argumentNode(mi, scope, alternative), pi);
                    initializerElements(mi, scope, unwrappedAlternative, pi);
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
        if (node != null) graph.addEdge(node, pi);
        // an intermediate (or a local assigned in this very statement): its sources, in the argument's own links
        sourcesOf(mi, scope, primary, links, pi);
    }

    /*
     An array created in place as an argument without links ('this(new Function[] { rightMapping }, ..)'): its elements
     flow into the parameter's. Without the edge, timefold AbstractJoiner's rightMapping led nowhere, and #68's walk
     found it tolerant by absence (Joiners' rightMapping parameters, test-manager 2026-10-30). A null element is
     seedCreated's. Gate NOINITIALIZERARGUMENTS.
     */
    private void initializerElements(MethodInfo mi, Scope scope, Expression argument, Object pi) {
        if (INITIALIZER_ARGUMENTS_OFF.get()) return;
        ArrayInitializer initializer = argument instanceof ArrayInitializer ai ? ai
                : argument instanceof ConstructorCall cc ? cc.arrayInitializer() : null;
        if (initializer == null) return;
        for (Expression element : initializer.expressions()) {
            for (Expression alternative : alternatives(element)) {
                if (!(NonNullFacts.unwrap(alternative) instanceof NullConstant)) {
                    graph.addEdge(argumentNode(mi, scope, alternative), new Content(pi));
                }
            }
        }
    }

    private static final Switches.Gate INITIALIZER_ARGUMENTS_OFF = Switches.gate("NOINITIALIZERARGUMENTS");

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
        if (!index.analysed.contains(callee) || callee.isConstructor() || callee.returnType().isVoid()) return;
        ParameterizedType rt = callee.returnType();
        boolean ownNull = rt.arrays() == 0 && rt.typeParameter() != null
                          && (!rt.typeParameter().isMethodTypeParameter()
                              || callee.parameters().stream().noneMatch(p ->
                                     rt.typeParameter().equals(p.parameterizedType().typeParameter())));
        if (!isTypeVariable(rt) || ownNull) graph.addEdge(callee, pi);
    }

    private void sourcesOf(MethodInfo mi, Scope scope, Variable primary, Links links, Object pi) {
        for (Link link : links) {
            if (!link.from().equals(primary)) continue;
            if (!(link.linkNature().isIdenticalTo() || link.linkNature().isAssignedFrom())) continue;
            if (isNullMarker(link.to())) {
                nullMarker(mi, link.to(), pi,
                        "null argument (via " + primary.simpleName() + ") in " + mi.fullyQualifiedName());
            } else {
                graph.addEdge(node(mi, scope, link.to()), pi);
            }
        }
    }

    // Java's default value: a non-final reference field without an initializer, which some constructor leaves alone
    private void seedDefaultValue(FieldInfo fi) {
        if (fi.type().isPrimitiveExcludingVoid() && fi.type().arrays() == 0) return;
        Expression initializer = fi.initializer();
        if (initializer != null) {
            seeds.seedCreated(fi, initializer, null);
            factorySlots(fi, initializer, null);
            initializerContent(fi, initializer);
        }
        if (initializer instanceof NullConstant) {
            seeds.seed(fi, "initializer null", fi.owner());
            return;
        }
        if (fi.isFinal() || initializer != null && !initializer.isEmpty()) return;
        List<MethodInfo> constructors = fi.owner().constructors();
        if (constructors.isEmpty()) {
            seeds.seedIndirect(fi, "default value: no constructor assigns it", fi.owner());
            return;
        }
        for (MethodInfo constructor : constructors) {
            if (!assigns(constructor, fi)) {
                seeds.seedIndirect(fi, "default value: not assigned in " + constructor.fullyQualifiedName(), fi.owner());
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
        } else if (e instanceof MethodCall mc && mc.methodInfo() != null && index.analysed.contains(mc.methodInfo())
                   && !mc.methodInfo().returnType().isVoid()) {
            source = mc.methodInfo();
        }
        if (source != null && source != fi) graph.flows.add(List.of(source, fi));
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

