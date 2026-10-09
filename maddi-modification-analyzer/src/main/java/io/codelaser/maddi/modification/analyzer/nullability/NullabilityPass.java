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

import io.codelaser.maddi.cst.api.analysis.Property;
import io.codelaser.maddi.cst.api.element.Element;
import io.codelaser.maddi.cst.api.expression.And;
import io.codelaser.maddi.cst.api.expression.ArrayInitializer;
import io.codelaser.maddi.cst.api.expression.Assignment;
import io.codelaser.maddi.cst.api.expression.BinaryOperator;
import io.codelaser.maddi.cst.api.expression.Cast;
import io.codelaser.maddi.cst.api.expression.ConstantExpression;
import io.codelaser.maddi.cst.api.expression.ConstructorCall;
import io.codelaser.maddi.cst.api.expression.Expression;
import io.codelaser.maddi.cst.api.expression.InlineConditional;
import io.codelaser.maddi.cst.api.expression.Lambda;
import io.codelaser.maddi.cst.api.expression.MethodCall;
import io.codelaser.maddi.cst.api.expression.MethodReference;
import io.codelaser.maddi.cst.api.expression.NullConstant;
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
import io.codelaser.maddi.cst.api.type.NullableState;
import io.codelaser.maddi.cst.api.type.ParameterizedType;
import io.codelaser.maddi.cst.api.variable.DependentVariable;
import io.codelaser.maddi.cst.api.variable.FieldReference;
import io.codelaser.maddi.cst.api.variable.LocalVariable;
import io.codelaser.maddi.cst.api.variable.Variable;
import io.codelaser.maddi.cst.impl.analysis.NullAnnotations;
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
     */
    public record Policy(NullableState unreached, boolean nullTests, boolean contracts, World world,
                         boolean assertContentWrites, boolean callResults, boolean assertAtDeclaration,
                         boolean libraryCallbacks) {
        public static final Policy NULL_MARKED = new Policy(NullableState.NONNULL, true, true, World.CLOSED, false,
                false, false, false);
        public static final Policy NULL_MARKED_FLOW_ONLY = new Policy(NullableState.NONNULL, false, true,
                World.CLOSED, false, false, false, false);
        public static final Policy CAUTIOUS = new Policy(NullableState.UNSPECIFIED, true, true, World.CLOSED, false,
                false, false, false);
        /**
         * {@link #NULL_MARKED} for the Java→Kotlin translation: content writes are asserted, and a variable assigned an
         * analysed method's nullable result is nullable (Kotlin will not compile it otherwise), and a parameter of an
         * override of a library method is nullable unless the library declares it non-null (the library calls it back
         * with what it likes; Kotlin checks a non-null parameter on entry).
         */
        public static final Policy KOTLIN = new Policy(NullableState.NONNULL, true, true, World.CLOSED, true, true,
                true, true);

        public Policy withoutContracts() {
            return new Policy(unreached, nullTests, false, world, assertContentWrites, callResults, assertAtDeclaration,
                    libraryCallbacks);
        }

        public Policy withWorld(World world) {
            return new Policy(unreached, nullTests, contracts, world, assertContentWrites, callResults, assertAtDeclaration,
                    libraryCallbacks);
        }

        public Policy withAssertContentWrites(boolean assertContentWrites) {
            return new Policy(unreached, nullTests, contracts, world, assertContentWrites, callResults, assertAtDeclaration,
                    libraryCallbacks);
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
     * @param verdicts fields, parameters, and methods (their return value)
     * @param locals   every declared local variable, by its declaration
     * @param useSites per statement, the locals and parameters known non-null when it starts (M4)
     * @param smartCasts the same, restricted to what Kotlin's smart cast derives: for a printer that drops
     *                   {@code !!} where Kotlin will see the variable as non-null
     */
    public record Report(Map<Info, ParameterizedType> verdicts, Map<Local, ParameterizedType> locals,
                         Map<Object, Object> cause, Map<Object, String> seedOrigin, NonNullFacts useSites,
                         NonNullFacts smartCasts, Set<Local> asserted, Set<Local> unobserved) {

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
    private static final boolean FIELD_FILLS_OFF = System.getenv("NOFIELDFILL") != null;
    // analysed methods whose return an earlier round found unreached by null (see go)
    private final Set<MethodInfo> trustedReturns;
    // analysed parameters an earlier round found non-null on every normal exit of their method: passing null throws
    private final Set<ParameterInfo> preconditions;
    private Set<Object> reached = Set.of();
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
    // evidence only (seedLibrary): Policy.assertContentWrites
    private final Set<Object> strictSeeds = new HashSet<>();
    private final Map<Object, Set<Object>> successors = new LinkedHashMap<>();
    private final Map<Object, String> seedOrigin = new LinkedHashMap<>();
    private final Set<MethodInfo> degraded = new LinkedHashSet<>();
    // the methods of this analysis: their own NULLABILITY_* may be this pass's output of an earlier run, so only the
    // others' (the library's, from the hints) are read as contracts
    private final Set<MethodInfo> analysed = new HashSet<>();
    // source declarations annotated non-null (Policy.contracts): null stops there
    private final Set<Object> nonNullContracts = new HashSet<>();
    // the analysed method a local belongs to (a lambda's local: the method the lambda is in)
    private final Map<Local, MethodInfo> localOwner = new HashMap<>();
    private MethodInfo owner;
    private NonNullFacts facts;
    // link-derived edges, decided after every statement was seen: dropped when the source is known non-null at every
    // statement that assigns it directly to the recipient (M4)
    private final Map<List<Object>, int[]> linkEdges = new LinkedHashMap<>();
    private final Map<Local, LocalVariable> declaredLocals = new LinkedHashMap<>();
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
        this(policy, Set.of(), Set.of());
    }

    private NullabilityPass(Policy policy, Set<MethodInfo> trustedReturns, Set<ParameterInfo> preconditions) {
        this.policy = policy;
        this.trustedReturns = trustedReturns;
        this.preconditions = preconditions;
    }

    private static final int MAX_ROUNDS = 5;

    /**
     * Runs the pass in rounds. Round 1 trusts no analysed method's return. Each later round takes the analysed
     * methods whose return the previous round found unreached by null (sound: that round's reachability
     * over-approximates) as known non-null values: {@code field = create()} then carries no null. Dropping edges
     * only shrinks what null reaches, so the trusted set grows until it is stable. Likewise the parameters an earlier
     * round found to be preconditions ({@link NonNullFacts#nonNullAtExit}): an argument passed to one is non-null
     * after the call, which may make the caller's own parameter a precondition.
     */
    public Report go(List<Info> analysisOrder) {
        Set<MethodInfo> trusted = Set.of();
        Set<ParameterInfo> nonNullAtExit = Set.of();
        for (int round = 1; ; round++) {
            NullabilityPass pass = new NullabilityPass(policy, trusted, nonNullAtExit);
            Report report = pass.once(analysisOrder);
            Set<MethodInfo> next = pass.unreachedReturns();
            Set<ParameterInfo> nextPreconditions = pass.preconditions();
            if (next.equals(trusted) && nextPreconditions.equals(nonNullAtExit) || round == MAX_ROUNDS) return report;
            trusted = next;
            nonNullAtExit = nextPreconditions;
        }
    }

    private Set<ParameterInfo> preconditions() {
        Set<ParameterInfo> set = new HashSet<>();
        for (MethodInfo mi : analysed) {
            // a call runs this body only when no override replaces it: an analysed one; in an open world any
            boolean overridable = !mi.isConstructor() && !mi.isStatic() && !mi.access().isPrivate()
                                  && !mi.isFinal() && !mi.typeInfo().isFinal();
            if (overridable && (policy.world() != World.CLOSED || implementations.containsKey(mi))) continue;
            for (ParameterInfo pi : mi.parameters()) {
                if (facts.nonNullAtExit(mi, pi)) set.add(pi);
            }
        }
        return Set.copyOf(set);
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
        methods.forEach(this::indexNullLiterals);
        for (MethodInfo mi : methods) if (mi.methodBody() != null) indexFills(mi.methodBody());
        for (MethodInfo mi : methods) {
            for (MethodInfo overridden : overrides(mi)) {
                implementations.computeIfAbsent(overridden, _ -> new java.util.ArrayList<>()).add(mi);
            }
        }
        facts = new NonNullFacts(this::parameterContract, this::returnContract)
                .withPredicates(NullPredicates.infer(methods));
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
        for (MethodInfo mi : methods) {
            owner = mi;
            buildForMethod(mi);
        }
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
            if (count[1] > 0 || count[2] > 0 || count[0] == 0 && slot) {
                addEdge(edge.get(0), edge.get(1));
            } else {
                flows.add(List.of(edge.get(0), edge.get(1))); // the value is not null there; its elements may be
            }
        });
        holderFields(methods, fields);
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

        Map<Object, Object> cause = new LinkedHashMap<>();
        reached = closure(cause);
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

        Map<Info, ParameterizedType> verdicts = new LinkedHashMap<>();
        Map<Local, ParameterizedType> locals = new LinkedHashMap<>();
        // an unreached local of a degraded method: its links are missing, so "no null reaches it" is not known
        declaredLocals.forEach((local, lv) -> locals.put(local, verdict(lv.parameterizedType(), local,
                degraded.contains(localOwner.get(local)))));
        shadows.forEach((local, pi) -> locals.put(local, verdict(pi.parameterizedType(), local,
                degraded.contains(pi.methodInfo()))));
        for (FieldInfo fi : fields) {
            verdicts.put(fi, contracted(fi, verdict(fi.type(), fi, false)));
        }
        for (MethodInfo mi : methods) {
            boolean deg = degraded.contains(mi);
            for (ParameterInfo pi : mi.parameters()) {
                verdicts.put(pi, contracted(pi, verdict(pi.parameterizedType(), pi, false)));
            }
            if (!mi.isConstructor() && !mi.returnType().isVoid()) {
                verdicts.put(mi, contracted(mi, verdict(mi.returnType(), mi, deg)));
            }
        }
        NonNullFacts smartCasts = facts.kotlinSmartCasts();
        methods.forEach(smartCasts::walk);
        return new Report(verdicts, locals, cause, seedOrigin, facts, smartCasts, Set.copyOf(asserted),
                Set.copyOf(unobservedCandidates));
    }

    /**
     * Writes the declaration verdicts as the B2 properties ({@code NULLABILITY_FIELD}, {@code _PARAMETER},
     * {@code _METHOD}), which the annotation decorator and the printers read. Locals have no property: they are
     * not {@link Info}s; a printer asks the {@link Report}. A declaration that carries a null annotation keeps the
     * value that annotation gave it: a contract wins over inference (M2).
     */
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

    // a source declaration's null annotation, under Policy.contracts: nullable seeds, non-null stops null
    private void contract(Info info, Object node) {
        NullableState state = NullAnnotations.explicitState(info);
        if (state == NullableState.NULLABLE) seed(node, "annotated nullable");
        else if (state == NullableState.NONNULL) nonNullContracts.add(node);
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
        NullableState state = NullAnnotations.explicitState(info);
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
        return policy.contracts() ? NullAnnotations.explicitState(pi) : null;
    }

    private NullableState returnContract(MethodInfo mi) {
        if (!analysed.contains(mi)) return stateOf(mi, PropertyImpl.NULLABILITY_METHOD);
        if (trustedReturns.contains(mi)) return NullableState.NONNULL;
        return policy.contracts() ? NullAnnotations.explicitState(mi) : null;
    }

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
        return java.util.stream.Stream.concat(java.util.stream.Stream.of(mi), overrides(mi).stream())
                .filter(m -> !analysed.contains(m))
                .filter(m -> stateOf(m, PropertyImpl.NULLABILITY_METHOD) == NullableState.NULLABLE)
                .map(MethodInfo::fullyQualifiedName).findFirst().orElse(null);
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
        NullableState explicit = NullAnnotations.explicitState(pi);
        if (explicit != null) return explicit;
        if (!isTypeVariable(pi.parameterizedType()) && NullAnnotations.inNullMarkedScope(pi.methodInfo())) {
            return NullableState.NONNULL;
        }
        return NullableState.UNSPECIFIED;
    }

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
        flows.add(List.of(from, to));
        successors.computeIfAbsent(from, _ -> new LinkedHashSet<>()).add(to);
    }

    private void seed(Object node, String origin) {
        if (node != null) {
            seedOrigin.putIfAbsent(node, origin);
            strictSeeds.add(node);
        }
    }

    // indirect evidence (Policy.assertContentWrites): a library contract, a field's default value (the Kotlin
    // reading is a 'lateinit' field), a comparison with null (the nullTests heuristic). Not a null that is written.
    private void seedIndirect(Object node, String origin) {
        if (node != null) seedOrigin.putIfAbsent(node, origin);
    }

    private Set<Object> closure(Map<Object, Object> cause) {
        if (!policy.assertContentWrites()) return closure(cause, seedOrigin.keySet(), null);
        // Policy.assertContentWrites: first what null reaches without the library contracts; a value outside it
        // enters a slot only as an asserted write, so that edge is not followed, and a library seed on a slot itself
        // is an asserted write too
        Map<Object, Object> strictCause = new HashMap<>();
        Set<Object> strict = closure(strictCause, strictSeeds, null);
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

    private static boolean isSlot(Object node) {
        return node instanceof Arg || node instanceof Content;
    }

    // strict: when not null, an edge from a value into a slot is followed only from a node in it
    private Set<Object> closure(Map<Object, Object> cause, Set<Object> seeds, Set<Object> strict) {
        // a seed on a non-null contract is the caller's error (an M5 finding), not a source of null
        Set<Object> reached = new LinkedHashSet<>(seeds);
        reached.removeAll(nonNullContracts);
        Deque<Object> queue = new ArrayDeque<>(reached);
        while (!queue.isEmpty()) {
            Object n = queue.removeFirst();
            for (Object s : successors.getOrDefault(n, Set.of())) {
                if (strict != null && isSlot(s) && !isSlot(n) && !strict.contains(n)) continue;
                if (!nonNullContracts.contains(s) && reached.add(s)) {
                    cause.put(s, n);
                    queue.addLast(s);
                }
            }
        }
        return reached;
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
                if (index < type.parameters().size()) addEdge(fi, new Arg(n, index));
            }
        }
    }

    /*
     Arrays are invariant in what they hold (Kotlin's Array<T>; Java's covariance is a store check at run time, not a
     licence): wherever an array flows, both ends denote the same objects, so their elements are one slot. Every
     flow between two array nodes, also one the top-level verdict drops because the array is known non-null there,
     ties their Contents both ways, recursively for nested arrays.
     */
    private void coupleContent() {
        Set<Object> writtenThrough = writtenThrough();
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
                successors.computeIfAbsent(c0, _ -> new LinkedHashSet<>()).add(c1);
                if (policy.assertContentWrites() || writtenThrough.contains(flow.get(1))) {
                    successors.computeIfAbsent(c1, _ -> new LinkedHashSet<>()).add(c0);
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
                    if (!isExtendsWildcard(p1) && (!class0 || class1)) {
                        successors.computeIfAbsent(a1, _ -> new LinkedHashSet<>()).add(a0);
                    }
                    queue.add(List.of(a0, a1));
                }
            }
        }
    }

    /*
     For Java annotations an array's elements need to flow only forward: Java arrays are covariant, and so is
     JSpecify. Backward only where the downstream array is written through ('void f(Object[] a) { a[0] = null; }'
     puts the null in the caller's array), directly or via another array it flows on into. Kotlin's Array<T> is
     invariant, so under Policy.assertContentWrites both ways regardless. Guava 2026-10-07: coupling both ways made
     ImmutableMap.Builder.entries, ImmutableList.array, Joiner.join(Object[]) and TypeToken's Type[] one slot.
     */
    private Set<Object> writtenThrough() {
        Set<Object> written = new HashSet<>();
        successors.forEach((from, tos) -> {
            if (from instanceof Content) return;
            for (Object to : tos) if (to instanceof Content c) written.add(c.of());
        });
        seedOrigin.forEach((node, origin) -> {
            if (node instanceof Content c && !origin.startsWith("array created") && !origin.startsWith("null in an array initializer")) {
                written.add(c.of());
            }
        });
        Map<Object, List<Object>> upstream = new HashMap<>();
        for (List<Object> flow : flows) upstream.computeIfAbsent(flow.get(1), _ -> new ArrayList<>()).add(flow.get(0));
        Deque<Object> queue = new ArrayDeque<>(written);
        while (!queue.isEmpty()) {
            for (Object up : upstream.getOrDefault(queue.removeFirst(), List.of())) {
                if (written.add(up)) queue.add(up);
            }
        }
        return written;
    }

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

    private static boolean isExtendsWildcard(ParameterizedType pt) {
        return pt.wildcard() != null && pt.wildcard().isExtends();
    }

    private boolean library(Object node) {
        return switch (node) {
            case Content c -> library(c.of());
            case Arg a -> library(a.of());
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
    private static final boolean PARAMETER_SHADOWS_OFF = System.getenv("NOPARAMSHADOW") != null;

    private static Set<ParameterInfo> parametersAssignedIn(Element element, MethodInfo mi) {
        Set<ParameterInfo> set = new HashSet<>();
        if (element == null || PARAMETER_SHADOWS_OFF) return set;
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
        MethodInfo owner = nullOwner(((LocalVariable) marker).assignmentExpression());
        if (owner != null && !owner.equals(mi) && analysed.contains(owner) && !owner.returnType().isVoid()) {
            addEdge(owner, target);
        } else {
            seed(target, origin);
        }
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
                if (!isTypeVariable(overridden.returnType())) addEdge(mi, overridden);
                int n = Math.min(mi.parameters().size(), overridden.parameters().size());
                for (int i = 0; i < n; i++) {
                    // DOWNWARD not from a type-variable parameter into an implementation that instantiates it with a
                    // concrete type other than Object: a null passed through the generic 'Comparator<T>.compare'
                    // reaches 'compare(boolean[] ...)' only where T is boolean[], which nothing here tells. Measured
                    // on guava (2026-10-07): -142 noise; keeping Object implementations ('IdentityFunction.apply')
                    // keeps the unsafe count. A heuristic: a null passed to a Comparator<String> does reach a String
                    // implementation.
                    ParameterizedType implementationType = mi.parameters().get(i).parameterizedType();
                    if (!isTypeVariable(overridden.parameters().get(i).parameterizedType())
                        || isTypeVariable(implementationType)
                        || implementationType.typeInfo() != null && implementationType.arrays() == 0
                           && implementationType.typeInfo().isJavaLangObject()) {
                        addEdge(overridden.parameters().get(i), mi.parameters().get(i));
                    }
                    if (!isTypeVariable(overridden.parameters().get(i).parameterizedType())) {
                        addEdge(mi.parameters().get(i), overridden.parameters().get(i));
                    }
                }
            }
        }
        if (!mi.isConstructor()) {
            String lib = libraryNullableReturn(mi);
            if (lib != null && !mi.returnType().isVoid()) seed(mi, "overrides " + lib);
            for (ParameterInfo pi : mi.parameters()) {
                String libParam = libraryNullableParameter(mi, pi.index());
                if (libParam != null) {
                    seed(pi, "overrides " + libParam);
                } else if (policy.libraryCallbacks()) {
                    String unhinted = libraryUnhintedParameter(mi, pi.index());
                    if (unhinted != null) seed(pi, "overrides " + unhinted + ", which does not declare it non-null");
                }
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
        for (Link link : links) {
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
            if (link.linkNature().isIdenticalTo() || link.linkNature().isAssignedFrom()) {
                if (isNullMarker(link.to())) {
                    // a variable becomes null only where it is assigned: '≡' is transitive in the links, so in
                    // 'checked = check(key)' with 'check' returning its argument or null, 'key ≡ return ≡ null'
                    // reaches 'key' (nacos SystemEnvPropertySource, reported by the diagnose session). A slot
                    // (Content, Arg) is written by calls too.
                    boolean slot = from instanceof Content || from instanceof Arg;
                    if (slot || statement == null || fromVar instanceof ReturnVariable
                        || assignsHere(statement, fromVar)) {
                        nullMarker(mi, link.to(), from, "null in " + mi.fullyQualifiedName());
                    }
                } else {
                    linkEdge(node(mi, scope, link.to()), from, link.to(), fromVar, statement);
                }
            } else if (link.linkNature().isIdenticalToOrAssignedFromTo()) {
                // '→': v is assigned to link.to()
                linkEdge(from, node(mi, scope, link.to()), fromVar, link.to(), statement);
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
        Object container = sideIsContainer ? side : other;
        Variable memberVar = sideIsContainer ? otherVar : sideVar;
        Object member = sideIsContainer ? other : side;
        if (!(container instanceof Arg || container instanceof Content)) return;
        if (member == null || member instanceof Arg || member instanceof Content) return;
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
        Guard guard = value == null ? argumentGuard(statement, sourceVar, recipientVar)
                : guard(value, sourceVar, facts.before(statement));
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

    private Guard guard(Expression value, Variable source, Set<Variable> known) {
        // a value known non-null as a whole carries no null, whatever flows into it ('return requireNonNull(f)')
        if (facts.nonNull(value, known)) return Guard.GUARDED;
        Expression e = NonNullFacts.unwrap(value);
        switch (e) {
            case VariableExpression ve -> {
                if (ve.variable().equals(source)) return known.contains(source) ? Guard.GUARDED : Guard.UNGUARDED;
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
            default -> {
                return Guard.OPAQUE;
            }
        }
    }

    private static boolean mentions(Expression e, Variable v) {
        boolean[] found = {false};
        e.visit(x -> {
            if (x instanceof VariableExpression ve && ve.variable().equals(v)) found[0] = true;
            return !found[0];
        });
        return found[0];
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
                if (e instanceof MethodCall mc && mc.methodInfo() != null) {
                    callSite(mi, own, statement, mc, mc.methodInfo(), mc.analysis(), mc.parameterExpressions());
                } else if (e instanceof ConstructorCall cc && cc.constructor() != null) {
                    callSite(mi, own, statement, cc, cc.constructor(), cc.analysis(), cc.parameterExpressions());
                }
                return true;
            });
            syntacticSeeds(mi, own, statement);
            if (statement instanceof ExplicitConstructorInvocation eci && eci.methodInfo() != null) {
                callSite(mi, own, statement, null, eci.methodInfo(), eci.analysis(), eci.parameterExpressions());
            }
            // from here on a read of a parameter the statement assigned is a read of the shadow
            shadowed.addAll(shadowedBySubBlocks);
            shadowed.addAll(parametersAssignedIn(statement, mi));
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
            case DoStatement _, ForStatement _, TryStatement _, Block _ -> false;
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

    private void syntacticSeeds(MethodInfo mi, Scope scope, Statement statement) {
        readScope = new ReadScope(mi, scope);
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
                    seed(local, "initialized null in " + mi.fullyQualifiedName());
                }
                callResult(local, lv.assignmentExpression());
                seedCreated(local, lv.assignmentExpression(), mi);
                constructorCopies(mi, scope, local, lv.assignmentExpression());
            });
        } else if (statement.expression() instanceof Assignment a && a.variableTarget() != null) {
            Object target = assignmentTarget(mi, scope, a.variableTarget());
            if (a.value() instanceof NullConstant) seed(target, "assigned null in " + mi.fullyQualifiedName());
            callResult(target, a.value());
            seedCreated(target, a.value(), mi);
            constructorCopies(mi, scope, target, a.value());
        } else if (statement instanceof ForEachStatement fe && fe.initializer() != null) {
            // for (T x : array): the elements flow into x
            Expression iterated = NonNullFacts.unwrap(fe.expression());
            Object array = iterated instanceof VariableExpression ve ? node(mi, scope, ve.variable())
                    : iterated instanceof MethodCall mc && mc.methodInfo() != null && analysed.contains(mc.methodInfo())
                    ? mc.methodInfo() : null;
            ParameterizedType type = array == null ? null : typeOf(array);
            if (type != null && (type.arrays() > 0 || type.parameters().size() == 1)) {
                // over an array its elements, over an Iterable<T> its one type argument; the statement's own scope
                // declares the loop variable
                Object elements = type.arrays() > 0 ? new Content(array) : new Arg(array, 0);
                fe.initializer().localVariableStream()
                        .forEach(lv -> addEdge(elements, local(mi, scope, lv.simpleName())));
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
                if (a.value() instanceof NullConstant) seed(target, "assigned null in " + mi.fullyQualifiedName());
                callResult(target, a.value());
                seedCreated(target, a.value(), mi);
            }
            return true;
        });
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
            if (argument == null) continue;
            ParameterizedType type = typeOf(argument);
            instantiatedSlots(argument, type != null ? type : arguments.get(i).parameterizedType(), parameters.get(i),
                    formal, target, targetType, constructor.typeInfo(), true);
        }
    }

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
                boolean receivesOnly = isExtendsWildcard(f);
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
            if (i + 1 < statements.size() && statements.get(i + 1) instanceof ForStatement loop) {
                Variable array = null;
                Expression init = null;
                if (statement instanceof LocalVariableCreation lvc) {
                    List<LocalVariable> locals = lvc.localVariableStream().toList();
                    if (locals.size() == 1) {
                        array = locals.getFirst();
                        init = locals.getFirst().assignmentExpression();
                    }
                } else if (!FIELD_FILLS_OFF && statement instanceof ExpressionAsStatement eas
                           && eas.expression() instanceof Assignment a && a.assignmentOperator() == null
                           && (a.variableTarget() instanceof FieldReference || a.variableTarget() instanceof LocalVariable)) {
                    // `this.workers = new Worker[n]; for (...) workers[i] = new Worker();` in a constructor (nacos's
                    // NacosExecuteTaskExecuteEngine, CodeLaser/maddi-mod#22 gap 4), or a local assigned after its declaration
                    array = a.variableTarget();
                    init = a.value();
                }
                if (init != null && arrayCreatedWithNulls(init) == 1
                    && NonNullFacts.unwrap(init) instanceof ConstructorCall cc
                    && fills(loop, array, cc.parameterExpressions().getFirst())) {
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
            if (e instanceof BreakOrContinueStatement || e instanceof ReturnStatement
                || e instanceof Assignment a && index.equals(a.variableTarget())) escapes[0] = true;
            return !escapes[0];
        });
        if (escapes[0]) return false;
        return loop.block().statements().stream().anyMatch(st -> st instanceof ExpressionAsStatement eas
                && eas.expression() instanceof Assignment a && a.assignmentOperator() == null
                && a.variableTarget() instanceof DependentVariable dv
                && isVariable(dv.arrayExpression(), array) && isVariable(dv.indexExpression(), index));
    }

    private static boolean isVariable(Expression e, Variable v) {
        return NonNullFacts.unwrap(e) instanceof VariableExpression ve && ve.variable().equals(v);
    }

    private void seedCreated(Object target, Expression value, MethodInfo mi) {
        if (target == null || value == null) return;
        int depth = filledCreations.contains(value) ? 0 : arrayCreatedWithNulls(value);
        if (depth > 0) {
            Object content = target;
            for (int d = 0; d < depth; d++) content = new Content(content);
            seed(content, "array created with null elements in " + where(mi));
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
                    seed(elements, "null in an array initializer in " + where(mi));
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
        if (analysed.contains(callee) && !callee.isConstructor() && !callee.returnType().isVoid()) {
            // the same object: its content slots are one (coupleContent); not where the callee's type arguments are
            // type variables, which each call instantiates anew
            if (concreteArguments(callee.returnType())) flows.add(List.of(callee, target));
            // a class type variable's own null ('E getWithKey(K k) { ... return null; }', E?) holds for every
            // instantiation; so does a method type variable's, unless a parameter of that very type can carry the
            // caller's null back out ('<X> X id(X x)'): '<T> T getAttribute(Key<T> key)' returns a map's lookup
            ParameterizedType rt = callee.returnType();
            boolean ownNull = rt.arrays() == 0 && rt.typeParameter() != null
                              && (!rt.typeParameter().isMethodTypeParameter()
                                  || callee.parameters().stream().noneMatch(pi ->
                                         rt.typeParameter().equals(pi.parameterizedType().typeParameter())));
            if (policy.callResults() && (!isTypeVariable(rt) || ownNull)) addEdge(callee, target);
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
        String lib = facts.keyPresentWhenCalled(mc) ? null : libraryNullableReturn(callee);
        if (lib != null) {
            seedIndirect(target, "assigned " + lib);
        } else if (policy.contracts() && analysed.contains(callee)
                   && NullAnnotations.explicitState(callee) == NullableState.NULLABLE) {
            seed(target, "assigned " + callee.fullyQualifiedName() + ", annotated nullable");
        }
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
                if (NonNullFacts.unwrap(argument) instanceof NullConstant) {
                    seed(slot, "null passed to " + callee.fullyQualifiedName() + " in " + where(mi));
                    continue;
                }
                seedCreated(slot, argument, mi);
                Object source = readScope == null ? null : argumentNode(readScope.mi(), readScope.scope(), argument);
                if (source != null) {
                    addEdge(source, slot);
                    flows.add(List.of(source, slot)); // the same object: its content slots are the slot's
                }
            }
        }
    }

    private String nullableLibraryCall(Expression e) {
        Expression unwrapped = e instanceof Cast c ? c.expression() : e;
        if (unwrapped instanceof MethodCall mc && mc.methodInfo() != null && !facts.keyPresentWhenCalled(mc)) {
            return libraryNullableReturn(mc.methodInfo());
        }
        return null;
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
                seedIndirect(node, "compared with null in " + mi.fullyQualifiedName());
            }
        }
    }

    private void callSite(MethodInfo mi, Scope scope, Statement statement, Expression call, MethodInfo callee,
                          io.codelaser.maddi.cst.api.analysis.PropertyValueMap analysis, List<Expression> arguments) {
        List<ParameterInfo> parameters = callee.parameters();
        if (parameters.isEmpty()) return;
        LinkComputer.ListOfLinks list = analysis.getOrNull(LinkComputerImpl.LINKED_VARIABLES_ARGUMENTS,
                LinkComputerImpl.ListOfLinksImpl.class);
        receiverSlots(mi, scope, statement, call, callee, list, arguments);
        instantiatedArguments(mi, scope, call, callee, arguments);
        for (int i = 0; i < arguments.size(); i++) {
            ParameterInfo parameter = parameters.get(Math.min(i, parameters.size() - 1)); // varargs: the last one
            if (parameter.isVarArgs() && !passesTheArray(arguments, parameters, i)) {
                // an element of the array the call creates: the array is never null, the argument is an element
                argument(mi, scope, statement, call, list, arguments, i, new Content(parameter));
                continue;
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
            if (parameter.parameterizedType().typeParameter() != null
                && !parameter.parameterizedType().typeParameter().isMethodTypeParameter()
                && parameter.parameterizedType().arrays() == 0
                && (!analysed.contains(callee)
                    || !callee.isAbstract() && !implementations.containsKey(callee)
                       && slotReceiver(mi, scope, call, callee) != null)) {
                continue;
            }
            for (ParameterInfo pi : typedDispatch(call, callee, parameter)) {
                argument(mi, scope, statement, call, list, arguments, i, pi);
            }
        }
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
            int index = slotIndex(type, callee.typeInfo(), pt.typeParameter().getIndex());
            if (index >= 0) argument(mi, scope, statement, call, list, arguments, i, new Arg(receiver, index));
        }
        contentCopies(mi, scope, receiver, callee, arguments);
    }

    // the receiver whose slots a call writes: a variable of the callee's generic class, with as many type arguments;
    // or an analysed method's result, whose slots are those of what it returns ('wrapper.getInits().addWithKey(v, k)'
    // writes the field 'inits' returns: fernflower InitializerProcessor)
    private Object slotReceiver(MethodInfo mi, Scope scope, Expression call, MethodInfo callee) {
        if (!(call instanceof MethodCall mc) || mc.object() == null || callee.isStatic()) return null;
        Expression object = NonNullFacts.unwrap(mc.object());
        Object receiver;
        if (object instanceof VariableExpression ve) {
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
        if (callee.analysis().getOrDefault(PropertyImpl.NON_MODIFYING_METHOD, ValueImpl.BoolImpl.FALSE).isTrue()) {
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

    // pi: the parameter, or the Content of a varargs parameter
    private void argument(MethodInfo mi, Scope scope, Statement statement, Expression call,
                          LinkComputer.ListOfLinks list, List<Expression> arguments, int i, Object pi) {
        if (arguments.get(i) instanceof NullConstant) {
            seed(pi, "null argument in " + mi.fullyQualifiedName());
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
            // Kotlin: a slot read passed on ('new Pair(cv.get(i), i)') gives the parameter the slot's value, as
            // callResult does for an assignment; the Java policies leave this to the links
            if (policy.callResults() && source instanceof Arg) addEdge(source, pi);
            // a stream's terminal operation as the argument: its elements are values of the parameter's slot
            streamTerminal(mi, scope, unwrappedArgument)
                    .forEach((j, nodes) -> nodes.forEach(n -> addEdge(n, new Arg(pi, j))));
        }
        String lib = nullableLibraryCall(arguments.get(i));
        if (lib != null) {
            seedIndirect(pi, "argument " + lib + " in " + mi.fullyQualifiedName());
            return;
        }
        if (list == null || i >= list.list().size()) {
            // no argument links ('super(attributes)', 'this(...)' carry none): the argument's own node
            if (!(unwrappedArgument instanceof MethodCall)) addEdge(argumentNode(mi, scope, arguments.get(i)), pi);
            else callResultArgument(unwrappedArgument, pi);
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
        }
        if (initializer instanceof NullConstant) {
            seed(fi, "initializer null");
            return;
        }
        if (fi.isFinal() || initializer != null && !initializer.isEmpty()) return;
        List<MethodInfo> constructors = fi.owner().constructors();
        if (constructors.isEmpty()) {
            seedIndirect(fi, "default value: no constructor assigns it");
            return;
        }
        for (MethodInfo constructor : constructors) {
            if (!assigns(constructor, fi)) {
                seedIndirect(fi, "default value: not assigned in " + constructor.fullyQualifiedName());
                return;
            }
        }
    }

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
