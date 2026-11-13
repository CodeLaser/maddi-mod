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

package io.codelaser.maddi.modification.analyzer.ownership;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import io.codelaser.maddi.cst.api.element.Element;
import io.codelaser.maddi.cst.api.expression.Assignment;
import io.codelaser.maddi.cst.api.expression.Cast;
import io.codelaser.maddi.cst.api.expression.ConstructorCall;
import io.codelaser.maddi.cst.api.expression.EnclosedExpression;
import io.codelaser.maddi.cst.api.expression.Expression;
import io.codelaser.maddi.cst.api.expression.Lambda;
import io.codelaser.maddi.cst.api.expression.MethodCall;
import io.codelaser.maddi.cst.api.expression.MethodReference;
import io.codelaser.maddi.cst.api.expression.VariableExpression;
import io.codelaser.maddi.cst.api.info.FieldInfo;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.ParameterInfo;
import io.codelaser.maddi.cst.api.type.ParameterizedType;
import io.codelaser.maddi.cst.api.statement.Block;
import io.codelaser.maddi.cst.api.statement.ExplicitConstructorInvocation;
import io.codelaser.maddi.cst.api.statement.ExpressionAsStatement;
import io.codelaser.maddi.cst.api.statement.LocalVariableCreation;
import io.codelaser.maddi.cst.api.statement.LoopStatement;
import io.codelaser.maddi.cst.api.statement.Statement;
import io.codelaser.maddi.cst.api.variable.FieldReference;
import io.codelaser.maddi.cst.api.variable.LocalVariable;
import io.codelaser.maddi.cst.api.variable.This;
import io.codelaser.maddi.cst.api.variable.Variable;
import io.codelaser.maddi.cst.impl.analysis.PropertyImpl;
import io.codelaser.maddi.cst.impl.analysis.ValueImpl;
import io.codelaser.maddi.modification.common.AnalysisHelper;
import io.codelaser.maddi.modification.link.impl.MethodLinkedVariablesImpl;
import io.codelaser.maddi.modification.prepwork.variable.Link;
import io.codelaser.maddi.modification.prepwork.variable.Links;
import io.codelaser.maddi.modification.prepwork.variable.MethodLinkedVariables;

/**
 * Transfer (docs/roadmap/ownership.md stage 5): a parameter whose every argument is handed over, created by the
 * caller for the call and not used by it afterwards. Stage 3 then counts a field assigned from it as arriving fresh.
 * <ol>
 *   <li><b>Every call site is known.</b> The method or constructor is private or package-private, or the world is
 *   closed ({@code ownershipClosedWorld}: the analysed code is an application, not a library). It overrides nothing,
 *   nothing overrides it, no method reference names it, it is not variadic in that position, and it has at least one
 *   call site.</li>
 *   <li><b>Every argument is handed over</b>, at every site, through casts, parentheses and identity methods
 *   ({@code Objects.requireNonNull}, {@code IDENTITY_METHOD}):
 *   <ul>
 *     <li>created on the spot: freshness's per-path evidence, without locals;</li>
 *     <li>a parameter of the caller that is itself transferred and that the caller mentions nowhere else
 *     ({@code this(a, null)});</li>
 *     <li>a local declared in the block of the statement holding the call, assigned only created values, that before
 *     that statement is only the receiver of calls that keep nothing of it (they neither may leak {@code this} nor
 *     dispatch elsewhere, and their result is discarded, primitive, immutable or an element: not {@code l.subList(..)}),
 *     that the statement mentions once, outside any loop or lambda in it, and that no later
 *     statement mentions. Deliberately narrow: no data-flow ordering beyond one block.</li>
 *     <li>a field of the caller's own object, when the caller is a {@code @Finalizer} that mentions it once, outside
 *     loops and lambdas, and the field is owned: a move out of a dying owner ({@link #moveOut}; the "owned" part is a
 *     greatest fixpoint with stage 3, {@link #retainMoves}).</li>
 *   </ul></li>
 * </ol>
 * Greatest fixpoint over the caller-parameter dependencies, as the other stages. What the callee does with the
 * parameter is stage 3's question ({@link #keptOnlyIn}).
 */
final class Transfers {
    private final Set<ParameterInfo> transferred = new LinkedHashSet<>();
    private final Map<ParameterInfo, String> reasons = new LinkedHashMap<>();
    private final Map<ParameterInfo, Set<ParameterInfo>> dependencies = new LinkedHashMap<>();
    private final Map<ParameterInfo, Set<Move>> moves = new LinkedHashMap<>();

