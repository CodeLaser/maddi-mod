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

package io.codelaser.maddi.run.kotlinmain;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Compares two verdict dumps of one program, one per front end (RunMixedPrepAnalyzer's {@code -Dmaddi.verdictDump}
 * and {@code -Dmaddi.memberVerdictDump}), for {@link TestKotlinVsJavaFernflower}.
 *
 * <p>The two front ends name some members differently. The comparison first pairs them, then sorts what still has
 * no counterpart by the reason, so that an "only" left over is a real gap and not a naming difference:
 * <ul>
 *     <li>a companion's member is {@code Outer.Companion.m()} in Kotlin, {@code Outer.m()} (static) in Java:
 *     {@code .Companion} is dropped from the Kotlin key;</li>
 *     <li>{@code MutableList.removeAt(int)} is Java's {@code remove(int)};</li>
 *     <li>a boxed parameter J2K made primitive ({@code Integer} → {@code Int}) is paired with its unboxed key;</li>
 *     <li>a Java record's accessor {@code x()} is the {@code @JvmRecord data class}'s {@code getX()};</li>
 *     <li>a property's {@code getX()/isX()/setX(T)} exist only in Kotlin, as do the companion's own field and
 *     static initializer, and a data class's {@code componentN()} and {@code copy}.</li>
 * </ul>
 * <p>A verdict that differs is directional: {@code true} is "unmodified" (F, P) or "non-modifying" (M), so
 * true→false is precision the Kotlin side lost and false→true is Kotlin claiming more than Java, to be checked for
 * soundness. {@code NONE} (no verdict at all) is counted apart.
 */
final class VerdictComparison {
    static final String NONE = "NONE";

    private static final Pattern ACCESSOR = Pattern.compile("(.*)\\.(get|is|set)([A-Z_][^.(]*)\\(([^)]*)\\)(#\\d+)?");
    private static final Pattern GETTER = Pattern.compile("(.*)\\.get([A-Z])([^.(]*)\\(\\)");
    private static final Pattern DATA_CLASS = Pattern.compile(".*\\.(component\\d+|copy|copy\\$default)\\([^)]*\\)(#\\d+)?");

    private VerdictComparison() {
    }

    /** `<verdict> <fqn>` (types) or `<kind> <verdict> <key>` (members), by key; a member's kind stays in its key. */
    static Map<String, String> read(Path dump, boolean types, boolean kotlin) throws IOException {
        return read(Files.readAllLines(dump), types, kotlin);
    }

    static Map<String, String> read(List<String> lines, boolean types, boolean kotlin) {
        Map<String, String> byKey = new TreeMap<>();
        for (String line : lines) {
            if (line.isBlank()) continue;
            String[] parts = line.split(" ", types ? 2 : 3);
            String key = types ? parts[1] : parts[0] + " " + parts[2];
            String value = types ? parts[0] : parts[1];
            byKey.put(kotlin ? kotlinKey(key) : key, value);
        }
        return byKey;
    }

    /** The Kotlin key under Java's name for the same member. */
    static String kotlinKey(String key) {
        return key.replace(".Companion.", ".").replace(".removeAt(int)", ".remove(int)");
    }

    static Map<String, String> only(Map<String, String> members, String kind) {
        Map<String, String> result = new TreeMap<>();
        members.forEach((k, v) -> {
            if (k.startsWith(kind + " ")) result.put(k.substring(2), v);
        });
        return result;
    }

    /** The outcome per kind: counts, and every element per category, for the report. */
    record Outcome(String kind, Map<String, List<String>> categories) {
        int count(String category) {
            return categories.getOrDefault(category, List.of()).size();
        }

        String summary() {
            StringBuilder sb = new StringBuilder(kind).append(":");
            categories.forEach((c, l) -> sb.append(' ').append(c).append('=').append(l.size()));
            return sb.toString();
        }
    }

    static final String AGREE = "agree";
    static final String WEAKER = "kotlin-weaker";
    static final String STRONGER = "kotlin-stronger";
    static final String KOTLIN_NONE = "kotlin-none";
    static final String JAVA_NONE = "java-none";
    static final String DIFFER = "differ"; // types: no order between the two levels is assumed
    static final String JAVA_ONLY = "java-only";
    static final String ACCESSOR_ONLY = "kotlin-only-accessor";
    static final String COMPANION_ONLY = "kotlin-only-companion";
    static final String STATIC_INIT_ONLY = "kotlin-only-static-init";
    static final String DATA_CLASS_ONLY = "kotlin-only-data-class";
    static final String ENUM_ONLY = "kotlin-only-enum-name";
    static final String JAVA_INITIALIZER_ONLY = "java-only-initializer";
    static final String NESTED_ONLY = "kotlin-only-nested-type";
    static final String KOTLIN_ONLY = "kotlin-only-other";

