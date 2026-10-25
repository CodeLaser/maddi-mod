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

package io.codelaser.maddi.modification.link.impl;

import io.codelaser.maddi.cst.api.analysis.Value;
import io.codelaser.maddi.cst.api.expression.Expression;
import io.codelaser.maddi.cst.api.expression.Lambda;
import io.codelaser.maddi.cst.api.expression.MethodReference;
import io.codelaser.maddi.cst.api.expression.TypeExpression;
import io.codelaser.maddi.cst.api.expression.VariableExpression;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.ParameterInfo;
import io.codelaser.maddi.cst.api.variable.FieldReference;
import io.codelaser.maddi.cst.api.variable.LocalVariable;
import io.codelaser.maddi.cst.api.variable.Variable;
import io.codelaser.maddi.cst.impl.analysis.PropertyImpl;
import io.codelaser.maddi.cst.impl.analysis.ValueImpl;
import io.codelaser.maddi.modification.prepwork.Util;
import io.codelaser.maddi.modification.prepwork.variable.Link;
import io.codelaser.maddi.modification.prepwork.variable.Links;
import io.codelaser.maddi.modification.prepwork.variable.Stage;
import io.codelaser.maddi.modification.prepwork.variable.VariableData;
import io.codelaser.maddi.modification.prepwork.variable.VariableInfo;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Call-site specialisation of a parameter that a method modifies only by handing it to one of its own functional
 * parameters ({@link PropertyImpl#MODIFIED_THROUGH_PASSED_FUNCTION}). vavr's {@code Value.toQueue()} calls
 * {@code ValueModule.toTraversable(this, Queue.empty(), Queue::of, Queue::ofAll)}, whose body is
 * {@code ofAll.apply(value)}: {@code value} is modified for SOME function ({@code Function.apply(@Modified T)}),
 * but not for {@code Queue::ofAll}, so {@code toQueue()} does not modify its receiver.
 * <p>
 * Two kinds of entry. {@code k:j}: the parameter itself is argument {@code j} of the SAM of functional parameter
 * {@code k}; the parameter stays modified (it is, for some function), and a call site passing a function that leaves
 * it alone does not modify its argument. {@code k:j:e}: an ELEMENT of the parameter (its hidden content, read by
 * {@code get}, an iterator or a for-each) is that SAM argument (CodeLaser/maddi-mod#67, option b); the parameter is
 * unmodified, and a call site passing a function that modifies its argument modifies the hidden content of its own
 * argument, deep-only.
 * <p>
 * Shared by the engine ({@link MethodModification}) and the MODREACH shadow pass, which must agree.
 */
public final class PassedFunction {

    private static final String ELEMENT = ":e";

    private PassedFunction() {
    }

    public static String entry(int functionalParameterIndex, int samArgumentIndex) {
        return functionalParameterIndex + ":" + samArgumentIndex;
    }

    public static String elementEntry(int functionalParameterIndex, int samArgumentIndex) {
        return entry(functionalParameterIndex, samArgumentIndex) + ELEMENT;
    }

    public static boolean isElementEntry(String entry) {
        return entry.endsWith(ELEMENT);
    }

    private record Entry(int functionalParameterIndex, int samArgumentIndex, boolean element) {
        static Entry parse(String entry) {
            String[] parts = entry.split(":");
            return new Entry(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), parts.length > 2);
        }
    }

    /** What a call site does to the argument of a parameter with entries, given the functions it passes. */
    public enum Effect {
        /** every function passed provably leaves its argument alone */
        UNMODIFIED,
        /** only functions of element entries may modify: the argument's hidden content is modified, deep-only */
        ELEMENTS,
        /** a function of a whole-parameter entry may modify, or the entries cannot be read here */
        ANY
    }

    private static Set<String> entries(ParameterInfo calleeParameter) {
        return calleeParameter.analysis().getOrDefault(PropertyImpl.MODIFIED_THROUGH_PASSED_FUNCTION,
                ValueImpl.SetOfStringsImpl.EMPTY_SET).set();
    }

    public static boolean hasEntries(ParameterInfo calleeParameter) {
        return !entries(calleeParameter).isEmpty();
    }

    /**
     * @return null when the parameter has no entries; otherwise what the functions passed at this call site do.
     */
    public static Effect effectAtCallSite(ParameterInfo calleeParameter, List<Expression> arguments) {
        Set<String> through = entries(calleeParameter);
        if (through.isEmpty()) return null;
        boolean elements = false;
        for (String s : through) {
            Entry entry = Entry.parse(s);
            if (entry.functionalParameterIndex >= arguments.size()) return Effect.ANY;
            Boolean modifies = modifiesArgument(arguments.get(entry.functionalParameterIndex), entry.samArgumentIndex);
            if (modifies == null || modifies) {
                if (!entry.element) return Effect.ANY;
                elements = true;
            }
        }
        return elements ? Effect.ELEMENTS : Effect.UNMODIFIED;
    }

    /**
     * @return true when the argument for {@code calleeParameter} is certainly NOT modified at this call site: the
     * callee modifies it only through its functional parameters, and every function passed for them here provably
     * leaves the corresponding argument alone. False whenever that cannot be established.
     */
    public static boolean unmodifiedAtCallSite(ParameterInfo calleeParameter, List<Expression> arguments) {
        return effectAtCallSite(calleeParameter, arguments) == Effect.UNMODIFIED;
    }

    /**
     * The element entries the CALLER takes over when it passes its own functional parameters on: for
     * {@code forward(list, f) { return listElement(list, f); }}, {@code listElement}'s {@code 1:0:e} becomes
     * {@code forward}'s own {@code 1:0:e}, for its callers to resolve. Null when a function that may modify is not
     * one of the caller's functional parameters, or belongs to a whole-parameter entry.
     */
    public static List<String> forwardedElementEntries(ParameterInfo calleeParameter, List<Expression> arguments,
                                                       MethodInfo caller) {
        List<String> out = new ArrayList<>();
        for (String s : entries(calleeParameter)) {
            Entry entry = Entry.parse(s);
            if (entry.functionalParameterIndex >= arguments.size()) return null;
            Expression function = arguments.get(entry.functionalParameterIndex);
            Boolean modifies = modifiesArgument(function, entry.samArgumentIndex);
            if (modifies != null && !modifies) continue;
            if (!entry.element || !(function instanceof VariableExpression ve)
                || !(ve.variable() instanceof ParameterInfo own) || own.methodInfo() != caller
                || !own.parameterizedType().isFunctionalInterface()) {
                return null;
            }
            out.add(elementEntry(own.index(), entry.samArgumentIndex));
        }
        return out;
    }

    /**
     * The parameters of {@code method} whose hidden content is all that {@code links}, the links of an argument
     * expression, reach: {@code list.get(0)} ({@code $rv ∈ list.§ts}), {@code opt.get()} ({@code $rv ← opt.§t}), the
     * variable of {@code for (T t : list)} ({@code t ∈ list.§ts}, in the variable data). Empty when the argument is
     * linked to anything else, or not linked at all.
     */
    public static Set<ParameterInfo> elementOf(Links links, VariableData variableData, Stage stage,
                                               MethodInfo method) {
        if (links == null || links.primary() == null) return Set.of();
        Iterable<Link> toFollow = links;
        if (links.isEmpty()) {
            Variable primary = links.primary();
            if (!(primary instanceof LocalVariable) || variableData == null
                || !variableData.isKnown(primary.fullyQualifiedName())) {
                return Set.of();
            }
            VariableInfo vi = variableData.variableInfo(primary, stage);
            Links ofLocal = vi == null ? null : vi.linkedVariables();
            if (ofLocal == null || ofLocal.isEmpty()) return Set.of();
            toFollow = ofLocal;
        }
        Set<ParameterInfo> out = new LinkedHashSet<>();
        for (Link link : toFollow) {
            Variable to = link.to();
            if (!(Util.primary(to) instanceof ParameterInfo pi) || pi.methodInfo() != method
                || Util.variableAndScopes(to).noneMatch(v -> v instanceof FieldReference fr
                                                             && Util.virtual(fr.fieldInfo()))) {
                return Set.of();
            }
            out.add(pi);
        }
        return out;
    }

    /** The parameters of {@code method} that the argument is, or whose hidden content is all it reaches. */
    public static Set<ParameterInfo> ownParametersHolding(Links links, VariableData variableData, Stage stage,
                                                          MethodInfo method) {
        if (links != null && links.isEmpty() && links.primary() instanceof ParameterInfo own
            && own.methodInfo() == method) {
            return Set.of(own);
        }
        return elementOf(links, variableData, stage, method);
    }

    /**
     * Does the function expression, applied, modify its SAM argument number {@code samArgumentIndex}?
     *
     * @return null when unknown: not a method reference or lambda, or the verdict is not decided yet
     */
    static Boolean modifiesArgument(Expression function, int samArgumentIndex) {
        if (function instanceof MethodReference mr) {
            MethodInfo target = mr.methodInfo();
            int index = samArgumentIndex;
            if (!target.isStatic() && !target.isConstructor() && mr.scope() instanceof TypeExpression) {
                // unbound receiver (String::length): SAM argument 0 is the receiver
                if (index == 0) {
                    Value.Bool nonModifying = target.analysis().getOrNull(PropertyImpl.NON_MODIFYING_METHOD,
                            ValueImpl.BoolImpl.class);
                    return nonModifying == null ? null : nonModifying.isFalse();
                }
                index--;
            }
            return parameterModified(target, index);
        }
        if (function instanceof Lambda lambda) {
            return parameterModified(lambda.methodInfo(), samArgumentIndex);
        }
        return null;
    }

    private static Boolean parameterModified(MethodInfo methodInfo, int index) {
        if (index >= methodInfo.parameters().size()) return null;
        ParameterInfo pi = methodInfo.parameters().get(index);
        if (pi.isVarArgs()) return null;
        Value.Bool unmodified = pi.analysis().getOrNull(PropertyImpl.UNMODIFIED_PARAMETER, ValueImpl.BoolImpl.class);
        return unmodified == null ? null : unmodified.isFalse();
    }
}