    /** A finalizer handing over a field of its own object: after the call, that object is dead. */
    record Move(MethodInfo finalizer, FieldInfo field) {
    }

    private record Site(MethodInfo caller, List<Expression> arguments, Element call) {
    }

    Transfers(Set<MethodInfo> analysed, Set<MethodInfo> analysedResults, Set<MethodInfo> fresh,
              ThisEscape thisEscape, boolean closedWorld) {
        Map<MethodInfo, List<Site>> sites = new LinkedHashMap<>();
        Set<MethodInfo> referenced = new HashSet<>();
        for (MethodInfo caller : analysed) {
            if (caller.methodBody() == null) continue;
            caller.methodBody().visit((Element e) -> {
                switch (e) {
                    case MethodReference mr -> {
                        if (mr.methodInfo() != null) referenced.add(mr.methodInfo());
                    }
                    case ConstructorCall cc -> {
                        if (cc.constructor() != null && cc.anonymousClass() == null) {
                            sites.computeIfAbsent(cc.constructor(), _ -> new ArrayList<>())
                                    .add(new Site(caller, cc.parameterExpressions(), cc));
                        }
                    }
                    case MethodCall mc -> {
                        if (mc.methodInfo() != null) {
                            sites.computeIfAbsent(mc.methodInfo(), _ -> new ArrayList<>())
                                    .add(new Site(caller, mc.parameterExpressions(), mc));
                        }
                    }
                    case ExplicitConstructorInvocation eci -> {
                        if (eci.methodInfo() != null) {
                            sites.computeIfAbsent(eci.methodInfo(), _ -> new ArrayList<>())
                                    .add(new Site(caller, eci.parameterExpressions(), eci));
                        }
                    }
                    default -> {
                    }
                }
                return true;
            });
        }
        // a call in a field initializer is in no body: its argument is not checked, so the callee does not transfer
        Set<FieldInfo> fields = new LinkedHashSet<>();
        analysed.forEach(m -> fields.addAll(m.typeInfo().fields()));
        for (MethodInfo callee : analysed) {
            String calleeReason = callee(callee, sites, referenced, closedWorld, fields);
            for (ParameterInfo pi : callee.parameters()) {
                if (pi.parameterizedType().isPrimitiveExcludingVoid()) continue;
                if (calleeReason != null) {
                    reasons.put(pi, calleeReason);
                    continue;
                }
                if (pi.isVarArgs()) {
                    reasons.put(pi, "variadic");
                    continue;
                }
                Set<ParameterInfo> deps = new LinkedHashSet<>();
                Set<Move> paramMoves = new LinkedHashSet<>();
                String r = null;
                for (Site site : sites.get(callee)) {
                    if (pi.index() >= site.arguments().size()) {
                        r = "a call without the argument";
                        break;
                    }
                    r = handedOver(site, site.arguments().get(pi.index()), analysedResults, fresh, thisEscape, deps,
                            paramMoves);
                    if (r != null) {
                        r = "at a call in " + site.caller().fullyQualifiedName() + ": " + r;
                        break;
                    }
                }
                if (r == null) {
                    transferred.add(pi);
                    dependencies.put(pi, deps);
                    moves.put(pi, paramMoves);
                } else {
                    reasons.put(pi, r);
                }
            }
        }
        propagate();
    }

    private void propagate() {
        boolean changed = true;
        while (changed) {
            changed = false;
            for (ParameterInfo pi : List.copyOf(transferred)) {
                for (ParameterInfo dep : dependencies.get(pi)) {
                    if (!transferred.contains(dep)) {
                        transferred.remove(pi);
                        reasons.put(pi, "the caller's " + dep.name() + " is not handed over");
                        changed = true;
                        break;
                    }
                }
            }
        }
    }

    /**
     * Greatest fixpoint with stage 3: drop the transfers that move a field out of a finalizer when that field is not
     * owned. Returns whether anything changed.
     */
    boolean retainMoves(Predicate<FieldInfo> owned) {
        boolean changed = false;
        for (ParameterInfo pi : List.copyOf(transferred)) {
            for (Move move : moves.getOrDefault(pi, Set.of())) {
                if (!owned.test(move.field())) {
                    transferred.remove(pi);
                    reasons.put(pi, "moves " + move.field().name() + " out of " + move.finalizer().name()
                                    + ", but that field is not owned");
                    changed = true;
                    break;
                }
            }
        }
        if (changed) propagate();
        return changed;
    }

