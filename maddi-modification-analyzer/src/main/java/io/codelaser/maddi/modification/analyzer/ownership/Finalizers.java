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

import io.codelaser.maddi.cst.api.analysis.Message;
import io.codelaser.maddi.cst.api.element.Element;
import io.codelaser.maddi.cst.api.expression.ConstructorCall;
import io.codelaser.maddi.cst.api.expression.Expression;
import io.codelaser.maddi.cst.api.expression.MethodCall;
import io.codelaser.maddi.cst.api.expression.MethodReference;
import io.codelaser.maddi.cst.api.expression.VariableExpression;
import io.codelaser.maddi.cst.api.info.FieldInfo;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.ParameterInfo;
import io.codelaser.maddi.cst.api.statement.ReturnStatement;
import io.codelaser.maddi.cst.api.variable.FieldReference;
import io.codelaser.maddi.cst.api.variable.LocalVariable;
import io.codelaser.maddi.cst.api.variable.This;
import io.codelaser.maddi.cst.impl.analysis.MessageImpl;

/**
 * {@code @Finalizer}, computed and checked (docs/roadmap/ownership.md stage 6). A finalizer is transfer of the
 * receiver: after the call nothing uses the object.
 * <p>
 * <b>Computed</b> ({@code FINALIZER_METHOD}) when the method both needs and may be one:
 * <ul>
 *   <li>need: it moves an owned field of its object out, returning {@code this.f} or handing it over to a parameter
 *   that transfers (stage 5's moves), mentioning the field once, outside loops and lambdas;</li>
 *   <li>permission: stage 5's rule 1 (every call site known: private or package-private, or a closed world; it
 *   overrides nothing, nothing overrides it, no method reference names it, no field initializer calls it), and at
 *   every call site a receiver that is handed over and was never shared: a construction whose constructor does not
 *   leak {@code this}, a fresh call, a fluent chain on one, a local in stage 5's sense, or {@code this.f} inside a
 *   finalizer of {@code f}'s owner, {@code f} owned. Never {@code this}, a parameter, or another object's field.</li>
 * </ul>
 * Call-site evidence alone would be vacuous (any method called once on a temporary). Greatest fixpoint with stage 3
 * and stage 5's moves: the moved field is owned only because the method that moves it is a finalizer.
 * <p>
 * <b>Checked</b>, for the contracted ones (source annotations, the annotated APIs: the stream operations), at every
 * call site in the analysed sources, for the use after the call only: a temporary is used once; a local not after the
 * call's statement (a rebinding {@code s = s.filter(p)} is no use after); a parameter only when it transfers (stage
 * 5: every caller hands its argument over; the book's rule 3, with Rust's by-value parameter as the exception);
 * {@code this.f} only inside a finalizer of its owner (rule 2); {@code this} only inside a finalizer. Each violation is
 * a warning of category {@value #CATEGORY}. The modification exemption of a finalizer call (it never modifies its
 * receiver) relies on the contract, so a violation also marks where that exemption is not sound.
 */
final class Finalizers {

    static final String CATEGORY = "finalizer-violation";

    record Site(MethodInfo caller, MethodCall call) {
    }

    // a call with arguments, of a method or a constructor
    private record ArgSite(MethodInfo caller, List<Expression> arguments, Element call) {
    }

    private final Map<MethodInfo, List<ArgSite>> argSites = new LinkedHashMap<>();
    private final Set<MethodInfo> referenced = new HashSet<>();
    private final boolean closedWorld;

    private final Set<MethodInfo> analysedResults;
    private final Set<MethodInfo> fresh;
    private final ThisEscape thisEscape;
    private final Map<MethodInfo, List<Site>> sites = new LinkedHashMap<>();
    private final Set<MethodInfo> computed = new LinkedHashSet<>();
    private final Map<MethodInfo, String> reasons = new LinkedHashMap<>();
    private final Map<MethodInfo, Set<FieldInfo>> needs = new LinkedHashMap<>();
    private final Map<MethodInfo, Set<MethodInfo>> finalizerDeps = new LinkedHashMap<>();
    private final Map<MethodInfo, Set<FieldInfo>> fieldDeps = new LinkedHashMap<>();
    private final List<Message> violations = new ArrayList<>();
    private final List<String[]> violationRows = new ArrayList<>();

