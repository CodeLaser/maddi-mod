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

package io.codelaser.maddi.modification.analyzer.roadtoimmutability;

import io.codelaser.maddi.cst.api.analysis.Value;
import io.codelaser.maddi.cst.api.info.FieldInfo;
import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.ParameterInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.impl.analysis.PropertyImpl;
import io.codelaser.maddi.cst.impl.analysis.ValueImpl;
import io.codelaser.maddi.modification.analyzer.CommonTest;
import io.codelaser.maddi.modification.analyzer.impl.IteratingAnalyzerImpl;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Base class of the tests that pin the examples of the book, "The Road to Immutability"
 * (maddi, road-to-immutability/src/docs/asciidoc/sections). One test class per chapter.
 * <p>
 * An example is analysed as the production engine does it (the reachability pass on, the ownership pass on), and
 * every type, field, method and parameter gets one line of verdicts, written as the book writes its annotations:
 * <ul>
 *     <li>{@code Type}: immutability, independence, {@code @Container};</li>
 *     <li>{@code Type.field}: {@code @Final} when effectively final without the modifier, modification,
 *     independence, {@code @Owned};</li>
 *     <li>{@code Type.method()}: modification, independence, then {@code @Fluent}, {@code @Identity},
 *     {@code @StaticSideEffects}, {@code @UnmodifiableView}, {@code @Fresh}, {@code @Finalizer};</li>
 *     <li>{@code Type.method(parameter)}: modification, independence, {@code @Borrowed}.</li>
 * </ul>
 * A test states the lines that the book states; where the analyzer deviates from the book, the test pins what the
 * analyzer computes, with a comment, and the book's chapter "The analyzer today" lists the deviation.
 * The complete set of lines of a run is written to {@code build/book-verdicts}, for whoever edits the book.
 */
public abstract class BookTest extends CommonTest {

    protected Map<String, String> verdicts(String fqn, String input) throws IOException {
        return verdicts(fqn, input, false);
    }

    protected Map<String, String> verdicts(String fqn, String input, boolean closedWorld) throws IOException {
        AnalyzerBundle bundle = buildAnalyzerBundle();
        TypeInfo primary = bundle.javaInspector().parse(fqn, input);
        List<TypeInfo> primaryTypes = primary.compilationUnit().types();
        List<Info> analysisOrder = primaryTypes.size() == 1 ? bundle.prepAnalyzer().doPrimaryType(primary)
                : bundle.prepAnalyzer().doPrimaryTypes(Set.copyOf(primaryTypes));
        new IteratingAnalyzerImpl(bundle.javaInspector(), new IteratingAnalyzerImpl.ConfigurationBuilder()
                .setMaxIterations(10).setModificationViaReachability(true).setOwnership(true)
                .setOwnershipClosedWorld(closedWorld).build())
                .analyze(analysisOrder);
        Map<String, String> map = new TreeMap<>();
        primaryTypes.stream().flatMap(TypeInfo::recursiveSubTypeStream).forEach(t -> {
            String tn = t.simpleName();
            map.put(tn, join(value(t, PropertyImpl.IMMUTABLE_TYPE, ValueImpl.ImmutableImpl.class),
                    value(t, PropertyImpl.INDEPENDENT_TYPE, ValueImpl.IndependentImpl.class),
                    flag(t, PropertyImpl.CONTAINER_TYPE, "@Container")));
            for (FieldInfo f : t.fields()) {
                map.put(tn + "." + f.name(), join(!f.isFinal() && f.isPropertyFinal() ? "@Final" : "",
                        modified(f.isUnmodified(), f.isStructurallyUnmodified()),
                        value(f, PropertyImpl.INDEPENDENT_FIELD, ValueImpl.IndependentImpl.class),
                        flag(f, PropertyImpl.OWNED_FIELD, "@Owned")));
            }
            for (MethodInfo m : t.constructorsAndMethods()) {
                String mn = tn + "." + (m.isConstructor() ? "<init>" : m.name());
                String key = mn + "()";
                int i = 1;
                while (map.containsKey(key)) key = mn + "#" + (++i) + "()";
                String prefix = key.substring(0, key.length() - 2);
                map.put(key, join(m.isConstructor() ? "" : m.isNonModifying() ? "@NotModified" : "@Modified",
                        value(m, PropertyImpl.INDEPENDENT_METHOD, ValueImpl.IndependentImpl.class),
                        m.isFluent() ? "@Fluent" : "", m.isIdentity() ? "@Identity" : "",
                        flag(m, PropertyImpl.STATIC_SIDE_EFFECTS_METHOD, "@StaticSideEffects"),
                        flag(m, PropertyImpl.UNMODIFIABLE_VIEW_METHOD, "@UnmodifiableView"),
                        flag(m, PropertyImpl.FRESH_METHOD, "@Fresh"),
                        m.isFinalizer() ? "@Finalizer" : ""));
                for (ParameterInfo p : m.parameters()) {
                    map.put(prefix + "(" + p.name() + ")", join(
                            modified(p.isUnmodified(), p.isStructurallyUnmodified()),
                            value(p, PropertyImpl.INDEPENDENT_PARAMETER, ValueImpl.IndependentImpl.class),
                            flag(p, PropertyImpl.BORROWED_PARAMETER, "@Borrowed")));
                }
            }
        });
        write(map, closedWorld);
        return map;
    }

