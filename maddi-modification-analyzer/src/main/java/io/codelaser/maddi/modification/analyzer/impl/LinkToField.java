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

package io.codelaser.maddi.modification.analyzer.impl;

import io.codelaser.maddi.modification.common.AnalysisHelper;
import io.codelaser.maddi.modification.link.impl.Gate;
import io.codelaser.maddi.modification.link.impl.LinkNatureImpl;
import io.codelaser.maddi.modification.prepwork.Util;
import io.codelaser.maddi.modification.prepwork.variable.Link;
import io.codelaser.maddi.modification.prepwork.variable.Links;
import io.codelaser.maddi.cst.api.analysis.Value;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.ParameterInfo;
import io.codelaser.maddi.cst.api.type.ParameterizedType;
import io.codelaser.maddi.cst.api.variable.FieldReference;
import io.codelaser.maddi.cst.api.variable.Variable;

/**
 * One link, seen as "does this reach a field of the instance, and how mutable is what it reaches?".
 * <p>
 * Extracted so that the analyzer deciding independence ({@code TypeModIndyAnalyzerImpl.worstLinkToFields}) and the
 * guard explaining a violation ({@code GuardAnalyzerImpl.blameMethodDependent}) share one definition. They must not
 * drift: the guard's job is to name the very link the analyzer's verdict rests on, so a second, hand-rolled notion of
 * "links to a mutable field" would sooner or later blame something other than the cause.
 * <p>
 * A variable CAPTURED from an enclosing method counts as a field of the instance (CodeLaser/maddi-mod#39). The method
 * of a local or anonymous class, or a lambda, that links to a parameter of the method it sits in reaches its own state:
 * the captured variable is the object's synthetic final field, and a caller that modifies what the method returns
 * modifies that state (guava's {@code new ForwardingDeque<T>() { delegate() { return delegate; } }}: read as independent,
 * it made the abstract {@code delegate()} independent through the fold, and every mutator of the six Forwarding* types
 * whose only implementations capture, {@code push}, {@code pop}, ..., non-modifying). The modification side already
 * counted a captured variable as the instance's ({@code copyModificationsIntoMethod}'s closure set); this is the
 * independence side of the same fact. A captured LOCAL variable does not reach the summary's links (the statement-level
 * link to it is not built), so only captured parameters are judged here. Gate {@code NOCAPTURED} restores the
 * fields-only rule, for an A/B.
 */
class LinkToField {

    private static final boolean NO_CAPTURED = Gate.isSet("NOCAPTURED");

    private LinkToField() {
        // utility
    }

    /**
     * The immutability of what {@code link} reaches, when it reaches a field of the instance; {@code null} when the
     * link does not lead to such a field, when its nature carries no type to judge, or when the reached type's
     * immutability is still UNDECIDED. The last case matters: the plain {@code typeImmutable} defaults an undecided
     * source type to MUTABLE, so a verdict derived from it reads a transient as a fact — and the derived
     * independence verdict is overwrite-protected, so the transient freezes (the composed-dogfood hc↔FF fork).
     * Callers that must distinguish "no field to judge" from "field, still undecided" pair this with
     * {@link #reachesJudgeableField}.
     */
    static Value.Immutable immutableOfLinkedField(Link link, MethodInfo methodInfo, AnalysisHelper analysisHelper) {
        Variable primaryTo = Util.firstRealVariable(link.to());
        if (!reachesInstance(primaryTo, methodInfo)) return null;
        ParameterizedType type = linkedType(link, primaryTo);
        if (type == null) return null;
        Value.Immutable ofDeclaredType = analysisHelper.typeImmutableNullIfUndecided(type);
        if (ofDeclaredType == null) return null; // undecided source type: no verdict, rather than the MUTABLE default
        Value.Immutable dynamic = primaryTo instanceof FieldReference fr && fr.scopeIsRecursivelyThis()
                ? dynamicImmutabilityOfWholeField(link, primaryTo, fr) : null;
        return dynamic == null ? ofDeclaredType : ofDeclaredType.max(dynamic);
    }

    /** A field of the instance, or a variable captured from an enclosing method: see the class comment. */
    private static boolean reachesInstance(Variable primaryTo, MethodInfo methodInfo) {
        if (primaryTo instanceof FieldReference fr && fr.scopeIsRecursivelyThis()) return true;
        return isCaptured(primaryTo, methodInfo);
    }

    /**
     * Whether {@code variable} (or the variable at the root of its field chain) is a parameter of a method that
     * encloses {@code methodInfo}: the enclosing method of {@code methodInfo}'s local or anonymous class, or of its
     * lambda, through any such chain. The method's own parameters are not captured, nor are a sibling method's
     * (which never reach a summary, {@code LinkComputerImpl.isParameterOfSiblingMethod}).
     */
    static boolean isCaptured(Variable variable, MethodInfo methodInfo) {
        if (NO_CAPTURED) return false;
        Variable root = Util.primary(variable);
        return root instanceof ParameterInfo pi
               && pi.methodInfo() != methodInfo
               && Util.isOrEncloses(pi.methodInfo(), methodInfo);
    }

