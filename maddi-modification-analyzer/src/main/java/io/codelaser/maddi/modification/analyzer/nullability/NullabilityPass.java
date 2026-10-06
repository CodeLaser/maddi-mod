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

import io.codelaser.maddi.cst.api.expression.Assignment;
import io.codelaser.maddi.cst.api.expression.BinaryOperator;
import io.codelaser.maddi.cst.api.expression.Cast;
import io.codelaser.maddi.cst.api.expression.ConstructorCall;
import io.codelaser.maddi.cst.api.expression.Expression;
import io.codelaser.maddi.cst.api.expression.Lambda;
import io.codelaser.maddi.cst.api.expression.MethodCall;
import io.codelaser.maddi.cst.api.expression.NullConstant;
import io.codelaser.maddi.cst.api.expression.VariableExpression;
import io.codelaser.maddi.cst.api.info.FieldInfo;
import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.ParameterInfo;
import io.codelaser.maddi.cst.api.statement.Block;
import io.codelaser.maddi.cst.api.statement.ExplicitConstructorInvocation;
import io.codelaser.maddi.cst.api.statement.LocalVariableCreation;
import io.codelaser.maddi.cst.api.statement.ReturnStatement;
import io.codelaser.maddi.cst.api.statement.Statement;
import io.codelaser.maddi.cst.api.type.NullableState;
import io.codelaser.maddi.cst.api.type.ParameterizedType;
import io.codelaser.maddi.cst.api.variable.FieldReference;
import io.codelaser.maddi.cst.api.variable.LocalVariable;
import io.codelaser.maddi.cst.api.variable.Variable;
import io.codelaser.maddi.cst.impl.analysis.PropertyImpl;
import io.codelaser.maddi.modification.link.LinkComputer;
import io.codelaser.maddi.modification.link.impl.LinkComputerImpl;
import io.codelaser.maddi.modification.prepwork.Util;
import io.codelaser.maddi.modification.prepwork.variable.Link;
import io.codelaser.maddi.modification.prepwork.variable.Links;
import io.codelaser.maddi.modification.prepwork.variable.ReturnVariable;
import io.codelaser.maddi.modification.prepwork.variable.VariableData;
import io.codelaser.maddi.modification.prepwork.variable.VariableInfo;
import io.codelaser.maddi.modification.prepwork.variable.impl.VariableDataImpl;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Declaration nullability as reachability over the converged link facts (docs/design/nullability.md §4.1, M3).
 * Modelled on {@code ShadowModificationPass}: a one-shot pass after the iterating analyzer has converged, so no
 * optimistic value is frozen before its evidence arrives.
 * <p>
 * <b>Nodes</b>: fields, parameters, and methods standing for their return value; locals carry null between
 * statements. <b>Seeds</b> (may be null): a null constant flowing into a node, as a link to a null-constant marker
 * or as a {@code null} argument; a field that can still hold Java's default value (not final, no initializer, not
 * assigned in every constructor); under {@link Policy#nullTests()}, a parameter or field compared with null; the
 * JDK's null contracts ({@link LibraryNullness}): overriding a method that may return null or accepts null, and
 * using such a method's result directly.
 * <b>Edges</b>, in the direction null travels (source to recipient): {@code ←}/{@code ≡}/{@code →} links between
 * nodes in each method's variable data; an argument's nodes to the callee's parameter (the per-call argument
 * links); an implementation's return to the return it overrides; and a parameter to the parameter at the same
 * index along the override chain, both ways (Kotlin's rule: one nullability per chain).
 * <p>
 * <b>Verdict</b>: reached is {@link NullableState#NULLABLE}; not reached is {@link Policy#unreached()}; a primitive
 * is {@link NullableState#NONNULL}; a type-variable type and the outputs of a degraded method (no links) are
 * {@link NullableState#UNSPECIFIED}. Type arguments are not inferred yet (content slots, §4.2 M3): they stay
 * UNSPECIFIED.
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
     */
    public record Policy(NullableState unreached, boolean nullTests) {
        public static final Policy NULL_MARKED = new Policy(NullableState.NONNULL, true);
        public static final Policy NULL_MARKED_FLOW_ONLY = new Policy(NullableState.NONNULL, false);
        public static final Policy CAUTIOUS = new Policy(NullableState.UNSPECIFIED, true);
    }

    public record Report(Map<Info, ParameterizedType> verdicts, Map<Object, Object> cause,
                         Map<Object, String> seedOrigin) {
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
                case MethodInfo mi -> "return " + mi.fullyQualifiedName();
                case Info info -> info.fullyQualifiedName();
                default -> String.valueOf(node);
            };
        }
    }

    private final Policy policy;
    private final Map<Object, Set<Object>> successors = new LinkedHashMap<>();
    private final Map<Object, String> seedOrigin = new LinkedHashMap<>();
    private final Set<MethodInfo> degraded = new LinkedHashSet<>();

    public NullabilityPass(Policy policy) {
        this.policy = policy;
    }

    public Report go(List<Info> analysisOrder) {
        List<MethodInfo> methods = analysisOrder.stream()
                .filter(i -> i instanceof MethodInfo).map(i -> (MethodInfo) i).toList();
        List<FieldInfo> fields = analysisOrder.stream()
                .filter(i -> i instanceof FieldInfo).map(i -> (FieldInfo) i).toList();
        for (MethodInfo mi : methods) buildForMethod(mi);
        for (FieldInfo fi : fields) seedDefaultValue(fi);

        Map<Object, Object> cause = new LinkedHashMap<>();
        Set<Object> reached = closure(cause);

        Map<Info, ParameterizedType> verdicts = new LinkedHashMap<>();
        for (FieldInfo fi : fields) {
            verdicts.put(fi, verdict(fi.type(), reached.contains(fi), false));
        }
        for (MethodInfo mi : methods) {
            boolean deg = degraded.contains(mi);
            for (ParameterInfo pi : mi.parameters()) {
                verdicts.put(pi, verdict(pi.parameterizedType(), reached.contains(pi), false));
            }
            if (!mi.isConstructor() && !mi.returnType().isVoid()) {
                verdicts.put(mi, verdict(mi.returnType(), reached.contains(mi), deg));
            }
        }
        return new Report(verdicts, cause, seedOrigin);
    }

    private ParameterizedType verdict(ParameterizedType declared, boolean reached, boolean degradedOutput) {
        ParameterizedType arguments = declared.parameters().isEmpty() ? declared
                : declared.withParameters(declared.parameters().stream()
                .map(p -> unspecified(p)).toList());
        NullableState state;
        if (declared.isPrimitiveExcludingVoid() && declared.arrays() == 0) state = NullableState.NONNULL;
        else if (reached) state = NullableState.NULLABLE; // also a type variable: '@Nullable V get(Object)'
        else if (degradedOutput || isTypeVariable(declared)) state = NullableState.UNSPECIFIED; // parametric
        else state = policy.unreached();
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
        successors.computeIfAbsent(from, _ -> new LinkedHashSet<>()).add(to);
    }

    private void seed(Object node, String origin) {
        if (node != null) seedOrigin.putIfAbsent(node, origin);
    }

    private Set<Object> closure(Map<Object, Object> cause) {
        Set<Object> reached = new LinkedHashSet<>(seedOrigin.keySet());
        Deque<Object> queue = new ArrayDeque<>(reached);
        while (!queue.isEmpty()) {
            Object n = queue.removeFirst();
            for (Object s : successors.getOrDefault(n, Set.of())) {
                if (reached.add(s)) {
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

    // a local variable of one method: a graph node without a verdict, through which null travels between statements
    record Local(MethodInfo methodInfo, String name) {
        @Override
        public String toString() {
            return name + " in " + methodInfo.fullyQualifiedName();
        }
    }

    // the node a variable stands for: a parameter, a field (field-insensitive in its object), a return value, a
    // local of this method. Markers ($_ce, $_v) and the link engine's intermediates ($__) are not nodes.
    private static Object node(MethodInfo mi, Variable v) {
        return switch (v) {
            case ParameterInfo pi -> pi;
            case ReturnVariable rv -> rv.methodInfo();
            case FieldReference fr when !Util.virtual(fr) -> fr.fieldInfo();
            case LocalVariable lv when !lv.simpleName().startsWith("$") -> new Local(mi, lv.fullyQualifiedName());
            case null, default -> null;
        };
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
            for (MethodInfo overridden : mi.overrides()) {
                if (!isTypeVariable(overridden.returnType())) addEdge(mi, overridden);
                int n = Math.min(mi.parameters().size(), overridden.parameters().size());
                for (int i = 0; i < n; i++) {
                    addEdge(overridden.parameters().get(i), mi.parameters().get(i));
                    if (!isTypeVariable(overridden.parameters().get(i).parameterizedType())) {
                        addEdge(mi.parameters().get(i), overridden.parameters().get(i));
                    }
                }
            }
        }
        if (!mi.isConstructor()) {
            String lib = LibraryNullness.nullableReturn(mi);
            if (lib != null && !mi.returnType().isVoid()) seed(mi, "overrides " + lib);
            for (ParameterInfo pi : mi.parameters()) {
                String libParam = LibraryNullness.nullableParameter(mi, pi.index());
                if (libParam != null) seed(pi, "overrides " + libParam);
            }
        }
        if (mi.analysis().getOrDefault(PropertyImpl.DEGRADED_ANALYSIS_METHOD,
                io.codelaser.maddi.cst.impl.analysis.ValueImpl.BoolImpl.FALSE).isTrue()) {
            degraded.add(mi);
        }
        VariableData vd = VariableDataImpl.of(mi);
        if (vd != null) {
            vd.variableInfoStream().forEach(vi -> linksOf(mi, vi));
        }
        if (mi.methodBody() != null) handleBlock(mi, mi.methodBody());
    }

    private void linksOf(MethodInfo mi, VariableInfo vi) {
        Variable v = vi.variable();
        Object recipient = node(mi, v);
        Links links = vi.linkedVariables();
        if (recipient == null || links == null) return;
        for (Link link : links) {
            if (!link.from().equals(v)) continue; // links about a face of v ('this.f.g') are not about v
            if (link.linkNature().isIdenticalTo() || link.linkNature().isAssignedFrom()) {
                if (isNullMarker(link.to())) {
                    seed(recipient, "null in " + mi.fullyQualifiedName());
                } else {
                    addEdge(node(mi, link.to()), recipient);
                }
            } else if (link.linkNature().isIdenticalToOrAssignedFromTo()) {
                // '→': v is assigned to link.to()
                addEdge(recipient, node(mi, link.to()));
            }
        }
    }

    private void handleBlock(MethodInfo mi, Block block) {
        for (Statement statement : block.statements()) {
            statement.subBlockStream().forEach(sb -> handleBlock(mi, sb));
            VariableData vd = VariableDataImpl.of(statement);
            if (vd != null) vd.variableInfoStream().forEach(vi -> linksOf(mi, vi));
            statement.visit(e -> {
                if (e instanceof Lambda lambda) {
                    if (lambda.methodBody() != null) handleBlock(lambda.methodInfo(), lambda.methodBody());
                    return false;
                }
                if (e instanceof Block) return false; // nested statements are handled with their own vd
                if (policy.nullTests() && e instanceof BinaryOperator bo) nullTest(mi, bo);
                if (e instanceof MethodCall mc && mc.methodInfo() != null) {
                    callSite(mi, mc.methodInfo(), mc.analysis(), mc.parameterExpressions());
                } else if (e instanceof ConstructorCall cc && cc.constructor() != null) {
                    callSite(mi, cc.constructor(), cc.analysis(), cc.parameterExpressions());
                }
                return true;
            });
            libraryResult(mi, statement);
            if (statement instanceof ExplicitConstructorInvocation eci && eci.methodInfo() != null) {
                callSite(mi, eci.methodInfo(), eci.analysis(), eci.parameterExpressions());
            }
        }
    }

    // a library call whose result may be null (LibraryNullness), USED DIRECTLY: returned, assigned, initializing a
    // local. Its value reaches the link graph only as an opaque '$_v', so the use is matched syntactically.
    private void libraryResult(MethodInfo mi, Statement statement) {
        if (statement instanceof ReturnStatement rs && !mi.isConstructor() && !mi.returnType().isVoid()) {
            String lib = nullableLibraryCall(rs.expression());
            if (lib != null) seed(mi, "returns " + lib);
        } else if (statement instanceof LocalVariableCreation lvc) {
            lvc.localVariableStream().forEach(lv -> {
                String lib = nullableLibraryCall(lv.assignmentExpression());
                if (lib != null) seed(new Local(mi, lv.fullyQualifiedName()), "assigned " + lib);
            });
        } else if (statement.expression() instanceof Assignment a && a.variableTarget() != null) {
            String lib = nullableLibraryCall(a.value());
            if (lib != null) seed(node(mi, a.variableTarget()), "assigned " + lib);
        }
    }

    private static String nullableLibraryCall(Expression e) {
        Expression unwrapped = e instanceof Cast c ? c.expression() : e;
        if (unwrapped instanceof MethodCall mc && mc.methodInfo() != null) {
            return LibraryNullness.nullableReturn(mc.methodInfo());
        }
        return null;
    }

    // in Java only == and != take a null operand
    private void nullTest(MethodInfo mi, BinaryOperator bo) {
        Expression other = bo.lhs() instanceof NullConstant ? bo.rhs() : bo.rhs() instanceof NullConstant ? bo.lhs() : null;
        if (other instanceof VariableExpression ve) {
            Object node = node(mi, ve.variable());
            if (node instanceof ParameterInfo || node instanceof FieldInfo) {
                seed(node, "compared with null in " + mi.fullyQualifiedName());
            }
        }
    }

    private void callSite(MethodInfo mi, MethodInfo callee,
                          io.codelaser.maddi.cst.api.analysis.PropertyValueMap analysis, List<Expression> arguments) {
        List<ParameterInfo> parameters = callee.parameters();
        if (parameters.isEmpty()) return;
        LinkComputer.ListOfLinks list = analysis.getOrNull(LinkComputerImpl.LINKED_VARIABLES_ARGUMENTS,
                LinkComputerImpl.ListOfLinksImpl.class);
        for (int i = 0; i < arguments.size(); i++) {
            ParameterInfo pi = parameters.get(Math.min(i, parameters.size() - 1)); // varargs: the last parameter
            if (pi.isVarArgs()) continue; // the array is never null; its elements are content (not yet)
            if (arguments.get(i) instanceof NullConstant) {
                seed(pi, "null argument in " + mi.fullyQualifiedName());
                continue;
            }
            String lib = nullableLibraryCall(arguments.get(i));
            if (lib != null) {
                seed(pi, "argument " + lib + " in " + mi.fullyQualifiedName());
                continue;
            }
            if (list == null || i >= list.list().size()) continue;
            Links links = list.list().get(i);
            Variable primary = links.primary();
            if (primary == null) continue;
            if (isNullMarker(primary)) {
                seed(pi, "null argument in " + mi.fullyQualifiedName());
                continue;
            }
            Object node = node(mi, primary);
            if (node != null) addEdge(node, pi);
            // an intermediate (or a local assigned in this very statement): its sources, in the argument's own links
            sourcesOf(mi, primary, links, pi);
        }
    }

    private void sourcesOf(MethodInfo mi, Variable primary, Links links, ParameterInfo pi) {
        for (Link link : links) {
            if (!link.from().equals(primary)) continue;
            if (!(link.linkNature().isIdenticalTo() || link.linkNature().isAssignedFrom())) continue;
            if (isNullMarker(link.to())) {
                seed(pi, "null argument (via " + primary.simpleName() + ") in " + mi.fullyQualifiedName());
            } else {
                addEdge(node(mi, link.to()), pi);
            }
        }
    }

    // Java's default value: a non-final reference field without an initializer, which some constructor leaves alone
    private void seedDefaultValue(FieldInfo fi) {
        if (fi.type().isPrimitiveExcludingVoid() && fi.type().arrays() == 0) return;
        Expression initializer = fi.initializer();
        if (initializer instanceof NullConstant) {
            seed(fi, "initializer null");
            return;
        }
        if (fi.isFinal() || initializer != null && !initializer.isEmpty()) return;
        List<MethodInfo> constructors = fi.owner().constructors();
        if (constructors.isEmpty()) {
            seed(fi, "default value: no constructor assigns it");
            return;
        }
        for (MethodInfo constructor : constructors) {
            if (!assigns(constructor, fi)) {
                seed(fi, "default value: not assigned in " + constructor.fullyQualifiedName());
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
