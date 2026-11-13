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
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.codelaser.maddi.cst.api.element.Element;
import io.codelaser.maddi.cst.api.expression.Assignment;
import io.codelaser.maddi.cst.api.expression.Cast;
import io.codelaser.maddi.cst.api.expression.ConstructorCall;
import io.codelaser.maddi.cst.api.expression.EmptyExpression;
import io.codelaser.maddi.cst.api.expression.EnclosedExpression;
import io.codelaser.maddi.cst.api.expression.Expression;
import io.codelaser.maddi.cst.api.expression.ExpressionWrapper;
import io.codelaser.maddi.cst.api.expression.InlineConditional;
import io.codelaser.maddi.cst.api.expression.Lambda;
import io.codelaser.maddi.cst.api.expression.MethodCall;
import io.codelaser.maddi.cst.api.expression.NullConstant;
import io.codelaser.maddi.cst.api.expression.VariableExpression;
import io.codelaser.maddi.cst.api.info.FieldInfo;
import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.ParameterInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.api.statement.Block;
import io.codelaser.maddi.cst.api.statement.LocalVariableCreation;
import io.codelaser.maddi.cst.api.statement.ReturnStatement;
import io.codelaser.maddi.cst.api.statement.Statement;
import io.codelaser.maddi.cst.api.variable.FieldReference;
import io.codelaser.maddi.cst.api.variable.LocalVariable;
import io.codelaser.maddi.cst.api.variable.Variable;
import io.codelaser.maddi.cst.impl.analysis.PropertyImpl;
import io.codelaser.maddi.cst.impl.analysis.ValueImpl;
import io.codelaser.maddi.modification.link.impl.LinkNatureImpl;
import io.codelaser.maddi.modification.link.impl.MethodLinkedVariablesImpl;
import io.codelaser.maddi.modification.prepwork.variable.Link;
import io.codelaser.maddi.modification.prepwork.variable.MethodLinkedVariables;
import io.codelaser.maddi.modification.prepwork.variable.ObjectCreationVariable;
import io.codelaser.maddi.modification.prepwork.variable.ReturnVariable;

/**
 * The ownership pass (docs/roadmap/ownership.md): one-shot, after the analysis has converged, over the link
 * summaries it left and the method bodies. It adds properties and never changes one the iteration wrote; nothing in
 * the iteration reads what it writes.
 *
 * <h2>{@code FRESH_METHOD}</h2>
 * A method is fresh when every object it returns was created during the call, or is {@code null}, and nothing
 * outside the call holds it afterwards. Every part of that needs <b>positive</b> evidence; the absence of links is
 * never evidence. A library call without a summary has no links at all: {@code Collections.emptyList()} returns a
 * singleton, {@code List.copyOf(l)} may return its argument.
 * <ol>
 *   <li><b>Created, on every path.</b> Every returned expression, read from the body, is a construction, {@code null},
 *   a call to a fresh method, or a local declared in the body and assigned only such values; through casts,
 *   parentheses and both branches of a conditional. Read per path because the link summary cannot tell paths
 *   apart: in {@code b ? new ArrayList<>() : Collections.emptyList()} the creation marker of one branch says
 *   nothing about the other, which has no link.</li>
 *   <li><b>Held nowhere else.</b> Every link from the return variable itself (not from a field of it:
 *   {@code copy.§$s ⊆ 0:l.§$s} says what the new object holds) is an assignment from, or identity with, a creation
 *   marker {@code oc:N} or the {@code null} marker {@code $_ce}. A link to a field, a parameter or a static, or from
 *   the result into one ({@code ret → this.stored}, {@code ret ∈ 0:sink.§$s}), means the object is, or is also,
 *   held elsewhere.</li>
 *   <li><b>Dispatch.</b> A call through a method may run an override, so a method is fresh only when all its
 *   implementations ({@code IMPLEMENTATIONS}, source overrides included since CodeLaser/maddi-mod#84) are; the
 *   fresh callees of rule 1 are fresh on the same terms.</li>
 *   <li><b>Summaries that are not the body that runs.</b> The links of rule 2 come from the callees' summaries, and
 *   a summary is the declared callee's. A local handed to a source abstract method, a library abstract method with
 *   source implementations, or a concrete method that sources override, may be kept by the body that runs: such a
 *   call, with a local as receiver or argument, refuses the method. (An abstract method's summary relates the
 *   argument's contents to the receiver, {@code 0:l.§m ≡ this.§m}, never the argument itself, even when the fold
 *   over its implementations knows it is {@code @Dependent}.) Stage 2's borrowed parameters can relax this.</li>
 *   <li>A degraded method (its summary is the shallow one) is not fresh.</li>
 * </ol>
 * A method of the analysed sources is decided by these rules; a {@code @Fresh} contract decides a bodiless method
 * (a library method through its hints, a source abstract method through its annotation). Rules 1 and 3 are decided
 * as a greatest fixpoint: every candidate starts fresh, and one whose dependency is not fresh is removed until
 * nothing changes. Recursion keeps its freshness, which is sound because every member's results are, path by path,
 * constructions, nulls or results of members.
 * <p>
 * Rule 2 needs the links of {@code trackObjectCreations} (the creation markers); the configuration turns it on.
 */
