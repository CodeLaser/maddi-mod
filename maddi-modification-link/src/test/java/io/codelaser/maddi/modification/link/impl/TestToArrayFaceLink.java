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

import static io.codelaser.maddi.modification.link.impl.MethodLinkedVariablesImpl.METHOD_LINKS;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.Test;

import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.modification.link.CommonTest;
import io.codelaser.maddi.modification.link.LinkComputer;
import io.codelaser.maddi.modification.prepwork.PrepAnalyzer;

/**
 * {@code Collection.toArray(T[] a)} may return {@code a} itself (CodeLaser/maddi#211): its summary links the return
 * value's modification face to the argument's, {@code toArray.§mT≡0:t.§mT}. A method that delegates to it must keep
 * that link to its own parameter, whatever the array's element type.
 */
public class TestToArrayFaceLink extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import java.util.*;
            class X<E> {
                private final List<E> delegate = new ArrayList<>();
                <T> T[] generic(T[] a) { return delegate.toArray(a); }
                <T> T[] genericParam(List<String> l, T[] a) { return l.toArray(a); }
                String[] concrete(List<String> l, String[] a) { return l.toArray(a); }
                List<String> sync(List<String> l) { return Collections.synchronizedList(l); }
            }
            """;

    @Test
    public void test() {
        TypeInfo X = javaInspector.parse("a.b.X", INPUT);
        new PrepAnalyzer(runtime, new PrepAnalyzer.Options.Builder().build()).doPrimaryType(X);
        LinkComputer tlc = new LinkComputerImpl(javaInspector);
        StringBuilder sb = new StringBuilder();
        for (String name : List.of("generic", "genericParam", "concrete", "sync")) {
            MethodInfo m = X.methods().stream().filter(mi -> mi.name().equals(name)).findFirst().orElseThrow();
            sb.append(name).append(": ").append(m.analysis().getOrCreate(METHOD_LINKS, () -> tlc.doMethod(m)))
                    .append('\n');
        }
        // the argument's face survives into the summary even though nothing else links the result to 'a' (its
        // content comes from the receiver); the library's face is named after its own type parameter, §mT
        assertEquals("""
                generic: [-] --> generic.§m←this*.delegate.§m,generic.§mT≡0:a*.§mT,generic.§ts⊆this*.delegate.§es
                genericParam: [-, -] --> genericParam.§m←0:l.§m,genericParam.§mT≡1:a*.§mT,genericParam.§ts⊆0:l.§$s
                concrete: [-, -] --> concrete.§$s⊆0:l.§$s,concrete.§m←0:l.§m,concrete.§mT≡1:a*.§mT
                sync: [-] --> sync.§$s⊆0:l.§$s,sync.§m≡0:l.§m
                """, sb.toString());
    }
}
