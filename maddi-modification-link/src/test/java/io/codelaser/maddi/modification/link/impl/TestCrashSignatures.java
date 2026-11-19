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

package io.codelaser.maddi.modification.link.impl;

import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.modification.link.CommonTest;
import io.codelaser.maddi.modification.prepwork.PrepAnalyzer;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Crashes of the link computation, each the shape of a method of a large open-source corpus that crashed the
 * analysis. One test per shape: linking the method must not throw.
 */
public class TestCrashSignatures extends CommonTest {

    private void link(String input, String... methodNames) {
        TypeInfo x = javaInspector.parse("a.b.X", input);
        new PrepAnalyzer(runtime, new PrepAnalyzer.Options.Builder().build()).doPrimaryType(x);
        LinkComputerImpl lc = new LinkComputerImpl(javaInspector);
        for (String methodName : methodNames) {
            MethodInfo mi = x.methodStream().filter(m -> m.name().equals(methodName)).findFirst().orElseThrow();
            System.out.println("CRASHSIG " + methodName + " " + lc.doMethod(mi));
        }
    }

    @Language("java")
    private static final String NULL_IN_AND = """
            package a.b;
            class X {
                interface State { }
                interface PartState extends State { Object part(); }
                static Boolean match(State s, State t) {
                    if (!(s instanceof PartState ps)) {
                        return false;
                    } else if (s == t) {
                        return null;
                    } else {
                        Object part = ps.part();
                        return part instanceof String str && str.isEmpty() ? null : false;
                    }
                }
            }
            """;

    @DisplayName("'c ? null : false', a Boolean, is no 'c && null' (EvalInlineConditional, EvalAnd)")
    @Test
    public void nullInAnd() {
        link(NULL_IN_AND, "match");
    }

    @Language("java")
    private static final String RECURSIVE_APPLY = """
            package a.b;
            import java.util.*;
            import java.util.function.Function;
            class X {
                static class Join { Group joined; Group getJoinedGroup() { return joined; } }
                static class Group { final List<Join> joins = new ArrayList<>();
                    List<Join> getJoins() { return joins; } }
                final List<Group> roots = new ArrayList<>();
                public <T> T query(Function<Group, T> action) {
                    for (int i = 0; i < roots.size(); i++) {
                        final T result = query(roots.get(i), action);
                        if (result != null) return result;
                    }
                    return null;
                }
                private <T> T query(Group group, Function<Group, T> action) {
                    final T result = action.apply(group);
                    if (result != null) return result;
                    final List<Join> joins = group.getJoins();
                    for (int i = 0; i < joins.size(); i++) {
                        final T nested = query(joins.get(i).getJoinedGroup(), action);
                        if (nested != null) return nested;
                    }
                    return null;
                }
            }
            """;

    @DisplayName("a recursive method applying its Function parameter (LinkAppliedFunctionalInterface, LinksImpl.add)")
    @Test
    public void recursiveApply() {
        link(RECURSIVE_APPLY, "query");
    }

    @Language("java")
    private static final String BICONSUMER_PUT = """
            package a.b;
            import java.util.*;
            import java.util.function.BiConsumer;
            class X {
                static class Ref { String alias; String getAlias() { return alias; } }
                static class Join { Ref joined; Ref getJoined() { return joined; } }
                static class Group { Ref primary; final List<Join> joins = new ArrayList<>();
                    Ref getPrimary() { return primary; } List<Join> getJoins() { return joins; } }
                private void collect(Ref ref, BiConsumer<String, Ref> consumer) {
                    consumer.accept(ref.getAlias(), ref);
                }
                private void collect(Join join, BiConsumer<String, Ref> consumer) {
                    collect(join.getJoined(), consumer);
                }
                Map<String, Ref> byAlias(Group group) {
                    final Map<String, Ref> byAlias = new HashMap<>();
                    collect(group.getPrimary(), byAlias::put);
                    for (int i = 0; i < group.getJoins().size(); i++) {
                        collect(group.getJoins().get(i), byAlias::put);
                    }
                    return byAlias;
                }
            }
            """;

    @DisplayName("a map's 'put' handed on as a BiConsumer, through an overload (SliceFactory.create)")
    @Test
    public void biConsumerPut() {
        link(BICONSUMER_PUT, "collect", "byAlias");
    }

    @Language("java")
    private static final String ARRAY_OF_COPY = """
            package a.b;
            import java.util.*;
            class X {
                private final String[] closure = new String[4];
                private final int span = 1;
                static String[] toStringArray(Collection<String> c) { return c.toArray(new String[0]); }
                private void associate(String tableName, Set<String> classNames, String[][] mapping) {
                    boolean found = false;
                    for (int i = 1; i < closure.length; i++) {
                        if (closure[i].equals(tableName)) {
                            found = true;
                            final int index = i - span;
                            if (index < 0 || index >= mapping.length) throw new IllegalStateException("out");
                            mapping[index] = toStringArray(classNames);
                            break;
                        }
                    }
                    if (!found) throw new IllegalStateException("none");
                }
            }
            """;

    @DisplayName("an element of an array parameter assigned a copy of a set parameter (Graph.liftElementsLeavingScope)")
    @Test
    public void arrayOfCopy() {
        link(ARRAY_OF_COPY, "associate");
    }
}