public final class OwnershipPass {
    private static final Logger LOGGER = LoggerFactory.getLogger(OwnershipPass.class);
    private static final String FRESH_ANNOTATION = "io.codelaser.maddi.annotation.Fresh";

    /** Stage 3's verdict on a field: OWNED_FIELD, SHARED_IMMUTABLE_FIELD, or neither. */
    public enum Owned {OWNED, SHARED_IMMUTABLE, NOT_OWNED}

    /** The verdicts, and for one that is negative, the first reason found (the census's worklist). */
    public record Report(Map<MethodInfo, Boolean> fresh, Map<MethodInfo, String> notFreshReason,
                         Map<ParameterInfo, Boolean> borrowed, Map<ParameterInfo, String> notBorrowedReason,
                         Map<FieldInfo, Owned> owned, Map<FieldInfo, String> notOwnedReason) {
    }

    private final boolean closedWorld;

    public OwnershipPass() {
        this(false);
    }

    /** @param closedWorld stage 5: every public callee's call sites are in the analysed sources */
    public OwnershipPass(boolean closedWorld) {
        this.closedWorld = closedWorld;
    }

    public Report go(List<Info> analysisOrder) {
        Set<MethodInfo> methods = methods(analysisOrder);
        Set<MethodInfo> all = allMethodsAndConstructors(analysisOrder);
        ThisEscape thisEscape = new ThisEscape(all);
        BorrowedParameters borrowedParameters = new BorrowedParameters(all, thisEscape);
        Map<MethodInfo, String> reason = new LinkedHashMap<>();
        Map<MethodInfo, Set<MethodInfo>> dependencies = new LinkedHashMap<>();
        Set<MethodInfo> fresh = new LinkedHashSet<>();
        for (MethodInfo methodInfo : methods) {
            Set<MethodInfo> deps = new LinkedHashSet<>();
            String r = candidate(methodInfo, methods, deps, thisEscape, borrowedParameters);
            if (r == null) {
                fresh.add(methodInfo);
                dependencies.put(methodInfo, deps);
            } else {
                reason.put(methodInfo, r);
            }
        }
        boolean changed = true;
        while (changed) {
            changed = false;
            for (MethodInfo methodInfo : List.copyOf(fresh)) {
                for (MethodInfo dependency : dependencies.get(methodInfo)) {
                    if (!fresh.contains(dependency)) {
                        fresh.remove(methodInfo);
                        reason.put(methodInfo, (implementations(methodInfo).contains(dependency) ? "implementation "
                                : "callee ") + dependency.fullyQualifiedName() + " is not fresh");
                        changed = true;
                        break;
                    }
                }
            }
        }
        Map<MethodInfo, Boolean> verdicts = new LinkedHashMap<>();
        for (MethodInfo methodInfo : methods) verdicts.put(methodInfo, fresh.contains(methodInfo));
        Transfers transfers = new Transfers(all, methods, fresh, thisEscape, closedWorld);
        OwnedFields ownedFields = new OwnedFields(analysisOrder, methods, fresh, thisEscape, borrowedParameters,
                transfers);
        // a move out of a finalizer needs the moved field owned, and that field is owned because it moves: greatest
        // fixpoint
        while (true) {
            OwnedFields current = ownedFields;
            if (!transfers.retainMoves(f -> current.verdicts().get(f) == Owned.OWNED)) break;
            ownedFields = new OwnedFields(analysisOrder, methods, fresh, thisEscape, borrowedParameters, transfers);
        }
        long nBorrowed = borrowedParameters.verdicts().values().stream().filter(b -> b).count();
        Map<Owned, Long> byOwned = new java.util.EnumMap<>(Owned.class);
        ownedFields.verdicts().values().forEach(o -> byOwned.merge(o, 1L, Long::sum));
        LOGGER.info("Ownership pass: {} method(s) with a reference result, {} fresh; {} parameter(s), {} borrowed; "
                    + "{} field(s): {}", methods.size(), fresh.size(), borrowedParameters.verdicts().size(), nBorrowed,
                ownedFields.verdicts().size(), byOwned);
        return new Report(verdicts, reason, borrowedParameters.verdicts(), borrowedParameters.reasons(),
                ownedFields.verdicts(), ownedFields.reasons());
    }