    Finalizers(Set<MethodInfo> analysed, Set<MethodInfo> analysedResults, Set<MethodInfo> fresh, ThisEscape thisEscape,
               boolean closedWorld) {
        this.analysedResults = analysedResults;
        this.fresh = fresh;
        this.thisEscape = thisEscape;
        this.closedWorld = closedWorld;
        for (MethodInfo caller : analysed) {
            if (caller.methodBody() == null) continue;
            caller.methodBody().visit((Element e) -> {
                if (e instanceof MethodReference mr && mr.methodInfo() != null) referenced.add(mr.methodInfo());
                if (e instanceof MethodCall mc && mc.methodInfo() != null && !mc.methodInfo().isStatic()
                    && mc.object() != null) {
                    sites.computeIfAbsent(mc.methodInfo(), _ -> new ArrayList<>()).add(new Site(caller, mc));
                }
                if (e instanceof MethodCall mc && mc.methodInfo() != null) {
                    argSites.computeIfAbsent(mc.methodInfo(), _ -> new ArrayList<>())
                            .add(new ArgSite(caller, mc.parameterExpressions(), mc));
                }
                if (e instanceof ConstructorCall cc && cc.constructor() != null) {
                    argSites.computeIfAbsent(cc.constructor(), _ -> new ArrayList<>())
                            .add(new ArgSite(caller, cc.parameterExpressions(), cc));
                }
                return true;
            });
        }
        Set<FieldInfo> fieldsWithInitializers = new LinkedHashSet<>();
        analysed.forEach(m -> m.typeInfo().fields().forEach(f -> {
            if (f.initializer() != null && !f.initializer().isEmpty()) fieldsWithInitializers.add(f);
        }));
        for (MethodInfo m : analysed) {
            if (m.isFinalizer() || m.isConstructor() || m.isStatic() || m.methodBody() == null) continue;
            Set<FieldInfo> need = need(m);
            if (!need.isEmpty()) needs.put(m, need);
        }
        // optimistic: every candidate a finalizer while the receivers are judged ('this.f' in a candidate); a
        // receiver depending on a candidate that falls is undone by retain
        computed.addAll(needs.keySet());
        for (MethodInfo m : needs.keySet()) {
            String r = permission(m, fieldsWithInitializers);
            if (r != null) {
                computed.remove(m);
                reasons.put(m, r);
            }
        }
    }

    boolean isFinalizer(MethodInfo m) {
        return m.isFinalizer() || computed.contains(m);
    }

    // ---- need

    // the fields of m's own object that m would move out: returned, or passed as an argument, once
    private static Set<FieldInfo> need(MethodInfo m) {
        Set<FieldInfo> fields = new LinkedHashSet<>();
        m.methodBody().visit((Element e) -> {
            if (e instanceof ReturnStatement rs && rs.expression() != null) {
                FieldInfo f = ownField(rs.expression(), m);
                if (f != null && Transfers.moveOut(m, f, rs, true) == null) fields.add(f);
            }
            List<Expression> arguments = e instanceof MethodCall mc ? mc.parameterExpressions()
                    : e instanceof ConstructorCall cc ? cc.parameterExpressions() : List.of();
            for (Expression a : arguments) {
                FieldInfo f = ownField(a, m);
                if (f != null && Transfers.moveOut(m, f, e, true) == null) fields.add(f);
            }
            return true;
        });
        return fields;
    }

    private static FieldInfo ownField(Expression expression, MethodInfo m) {
        if (Transfers.unwrap(expression) instanceof VariableExpression ve && ve.variable() instanceof FieldReference fr
            && Transfers.isThisField(fr, fr.fieldInfo()) && fr.fieldInfo().owner().equals(m.typeInfo())
            // a field that can be owned (OwnedFields judges no primitive)
            && !fr.fieldInfo().type().isPrimitiveExcludingVoid()) {
            return fr.fieldInfo();
        }
        return null;
    }

    // ---- permission