    /** Whether {@code finalizer} hands its {@code field} over to a parameter that transfers. */
    boolean movesOut(MethodInfo finalizer, FieldInfo field) {
        Move move = new Move(finalizer, field);
        return transferred.stream().anyMatch(pi -> moves.get(pi).contains(move));
    }

    /** Whether the transferred {@code pi} receives {@code field}, moved out of a finalizer of its owner. */
    boolean receivesMoved(ParameterInfo pi, FieldInfo field) {
        return transferred.contains(pi) && moves.get(pi).stream().anyMatch(m -> m.field().equals(field));
    }

    /**
     * Why {@code method}'s use of its own {@code field} at {@code use} is not a move out of a dying owner; null when it
     * is: the method is a finalizer of the field's type, mentions the field once, and not in a loop or lambda.
     */
    static String moveOut(MethodInfo method, FieldInfo field, Element use) {
        if (!method.isFinalizer()) return "passes " + field.name() + " outside a finalizer";
        if (field.isStatic() || !field.owner().equals(method.typeInfo())) {
            return "passes " + field.name() + ", not a field of the finalizer's object";
        }
        if (method.methodBody() == null) return "no body";
        if (mentions(method.methodBody(), v -> isThisField(v, field)) != 1) {
            return field.name() + " is used again in " + method.name();
        }
        if (insideLoopOrLambda(method.methodBody(), use)) return "the move may run more than once";
        return null;
    }

    boolean isTransferred(ParameterInfo pi) {
        return transferred.contains(pi);
    }

    String reason(ParameterInfo pi) {
        return reasons.get(pi);
    }

    // rule 1
    private static String callee(MethodInfo callee, Map<MethodInfo, List<Site>> sites, Set<MethodInfo> referenced,
                                 boolean closedWorld, Set<FieldInfo> fields) {
        if (callee.isAbstract() || callee.methodBody() == null) return "no body";
        if (!callee.isConstructor() && !callee.overrides().isEmpty()) return "overrides a method";
        if (!OwnershipPass.implementationsOf(callee).isEmpty()) return "overridden";
        if (referenced.contains(callee)) return "named by a method reference";
        var access = callee.effectiveAccess();
        if (!closedWorld && !access.isPrivate() && !access.isPackage()) return "callable from outside";
        if (!sites.containsKey(callee)) {
            return "no call site in a method body";
        }
        for (FieldInfo f : fields) {
            if (f.initializer() != null && mentionsCall(f.initializer(), callee)) return "called by a field initializer";
        }
        return null;
    }

    private static boolean mentionsCall(Expression expression, MethodInfo callee) {
        boolean[] found = new boolean[1];
        expression.visit((Element e) -> {
            if (e instanceof ConstructorCall cc && callee.equals(cc.constructor())
                || e instanceof MethodCall mc && callee.equals(mc.methodInfo())) {
                found[0] = true;
            }
            return !found[0];
        });
        return found[0];
    }

    // rule 2
    private static String handedOver(Site site, Expression argument, Set<MethodInfo> analysedResults,
                                     Set<MethodInfo> fresh, ThisEscape thisEscape, Set<ParameterInfo> deps,
                                     Set<Move> moves) {
        Expression e = unwrap(argument);
        if (e instanceof VariableExpression ve) {
            if (ve.variable() instanceof ParameterInfo q && q.methodInfo().equals(site.caller())) {
                if (mentions(site.caller().methodBody(), q) != 1) return q.name() + " is used elsewhere in the caller";
                deps.add(q);
                return null;
            }
            if (ve.variable() instanceof LocalVariable lv) return local(site, lv, analysedResults, fresh, thisEscape);
            if (ve.variable() instanceof FieldReference fr && isThisField(fr, fr.fieldInfo())) {
                String r = moveOut(site.caller(), fr.fieldInfo(), site.call());
                if (r != null) return r;
                moves.add(new Move(site.caller(), fr.fieldInfo()));
                return null;
            }
            return "passes " + ve.variable().simpleName() + ", which it does not create";
        }
        Set<MethodInfo> freshDeps = new LinkedHashSet<>();
        String r = new OwnershipPass.Body(Set.of(), List.of(), analysedResults, freshDeps, thisEscape, new HashSet<>())
                .created(e);
        if (r != null) return r;
        for (MethodInfo dep : freshDeps) {
            if (!fresh.contains(dep)) return "passes the result of " + dep.fullyQualifiedName() + ", not fresh";
        }
        return null;
    }