    /**
     * The census (docs/roadmap/ownership.md stage 4): one line per verdict, {@code kind, element, verdict, reason};
     * the reason is the first rule that failed, the worklist of a negative verdict.
     */
    public static void writeTsv(Report report, java.nio.file.Path path) {
        try (java.io.PrintWriter pw = new java.io.PrintWriter(java.nio.file.Files.newBufferedWriter(path))) {
            pw.println("kind\telement\tverdict\treason");
            report.fresh().forEach((m, b) -> pw.println("fresh\t" + m.fullyQualifiedName() + "\t" + b + "\t"
                                                        + report.notFreshReason().getOrDefault(m, "")));
            report.borrowed().forEach((p, b) -> pw.println("borrowed\t" + p.methodInfo().fullyQualifiedName() + ":"
                                                           + p.index() + ":" + p.name() + "\t" + b + "\t"
                                                           + report.notBorrowedReason().getOrDefault(p, "")));
            report.owned().forEach((f, o) -> pw.println("owned\t" + f.fullyQualifiedName() + "\t" + o + "\t"
                                                        + report.notOwnedReason().getOrDefault(f, "")));
        } catch (java.io.IOException e) {
            LOGGER.error("Ownership census: cannot write {}", path, e);
        }
    }

    public static void write(Report report) {
        report.fresh().forEach((methodInfo, fresh) -> {
            // a contract was written by the shallow analyzer and is not second-guessed
            if (!isContracted(methodInfo)) {
                methodInfo.analysis().setAllowControlledOverwrite(PropertyImpl.FRESH_METHOD,
                        ValueImpl.BoolImpl.from(fresh));
            }
        });
        report.owned().forEach((fi, owned) -> {
            fi.analysis().setAllowControlledOverwrite(PropertyImpl.OWNED_FIELD,
                    ValueImpl.BoolImpl.from(owned == Owned.OWNED));
            fi.analysis().setAllowControlledOverwrite(PropertyImpl.SHARED_IMMUTABLE_FIELD,
                    ValueImpl.BoolImpl.from(owned == Owned.SHARED_IMMUTABLE));
        });
        report.borrowed().forEach((pi, borrowed) -> {
            if (!BorrowedParameters.isContracted(pi)) {
                pi.analysis().setAllowControlledOverwrite(PropertyImpl.BORROWED_PARAMETER,
                        ValueImpl.BoolImpl.from(borrowed));
            }
        });
    }

    // the methods the property is about: a result that is an object
    private static Set<MethodInfo> methods(List<Info> analysisOrder) {
        Set<MethodInfo> methods = new LinkedHashSet<>();
        for (Info info : analysisOrder) {
            if (info instanceof MethodInfo methodInfo) {
                if (applies(methodInfo)) methods.add(methodInfo);
            } else if (info instanceof TypeInfo typeInfo) {
                // an order may list types only (tests); their methods are what the property is about
                typeInfo.methodStream().filter(OwnershipPass::applies).forEach(methods::add);
            }
        }
        return methods;
    }

