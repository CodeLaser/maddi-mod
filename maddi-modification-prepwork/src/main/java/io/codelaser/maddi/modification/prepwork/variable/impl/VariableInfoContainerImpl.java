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

import java.util.function.Consumer;
import java.util.function.Predicate;

import io.codelaser.maddi.cst.api.analysis.Property;
import io.codelaser.maddi.cst.api.info.InfoMapView;
import io.codelaser.maddi.cst.api.variable.Variable;
import io.codelaser.maddi.modification.prepwork.variable.Stage;
import io.codelaser.maddi.modification.prepwork.variable.VariableInfo;
import io.codelaser.maddi.modification.prepwork.variable.VariableInfoContainer;
import io.codelaser.maddi.modification.prepwork.variable.VariableNature;
import io.codelaser.maddi.support.SetOnce;

public final class VariableInfoContainerImpl implements VariableInfoContainer, PreviousOrInitial {
    private final Variable variable;
    private final VariableNature variableNature;
    // the previous statement's container, or this container's own initial info; final, and no wrapper: an Either
    // here was 16 bytes per container, 0.66 GB of closed-core's prep
    private final PreviousOrInitial previousOrInitial;
    private final VariableInfoImpl evaluation;
    private final SetOnce<VariableInfoImpl> merge;

    /** A container that continues the previous statement's container of the same variable. */
    public static VariableInfoContainerImpl withPrevious(Variable variable,
                                                         VariableNature variableNature,
                                                         VariableInfoContainer previous,
                                                         VariableInfoImpl evaluation,
                                                         boolean haveMerge) {
        return new VariableInfoContainerImpl(variable, variableNature, (VariableInfoContainerImpl) previous,
                evaluation, haveMerge);
    }

    /** A container that starts from an initial info of its own. */
    public static VariableInfoContainerImpl withInitial(Variable variable,
                                                        VariableNature variableNature,
                                                        VariableInfoImpl initial,
                                                        VariableInfoImpl evaluation,
                                                        boolean haveMerge) {
        return new VariableInfoContainerImpl(variable, variableNature, initial, evaluation, haveMerge);
    }

    private VariableInfoContainerImpl(Variable variable,
                                      VariableNature variableNature,
                                      PreviousOrInitial previousOrInitial,
                                      VariableInfoImpl evaluation,
                                      boolean haveMerge) {
        this.variable = variable;
        this.variableNature = variableNature;
        this.previousOrInitial = previousOrInitial;
        this.evaluation = evaluation;
        this.merge = haveMerge ? new SetOnce<>() : null;
        assert evaluation == null || evaluation.variable() == variable;
        assert switch (previousOrInitial) {
            case VariableInfoContainerImpl previous -> previous.variable() == variable;
            case VariableInfoImpl initial -> initial.variable() == variable;
        };
    }

    /** {@link VariableInfoImpl#clearDerived} on the infos this container owns: the initial, the evaluation, the merge. */
    public void clearDerived(Predicate<Property> derived) {
        if (previousOrInitial instanceof VariableInfoImpl initial) initial.clearDerived(derived);
        if (evaluation != null) evaluation.clearDerived(derived);
        if (merge != null && merge.isSet()) merge.get().clearDerived(derived);
    }

    /** The infos this container owns, as {@link #clearDerived} visits them: the initial, the evaluation, the merge. */
    public void forEachOwnInfo(Consumer<VariableInfoImpl> consumer) {
        if (previousOrInitial instanceof VariableInfoImpl initial) consumer.accept(initial);
        if (evaluation != null) consumer.accept(evaluation);
        if (merge != null && merge.isSet()) consumer.accept(merge.get());
    }

    public void setMerge(VariableInfoImpl merge) {
        this.merge.set(merge);
    }

    @Override
    public Variable variable() {
        return variable;
    }

    @Override
    public VariableNature variableNature() {
        return variableNature;
    }

    @Override
    public boolean hasEvaluation() {
        return evaluation != null;
    }

    @Override
    public boolean hasMerge() {
        return merge != null;
    }

    @Override
    public boolean isPrevious() {
        return previousOrInitial instanceof VariableInfoContainerImpl;
    }

    @Override
    public boolean has(Stage stage) {
        return switch (stage) {
            case MERGE -> merge != null;
            case EVALUATION -> evaluation != null;
            case INITIAL -> isInitial();
        };
    }

    @Override
    public VariableInfo best() {
        return merge != null ? merge.get() : evaluation != null ? evaluation : getPreviousOrInitial();
    }

