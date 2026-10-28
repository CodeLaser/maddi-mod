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

package io.codelaser.maddi.modification.prepwork.variable.impl;

import io.codelaser.maddi.modification.prepwork.variable.Link;
import io.codelaser.maddi.modification.prepwork.variable.Links;
import io.codelaser.maddi.modification.prepwork.variable.ReturnVariable;
import io.codelaser.maddi.modification.prepwork.variable.VariableInfo;
import io.codelaser.maddi.cst.api.analysis.Property;
import io.codelaser.maddi.cst.api.analysis.PropertyValueMap;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.api.variable.LocalVariable;
import io.codelaser.maddi.cst.api.variable.Variable;
import io.codelaser.maddi.cst.impl.analysis.PropertyImpl;
import io.codelaser.maddi.cst.impl.analysis.AnalysisAccessRecorder;
import io.codelaser.maddi.cst.impl.analysis.PropertyValueMapImpl;
import io.codelaser.maddi.cst.impl.analysis.ValueImpl;

import java.util.Set;

public class VariableInfoImpl implements VariableInfo, AnalysisAccessRecorder.Owner {
    public static final Property UNMODIFIED_VARIABLE = new PropertyImpl("unmodifiedVariable");
    /**
     * The structural twin of {@link #UNMODIFIED_VARIABLE}: the variable's object is not modified itself, its hidden
     * content (elements) may be. Never FALSE where UNMODIFIED_VARIABLE is TRUE; a reader falls back to the latter
     * when this one is absent. See {@code PropertyImpl.STRUCTURALLY_NON_MODIFYING_METHOD}.
     */
    public static final Property STRUCTURALLY_UNMODIFIED_VARIABLE = new PropertyImpl("structurallyUnmodifiedVariable");

    public static final Property DOWNCAST_VARIABLE = new PropertyImpl("downcastVariable",
            ValueImpl.SetOfTypeInfoImpl.EMPTY);

    private Links linkedVariables;
    /*
    CodeLaser/maddi-mod#76: the link computer writes a variable's links here, outside the analysis maps, and reads
    them back in its next computation of the method; the link cache must see both
     */
    private static final Object LINKED_VARIABLES = "linkedVariables";

    private final PropertyValueMap analysis = new PropertyValueMapImpl();

    private final Variable variable;
    private final Assignments assignments;
    private final Reads reads;
    private final boolean isVariableInClosure;

    public VariableInfoImpl(Variable variable, Assignments assignments, Reads reads, boolean isVariableInClosure) {
        this.variable = variable;
        this.assignments = assignments;
        this.reads = reads;
        this.isVariableInClosure = isVariableInClosure;
    }

    public void setLinkedVariables(Links linkedVariables) {
        assert linkedVariables != null;
        if (AnalysisAccessRecorder.active()) AnalysisAccessRecorder.write(this, LINKED_VARIABLES);
        if (this.linkedVariables == null) {
            this.linkedVariables = linkedVariables;
        } else if (this.linkedVariables.equals(linkedVariables)) {
            // Links equality is primary-only: with the same primary, a recomputed value that differs in CONTENT
            // replaced nothing, so a variable's statement-level links stayed at the method's FIRST link computation
            // for the rest of the run -- every later pass computed and discarded the settled links, and the
            // statement-data readers (ShadowModificationPass, FieldAnalyzerImpl, the link computer's own
            // previous-statement reads) worked from the first pass (EC O4: 'iterator ↦ -' kept over
            // 'iterator.§m ☷{remove} this.§m'). The last computation wins.
            if (!sameContent(this.linkedVariables, linkedVariables)) this.linkedVariables = linkedVariables;
        } else {
            if (this.linkedVariables.overwriteAllowed(linkedVariables)) {
                this.linkedVariables = linkedVariables;
            } else {
                throw new UnsupportedOperationException("Not allowed to overwrite");
            }
        }
    }

    private static boolean sameContent(Links l1, Links l2) {
        return contentKey(l1).equals(contentKey(l2));
    }

    private static java.util.Set<java.util.List<Object>> contentKey(Links links) {
        java.util.Set<java.util.List<Object>> set = new java.util.HashSet<>();
        for (Link link : links) {
            set.add(java.util.List.of(link.from(), link.linkNature(), link.to(), link.mediated()));
        }
        return set;
    }

    @Override
    public boolean isVariableInClosure() {
        return isVariableInClosure;
    }

    @Override
    public Variable variable() {
        return variable;
    }

    @Override
    public Links linkedVariables() {
        if (AnalysisAccessRecorder.active()) AnalysisAccessRecorder.read(this, LINKED_VARIABLES, linkedVariables);
        return linkedVariables;
    }

    @Override
    public Links linkedVariablesOrEmpty() {
        if (AnalysisAccessRecorder.active()) AnalysisAccessRecorder.read(this, LINKED_VARIABLES, linkedVariables);
        return linkedVariables == null ? LinksImpl.EMPTY : linkedVariables;
    }

    @Override
    public Object peek(Object key) {
        return linkedVariables;
    }

    @Override
    public boolean sameValue(Object key, Object a, Object b) {
        return a.equals(b) && sameContent((Links) a, (Links) b);
    }

    @Override
    public PropertyValueMap analysis() {
        return analysis;
    }

    @Override
    public Assignments assignments() {
        return assignments;
    }

    @Override
    public Reads reads() {
        return reads;
    }

    @Override
    public boolean hasBeenDefined(String index) {
        if (variable instanceof LocalVariable || variable instanceof ReturnVariable) {
            return assignments.hasAValueAt(index);
        }
        return true;
    }

    @Override
    public boolean isUnmodified() {
        return analysis.getOrDefault(UNMODIFIED_VARIABLE, ValueImpl.BoolImpl.FALSE).isTrue();
    }

    @Override
    public boolean isStructurallyUnmodified() {
        io.codelaser.maddi.cst.api.analysis.Value.Bool structural =
                analysis.getOrNull(STRUCTURALLY_UNMODIFIED_VARIABLE, ValueImpl.BoolImpl.class);
        return structural != null ? structural.isTrue() : isUnmodified();
    }

    @Override
    public Set<TypeInfo> downcast() {
        return analysis.getOrDefault(DOWNCAST_VARIABLE, ValueImpl.SetOfTypeInfoImpl.EMPTY).typeInfoSet();
    }

    @Override
    public String toString() {
        return "VI[" + variable + "]";
    }
}