    private static Set<MethodInfo> allMethodsAndConstructors(List<Info> analysisOrder) {
        Set<MethodInfo> set = new LinkedHashSet<>();
        for (Info info : analysisOrder) {
            if (info instanceof MethodInfo methodInfo) set.add(methodInfo);
            else if (info instanceof TypeInfo typeInfo) typeInfo.constructorAndMethodStream().forEach(set::add);
        }
        return set;
    }

    private static boolean applies(MethodInfo methodInfo) {
        return !methodInfo.isConstructor() && !methodInfo.isVoid()
               && !methodInfo.returnType().isPrimitiveExcludingVoid();
    }

    // a contract decides a bodiless method; in the analysed sources, that is an abstract one
    private static boolean isContracted(MethodInfo methodInfo) {
        return methodInfo.isAbstract() && methodInfo.annotations().stream()
                .anyMatch(ae -> FRESH_ANNOTATION.equals(ae.typeInfo().fullyQualifiedName()));
    }

    /*
     Null when the method is a candidate; then its dependencies are in 'deps'.
     */
    private static String candidate(MethodInfo methodInfo, Set<MethodInfo> analysed, Set<MethodInfo> deps,
                                    ThisEscape thisEscape, BorrowedParameters borrowedParameters) {
        if (isContracted(methodInfo)) {
            return methodInfo.analysis().getOrDefault(PropertyImpl.FRESH_METHOD, ValueImpl.BoolImpl.FALSE).isTrue()
                    ? null : "contract";
        }
        if (methodInfo.analysis().getOrDefault(PropertyImpl.DEGRADED_ANALYSIS_METHOD, ValueImpl.BoolImpl.FALSE)
                .isTrue()) {
            return "degraded";
        }
        deps.addAll(implementations(methodInfo)); // rule 3
        if (methodInfo.isAbstract()) {
            return deps.isEmpty() ? "abstract, no implementation" : null;
        }
        // rule 1
        String created = createdOnEveryPath(methodInfo, analysed, deps, thisEscape);
        if (created != null) return created;
        // rule 2
        MethodLinkedVariables mlv = methodInfo.analysis().getOrNull(MethodLinkedVariablesImpl.METHOD_LINKS,
                MethodLinkedVariablesImpl.class);
        if (mlv == null) return "no summary";
        for (Link link : mlv.ofReturnValue()) {
            if (link.linkNature().isDecoration() || !(link.from() instanceof ReturnVariable)) continue;
            boolean assignedFrom = link.linkNature() == LinkNatureImpl.IS_ASSIGNED_FROM
                                   || link.linkNature().isIdenticalTo();
            if (!assignedFrom || !(link.to() instanceof ObjectCreationVariable || isNullConstant(link.to()))) {
                return "result " + link.linkNature() + " " + link.to();
            }
        }
        // rule 4
        return localHandedToBodyThatMayKeepIt(methodInfo, thisEscape, borrowedParameters);
    }

    // ---- rule 1

    private static String createdOnEveryPath(MethodInfo methodInfo, Set<MethodInfo> analysed, Set<MethodInfo> deps,
                                             ThisEscape thisEscape) {
        if (methodInfo.methodBody() == null) return "no body";
        List<Expression> returned = new ArrayList<>();
        methodInfo.methodBody().visit((Element e) -> {
            if (e instanceof Lambda) return false; // its returns are its own
            if (e instanceof ReturnStatement rs && rs.expression() != null) returned.add(rs.expression());
            return true;
        });
        if (returned.isEmpty()) return "no return"; // throws on every path: nothing to say
        Body body = Body.of(methodInfo, analysed, deps, thisEscape);
        for (Expression expression : returned) {
            String r = body.created(expression);
            if (r != null) return r;
        }
        return null;
    }