    /**
     * Checks what the book states, given as "key = verdicts": every verdict listed must be among the computed ones
     * of that element, and one preceded by '!' must not be.
     */
    protected static void assertVerdicts(Map<String, String> actual, String... expected) {
        StringBuilder failures = new StringBuilder();
        for (String line : expected) {
            int eq = line.indexOf(" = ");
            if (eq < 0) throw new IllegalArgumentException("Expect 'key = verdicts': " + line);
            String key = line.substring(0, eq).trim();
            String computed = actual.getOrDefault(key, "<no such element>");
            List<String> tokens = List.of(computed.split(" "));
            for (String token : line.substring(eq + 3).trim().split(" ")) {
                boolean ok = token.startsWith("!") ? !tokens.contains(token.substring(1)) : tokens.contains(token);
                if (!ok) {
                    failures.append(key).append(": the book says ").append(line.substring(eq + 3).trim())
                            .append(", computed ").append(computed).append("\n");
                    break;
                }
            }
        }
        assertEquals("", failures.toString());
    }

    private static String print(Map<String, String> map) {
        return map.entrySet().stream().map(e -> e.getKey() + " = " + e.getValue())
                .collect(Collectors.joining("\n", "", "\n"));
    }

    private static String modified(boolean unmodified, boolean structurallyUnmodified) {
        return unmodified ? "@NotModified" : structurallyUnmodified ? "@Modified(hc=true)" : "@Modified";
    }

    private static String value(Info info, io.codelaser.maddi.cst.api.analysis.Property property,
                                Class<? extends Value> clazz) {
        Value v = info.analysis().getOrNull(property, clazz);
        return v == null ? "" : v.toString();
    }

    private static String flag(Info info, io.codelaser.maddi.cst.api.analysis.Property property, String label) {
        return info.analysis().getOrDefault(property, ValueImpl.BoolImpl.FALSE).isTrue() ? label : "";
    }

    private static String join(String... parts) {
        String s = java.util.Arrays.stream(parts).filter(p -> p != null && !p.isBlank())
                .collect(Collectors.joining(" "));
        return s.isEmpty() ? "-" : s;
    }

    private void write(Map<String, String> map, boolean closedWorld) throws IOException {
        Path dir = Path.of("build", "book-verdicts");
        Files.createDirectories(dir);
        StackWalker.StackFrame frame = StackWalker.getInstance().walk(s -> s
                .filter(f -> !f.getClassName().equals(BookTest.class.getName())).findFirst().orElseThrow());
        String name = frame.getClassName().substring(frame.getClassName().lastIndexOf('.') + 1)
                      + "." + frame.getMethodName() + (closedWorld ? ".closed" : "") + ".txt";
        Files.writeString(dir.resolve(name), print(map));
    }
}
