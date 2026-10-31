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

import io.codelaser.maddi.cst.api.element.Element;
import io.codelaser.maddi.cst.api.expression.Assignment;
import io.codelaser.maddi.cst.api.expression.Expression;
import io.codelaser.maddi.cst.api.expression.Lambda;
import io.codelaser.maddi.cst.api.expression.MethodCall;
import io.codelaser.maddi.cst.api.expression.VariableExpression;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.ParameterInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.api.type.ParameterizedType;
import io.codelaser.maddi.cst.api.variable.DependentVariable;
import io.codelaser.maddi.cst.api.variable.FieldReference;
import io.codelaser.maddi.cst.api.variable.LocalVariable;
import io.codelaser.maddi.cst.api.variable.This;
import io.codelaser.maddi.cst.api.variable.Variable;
import io.codelaser.maddi.modification.prepwork.Util;
import io.codelaser.maddi.modification.prepwork.variable.ReturnVariable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.codelaser.maddi.modification.analyzer.nullability.Types.*;

/**
 * What a variable or an expression of the walk stands for in the graph: a parameter (or a reassigned parameter's
 * shadow local), a field, a method's return, a {@link Local} resolved through the block scopes, an element or type
 * argument slot, a generic class's own {@code this}. The walk's resolution state: the method whose body it is in
 * ({@link #owner}), the reassigned parameters, and those whose reads resolve to the shadow from here on.
 */
final class Nodes {
    private final Policy policy;
    private final Index index;
    private final NullGraph graph;

    Nodes(Policy policy, Index index, NullGraph graph) {
        this.policy = policy;
        this.index = index;
        this.graph = graph;
    }

    MethodInfo owner;
    // a parameter the body assigns (CodeLaser/maddi-mod#22 gap 5, #9): the parameter node is the caller's value only; what
    // the body assigns goes to a shadow local of the same name (the Kotlin printer's `var p = p`), which the parameter
    // flows into, and which reads after the first assignment resolve to (from the loop's start, for an assignment
    // inside a loop)
    final Set<ParameterInfo> reassigned = new HashSet<>();
    final Set<ParameterInfo> shadowed = new HashSet<>(); // walk state: reads resolve to the shadow from here on

    static final Switches.Gate SUPERTYPE_SLOTS_OFF = Switches.gate("NOSUPERTYPESLOTS");

    /*
     a class whose superclass has a type argument that is not a type variable: its 'this' has slots of its own. A
     private one only: its superclass is its type nowhere else. jenkins' public EnvVars as a TreeMap<String, String?>
     broke the ~15 places that pass it as a Map<String, String> (Kotlin's maps are invariant), FingerprintMap and
     DiskSpaceMonitorDescriptor likewise: 127 -> 159 errors (worktree-maddi-all 2026-10-22). Gate
     NOPRIVATESUPERTYPESONLY (any class, as before).
     */
    static boolean thisTypedBySuperclass(TypeInfo type) {
        ParameterizedType parent = type.parentClass();
        return parent != null && parent.arrays() == 0 && parent.typeInfo() != null
               && (type.access().isPrivate() || PRIVATE_SUPERTYPES_ONLY_OFF.get())
               && parent.parameters().stream().anyMatch(a -> a.typeParameter() == null && a.wildcard() == null);
    }

    private static final Switches.Gate PRIVATE_SUPERTYPES_ONLY_OFF = Switches.gate("NOPRIVATESUPERTYPESONLY");

    // the shadow local of a reassigned parameter; created once, the caller's value flows into it
    Local shadow(ParameterInfo pi) {
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

    static Set<ParameterInfo> parametersAssignedIn(Element element, MethodInfo mi) {
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
    Object assignmentTarget(MethodInfo mi, Scope scope, Variable target) {
        return target instanceof ParameterInfo pi && reassigned.contains(pi) ? shadow(pi) : node(mi, scope, target);
    }

    void declare(Scope scope, MethodInfo mi, Element declaration, LocalVariable lv) {
        Local local = new Local(mi, declaration, lv.simpleName());
        scope.names().put(lv.simpleName(), local);
        graph.declaredLocals.put(local, lv);
        graph.localOwner.put(local, owner);
        graph.declaredByName.computeIfAbsent(mi, _ -> new HashMap<>())
                .computeIfAbsent(lv.simpleName(), _ -> new ArrayList<>()).add(local);
    }

    // the node a variable stands for: a parameter, a field (field-insensitive in its object), a return value, a
    // local in scope. Markers ($_ce, $_v) and the link engine's intermediates ($__) are not nodes.
    Object node(MethodInfo mi, Scope scope, Variable v) {
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

    Local local(MethodInfo mi, Scope scope, String name) {
        Local inScope = scope == null ? null : scope.resolve(name);
        if (inScope != null) return inScope;
        List<Local> declared = graph.declaredByName.getOrDefault(mi, Map.of()).getOrDefault(name, List.of());
        return declared.size() == 1 ? declared.getFirst() : new Local(mi, null, name);
    }

    /*
     Type argument j of what an expression denotes: of its node (argumentNode), or of a library view of a generic
     receiver, whose own type arguments are the receiver's class's type variables ('mapNext.values()' is a
     Collection<V>: its argument 0 is the map's V; fernflower FinallyProcessor 'new HashSet<>(mapNext.values())').
     */
    Object slotOf(MethodInfo mi, Scope scope, Expression expression, int j) {
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
    Object argumentNode(MethodInfo mi, Scope scope, Expression argument) {
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
    Object receiverNode(MethodInfo mi, Scope scope, MethodCall mc) {
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
     Kotlin: a double-brace initialization ('new HashMap<String, String>(8) {{ put(APPNAME, labels.get(APPNAME)); }}')
     writes the new object's slots through its implicit 'this': the anonymous class's 'this' is a node typed by its
     generic superclass, whose slots flow into what the creation is assigned to (nacos RequestMeta.java:103,
     ConnectionMeta.java:164, 2026-10-19: a HashMap<String, String> given nulls). Gate NOANONYMOUSTHIS.
     */
    static final Switches.Gate ANONYMOUS_THIS_OFF = Switches.gate("NOANONYMOUSTHIS");

    AnonymousThis anonymousThis(Variable v) {
        if (ANONYMOUS_THIS_OFF.get() || !policy.callResults() || !(v instanceof This t)) return null;
        TypeInfo type = t.typeInfo();
        if (type == null || type.parentClass() == null || type.parentClass().parameters().isEmpty()) return null;
        // a named class too, whose superclass's slots its own code writes (parentClassVerdicts)
        if (!type.isAnonymous() && (SUPERTYPE_SLOTS_OFF.get() || !thisTypedBySuperclass(type))) return null;
        return new AnonymousThis(type);
    }
}