    /**
     * @param kotlinFields the Kotlin side's field keys (without kind), to recognise a property's accessors
     * @param javaTypes    the Java side's type names, to recognise a nested type the Java type dump leaves out
     */
    static Outcome compare(String kind, Map<String, String> java, Map<String, String> kotlinIn,
                           Set<String> kotlinFields, Set<String> javaTypes) {
        Map<String, String> kotlin = unboxedPaired(java, recordAccessorsPaired(java, kotlinIn));
        Map<String, List<String>> categories = new LinkedHashMap<>();
        for (String c : List.of(AGREE, WEAKER, STRONGER, DIFFER, KOTLIN_NONE, JAVA_NONE, JAVA_ONLY, ACCESSOR_ONLY,
                COMPANION_ONLY, STATIC_INIT_ONLY, DATA_CLASS_ONLY, ENUM_ONLY, NESTED_ONLY, KOTLIN_ONLY,
                JAVA_INITIALIZER_ONLY)) {
            categories.put(c, new ArrayList<>());
        }
        boolean types = "T".equals(kind);
        java.forEach((key, j) -> {
            String k = kotlin.get(key);
            String category;
            if (k == null) category = key.contains(".<init_") || key.contains(".<static_") ? JAVA_INITIALIZER_ONLY : JAVA_ONLY;
            else if (k.equals(j)) category = AGREE;
            else if (NONE.equals(k)) category = KOTLIN_NONE;
            else if (NONE.equals(j)) category = JAVA_NONE;
            else if (types) category = DIFFER;
            else category = "true".equals(j) ? WEAKER : STRONGER;
            categories.get(category).add(category.equals(AGREE) || k == null
                    ? j + "  " + key : j + " -> " + k + "  " + key);
        });
        kotlin.forEach((key, k) -> {
            if (!java.containsKey(key)) {
                categories.get(kotlinOnlyCategory(key, types, kotlinFields, javaTypes)).add(k + "  " + key);
            }
        });
        return new Outcome(kind, categories);
    }

    private static final Map<String, String> UNBOXED = Map.of("Integer", "int", "Long", "long", "Boolean", "boolean",
            "Character", "char", "Double", "double", "Float", "float", "Short", "short", "Byte", "byte");
    private static final Pattern BOXED = Pattern.compile("(?<=[(,])(Integer|Long|Boolean|Character|Double|Float|Short|Byte)(?=[,)])");

    /** A Kotlin key under Java's when the two are equal once every boxed parameter type is unboxed, and neither has a match. */
    private static Map<String, String> unboxedPaired(Map<String, String> java, Map<String, String> kotlin) {
        Map<String, String> javaByUnboxed = new TreeMap<>();
        java.keySet().forEach(j -> {
            if (!kotlin.containsKey(j)) {
                String u = BOXED.matcher(j).replaceAll(r -> UNBOXED.get(r.group()));
                if (!u.equals(j)) javaByUnboxed.put(u, j);
            }
        });
        Map<String, String> result = new TreeMap<>();
        kotlin.forEach((key, v) -> {
            String j = java.containsKey(key) ? null : javaByUnboxed.get(BOXED.matcher(key).replaceAll(r -> UNBOXED.get(r.group())));
            result.put(j != null ? j : key, v);
        });
        return result;
    }

    /** {@code getX()} in Kotlin, under Java's {@code x()} when that is a Java member and {@code getX()} is not. */
    private static Map<String, String> recordAccessorsPaired(Map<String, String> java, Map<String, String> kotlin) {
        Map<String, String> result = new TreeMap<>();
        kotlin.forEach((key, v) -> {
            Matcher m = GETTER.matcher(key);
            String record = m.matches() ? m.group(1) + "." + Character.toLowerCase(m.group(2).charAt(0)) + m.group(3) + "()"
                    : null;
            result.put(record != null && !java.containsKey(key) && java.containsKey(record) && !kotlin.containsKey(record)
                    ? record : key, v);
        });
        return result;
    }