    @Override
    public VariableInfo best(Stage level) {
        if (level == Stage.MERGE && merge != null) {
            return merge.get();
        }
        if ((level == Stage.MERGE || level == Stage.EVALUATION) && evaluation != null) {
            return evaluation;
        }
        return getPreviousOrInitial();
    }

    @Override
    public VariableInfo getPreviousOrInitial() {
        return switch (previousOrInitial) {
            case VariableInfoContainerImpl previous -> previous.best();
            case VariableInfoImpl initial -> initial;
        };
    }

    @Override
    public boolean isInitial() {
        return previousOrInitial instanceof VariableInfoImpl;
    }

    @Override
    public boolean isRecursivelyInitial() {
        if (!(previousOrInitial instanceof VariableInfoContainerImpl previous)) return true;
        if (!previous.hasEvaluation() && (stageForPrevious() == Stage.EVALUATION || !previous.hasMerge())) {
            return previous.isRecursivelyInitial();
        }
        return false;
    }

    @Override
    public VariableInfo getRecursiveInitial() {
        return switch (previousOrInitial) {
            case VariableInfoContainerImpl previous -> previous.getRecursiveInitial();
            case VariableInfoImpl initial -> initial;
        };
    }

    private Stage stageForPrevious() {
        VariableInfoContainer prev = (VariableInfoContainerImpl) previousOrInitial;
        return prev.hasMerge() ? Stage.MERGE : Stage.EVALUATION;
    }

    @Override
    public VariableInfo bestCurrentlyComputed() {
        if (merge != null && merge.isSet()) return merge.get();
        return evaluation;
    }

    @Override
    public String indexOfDefinition() {
        if (evaluation != null) return evaluation.assignments().indexOfDefinition();
        if (merge != null && merge.isSet()) return merge.get().assignments().indexOfDefinition();
        return switch (previousOrInitial) {
            case VariableInfoContainerImpl previous -> previous.indexOfDefinition();
            case VariableInfoImpl initial -> initial.assignments().indexOfDefinition();
        };
    }

    /** See {@link VariableDataImpl#rewire}: once per rewire, the variable, previous and stages rewired with it. */
    public VariableInfoContainer rewire(InfoMapView infoMap) {
        return infoMap.rewired(this, () -> {
            // ⚠ one Variable for all of them: the constructor asserts identity with the evaluation's and the
            // previous container's variable, which holds because Variable.rewire is itself once per rewire
            Variable rewiredVariable = variable.rewire(infoMap);
            PreviousOrInitial rewiredPrevious = switch (previousOrInitial) {
                case VariableInfoContainerImpl previous -> (VariableInfoContainerImpl) previous.rewire(infoMap);
                case VariableInfoImpl initial -> initial.rewire(infoMap);
            };
            VariableInfoContainerImpl copy = new VariableInfoContainerImpl(rewiredVariable, variableNature,
                    rewiredPrevious, evaluation == null ? null : evaluation.rewire(infoMap), merge != null);
            if (merge != null && merge.isSet()) copy.setMerge(merge.get().rewire(infoMap));
            return copy;
        });
    }

    /** For {@link VariableDataImpl#toString}: no identities, only what prep computed. */
    String describe() {
        VariableInfo best = best();
        return "[" + variableNature + (isInitial() ? " initial" : "") + (hasEvaluation() ? " E" : "")
               + (hasMerge() ? " M" : "") + " a=" + best.assignments() + " r=" + best.reads()
               + (best.isVariableInClosure() ? " closure" : "") + "]";
    }

    @Override
    public VariableInfoContainer flattened() {
        if (previousOrInitial instanceof VariableInfoImpl) return this; // already back-reference-free
        // Collapse the previous-container chain link to a value snapshot of what
        // getPreviousOrInitial() currently resolves to. best()/best(stage)/has(stage)/
        // bestCurrentlyComputed() are unchanged because this container's own evaluation and merge are
        // carried over verbatim; only the "came from a previous statement" structure is dropped. The
        // captured VariableInfoImpl holds this variable's value (assignments/reads/links), not the
        // previous containers, so the intermediate chain becomes collectible once its statements'
        // VARIABLE_DATA is dropped too.
        VariableInfoImpl previousSnapshot = (VariableInfoImpl) getPreviousOrInitial();
        VariableInfoContainerImpl copy = new VariableInfoContainerImpl(variable, variableNature,
                previousSnapshot, evaluation, merge != null);
        if (merge != null && merge.isSet()) copy.setMerge(merge.get());
        return copy;
    }
}
