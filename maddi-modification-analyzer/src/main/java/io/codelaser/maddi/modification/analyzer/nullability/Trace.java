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
import io.codelaser.maddi.cst.api.info.Info;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The nullability pass's debugging output: every part prints through this one object, and only it reads the filter.
 * <p>
 * {@code NULLWHY=<substring>[|<substring>…]}: why a node matching one is (not) nullable: its seeds, the non-null
 * contracts put on it (with the code that did), and its final state. A debugging aid, as NULLMARKEDDEBUG.
 */
final class Trace {

    // the filter of the environment, read once
    static final Trace NULL_WHY = new Trace(System.getenv("NULLWHY"));

    private static final int CULPRIT_TEXT = 60;

    private final String filter;

    Trace(String filter) {
        this.filter = filter;
    }

    // whether any node is traced at all
    boolean enabled() {
        return filter != null;
    }

    boolean why(Object node) {
        if (filter == null || node == null) return false;
        String name = name(node);
        for (String part : filter.split("\\|")) if (name.contains(part)) return true;
        return false;
    }

    static String name(Object node) {
        return node instanceof Info info ? info.fullyQualifiedName() : String.valueOf(node);
    }

    // ' chain=' and the nodes the closure reached this one through, back to the seed and its origin
    String chain(Map<Object, Object> cause, Map<Object, String> seedOrigin, Object node) {
        StringBuilder sb = new StringBuilder(" chain=");
        Object n = node;
        Set<Object> seen = new HashSet<>();
        while (cause.containsKey(n) && seen.add(n)) {
            n = cause.get(n);
            sb.append(" <- ").append(NullabilityPass.Report.label(n));
        }
        String origin = seedOrigin.get(n);
        return origin == null ? sb.toString() : sb.append(" <- ").append(origin).toString();
    }

    void log(String what, Object node, String detail) {
        StringBuilder sb = new StringBuilder("NULLWHY ").append(what).append(' ').append(name(node));
        if (detail != null) sb.append(" :: ").append(detail);
        StackTraceElement[] trace = Thread.currentThread().getStackTrace();
        for (int i = 3; i < Math.min(trace.length, 7); i++) {
            sb.append("\n    at ").append(trace[i].getMethodName()).append(':').append(trace[i].getLineNumber());
        }
        print(sb.toString());
    }

    // NULLWHY: the path from the traced parameter to the node where its tolerance walk ended or passed; false
    boolean tolerance(Map<Object, Object> from, Object node, String what) {
        if (from == null) return false;
        StringBuilder sb = new StringBuilder("NULLWHY tolerance " + what + " at " + NullabilityPass.Report.label(node));
        Object n = node;
        for (int i = 0; i < 40 && from.get(n) != null; i++) {
            n = from.get(n);
            sb.append(" <- ").append(NullabilityPass.Report.label(n));
        }
        print(sb.toString());
        return false;
    }

    void print(Object line) {
        System.err.println(line);
    }

    // Culprits: the text of a statement in a step
    static String culpritText(Element element) {
        return shorten(element.toString().replaceAll("\\s+", " ").strip());
    }

    static String shorten(String s) {
        return s.length() <= CULPRIT_TEXT ? s : s.substring(0, CULPRIT_TEXT - 1) + "…";
    }
}