    /**
     * Whether {@code link} reaches a field of the instance with a judgeable type — i.e. whether
     * {@link #immutableOfLinkedField} could produce a verdict once the type's immutability is decided. A
     * {@code null} verdict on a link that passes this test means UNDECIDED (wait), not "nothing to judge" (skip).
     */
    static boolean reachesJudgeableField(Link link, MethodInfo methodInfo) {
        Variable primaryTo = Util.firstRealVariable(link.to());
        return reachesInstance(primaryTo, methodInfo) && linkedType(link, primaryTo) != null;
    }

    /**
     * The type whose immutability {@link #immutableOfLinkedField} judges for this link, or {@code null} when the
     * link does not reach a field of the instance with a judgeable type.
     */
    static ParameterizedType reachedFieldType(Link link, MethodInfo methodInfo) {
        Variable primaryTo = Util.firstRealVariable(link.to());
        if (!reachesInstance(primaryTo, methodInfo)) return null;
        return linkedType(link, primaryTo);
    }

    /**
     * The field's dynamic immutability, but only when the link reaches the field as a WHOLE object: a content-tier
     * link ({@code §es}, {@code ∋}, {@code ∈}) reaches an element, which a container-level promise says nothing
     * about. See {@link DynamicImmutability} for the rule.
     */
    private static Value.Immutable dynamicImmutabilityOfWholeField(Link link, Variable primaryTo, FieldReference fr) {
        boolean wholeField = primaryTo == link.to()
                             && (link.linkNature().equals(LinkNatureImpl.IS_ASSIGNED_TO)
                                 || link.linkNature().equals(LinkNatureImpl.IS_ASSIGNED_FROM));
        return wholeField ? DynamicImmutability.ofField(fr.fieldInfo()) : null;
    }

    private static ParameterizedType linkedType(Link link, Variable primaryTo) {
        if (primaryTo == link.to() && (
                link.linkNature().equals(LinkNatureImpl.IS_ASSIGNED_TO)
                || link.linkNature().equals(LinkNatureImpl.IS_ASSIGNED_FROM))) {
            // this.set ← 0:set, TestFieldAnalyzer,1,2;
            return primaryTo.parameterizedType();
        }
        if (link.linkNature().equals(LinkNatureImpl.SHARES_ELEMENTS)
            || link.linkNature().equals(LinkNatureImpl.IS_SUBSET_OF)
            || link.linkNature().equals(LinkNatureImpl.IS_SUPERSET_OF)) {
            // 0:set.§cs⊇this.set.§cs, TestFieldAnalyzer,3. ⊆ is the same shared-content reach as ~/⊇
            // seen from the other side, and the link engine's combination lattice emits either variant
            // for the same pair depending on combination order (run-to-run): judging one while skipping
            // the other manufactured a verdict fork out of a cosmetic emission choice
            // (docs/design/eventual-info-hierarchy.md §"The independence sampling round").
            return link.to().parameterizedType().copyWithoutArrays();
        }
        if (link.linkNature().isIdenticalTo() && link.linkNature().pass().isEmpty()
            && Util.isVirtualModification(link.from()) && Util.isVirtualModification(link.to())) {
            // view.§m ≡ this.list.§m: a view of the field (Collections.unmodifiableList, synchronizedList), whatever
            // modifies the field shows through the result; judged as the whole field (CodeLaser/maddi-mod#60). The ☷
            // variant (a pass set) is an exception list, TypeModIndyAnalyzerImpl.withDependentExceptions
            return primaryTo.parameterizedType();
        }
        if (link.linkNature().equals(LinkNatureImpl.CONTAINS_AS_MEMBER)) {
            // 0:element ∋ this.set.§cs
            return link.to().parameterizedType();
        }
        if (link.linkNature().equals(LinkNatureImpl.IS_ELEMENT_OF)) {
            //getFile∈this._mruFileList.§es
            return link.from().parameterizedType();
        }
        // deliberately NOT judged: ≤/≥ (object graph) and ∩ (overlap). ≤'s to() is the whole container,
        // so judging it reads a DIFFERENT type than the ~/⊆ face variants judge for the same pair, which
        // would re-open the variance fork from the other side; ∩ is a speculative overlap, present
        // identically in both measured worlds. Extend only with a concrete variance witness in hand.
        return null;
    }

    /**
     * The field (or captured variable) the first dependence-causing link of {@code links} reaches: the one that makes
     * {@code worstLinkToFields} return DEPENDENT, i.e. the first link to a field of the instance whose reached type is
     * mutable. {@code null} when no link is that link.
     */
    static Variable firstDependentLinkToField(Links links, MethodInfo methodInfo, AnalysisHelper analysisHelper) {
        for (Link link : links) {
            Value.Immutable immutable = immutableOfLinkedField(link, methodInfo, analysisHelper);
            if (immutable != null && immutable.isMutable()) {
                return Util.firstRealVariable(link.to());
            }
        }
        return null;
    }
}
