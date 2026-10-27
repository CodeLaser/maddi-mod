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

package io.codelaser.maddi.modification.analyzer.modification;

import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.modification.analyzer.CommonTest;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A modification through a field of a call's result reaches what the result stands for: 'parent.self().data.add(x)'
 * modifies parent when self() returns this. The variable 'parent.self().data' has no scope variable past the call,
 * so the modification stopped there; a getter ('parent.next().data') escaped it by being inlined as 'parent.next'.
 * fernflower's NestedClassProcessor.insertLocalVars modifies its parameter only through
 * 'parent.getWrapper().getMethods().getWithKey(..)', a local it modifies in a loop (CodeLaser/maddi-mod#72).
 */
public class TestModificationThroughCallResult extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import java.util.*;
            public class X {
                static class Node {
                    Node next;
                    Node parent;
                    int type;
                    List<String> data;
                    Map<String, Node> map;
                    Node self() { return this; }
                    Node either(boolean c) { return c ? this : next; }
                }
                static void viaSelf(Node parent) { parent.self().data.add("x"); }
                static void viaEither(Node parent, boolean c) { parent.either(c).data.add("x"); }
                static void viaLocal(Node parent, boolean c, String key, List<String> xs) {
                    Node em = parent.either(c).map.get(key);
                    for (String x : xs) { if (x != null) em.data.add(x); }
                }
                static int reads(Node parent) { return parent.self().data.size(); }
            }
            """;

    @DisplayName("a modification through a field of a call's result reaches the object the result stands for")
    @Test
    public void test() {
        TypeInfo X = javaInspector.parse("a.b.X", INPUT);
        List<Info> ao = prepWork(X);
        analyzer.go(ao, 3);
        assertFalse(X.findUniqueMethod("viaSelf", 1).parameters().getFirst().isUnmodified(), "viaSelf");
        assertFalse(X.findUniqueMethod("viaEither", 2).parameters().getFirst().isUnmodified(), "viaEither");
        assertFalse(X.findUniqueMethod("viaLocal", 4).parameters().getFirst().isUnmodified(), "viaLocal");
        assertTrue(X.findUniqueMethod("reads", 1).parameters().getFirst().isUnmodified(), "a read modifies nothing");
    }
}