    /*
     Rule 1's evidence for one expression of one body: null when it is created (or null) on every path, else why not.
     The analysed callees it rests on go to 'deps'. Also used for the values assigned to a field (stage 3).
     */
    record Body(Set<LocalVariable> plainLocals, List<Assignment> assignments, Set<MethodInfo> analysed,
                Set<MethodInfo> deps, ThisEscape thisEscape, Set<LocalVariable> visiting) {

        static Body of(MethodInfo methodInfo, Set<MethodInfo> analysed, Set<MethodInfo> deps, ThisEscape thisEscape) {
            Set<LocalVariable> plainLocals = new HashSet<>();
            List<Assignment> assignments = new ArrayList<>();
            if (methodInfo.methodBody() != null) {
                methodInfo.methodBody().visit((Element e) -> {
                    if (e instanceof Lambda) return false; // its locals are its own
                    if (e instanceof Block block) {
                        // a local declared by a statement of a block; not a for-each, catch or resource variable,
                        // whose value is assigned by the statement that declares it
                        for (Statement s : block.statements()) {
                            if (s instanceof LocalVariableCreation lvc) lvc.localVariableStream().forEach(plainLocals::add);
                        }
                    }
                    if (e instanceof Assignment a) assignments.add(a);
                    return true;
                });
            }
            return new Body(plainLocals, assignments, analysed, deps, thisEscape, new HashSet<>());
        }

        String created(Expression expression) {
            Expression e = unwrap(expression);
            if (e instanceof NullConstant) return null;
            if (e instanceof ConstructorCall cc) {
                // a constructor that leaves 'this' held elsewhere (a captured lambda registered on a bus): no link
                // says so when the capture is indirect
                if (cc.constructor() != null && cc.anonymousClass() == null && thisEscape.mayLeak(cc.constructor())) {
                    return "constructor " + cc.constructor().fullyQualifiedName() + " may leak this: "
                           + thisEscape.why(cc.constructor());
                }
                return null;
            }
            if (e instanceof InlineConditional ic) {
                String r = created(ic.ifTrue());
                return r != null ? r : created(ic.ifFalse());
            }
            if (e instanceof MethodCall mc) {
                MethodInfo callee = mc.methodInfo();
                if (callee == null) return "returns an unresolved call";
                if (analysed.contains(callee)) {
                    deps.add(callee);
                    return null;
                }
                // outside the analysed sources: a contract (the hints), or a value from an earlier run
                return freshOutsideSources(callee)
                        ? null : "returns " + callee.fullyQualifiedName() + ", not known to be fresh";
            }
            if (e instanceof VariableExpression ve && ve.variable() instanceof LocalVariable lv) {
                if (!plainLocals.contains(lv)) return "returns " + lv.simpleName() + ", assigned where it is declared";
                if (!visiting.add(lv)) return null; // a cycle of local assignments: its other values decide
                Expression init = lv.assignmentExpression();
                if (init != null && !(init instanceof EmptyExpression)) {
                    String r = created(init);
                    if (r != null) return r;
                }
                for (Assignment a : assignments) {
                    if (a.target().variable().equals(lv)) {
                        String r = created(a.value());
                        if (r != null) return r;
                    }
                }
                return null;
            }
            return "returns " + e.getClass().getSimpleName().replace("Impl", "") + " " + e;
        }

        /*
         A contract binds every override: List.toArray() is fresh because Collection.toArray() is. An explicit
         @Fresh(absent = true) on the callee itself wins.
         */
        private static boolean freshOutsideSources(MethodInfo callee) {
            if (callee.analysis().haveAnalyzedValueFor(PropertyImpl.FRESH_METHOD)) {
                return callee.analysis().getOrDefault(PropertyImpl.FRESH_METHOD, ValueImpl.BoolImpl.FALSE).isTrue();
            }
            return callee.overrides().stream().anyMatch(o ->
                    o.analysis().getOrDefault(PropertyImpl.FRESH_METHOD, ValueImpl.BoolImpl.FALSE).isTrue());
        }

        private static Expression unwrap(Expression expression) {
            Expression e = expression;
            while (true) {
                if (e instanceof Cast c) e = c.expression();
                else if (e instanceof EnclosedExpression ee) e = ee.inner();
                else if (e instanceof ExpressionWrapper w) e = w.expression();
                else if (e instanceof Assignment a) e = a.value();
                else return e;
            }
        }
    }