    private String permission(MethodInfo m, Set<FieldInfo> fieldsWithInitializers) {
        if (m.isAbstract()) return "abstract";
        if (!m.overrides().isEmpty()) return "overrides a method";
        if (!OwnershipPass.implementationsOf(m).isEmpty()) return "overridden";
        if (referenced.contains(m)) return "named by a method reference";
        var access = m.effectiveAccess();
        if (!closedWorld && !access.isPrivate() && !access.isPackage()) return "callable from outside";
        List<Site> list = sites.get(m);
        if (list == null) return "no call site in a method body";
        for (FieldInfo f : fieldsWithInitializers) {
            if (mentionsCall(f.initializer(), m)) return "called by a field initializer";
        }
        Set<MethodInfo> fDeps = new LinkedHashSet<>();
        Set<FieldInfo> oDeps = new LinkedHashSet<>();
        for (Site site : list) {
            String r = receiver(site, true, fDeps, oDeps);
            if (r != null) return "at a call in " + site.caller().fullyQualifiedName() + ": " + r;
        }
        finalizerDeps.put(m, fDeps);
        fieldDeps.put(m, oDeps);
        return null;
    }

    private static boolean mentionsCall(Expression expression, MethodInfo callee) {
        boolean[] found = new boolean[1];
        expression.visit((Element e) -> {
            if (e instanceof MethodCall mc && callee.equals(mc.methodInfo())) found[0] = true;
            return !found[0];
        });
        return found[0];
    }

    /**
     * Why the receiver of {@code site} is not handed over; null when it is. {@code compute}: also never shared before
     * (stage 6's permission); otherwise only the use after (the check of a contract).
     */
    private String receiver(Site site, boolean compute, Set<MethodInfo> fDeps, Set<FieldInfo> oDeps) {
        MethodInfo caller = site.caller();
        Expression object = Transfers.unwrap(site.call().object());
        Expression root = object;
        while (root instanceof MethodCall mc && mc.methodInfo() != null && mc.methodInfo().isFluent()
               && !mc.methodInfo().isStatic() && mc.object() != null) {
            if (compute && (thisEscape.mayLeak(mc.methodInfo()) || OwnershipPass.mayDispatchElsewhere(mc.methodInfo()))) {
                return "the fluent " + mc.methodInfo().name() + " may keep the receiver";
            }
            root = Transfers.unwrap(mc.object());
        }
        if (root instanceof VariableExpression ve) {
            switch (ve.variable()) {
                case This _ -> {
                    if (compute) return "called on this";
                    return isFinalizer(caller) ? null : "called on this, outside a finalizer";
                }
                case ParameterInfo pi when pi.methodInfo().equals(caller) -> {
                    return "called on the parameter " + pi.name();
                }
                case LocalVariable lv -> {
                    return Transfers.local(caller, site.call(), lv, analysedResults, fresh, thisEscape, compute);
                }
                case FieldReference fr when Transfers.isThisField(fr, fr.fieldInfo())
                                            && fr.fieldInfo().owner().equals(caller.typeInfo()) -> {
                    String r = Transfers.moveOut(caller, fr.fieldInfo(), site.call(), isFinalizer(caller));
                    if (r != null) return r;
                    if (compute) {
                        if (!caller.isFinalizer()) fDeps.add(caller);
                        oDeps.add(fr.fieldInfo());
                    }
                    return null;
                }
                default -> {
                    return "called on " + ve.variable().simpleName() + ", which is not its own";
                }
            }
        }
        if (!compute) return null; // a temporary: used once
        Set<MethodInfo> freshDeps = new LinkedHashSet<>();
        String r = new OwnershipPass.Body(Set.of(), List.of(), analysedResults, freshDeps, thisEscape, new HashSet<>())
                .created(root);
        if (r != null) return "the receiver " + r;
        for (MethodInfo dep : freshDeps) {
            if (!fresh.contains(dep)) return "the receiver is the result of " + dep.fullyQualifiedName() + ", not fresh";
        }
        return null;
    }

    // ---- the fixpoint with stage 3 and stage 5's moves

