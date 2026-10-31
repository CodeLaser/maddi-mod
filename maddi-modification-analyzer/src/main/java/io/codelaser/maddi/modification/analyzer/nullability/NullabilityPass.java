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
    private Set<Object> reached = Set.of();
    private final Set<Local> asserted = new HashSet<>();
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
    private final Nodes nodes;
    // the parts that read the walk's facts, built once those are (once)
    private CallResults callResults;
    private Preconditions preconditionChecks;
    private MethodWalker walker;
    // the narrowed reads: this round's, and the next round's from what null reaches in it
    private final ReadNarrowing narrowing;
    private static final int CULPRIT_STEPS = 12;
    private NonNullFacts facts;
    private NullPredicates predicates;
    private java.util.function.BiPredicate<Object, TypeInfo> narrowedReads;

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
        this.graph = new NullGraph(policy, trace, site, index, typeUses, seeds, contracts);
        this.nodes = new Nodes(policy, index, graph);
        this.narrowing = new ReadNarrowing(policy, index, contracts, narrowed);
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
            Set<ParameterInfo> nextPreconditions = pass.preconditionChecks.preconditions();
            NarrowedReads nextNarrowed = pass.narrowing.next(pass.reached, pass.external, pass.facts);
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
                .withNarrowedInMethod(narrowedReads == null ? null : narrowing::narrowedInMethod)
                .withFieldsAtEntry(fieldsAtEntry)
                .withSuperCalls(policy.callResults() && !Contracts.SUPER_CALLS_OFF.get() ? contracts::ownReturnsNonNull : null);
        contracts.facts = facts;
        callResults = new CallResults(policy, index, seeds, contracts, graph, nodes, facts);
        preconditionChecks = new Preconditions(policy, index, nodes, facts, preconditions);
        walker = new MethodWalker(policy, trace, site, index, seeds, contracts, graph, nodes, callResults,
                preconditionChecks, facts, preconditions);
        OutsideCallers outsideCallers = new OutsideCallers(policy, index, contracts, graph, facts);
        Tolerance tolerance = new Tolerance(trace, index, seeds, contracts, graph, outsideCallers, facts);
        GraphCompletion completion = new GraphCompletion(policy, index, seeds, contracts, graph, nodes, callResults,
                outsideCallers);
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
        if (policy.callResults() && !CallResults.DEAD_NULLS_OFF.get()) {
            for (MethodInfo mi : methods) if (mi.methodBody() != null) callResults.deadNullAssignments(mi.methodBody());
        }
        for (MethodInfo mi : methods) {
            nodes.owner = mi;
            walker.buildForMethod(mi);
        }
        completion.keyedEdges(methods);
        for (FieldInfo fi : fields) completion.seedDefaultValue(fi);
        graph.linkEdges.forEach((edge, count) -> {
            // [0] assigned (or passed) where the source is known non-null, [1] where it is not, [2] assigned by a
            // value not seen through. Never observed at all: only the links' transitive closure. In
            // 'imp.getNestedName(root ? node.classStruct.qualifiedName : node.simpleName)' both alternatives are '≡'
            // the argument, and so each other: fernflower's ClassNode.simpleName (compared with null) reached
            // StructClass.qualifiedName and from there most of the program. A slot is written by calls, without
            // an assignment the statement shows: kept.
            // [3] a link of a method's summary (no statement): kept as before, unless guarded somewhere
            boolean slot = edge.get(1) instanceof Arg || edge.get(1) instanceof Content;
            if (policy.callResults() && !NullGraph.DIFFERENT_SHAPES_OFF.get() && graph.differentShapes(edge.get(0), edge.get(1))) return;
            if (graph.liftedIntoNarrower(edge.get(0), edge.get(1))) return;
            if (count[1] > 0 || count[2] > 0 || count[0] == 0 && slot) {
                graph.addEdge(edge.get(0), edge.get(1));
            } else {
                graph.flows.add(List.of(edge.get(0), edge.get(1))); // the value is not null there; its elements may be
            }
        });
        completion.holderFields(methods, fields);
        completion.fieldInitializerNullArguments(fields);
        completion.assignedParameterSlots(methods);
        completion.enumValueOfContracts(methods);
        completion.nullFilteredParameters(methods);
        callResults.resolveTypeVariableArguments();
        graph.coupleContent();
        if (!graph.invariantTies.isEmpty()) {
            // a class type variable's slot is reached only by the class's own nulls (coupleContent never ties it
            // to an instantiation's slot), so that is known before the invariance edges are added
            Set<Object> own = graph.closure(new HashMap<>());
            boolean added = false;
            for (Object[] tie : graph.invariantTies) {
                if (!own.contains(tie[2])) {
                    graph.addEdge(tie[0], tie[1]);
                    added = true;
                }
            }
            if (added) graph.coupleContent(); // the nested slots of the new flows
        }

        contracts.libraryParameterContracts(graph.successors);
        if (!Tolerance.TOLERANT_OFF.get()) tolerance.tolerantParameters(methods);
        Map<Object, Object> cause = new LinkedHashMap<>();
        reached = graph.closure(cause);
        List<NullArgument> nullArguments = preconditionChecks.nullArguments(reached);
        Set<ParameterInfo> preconditionsNonNull = Set.of();
        if (policy.preconditionsNonNull()) {
            Set<ParameterInfo> declared = preconditionChecks.declaredPreconditions();
            Set<Object> reachedBefore = reached;
            // the printers report only these: elsewhere the parameter is non-null without the policy
            preconditionsNonNull = declared.stream().filter(reachedBefore::contains)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            if (!declared.isEmpty()) {
                contracts.addAll(declared);
                cause.clear();
                reached = graph.closure(cause);
            }
        }
        if (policy.assertAtDeclaration()) {
            // only where null arrives: elsewhere the assertion would be a '!!' on a non-null value
            walker.assertCandidates.forEach(local -> {
                if (reached.contains(local)) asserted.add(local);
            });
            if (!asserted.isEmpty()) {
                contracts.addAll(asserted);
                cause.clear();
                reached = graph.closure(cause);
            }
        }
        if (policy.world() != World.CLOSED) external = outsideCallers.externalClosure(methods, reached);
        if (policy.callResults()) reached = Verdicts.storedOnlyIntoNullableFields(index, methods, reached, cause);
        Verdicts verdictRules = new Verdicts(policy, index, contracts, reached, external);

        Map<Info, ParameterizedType> verdicts = new LinkedHashMap<>();
        Map<Local, ParameterizedType> locals = new LinkedHashMap<>();
        // an unreached local of a degraded method: its links are missing, so "no null reaches it" is not known
        graph.declaredLocals.forEach((local, lv) -> {
            ParameterizedType v = verdictRules.verdict(lv.parameterizedType(), local, contracts.degraded.contains(graph.localOwner.get(local)));
            locals.put(local, callResults.guardedLocals.contains(local) && !(v.isPrimitiveExcludingVoid() && v.arrays() == 0)
                    ? v.withNullable(NullableState.NULLABLE) : v);
        });
        graph.shadows.forEach((local, pi) -> locals.put(local, verdictRules.verdict(pi.parameterizedType(), local,
                contracts.degraded.contains(pi.methodInfo()))));
        for (FieldInfo fi : fields) {
            verdicts.put(fi, contracts.contracted(fi, verdictRules.verdict(fi.type(), fi, false)));
            if (trace.why(fi)) {
                trace.print("NULLWHY final " + fi.fullyQualifiedName() + " reached=" + reached.contains(fi)
                                   + trace.chain(cause, seeds.seedOrigin, fi) + " elements reached=" + reached.contains(new Content(fi))
                                   + trace.chain(cause, seeds.seedOrigin, new Content(fi)) + " verdict=" + verdicts.get(fi));
            }
        }
        for (MethodInfo mi : methods) {
            boolean deg = contracts.degraded.contains(mi);
            for (ParameterInfo pi : mi.parameters()) {
                verdicts.put(pi, contracts.contracted(pi, verdictRules.verdict(pi.parameterizedType(), pi, false)));
            }
            if (!mi.isConstructor() && !mi.returnType().isVoid()) {
                verdicts.put(mi, contracts.contracted(mi, verdictRules.verdict(mi.returnType(), mi, deg)));
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
                && !graph.callResultNullable(callee, id, finalReached, finalReached::contains, intrinsic)) {
                nonNullCallResults.add(call);
            }
        });
        // gap 7: the facts handed out know what the closure decided; the walk that built the graph could not
        NonNullFacts useSites = new NonNullFacts(contracts::parameterContract, contracts::returnContract)
                .withPredicates(predicates)
                .withNarrowedReads(narrowedReads)
                .withNarrowedInMethod(narrowedReads == null ? null : narrowing::narrowedInMethod)
                .withNotNullable(e -> verdictRules.notNullable(e, nonNullCallResults))
                .withFieldsAtEntry(fieldsAtEntry);
        methods.forEach(useSites::walk);
        nextFieldsAtEntry = fieldsAtEntry(useSites, methods, fields);
        if (!ROUND_KEYS_OFF.get()) {
            Set<Expression> present = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
            for (MethodCall lookup : contracts.lookups) if (useSites.keyPresentWhenCalled(lookup)) present.add(lookup);
            nextPresentLookups = present;
        }
        return new Report(verdicts, locals, cause, seeds.seedOrigin, useSites, smartCasts, Set.copyOf(asserted),
                Set.copyOf(walker.unobservedCandidates), nonNullCallResults, preconditions, nullArguments, preconditionsNonNull,
                preconditionChecks.rejectsNull(), graph.writtenReach, Set.copyOf(typeUses.nonNullTypeParameters), verdictRules.parentClassVerdicts(methods));
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
}