    // a local: the narrow ordering of rule 2
    private static String local(Site site, LocalVariable lv, Set<MethodInfo> analysedResults, Set<MethodInfo> fresh,
                                ThisEscape thisEscape) {
        MethodInfo caller = site.caller();
        Block[] block = new Block[1];
        int[] index = {-1};
        caller.methodBody().visit((Element e) -> {
            if (block[0] != null) return false;
            if (e instanceof Block b) {
                List<Statement> statements = b.statements();
                for (int i = 0; i < statements.size(); i++) {
                    if (statements.get(i) instanceof LocalVariableCreation lvc
                        && lvc.localVariableStream().anyMatch(lv::equals)) {
                        for (int j = i + 1; j < statements.size(); j++) {
                            if (contains(statements.get(j), site.call())) {
                                block[0] = b;
                                index[0] = j;
                                return false;
                            }
                        }
                    }
                }
            }
            return true;
        });
        if (block[0] == null) return lv.simpleName() + " is not declared in the block of the call";
        List<Statement> statements = block[0].statements();
        Statement holding = statements.get(index[0]);
        if (mentions(holding, lv) != 1) return lv.simpleName() + " is mentioned again in the statement of the call";
        if (insideLoopOrLambda(holding, site.call())) return "the call may run more than once";
        for (int j = index[0] + 1; j < statements.size(); j++) {
            if (mentions(statements.get(j), lv) > 0) return lv.simpleName() + " is used after the call";
        }
        // before: assigned created values, or the receiver of calls that keep nothing of it
        Set<MethodInfo> freshDeps = new LinkedHashSet<>();
        OwnershipPass.Body body = new OwnershipPass.Body(Set.of(), List.of(), analysedResults, freshDeps, thisEscape,
                new HashSet<>());
        for (int j = 0; j < index[0]; j++) {
            String r = usesBefore(statements.get(j), lv, body, thisEscape);
            if (r != null) return r;
        }
        for (MethodInfo dep : freshDeps) {
            if (!fresh.contains(dep)) return lv.simpleName() + " holds the result of " + dep.fullyQualifiedName();
        }
        return null;
    }

    private static String usesBefore(Statement statement, LocalVariable lv, OwnershipPass.Body body,
                                     ThisEscape thisEscape) {
        int all = mentions(statement, lv);
        if (all == 0) return null;
        int[] allowed = {0};
        String[] found = new String[1];
        Set<Element> discarded = new HashSet<>();
        statement.visit((Element e) -> {
            if (found[0] != null) return false;
            if (e instanceof ExpressionAsStatement eas) discarded.add(eas.expression());
            if (e instanceof Lambda) {
                if (mentionsIn(e, lv)) found[0] = lv.simpleName() + " is captured";
                return false;
            }
            if (e instanceof LocalVariableCreation lvc && lvc.localVariableStream().anyMatch(lv::equals)) {
                Expression init = lv.assignmentExpression();
                if (init != null && !(init instanceof io.codelaser.maddi.cst.api.expression.EmptyExpression)) {
                    String r = body.created(init);
                    if (r != null) found[0] = lv.simpleName() + " " + r;
                }
            }
            if (e instanceof Assignment a && a.target().variable().equals(lv)) {
                allowed[0]++;
                String r = body.created(a.value());
                if (r != null) found[0] = lv.simpleName() + " " + r;
            }
            if (e instanceof MethodCall mc && unwrap(mc.object()) instanceof VariableExpression ve
                && ve.variable().equals(lv) && mc.methodInfo() != null) {
                if (thisEscape.mayLeak(mc.methodInfo()) || OwnershipPass.mayDispatchElsewhere(mc.methodInfo())) {
                    found[0] = lv.simpleName() + " is the receiver of " + mc.methodInfo().name() + ", which may keep it";
                } else if (!discarded.contains(mc) && !cannotHold(mc.parameterizedType())
                           && !returnsElement(mc.methodInfo())) {
                    // l.subList(..), l.iterator(), l.stream(): the result may still reach l
                    found[0] = lv.simpleName() + " is the receiver of " + mc.methodInfo().name()
                               + ", whose result may hold it";
                } else {
                    allowed[0]++;
                }
            }
            return true;
        });
        if (found[0] != null) return found[0];
        if (all != allowed[0]) return lv.simpleName() + " is used before the call other than as a receiver";
        return null;
    }