    /**
     * Drops the computed finalizers whose need or permission no longer holds: no moved field is owned (or moved out
     * any more), a field a receiver depends on is not owned, a finalizer a receiver depends on is dropped.
     */
    boolean retain(Predicate<FieldInfo> owned, Transfers transfers) {
        boolean any = false;
        boolean changed = true;
        while (changed) {
            changed = false;
            for (MethodInfo m : List.copyOf(computed)) {
                String r = null;
                if (needs.get(m).stream().noneMatch(f -> owned.test(f) && OwnedFields.movesOut(m, f, transfers))) {
                    r = "moves out no owned field";
                } else {
                    for (FieldInfo f : fieldDeps.get(m)) {
                        if (!owned.test(f)) r = "a receiver is " + f.name() + ", which is not owned";
                    }
                    for (MethodInfo d : finalizerDeps.get(m)) {
                        if (!isFinalizer(d)) r = "a receiver is a field moved out of " + d.name() + ", not a finalizer";
                    }
                }
                if (r != null) {
                    computed.remove(m);
                    reasons.put(m, r);
                    changed = true;
                    any = true;
                }
            }
        }
        return any;
    }

    // ---- the check of the contracts

    /** Every call of a contracted finalizer in the analysed sources, its receiver's use after the call. */
    void check() {
        for (Map.Entry<MethodInfo, List<Site>> entry : sites.entrySet()) {
            MethodInfo finalizer = entry.getKey();
            if (!finalizer.isFinalizer()) continue;
            for (Site site : entry.getValue()) {
                String r = receiver(site, false, new HashSet<>(), new HashSet<>());
                if (r != null && r.startsWith("called on the parameter ")
                    && Transfers.unwrap(site.call().object()) instanceof VariableExpression ve
                    && ve.variable() instanceof ParameterInfo pi) {
                    String byValue = byValue(pi);
                    // a by-value parameter: no caller uses its argument after the call
                    r = byValue == null ? null : r + ", and " + byValue;
                }
                if (r == null) continue;
                String text = "finalizer " + finalizer.fullyQualifiedName() + " in " + site.caller().fullyQualifiedName()
                              + ": " + r;
                violations.add(MessageImpl.warn(site.caller(), CATEGORY, text,
                        MessageImpl.cause(site.call().source(), site.caller(), "the call of " + finalizer.name())));
                violationRows.add(new String[]{site.caller().fullyQualifiedName(), finalizer.fullyQualifiedName(), r});
            }
        }
    }

    /*
     Rule 3's exception, Rust's by-value parameter: every call site is known (stage 5's rule 1) and no caller uses its
     argument after the call: a temporary, or a local in stage 5's ordering. Not stage 5's transfer, which also wants
     the argument fresh: here only the use after counts.
     */
    private String byValue(ParameterInfo pi) {
        MethodInfo m = pi.methodInfo();
        if (pi.isVarArgs()) return "it is variadic";
        if (!m.isConstructor() && !m.overrides().isEmpty()) return "its method overrides another";
        if (!OwnershipPass.implementationsOf(m).isEmpty()) return "its method is overridden";
        if (referenced.contains(m)) return "a method reference names its method";
        var access = m.effectiveAccess();
        if (!closedWorld && !access.isPrivate() && !access.isPackage()) return "its method is callable from outside";
        List<ArgSite> list = argSites.get(m);
        if (list == null) return "its method has no call site";
        for (ArgSite site : list) {
            if (pi.index() >= site.arguments().size()) return "a call without the argument";
            Expression a = Transfers.unwrap(site.arguments().get(pi.index()));
            if (a instanceof VariableExpression ve) {
                if (!(ve.variable() instanceof LocalVariable lv)) {
                    return site.caller().name() + " passes " + ve.variable().simpleName() + ", not a local";
                }
                String r = Transfers.local(site.caller(), site.call(), lv, analysedResults, fresh, thisEscape, false);
                if (r != null) return site.caller().name() + ": " + r;
            }
        }
        return null;
    }

    Set<MethodInfo> computed() {
        return computed;
    }

    /** The methods that need to be a finalizer (they move an owned-looking field out), with their verdict. */
    Map<MethodInfo, Boolean> verdicts() {
        Map<MethodInfo, Boolean> map = new LinkedHashMap<>();
        for (MethodInfo m : needs.keySet()) map.put(m, computed.contains(m));
        return map;
    }

    Map<MethodInfo, String> reasons() {
        return reasons;
    }

    List<Message> violations() {
        return violations;
    }

    List<String[]> violationRows() {
        return violationRows;
    }
}