    private static String kotlinOnlyCategory(String key, boolean types, Set<String> kotlinFields, Set<String> javaTypes) {
        if (key.endsWith(".Companion") || key.contains(".Companion#")) return COMPANION_ONLY;
        if (types) {
            int dot = key.lastIndexOf('.');
            return dot > 0 && isTypeOrNested(key.substring(0, dot), javaTypes) ? NESTED_ONLY : KOTLIN_ONLY;
        }
        if (key.contains(".<static_")) return STATIC_INIT_ONLY;
        if (DATA_CLASS.matcher(key).matches()) return DATA_CLASS_ONLY;
        if (key.endsWith(".name()")) return ENUM_ONLY;
        Matcher m = ACCESSOR.matcher(key);
        if (m.matches()) {
            String owner = m.group(1);
            String name = m.group(3);
            boolean getter = !"set".equals(m.group(2));
            if (getter == m.group(4).isEmpty()
                && (kotlinFields.contains(owner + "." + Character.toLowerCase(name.charAt(0)) + name.substring(1))
                    || kotlinFields.contains(owner + "." + name)
                    || kotlinFields.contains(owner + ".is" + name)
                    || "is".equals(m.group(2)) && kotlinFields.contains(owner + ".is" + name))) {
                return ACCESSOR_ONLY;
            }
        }
        // a property named isX keeps its name as its getter's
        int paren = key.indexOf("()");
        if (paren > 0 && paren == key.length() - 2 && kotlinFields.contains(key.substring(0, paren))) return ACCESSOR_ONLY;
        return KOTLIN_ONLY;
    }

    /** The Java type dump lists primary types only: a nested type counts as known when its primary type is. */
    private static boolean isTypeOrNested(String name, Set<String> javaTypes) {
        for (String n = name; ; ) {
            if (javaTypes.contains(n)) return true;
            int dot = n.lastIndexOf('.');
            if (dot < 0) return false;
            n = n.substring(0, dot);
        }
    }

    /** The report: one summary line per kind, then every element that is not in agreement, by category. */
    static List<String> report(List<Outcome> outcomes) {
        List<String> lines = new ArrayList<>();
        outcomes.forEach(o -> lines.add(o.summary()));
        for (Outcome o : outcomes) {
            o.categories().forEach((c, l) -> {
                if (!c.equals(AGREE)) l.forEach(e -> lines.add("  " + o.kind() + " " + c + "  " + e));
            });
        }
        return lines;
    }

    /**
     * The categories the ratchet pins element by element: every verdict that differs, and every member with no
     * counterpart that no naming rule explains. The naming categories (accessors, companions, ...) are pinned by their
     * counts only, in the summary lines.
     */
    static final List<String> PINNED = List.of(WEAKER, STRONGER, KOTLIN_NONE, JAVA_NONE, DIFFER, JAVA_ONLY, KOTLIN_ONLY);

    /** What the ratchet records: the per-side lines, every kind's summary, then each element of a pinned category. */
    static List<String> ratchetLines(List<String> sides, List<Outcome> outcomes) {
        List<String> lines = new ArrayList<>(sides);
        outcomes.forEach(o -> lines.add(o.summary()));
        for (Outcome o : outcomes) {
            for (String c : PINNED) {
                o.categories().getOrDefault(c, List.of()).forEach(e -> lines.add(o.kind() + " " + c + "  " + e));
            }
        }
        return lines;
    }

    /**
     * Empty when the actual lines are the recorded ones (comments and blank lines skipped; order is free). Otherwise
     * the lines that are new and the lines that are gone. A per-side line (`java ...`, `kotlin ...`) is compared only
     * when the actual lines have them: a re-comparison of saved dumps has no run summaries.
     */
    static String ratchetDiff(List<String> recorded, List<String> actual) {
        boolean sides = actual.stream().anyMatch(VerdictComparison::isSideLine);
        Set<String> want = new TreeSet<>();
        for (String l : recorded) {
            if (l.isBlank() || l.startsWith("#") || !sides && isSideLine(l)) continue;
            want.add(l);
        }
        Set<String> have = new TreeSet<>(actual);
        Set<String> added = new TreeSet<>(have);
        added.removeAll(want);
        Set<String> gone = new TreeSet<>(want);
        gone.removeAll(have);
        if (added.isEmpty() && gone.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        if (!added.isEmpty()) {
            sb.append("new, not recorded (a regression, or a change to record deliberately):\n");
            added.forEach(l -> sb.append("+ ").append(l).append('\n'));
        }
        if (!gone.isEmpty()) {
            sb.append("recorded, now gone (an improvement: remove them from the ratchet in this commit):\n");
            gone.forEach(l -> sb.append("- ").append(l).append('\n'));
        }
        return sb.toString();
    }

    private static boolean isSideLine(String l) {
        return l.startsWith("java ") || l.startsWith("kotlin ");
    }

    /** A culprit dump ({@code <kind> <member>}, then indented steps), by key; Kotlin keys under Java's names. */
    static Map<String, List<String>> readCulprits(List<String> lines, boolean kotlin) {
        Map<String, List<String>> result = new TreeMap<>();
        List<String> current = null;
        for (String line : lines) {
            if (line.isBlank()) continue;
            if (line.startsWith(" ")) {
                if (current != null) current.add(line);
            } else {
                current = new ArrayList<>();
                result.put(kotlin ? kotlinKey(line) : line, current);
            }
        }
        return result;
    }

    static Set<String> keysOf(Map<String, String> map) {
        return new TreeSet<>(map.keySet());
    }
}
