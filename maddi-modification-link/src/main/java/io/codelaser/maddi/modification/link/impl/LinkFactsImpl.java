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

import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.ParameterInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.api.variable.DependentVariable;
import io.codelaser.maddi.cst.api.variable.FieldReference;
import io.codelaser.maddi.cst.api.variable.This;
import io.codelaser.maddi.cst.api.variable.Variable;
import io.codelaser.maddi.cst.impl.analysis.LinkFacts;
import io.codelaser.maddi.modification.prepwork.variable.Link;
import io.codelaser.maddi.modification.prepwork.variable.MethodLinkedVariables;
import io.codelaser.maddi.modification.prepwork.variable.ObjectCreationVariable;
import io.codelaser.maddi.modification.prepwork.variable.ReturnVariable;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@link LinkFacts} read off {@link MethodLinkedVariablesImpl#METHOD_LINKS}, the links of a method's return value
 * (linking manual §6): a link FROM the return variable itself ({@code get ∈ 0:list.§xs}, {@code read ← 0:box.t},
 * {@code readField ← this.myBox.t}) makes the result an object that existed before the call; links only from its
 * fields ({@code make.t ← 0:x}, {@code copy.§xs ⊆ 0:list.§xs}) make it a new object holding what they link to. The
 * roots of the links' targets are the receiver and the parameters the result is or holds. A method of {@code order}
 * that returns an object and has links but none of its return value returns a new object holding nothing of the
 * receiver or the parameters; outside {@code order}, without links, a constructor or a void method: not known.
 */
public final class LinkFactsImpl implements LinkFacts {

    private record Facts(Result result, boolean receiver, Set<ParameterInfo> kept) {
    }

    private final Map<MethodInfo, Facts> facts = new HashMap<>();

    public LinkFactsImpl(List<Info> order) {
        for (Info info : order) {
            if (info instanceof MethodInfo methodInfo) {
                Facts f = compute(methodInfo);
                if (f != null) facts.put(methodInfo, f);
            } else if (info instanceof TypeInfo typeInfo) {
                // an order may list types only; their methods are what the facts are about
                typeInfo.constructorAndMethodStream().forEach(mi -> {
                    Facts f = compute(mi);
                    if (f != null) facts.putIfAbsent(mi, f);
                });
            }
        }
    }

    private static Facts compute(MethodInfo methodInfo) {
        if (methodInfo.isConstructor() || methodInfo.isVoid()) return null;
        MethodLinkedVariables mlv = methodInfo.analysis().getOrNull(MethodLinkedVariablesImpl.METHOD_LINKS,
                MethodLinkedVariablesImpl.class);
        if (mlv == null) return null;
        boolean existing = false;
        boolean receiver = false;
        Set<ParameterInfo> kept = new LinkedHashSet<>();
        for (Link link : mlv.ofReturnValue()) {
            // the MODREACH cutover links a new object to its creation site (fresh ← oc:14-35): new, not existing
            if (link.linkNature().isDecoration() || link.to() instanceof ObjectCreationVariable) continue;
            if (link.from() instanceof ReturnVariable) existing = true;
            Variable root = root(link.to());
            if (root instanceof This) receiver = true;
            else if (root instanceof ParameterInfo pi && pi.methodInfo() == methodInfo) kept.add(pi);
            // a field without a scope variable: this's when not static; static state is neither
            else if (root instanceof FieldReference fr && !fr.fieldInfo().isStatic()) receiver = true;
        }
        return new Facts(existing ? Result.EXISTING : Result.NEW, receiver, Set.copyOf(kept));
    }

    /** The variable a field access, an array element or a virtual field is ultimately part of. */
    private static Variable root(Variable variable) {
        if (variable instanceof FieldReference fr && fr.scopeVariable() != null) return root(fr.scopeVariable());
        if (variable instanceof DependentVariable dv) return root(dv.arrayVariable());
        return variable;
    }

    @Override
    public Result result(MethodInfo methodInfo) {
        Facts f = facts.get(methodInfo);
        return f == null ? null : f.result;
    }

    @Override
    public boolean receiverInResult(MethodInfo methodInfo) {
        Facts f = facts.get(methodInfo);
        return f != null && f.receiver;
    }

    @Override
    public Set<ParameterInfo> keptInResult(MethodInfo methodInfo) {
        Facts f = facts.get(methodInfo);
        return f == null ? Set.of() : f.kept;
    }
}
