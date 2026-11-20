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

package io.codelaser.maddi.modification.analyzer.integration;

import static io.codelaser.maddi.modification.link.impl.MethodLinkedVariablesImpl.METHOD_LINKS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.Test;

import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.api.statement.Statement;
import io.codelaser.maddi.modification.analyzer.CommonTest;
import io.codelaser.maddi.modification.analyzer.impl.IteratingAnalyzerImpl;
import io.codelaser.maddi.modification.link.impl.MethodLinkedVariablesImpl;
import io.codelaser.maddi.modification.prepwork.variable.impl.VariableDataImpl;

/**
 * CodeLaser/maddi-mod#138: objects mapped out of a parameter's elements with stream().map(..) were not linked to it, so
 * modifying them left the parameter unmodified. The for-each walk was right.
 */
public class TestMappedElements extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import java.util.ArrayList;
            import java.util.List;
            import java.util.Set;
            import java.util.stream.Collectors;
            class X {
                public static class St { public final List<String> exprents = new ArrayList<>(); }
                public record Item(St statement, boolean delete) { }
                static void viaLoop(List<Item> items) {
                    for (Item i : items) i.statement().exprents.clear();
                }
                static void viaMapToList(List<Item> items) {
                    List<St> sts = items.stream().map(a -> a.statement()).toList();
                    for (St s : sts) s.exprents.clear();
                }
                static void viaCollectSet(List<Item> items) {
                    Set<St> sts = items.stream().filter(a -> a.delete).map(a -> a.statement()).collect(Collectors.toSet());
                    for (St s : sts) s.exprents.clear();
                }
                static void viaMethodRef(List<Item> items) {
                    Set<St> sts = items.stream().map(Item::statement).collect(Collectors.toSet());
                    for (St s : sts) s.exprents.clear();
                }
                static void fresh(List<Item> items) {
                    List<St> sts = items.stream().map(a -> new St()).toList();
                    for (St s : sts) s.exprents.clear();
                }
                static void flags(List<Item> items) {
                    List<Boolean> bs = items.stream().map(a -> a.delete()).toList();
                    List<Boolean> copy = new ArrayList<>(bs);
                    copy.clear();
                }
                static void copyCleared(List<Item> items) {
                    List<St> sts = items.stream().map(a -> a.statement()).toList();
                    List<St> copy = new ArrayList<>(sts);
                    copy.clear();
                }
                static void flagsDirect(List<Item> items) {
                    List<Boolean> bs = new ArrayList<>(items.stream().map(a -> a.delete()).toList());
                    bs.clear();
                }
            }
            """;

    @Test
    public void test() {
        TypeInfo X = javaInspector.parse("a.b.X", INPUT);
        List<Info> analysisOrder = prepWork(X);
        analyzer.go(analysisOrder, 3);
        StringBuilder wrong = new StringBuilder();
        for (String name : new String[]{"viaLoop", "viaMapToList", "viaCollectSet", "viaMethodRef"}) {
            MethodInfo mi = X.findUniqueMethod(name, 1);
            Statement s0 = mi.methodBody().statements().getFirst();
            System.out.println(name + ": unmodified=" + mi.parameters().getFirst().isUnmodified() + " "
                               + mi.analysis().getOrNull(METHOD_LINKS, MethodLinkedVariablesImpl.class)
                               + " modified=" + mi.analysis().getOrNull(METHOD_LINKS, MethodLinkedVariablesImpl.class).modified());
            if (!name.equals("viaLoop")) {
                System.out.println("   s0 sts: " + VariableDataImpl.of(s0).variableInfo("sts").linkedVariables());
            }
            if (mi.parameters().getFirst().isUnmodified()) wrong.append(name).append(' ');
        }
        assertFalse(!wrong.isEmpty(), "items reported unmodified in: " + wrong);
        StringBuilder wronglyModified = new StringBuilder();
        for (String name : new String[]{"fresh", "flags", "copyCleared", "flagsDirect"}) {
            MethodInfo mi = X.findUniqueMethod(name, 1);
            System.out.println(name + ": unmodified=" + mi.parameters().getFirst().isUnmodified() + " "
                               + mi.analysis().getOrNull(METHOD_LINKS, MethodLinkedVariablesImpl.class));
            if (!mi.parameters().getFirst().isUnmodified()) wronglyModified.append(name).append(' ');
        }
        assertTrue(wronglyModified.isEmpty(), "the objects modified are not items', yet items modified in: "
                                              + wronglyModified);
    }

    @Language("java")
    private static final String INPUT2 = """
            package a.b;
            import java.util.ArrayList;
            import java.util.List;
            import java.util.Set;
            import java.util.stream.Collectors;
            class X {
                public static class St {
                    List<String> exprents = new ArrayList<>();
                    St parent;
                    List<String> getExprents() { return exprents; }
                    St getParent() { return parent; }
                }
                public record Item(St statement, boolean delete) { }
                static void p1(List<Item> items) {
                    Set<St> sts = items.stream().map(a -> a.statement()).collect(Collectors.toSet());
                    for (St st : sts) st.getExprents().clear();
                }
                static void p2(List<Item> items) {
                    Set<St> sts = items.stream().map(a -> a.statement()).collect(Collectors.toSet());
                    for (St st : sts) { St parent = st; parent.getExprents().clear(); }
                }
                static void p3(List<Item> items) {
                    Set<St> sts = items.stream().map(a -> a.statement()).collect(Collectors.toSet());
                    for (St st : sts) {
                        St parent = st;
                        while (parent != null) { parent.getExprents().clear(); parent = parent.getParent(); }
                    }
                }
                static void p5(List<Item> items) {
                    for (Item item : items) {
                        St parent = item.statement();
                        while (parent != null) {
                            List<List<String>> lists = new ArrayList<>();
                            lists.add(parent.getExprents());
                            for (List<String> l : lists) l.removeAll(List.of("x"));
                            parent = parent.getParent();
                        }
                    }
                }
                static void p4(List<Item> items) {
                    Set<St> sts = items.stream().map(a -> a.statement()).collect(Collectors.toSet());
                    for (St st : sts) {
                        List<List<String>> lists = new ArrayList<>();
                        lists.add(st.getExprents());
                        for (List<String> l : lists) l.removeAll(List.of("x"));
                    }
                }
                static void p6(List<Item> items) {
                    Set<St> sts = items.stream().map(a -> a.statement()).collect(Collectors.toSet());
                    for (St st : sts) {
                        St parent = st;
                        while (parent != null) {
                            List<List<String>> lists = new ArrayList<>();
                            lists.add(parent.getExprents());
                            for (List<String> l : lists) l.removeAll(List.of("x"));
                            parent = parent.getParent();
                        }
                    }
                }
                static void p7(List<Item> items) {
                    Set<St> sts = items.stream().map(a -> a.statement()).collect(Collectors.toSet());
                    for (St st : sts) {
                        List<List<String>> lists = new ArrayList<>();
                        lists.add(new ArrayList<>(st.getExprents()));
                        for (List<String> l : lists) l.clear();
                    }
                }
            }
            """;

    /*
     The steps of fernflower's SwitchHelper.removeTempVariableDeclarations: through a getter (p1), through an alias of
     the element (p2: 'St parent = st;', a shared-variable group, whose facts are on the group's representative),
     walking up the parents (p3); the element's list put into a list of lists and modified from there (p4, and p6
     inside the walk: 'lists.add(parent.getExprents()); for (List<String> l : lists) l.removeAll(..)'), which
     fernflower's method does. p5 is the walk without the stream. p7, the control: the list of lists holds copies.
     Both modes: production runs the reachability pass. fernflower's own parameter is right since the merge of
     376c6ddb (the reachability pass's element sources) with b7cbf009.
     */
    @Test
    public void stepsReachability() {
        assertEquals("p1=M p2=M p3=M p4=M p5=M p6=M p7=U", steps(true));
    }

    /*
     KNOWN GAP, the fixpoint engine only (production runs the reachability pass): p4 and p6, the list of lists after
     the stream; p5, the same without the stream, is right. Not traced. Likely: WriteLinksAndModification's #138
     rule needs a modified element of 'sts' ('sts.§$s ∩ items.§$s'), and the list modified here
     ('for (List<String> l : lists) l.removeAll(..)') is an element of 'lists', holding 'st.exprents'.
     */
    @Test
    public void stepsFixpoint() {
        assertEquals("p1=M p2=M p3=M p4=U p5=M p6=U p7=U", steps(false));
    }

    private String steps(boolean reach) {
        TypeInfo X = javaInspector.parse("a.b.X", INPUT2);
        List<Info> analysisOrder = prepWork(X);
        new IteratingAnalyzerImpl(javaInspector, new IteratingAnalyzerImpl.ConfigurationBuilder()
                .setMaxIterations(10).setModificationViaReachability(reach).build()).analyze(analysisOrder);
        StringBuilder sb = new StringBuilder();
        for (String name : new String[]{"p1", "p2", "p3", "p4", "p5", "p6", "p7"}) {
            MethodInfo mi = X.findUniqueMethod(name, 1);
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(name).append('=').append(mi.parameters().getFirst().isUnmodified() ? 'U' : 'M');
        }
        return sb.toString();
    }
}