    // ---- rule 2

    // a constant marker ('$_ceN', the link module's MarkerVariable, seen through the API) holding the null constant;
    // as the nullability pass's MethodWalker.isNullMarker
    static boolean isNullConstant(Variable variable) {
        return variable instanceof LocalVariable lv && lv.simpleName().startsWith("$_ce")
               && lv.assignmentExpression() instanceof NullConstant;
    }

    // ---- rule 4

    /*
     A local handed to a body that may keep it where no link shows: as an argument to a method or constructor whose
     parameter is not borrowed (a callee may capture it; stage 2 decides), or as the receiver of a method that may
     leave 'this' held elsewhere (the summaries have no links for 'this'), or of one that may dispatch to another body.
     */
    static String localHandedToBodyThatMayKeepIt(MethodInfo methodInfo, ThisEscape thisEscape,
                                                         BorrowedParameters borrowedParameters) {
        String[] found = new String[1];
        methodInfo.methodBody().visit((Element e) -> {
            if (found[0] != null || e instanceof Lambda) return false;
            MethodInfo callee = e instanceof MethodCall mc ? mc.methodInfo()
                    : e instanceof ConstructorCall cc && cc.anonymousClass() == null ? cc.constructor() : null;
            if (callee != null) {
                List<Expression> arguments = e instanceof MethodCall mc ? mc.parameterExpressions()
                        : ((ConstructorCall) e).parameterExpressions();
                if (e instanceof MethodCall mc && isLocal(mc.object())
                    && (thisEscape.mayLeak(callee) || mayDispatchElsewhere(callee))) {
                    found[0] = "local is the receiver of " + callee.fullyQualifiedName() + ", which may keep it";
                    return false;
                }
                for (int i = 0; i < arguments.size(); i++) {
                    if (isLocal(arguments.get(i))) {
                        String kept = borrowedParameters.keptByCallee(callee, i);
                        if (kept != null) {
                            found[0] = "local " + kept;
                            return false;
                        }
                    }
                }
            }
            return true;
        });
        return found[0];
    }

    /*
     A source abstract method: any implementation, seen or not. A library abstract method: its source implementations
     (a library method without them is described by its hints). A concrete method: its source overrides.
     */
    static boolean mayDispatchElsewhere(MethodInfo callee) {
        if (callee.isAbstract()) {
            if (!callee.typeInfo().compilationUnit().externalLibrary()) return true;
            return !callee.analysis().getOrDefault(PropertyImpl.EXTERNAL_IMPLEMENTATIONS,
                    ValueImpl.SetOfMethodInfoImpl.EMPTY).isEmpty();
        }
        return !implementations(callee).isEmpty();
    }

    private static boolean isLocal(Expression expression) {
        if (!(expression instanceof VariableExpression ve)) return false;
        Variable v = ve.variable();
        while (v instanceof FieldReference fr && fr.scopeVariable() != null) v = fr.scopeVariable();
        return v instanceof LocalVariable;
    }

    private static List<MethodInfo> implementations(MethodInfo methodInfo) {
        return List.copyOf(implementationsOf(methodInfo));
    }

    static Set<MethodInfo> implementationsOf(MethodInfo methodInfo) {
        Set<MethodInfo> set = new LinkedHashSet<>();
        methodInfo.analysis().getOrDefault(PropertyImpl.IMPLEMENTATIONS, ValueImpl.SetOfMethodInfoImpl.EMPTY)
                .methodInfoSet().forEach(set::add);
        return set;
    }

    static Set<MethodInfo> externalImplementationsOf(MethodInfo methodInfo) {
        Set<MethodInfo> set = new LinkedHashSet<>();
        methodInfo.analysis().getOrDefault(PropertyImpl.EXTERNAL_IMPLEMENTATIONS, ValueImpl.SetOfMethodInfoImpl.EMPTY)
                .methodInfoSet().forEach(set::add);
        return set;
    }
}
