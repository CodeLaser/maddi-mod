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
        callResults = new CallResults(policy, index, seeds, contracts, graph, nodes, facts);
        preconditionChecks = new Preconditions(policy, index, nodes, facts, preconditions);
        walker = new MethodWalker(policy, trace, site, index, seeds, contracts, graph, nodes, callResults,
                preconditionChecks, facts, preconditions);
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
            if (policy.callResults() && !NullGraph.DIFFERENT_SHAPES_OFF.get() && graph.differentShapes(edge.get(0), edge.get(1))) return;
            if (graph.liftedIntoNarrower(edge.get(0), edge.get(1))) return;
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
        if (!TOLERANT_OFF.get()) tolerantParameters(methods);
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
        if (policy.world() != World.CLOSED) external = externalClosure(methods);
        if (policy.callResults()) reached = storedOnlyIntoNullableFields(methods, reached, cause);

        Map<Info, ParameterizedType> verdicts = new LinkedHashMap<>();
        Map<Local, ParameterizedType> locals = new LinkedHashMap<>();
        // an unreached local of a degraded method: its links are missing, so "no null reaches it" is not known
        graph.declaredLocals.forEach((local, lv) -> {
            ParameterizedType v = verdict(lv.parameterizedType(), local, contracts.degraded.contains(graph.localOwner.get(local)));
            locals.put(local, callResults.guardedLocals.contains(local) && !(v.isPrimitiveExcludingVoid() && v.arrays() == 0)
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
                && !graph.callResultNullable(callee, id, finalReached, finalReached::contains, intrinsic)) {
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
                Set.copyOf(walker.unobservedCandidates), nonNullCallResults, preconditions, nullArguments, preconditionsNonNull,
                preconditionChecks.rejectsNull(), graph.writtenReach, Set.copyOf(typeUses.nonNullTypeParameters), parentClassVerdicts(methods));
    }

    /*
     Kotlin: a class whose own code writes null into its generic superclass's slots ('class OwnerContext extends
     LinkedList<String>' calling 'addFirst(owner)' with a nullable owner; DiskSpaceMonitorDescriptor's
     'e.setValue(get(c))' into the Map<Computer, T> of its AbstractAsyncNodeMonitorDescriptor<DiskSpace>) extends a
     LinkedList<String?>, an AbstractAsyncNodeMonitorDescriptor<DiskSpace?> (jenkins RobustReflectionConverter.kt:449,
     DiskSpaceMonitorDescriptor.kt:38, 2026-10-22). Only the classes with such a slot.
     */
    private Map<TypeInfo, ParameterizedType> parentClassVerdicts(List<MethodInfo> methods) {
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
        Set<Object> before = graph.closure(new HashMap<>());
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
                    && ve.variable() instanceof ParameterInfo pi && !nodes.reassigned.contains(pi)
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
            Set<Object> reachedFromOutside = graph.closure(new HashMap<>(), outside, null);
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
     After all writes are seen: the values under a key not known reach every key; every key's values are the field's
     values too (and their own slots are tied to the field's, coupleContent). In an open world an outside caller of a
     keyed putter writes under any key.
     */
    private void keyedEdges(List<MethodInfo> methods) {
        graph.keysUsed.forEach((slot, keys) -> {
            Keyed unknown = new Keyed(slot, null);
            for (Object key : keys) {
                if (key == NullGraph.NO_KEY) continue;
                Keyed k = new Keyed(slot, key);
                graph.addEdge(unknown, k);
                graph.addEdge(k, slot);
            }
        });
        if (policy.world() == World.CLOSED) return;
        index.keyedPutters.forEach((mi, putter) -> {
            if (externallyCallable(mi)) {
                graph.addEdge(mi.parameters().get(putter.valueIndex()), new Keyed(index.keyedSlots.get(putter.field()), null));
                for (Object key : graph.keysUsed.getOrDefault(index.keyedSlots.get(putter.field()), Set.of())) {
                    if (key != NullGraph.NO_KEY) graph.addEdge(new Keyed(index.keyedSlots.get(putter.field()), null),
                            new Keyed(index.keyedSlots.get(putter.field()), key));
                }
            }
        });
    }

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
                || !callResults.nullFilter(mc.parameterExpressions().get(1))
                || !(NonNullFacts.unwrap(mc.parameterExpressions().getFirst()) instanceof VariableExpression ve)
                || !(ve.variable() instanceof ParameterInfo pi) || !mi.equals(pi.methodInfo())) continue;
            ParameterizedType type = pi.parameterizedType();
            // only a covariant container ('Iterator<out T>', 'Iterable<out T>'): every caller's argument fits T?
            String container = type.typeInfo() == null ? "" : type.typeInfo().fullyQualifiedName();
            if (type.arrays() == 0 && type.parameters().size() == 1 && isTypeVariable(type.parameters().getFirst())
                && ("java.util.Iterator".equals(container) || "java.lang.Iterable".equals(container))) {
                Arg slot = new Arg(pi, 0);
                graph.typeVariableElementSlots.add(slot); // a T? slot, not held at T (boundedSlot)
                seeds.seed(slot, "filtered of nulls in " + mi.fullyQualifiedName(), mi.typeInfo());
            }
        }
    }

    private static final Switches.Gate NULL_FILTERED_PARAMETERS_OFF = Switches.gate("NONULLFILTEREDPARAMETERS");

    // Java's default value: a non-final reference field without an initializer, which some constructor leaves alone
    private void seedDefaultValue(FieldInfo fi) {
        if (fi.type().isPrimitiveExcludingVoid() && fi.type().arrays() == 0) return;
        Expression initializer = fi.initializer();
        if (initializer != null) {
            seeds.seedCreated(fi, initializer, null);
            callResults.factorySlots(fi, initializer, null);
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