    /**
     * Whether the callee keeps {@code pi} in {@code field} only: every link from the parameter goes to that field of
     * {@code this} (or its modification face to the field's), nothing reaches the parameter itself, and nothing keeps
     * it where no link shows (stage 2's hidden verdict).
     */
    static boolean keptOnlyIn(ParameterInfo pi, FieldInfo field, BorrowedParameters borrowedParameters) {
        if (!borrowedParameters.isHidden(pi)) return false;
        MethodLinkedVariables mlv = pi.methodInfo().analysis().getOrNull(MethodLinkedVariablesImpl.METHOD_LINKS,
                MethodLinkedVariablesImpl.class);
        if (mlv == null) return false;
        if (mlv.ofReturnValue().stream().anyMatch(l -> pi.equals(l.to()))) return false;
        boolean intoField = false;
        for (Links links : mlv.ofParameters()) {
            for (Link link : links) {
                if (link.linkNature().isDecoration()) continue;
                if (pi.equals(link.from())) {
                    if (!isThisField(link.to(), field)) return false;
                    intoField = true;
                } else if (pi.equals(link.to())) {
                    return false;
                }
            }
        }
        return intoField;
    }

    static boolean isThisField(Variable variable, FieldInfo field) {
        return variable instanceof FieldReference fr && fr.fieldInfo().equals(field) && !field.isStatic()
               && (fr.scopeVariable() == null || fr.scopeVariable() instanceof This);
    }

    // a primitive, or a deeply immutable value: it cannot reach a mutable object
    private static boolean cannotHold(ParameterizedType type) {
        return type.isPrimitiveExcludingVoid() || type.isVoid()
               || new AnalysisHelper().typeImmutable(type).isImmutable();
    }

    /*
     a method declared to return a type parameter of its type ('V get(Object)', 'E next()') returns an element, hidden
     content of the receiver, not the receiver itself; elements are not judged (stage 3). A method's own type parameter
     ('<T> T as()') can be anything, the receiver included.
     */
    private static boolean returnsElement(MethodInfo methodInfo) {
        ParameterizedType rt = methodInfo.returnType();
        return rt.isTypeParameter() && rt.typeParameter() != null && !rt.typeParameter().isMethodTypeParameter();
    }

    /** Through casts, parentheses and identity methods ({@code Objects.requireNonNull(x)} is {@code x}). */
    static Expression unwrap(Expression expression) {
        Expression e = expression;
        while (true) {
            if (e instanceof Cast c) e = c.expression();
            else if (e instanceof EnclosedExpression ee) e = ee.inner();
            else if (e instanceof MethodCall mc && mc.methodInfo() != null && !mc.parameterExpressions().isEmpty()
                     && mc.methodInfo().analysis().getOrDefault(PropertyImpl.IDENTITY_METHOD,
                    ValueImpl.BoolImpl.FALSE).isTrue()) {
                e = mc.parameterExpressions().getFirst();
            } else return e;
        }
    }

    private static int mentions(Element element, Variable v) {
        return mentions(element, v::equals);
    }

    private static int mentions(Element element, Predicate<Variable> v) {
        if (element == null) return 0;
        int[] n = new int[1];
        element.visit((Element e) -> {
            if (e instanceof VariableExpression ve && v.test(ve.variable())) n[0]++;
            return true;
        });
        return n[0];
    }

    private static boolean mentionsIn(Element element, Variable v) {
        if (element instanceof Lambda lambda) {
            return lambda.methodInfo() == null || lambda.methodInfo().methodBody() == null
                   || mentions(lambda.methodInfo().methodBody(), v) > 0;
        }
        return mentions(element, v) > 0;
    }

    private static boolean contains(Element element, Element target) {
        boolean[] found = new boolean[1];
        element.visit((Element e) -> {
            if (e == target) found[0] = true;
            return !found[0];
        });
        return found[0];
    }

    private static boolean insideLoopOrLambda(Statement statement, Element call) {
        boolean[] inside = new boolean[1];
        statement.visit((Element e) -> {
            if (inside[0]) return false;
            if ((e instanceof LoopStatement || e instanceof Lambda) && contains(e, call)) {
                inside[0] = true;
                return false;
            }
            return true;
        });
        return inside[0];
    }
}
