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

import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.codelaser.maddi.cst.api.info.FieldInfo;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.variable.FieldReference;
import io.codelaser.maddi.modification.prepwork.variable.Link;
import io.codelaser.maddi.modification.prepwork.variable.Links;
import io.codelaser.maddi.modification.prepwork.variable.VariableData;
import io.codelaser.maddi.modification.prepwork.variable.VariableInfo;
import io.codelaser.maddi.modification.prepwork.variable.impl.VariableDataImpl;

/*
 DIAGNOSTIC FIELDLINKTRACE=Type.field,Type.field: a field's links are read off the last statement of every method of
 its primary type that refers to it. This logs, for the named fields, what a method's variable data offers the field
 each time the method's links are computed ("method") and each time the field analyzer reads it ("field"), as the
 number of links and the '$_' markers among their targets. It tells a field that read a method before its last
 computation from a method whose computations differ (fernflower's StructContext.loader, 2026-11-10: a link to one
 marker present in some runs and absent in others).
 */
final class FieldLinkTrace {
    private static final Logger LOGGER = LoggerFactory.getLogger(FieldLinkTrace.class);
    private static final Set<String> FIELDS = System.getenv("FIELDLINKTRACE") == null ? Set.of()
            : Arrays.stream(System.getenv("FIELDLINKTRACE").split(",")).map(String::strip)
            .collect(Collectors.toUnmodifiableSet());

    private FieldLinkTrace() {
    }

    static boolean traced(FieldInfo fieldInfo) {
        return !FIELDS.isEmpty() && FIELDS.contains(fieldInfo.owner().simpleName() + "." + fieldInfo.name());
    }

    /* the method's links have just been computed: what does it now offer the traced fields it refers to? */
    static void afterMethod(MethodInfo methodInfo, int pass) {
        if (FIELDS.isEmpty() || methodInfo.methodBody() == null || methodInfo.methodBody().isEmpty()) return;
        VariableData vd = VariableDataImpl.of(methodInfo.methodBody().lastStatement());
        if (vd == null) return;
        for (VariableInfo vi : vd.variableInfoIterable()) {
            if (vi.variable() instanceof FieldReference fr && traced(fr.fieldInfo())) {
                LOGGER.info("FIELDLINKTRACE method pass {} {} offers {}: {}", pass, methodInfo.fullyQualifiedName(),
                        fr.fieldInfo().name(), describe(vi.linkedVariables()));
            }
        }
    }

    /* the field analyzer reads this method's variable data for the field */
    static void fieldReads(FieldInfo fieldInfo, MethodInfo methodInfo, Links links) {
        LOGGER.info("FIELDLINKTRACE field {} reads {}: {}", fieldInfo.name(), methodInfo.fullyQualifiedName(),
                describe(links));
    }

    /* the field is linked to a local of the method; the local's own links are composed onto the field */
    static void throughLocal(FieldInfo fieldInfo, VariableData vd, Link fieldToLocal, Links localLinks) {
        LOGGER.info("FIELDLINKTRACE field {} through local, vd {}: {}; the local has {}", fieldInfo.name(),
                Integer.toHexString(System.identityHashCode(vd)), fieldToLocal, describe(localLinks));
    }

    static void fieldResult(FieldInfo fieldInfo, Links links) {
        LOGGER.info("FIELDLINKTRACE field {} result: {}", fieldInfo.name(), describe(links));
    }

    private static String describe(Links links) {
        if (links == null) return "no links yet";
        Set<String> markers = new TreeSet<>();
        int n = 0;
        for (Link l : links) {
            ++n;
            String to = String.valueOf(l.to());
            if (to.startsWith("$_")) markers.add(to);
        }
        return n + " link(s), markers " + markers;
    }
}
