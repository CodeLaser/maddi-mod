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

package io.codelaser.maddi.modification.analyzer.impl;

import io.codelaser.maddi.cst.api.analysis.Value;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.ParameterInfo;
import io.codelaser.maddi.cst.api.type.ParameterizedType;
import io.codelaser.maddi.cst.api.expression.MethodCall;
import io.codelaser.maddi.cst.api.expression.VariableExpression;
import io.codelaser.maddi.cst.api.variable.DependentVariable;
import io.codelaser.maddi.cst.api.variable.FieldReference;
import io.codelaser.maddi.cst.api.variable.This;
import io.codelaser.maddi.cst.api.variable.Variable;
import io.codelaser.maddi.modification.common.AnalysisHelper;
import io.codelaser.maddi.modification.common.util.OwnStaticState;
import io.codelaser.maddi.modification.link.impl.Gate;
import io.codelaser.maddi.modification.link.impl.LinkNatureImpl;
import io.codelaser.maddi.modification.prepwork.Util;
import io.codelaser.maddi.modification.prepwork.variable.Link;
import io.codelaser.maddi.modification.prepwork.variable.Links;

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
        if (!reaches(link, primaryTo, methodInfo)) return null;
        ParameterizedType type = linkedType(link, primaryTo);
        if (type == null) return null;
        Value.Immutable ofDeclaredType = analysisHelper.typeImmutableNullIfUndecided(type);
        if (ofDeclaredType == null) return null; // undecided source type: no verdict, rather than the MUTABLE default
        Value.Immutable dynamic = primaryTo instanceof FieldReference fr && fr.scopeIsRecursivelyThis()
                ? dynamicImmutabilityOfWholeField(link, primaryTo, fr) : null;
        return dynamic == null ? ofDeclaredType : ofDeclaredType.max(dynamic);
    }

    /*
     A field of the instance, a variable captured from an enclosing method (see the class comment), or the static state
     the method's type owns. Independence is defined against "the fields" (road-to-immutability §045), and "the
     definitions make no distinction between static and instance fields" (§050): a static setter that stores its
     argument in its type's static state, or a static getter that hands it out, links to the type's fields just as an
     instance one does (fernflower's DecompilerContext, CodeLaser/maddi-mod#107). Ownership as for modification
     (OwnStaticState); another type's static state is a static side effect, not dependence. Gate NOSTATICINDEP.
     */
    private static final boolean NO_THIS_CONTENT_INDEP = Gate.isSet("NOTHISCONTENTINDEP");

    /*
     A field of the instance (reachesInstance), or the instance's own hidden faces: 'all.§m ≡ this.§m', 'all.§$s ∋
     this.§$'. An abstract type's content is its subtypes' fields, which no field reference names: fernflower's
     'List<Exprent> getAllExprents() { List<Exprent> l = new ArrayList<>(); getAllExprents(l); return l; }' hands the
     list to the abstract filler, whose links say it shares this.§m, and the result was INDEPENDENT while every
     override filled it with its own subexpressions (CodeLaser/maddi-mod#135). A link to 'this' as a whole object
     ('return this') is not judged here. Gate NOTHISCONTENTINDEP.
     */
    private static boolean reaches(Link link, Variable primaryTo, MethodInfo methodInfo) {
        if (reachesInstance(primaryTo, methodInfo)) return true;
        if (fieldOfThis(link, primaryTo, methodInfo)) return true;
        return !NO_THIS_CONTENT_INDEP && primaryTo instanceof This thisVar && link.to() != primaryTo
               && Util.virtual(link.to()) && methodInfo.typeInfo().isEqualToOrInnerClassOf(thisVar.typeInfo());
    }

    private static boolean reachesInstance(Variable primaryTo, MethodInfo methodInfo) {
        if (primaryTo instanceof FieldReference fr && fr.scopeIsRecursivelyThis()) return true;
        if (!NO_ARRAY_ELEMENT_INDEP && primaryTo instanceof FieldReference fr && throughArrayElementOfThis(fr)) return true;
        if (!NO_STATIC_INDEP && OwnStaticState.staticRoot(primaryTo) instanceof FieldReference root
            && OwnStaticState.owns(methodInfo, root.fieldInfo().owner())) {
            return true;
        }
        if (!NO_THROUGH_CALL && primaryTo instanceof FieldReference fr && fr.scopeVariable() == null
            && fr.scope() instanceof MethodCall mc && mc.object() instanceof VariableExpression ve) {
            // 'this.itl.get().collector', 'X.current.get().collector': a field of what a call on the instance's (or
            // the type's static) state returns is part of that state's object graph (CodeLaser/maddi-mod#107)
            return reachesInstance(ve.variable(), methodInfo);
        }
        if (primaryTo instanceof This thisVar) return false;
        return isCaptured(primaryTo, methodInfo);
    }

    private static final boolean NO_THROUGH_CALL = Gate.isSet("NOTHROUGHCALL");

    /*
     'this.elements[0].factory': a field of an element of the instance's array field. scopeIsRecursivelyThis follows
     field scopes only, so 'return elements[0].copy()', whose copy shares the element's factory
     ('first.factory ← this.elements[0].factory'), came out independent; fernflower's SFormsFastMapDirect.getCopy,
     whose sets share their factory with the receiver's (the dynamic check's 2c). Gate NOARRAYELEMENTINDEP.
     */
    private static final boolean NO_ARRAY_ELEMENT_INDEP = Gate.isSet("NOARRAYELEMENTINDEP");

    private static boolean throughArrayElementOfThis(FieldReference fr) {
        Variable scope = fr.scopeVariable();
        while (true) {
            if (scope instanceof DependentVariable dv) {
                scope = dv.arrayVariable();
            } else if (scope instanceof FieldReference sfr) {
                // fr itself is not recursively this (the caller checked), so the walk passed an array element
                if (sfr.scopeIsRecursivelyThis()) return true;
                scope = sfr.scopeVariable();
            } else {
                return false;
            }
        }
    }

    /*
     'getWrapper ≺ this': the value is one of the instance's fields, the links do not say which. fernflower's
     'ClassNode node = this; while (node.type == LAMBDA) node = node.parent; return node.wrapper;' summarises as
     'getWrapper ≺ this.parent, getWrapper ≺ this', and both were left unjudged (only a virtual target was, #107): the
     getter came out independent (the dynamic check's 2c). Judged by the value's own type, as the real-target '≺'
     below. Gate NOFIELDOFINDEP.
     */
    private static final boolean NO_FIELD_OF_INDEP = Gate.isSet("NOFIELDOFINDEP");

    private static boolean fieldOfThis(Link link, Variable primaryTo, MethodInfo methodInfo) {
        return !NO_FIELD_OF_INDEP && link.linkNature().equals(LinkNatureImpl.IS_FIELD_OF) && link.to() == primaryTo
               && primaryTo instanceof This thisVar && methodInfo.typeInfo().isEqualToOrInnerClassOf(thisVar.typeInfo());
    }

    private static final boolean NO_STATIC_INDEP = Gate.isSet("NOSTATICINDEP");

    private static final boolean NO_OBJECT_GRAPH_INDEP = Gate.isSet("NOOBJECTGRAPHINDEP");

    private static final boolean NO_OG_CONTAINS_FIELD = Gate.isSet("NOOGCONTAINSFIELD");

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
        return reaches(link, primaryTo, methodInfo) && linkedType(link, primaryTo) != null;
    }

    /**
     * The type whose immutability {@link #immutableOfLinkedField} judges for this link, or {@code null} when the
     * link does not reach a field of the instance with a judgeable type.
     */
    static ParameterizedType reachedFieldType(Link link, MethodInfo methodInfo) {
        Variable primaryTo = Util.firstRealVariable(link.to());
        if (!reaches(link, primaryTo, methodInfo)) return null;
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
                                 || link.linkNature().equals(LinkNatureImpl.IS_ASSIGNED_FROM))
                             || sharesModification(link);
        return wholeField ? DynamicImmutability.ofField(fr.fieldInfo()) : null;
    }

    /** {@code view.§m ≡ this.list.§m}: the two share the field's modification, a plain ≡ (no pass set) */
    static boolean sharesModification(Link link) {
        return link.linkNature().isIdenticalTo() && link.linkNature().pass().isEmpty()
               && Util.isVirtualModification(link.from()) && Util.isVirtualModification(link.to());
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
        if (sharesModification(link)) {
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
        if (!NO_FIELD_OF_INDEP && link.linkNature().equals(LinkNatureImpl.IS_FIELD_OF) && !Util.virtual(link.to())
            && !Util.virtual(link.from())) {
            // 'getWrapper ≺ this.parent', 'getWrapper ≺ this' (fieldOfThis): the value is a field of an object in the
            // instance's graph, or of the instance itself; judged by the value's type. Gate NOFIELDOFINDEP
            return link.from().parameterizedType();
        }
        if (!NO_THROUGH_CALL && link.linkNature().equals(LinkNatureImpl.IS_FIELD_OF) && Util.virtual(link.to())) {
            // 'getTL ≺ X.current.§$': the value is a field of the object held as the field's content (a ThreadLocal's,
            // an AtomicReference's), as '∈' is an element of it (CodeLaser/maddi-mod#107)
            return link.from().parameterizedType();
        }
        if (!NO_THROUGH_CALL && link.linkNature().equals(LinkNatureImpl.IS_ASSIGNED_FROM) && primaryTo != link.to()
            && Util.virtual(link.to()) && !Util.virtual(link.from())) {
            // 'instTL ← this.itl.§$': 'return itl.get()' hands out the object the field holds as its content
            return link.from().parameterizedType();
        }
        if (!NO_OBJECT_GRAPH_INDEP && link.linkNature().equals(LinkNatureImpl.IS_IN_OBJECT_GRAPH)
            && Util.virtual(link.to()) && !Util.isVirtualModification(link.to())) {
            // 'copy.§$s ≤ this.map.§$$s[-2]': 'new ArrayList<>(map.get(k))' holds objects from inside the field's hidden
            // content, the elements of one of its values. Judged by what is shared, the FROM side's type without its
            // arrays (Edge), the same type the ⊆/~ variant judges for a copy of the field's own list, so the two
            // emissions agree (CodeLaser/maddi-mod#112). A parameter stored inside an element of the field's content
            // ('t ≤ this.events.§$s') is dependent too: it sits in accessible content (#119). Gate NOOBJECTGRAPHINDEP
            return link.from().parameterizedType().copyWithoutArrays();
        }
        if (!NO_OBJECT_GRAPH_INDEP && link.linkNature().equals(LinkNatureImpl.OBJECT_GRAPH_OVERLAPS)
            && elementSet(link.from()) && elementSet(link.to())) {
            // 'res.§$s ∩ this.map.§$$s[-2]': 'res.addAll(map.get(k))', or 'res.add(e)' for each e of it, fills a new
            // list with the elements of one of the field's values, as the copy constructor's '≤' above does. Only
            // between two element sets: there the shared objects are elements, of the FROM side's element type.
            // The witness is fernflower's Statement.getEdges, whose gathering branch made getAllSuccessorEdges
            // INDEPENDENT while its callers wrote the edges (CodeLaser/maddi-mod#134).
            // Not a single hidden content on either side: of 'print.§$ ∩ this.concreteReturnType.§$' (a new
            // OutputBuilder holding what the type's print added) nothing says what is shared, and judging the
            // builder's own type made every print(Qualification) dependent: maddi's Element, Statement and
            // ParameterizedType fell to @FinalFields (TestEventualRatchet, 2026-11-17). 'methodModifiers.§$s ∩
            // this.inspection.§$' (MethodInfo) is the same overlap with what a holder's get() returns: unjudged,
            // as before #134. A '∩' between variables stays unjudged, below.
            return link.from().parameterizedType().copyWithoutArrays();
        }
        if (!NO_OG_CONTAINS_FIELD && link.linkNature().equals(LinkNatureImpl.OBJECT_GRAPH_CONTAINS)
            && primaryTo == link.to() && !Util.virtual(link.to())) {
            // 'r0.pairs.§$s ≥ this.left': the field's object itself sits inside the result's graph, here wrapped in a
            // record in a list ('r.add(left, t)' with 'pairs.add(new Pair(e, t))'). Judged as the whole field, as
            // '←' is: the field's own type. fernflower's AssignmentExprent.checkExprTypeBounds, whose result holds
            // the receiver's subexpressions (the dynamic check's 2c). Gate NOOGCONTAINSFIELD.
            return primaryTo.parameterizedType();
        }
        // deliberately NOT judged: ≥ (object graph, the other direction) other than a whole field above, and ∩ (overlap) other than the element-set
        // case above; ≤ only as above. ≤'s to() is the whole container,
        // so judging it reads a DIFFERENT type than the ~/⊆ face variants judge for the same pair, which
        // would re-open the variance fork from the other side; ∩ is a speculative overlap, present
        // identically in both measured worlds. Extend only with a concrete variance witness in hand.
        return null;
    }

    // a hidden content that is a set of elements ('l.§$s', 'm.§$$s[-2]'), not a single object ('h.§$') nor '§m'
    private static boolean elementSet(Variable v) {
        return Util.virtual(v) && !Util.isVirtualModification(v) && v.parameterizedType().arrays() > 0;
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
