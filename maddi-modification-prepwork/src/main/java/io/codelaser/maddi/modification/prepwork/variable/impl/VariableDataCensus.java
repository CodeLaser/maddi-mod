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

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.api.statement.Statement;
import io.codelaser.maddi.modification.prepwork.variable.Links;
import io.codelaser.maddi.modification.prepwork.variable.Stage;
import io.codelaser.maddi.modification.prepwork.variable.VariableData;
import io.codelaser.maddi.modification.prepwork.variable.VariableInfo;
import io.codelaser.maddi.modification.prepwork.variable.VariableInfoContainer;

/**
 * A measurement, switched on by the environment variable {@code VDCENSUS}: how much of the per-statement variable
 * data repeats the previous statement's. Logged once after prep and once after the modification analysis; it changes
 * nothing. The numbers decide whether the copy-on-write evaluation (a container that inherits its previous
 * statement's evaluation until a write differs) and shared merge values are worth building.
 */
public final class VariableDataCensus {
    private static final Logger LOGGER = LoggerFactory.getLogger(VariableDataCensus.class);
    public static final boolean ON = System.getenv("VDCENSUS") != null;

    private long statementData;
    private long entries;
    private long previous;
    private long untouched;          // carried, evaluation with the previous info's own Assignments and Reads
    private long untouchedSameLinks; // ... and the same links (content) as the previous info
    private long untouchedSameAll;   // ... and the same analysis properties: a copy that stores nothing new
    private long linksSet;           // evaluations with links written
    private long merges;
    private long mergeSameAssignments;
    private long mergeSameReads;
    private long mergeSameAll;       // a merge equal to its evaluation in assignments, reads, links and properties
    private long readsIndices;       // total read indices held by distinct Reads objects
    private long distinctReads;
    private long assignmentIndices;
    private long distinctAssignments;

    private VariableDataCensus() {
    }

    public static void log(String when, Iterable<TypeInfo> primaryTypes) {
        if (!ON) return;
        VariableDataCensus c = new VariableDataCensus();
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<Object> values = Collections.newSetFromMap(new IdentityHashMap<>());
        for (TypeInfo primary : primaryTypes) {
            primary.recursiveSubTypeStreamIncludingLocal().forEach(t -> t.constructorAndMethodStream().forEach(mi ->
                    c.method(mi, seen, values)));
        }
        LOGGER.info("VDCENSUS {}: statement VDs {}, containers {} (previous {}), untouched {}"
                    + " (same links {}, same links+properties {}), evaluations with links {}; merges {} (same"
                    + " assignments {}, same reads {}, same all {}); Reads {} holding {} indices, Assignments {}"
                    + " holding {} indices", when, c.statementData, c.entries, c.previous,
                c.untouched, c.untouchedSameLinks, c.untouchedSameAll, c.linksSet, c.merges,
                c.mergeSameAssignments, c.mergeSameReads, c.mergeSameAll, c.distinctReads, c.readsIndices,
                c.distinctAssignments, c.assignmentIndices);
    }

    private void method(MethodInfo mi, Set<Object> seen, Set<Object> values) {
        if (mi.methodBody() == null) return;
        walk(mi.methodBody().statements(), seen, values);
    }

    private void walk(List<Statement> statements, Set<Object> seen, Set<Object> values) {
        for (Statement s : statements) {
            VariableData vd = VariableDataImpl.of(s);
            if (vd != null && seen.add(vd)) {
                statementData++;
                vd.variableInfoContainerStream().forEach(vic -> container(vic, values));
            }
            s.subBlockStream().forEach(b -> walk(b.statements(), seen, values));
        }
    }

    private void container(VariableInfoContainer vic, Set<Object> values) {
        entries++;
        VariableInfo eval = vic.hasEvaluation() ? vic.best(Stage.EVALUATION) : null;
        for (VariableInfo vi : List.of(vic.best(Stage.MERGE), vic.best(Stage.EVALUATION),
                vic.getPreviousOrInitial())) {
            if (values.add(vi.reads())) {
                distinctReads++;
                readsIndices += vi.reads().indices().size();
            }
            if (values.add(vi.assignments())) {
                distinctAssignments++;
                assignmentIndices += vi.assignments().size();
            }
        }
        if (eval != null && eval.linkedVariables() != null) linksSet++;
        if (vic.isPrevious()) {
            previous++;
            VariableInfo prev = vic.getPreviousOrInitial();
            if (eval != null && eval != prev && eval.assignments() == prev.assignments()
                && eval.reads() == prev.reads()) {
                untouched++;
                if (sameLinks(eval.linkedVariables(), prev.linkedVariables())) {
                    untouchedSameLinks++;
                    if (sameProperties(eval, prev)) untouchedSameAll++;
                }
            }
        }
        if (vic.hasMerge()) {
            VariableInfo merge = vic.best(Stage.MERGE);
            VariableInfo base = vic.best(Stage.EVALUATION);
            if (merge != base) {
                merges++;
                boolean sameA = merge.assignments().equals(base.assignments());
                boolean sameR = merge.reads().indices().equals(base.reads().indices());
                if (sameA) mergeSameAssignments++;
                if (sameR) mergeSameReads++;
                if (sameA && sameR && sameLinks(merge.linkedVariables(), base.linkedVariables())
                    && sameProperties(merge, base)) {
                    mergeSameAll++;
                }
            }
        }
    }

    private static boolean sameLinks(Links l1, Links l2) {
        if (l1 == l2) return true;
        if (l1 == null || l2 == null) return false;
        return VariableInfoImpl.contentKey(l1).equals(VariableInfoImpl.contentKey(l2));
    }

    private static boolean sameProperties(VariableInfo v1, VariableInfo v2) {
        return Objects.equals(
                v1.analysis().propertyValueStream().collect(Collectors.toSet()),
                v2.analysis().propertyValueStream().collect(Collectors.toSet()));
    }
}
