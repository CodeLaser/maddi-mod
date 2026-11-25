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

import static org.junit.jupiter.api.Assertions.*;

import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.Test;

import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.modification.analyzer.CommonTest;
import io.codelaser.maddi.modification.analyzer.impl.IteratingAnalyzerImpl;

/**
 * CodeLaser/maddi#217: Stream.concat consumes both streams, and a field updater writes the field of the object it is
 * given, not its own state (JDK hints, JavaUtilStream and JavaUtilConcurrentAtomic).
 */
public class TestJdkHintsConcatAndFieldUpdaters extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
            import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
            import java.util.stream.IntStream;
            import java.util.stream.Stream;
            public class X {
              private volatile int n;
              private volatile String s;
              private static final AtomicIntegerFieldUpdater<X> N = AtomicIntegerFieldUpdater.newUpdater(X.class, "n");
              private static final AtomicReferenceFieldUpdater<X, String> S =
                  AtomicReferenceFieldUpdater.newUpdater(X.class, String.class, "s");
              public int inc() { return N.incrementAndGet(this); }
              public int read() { return N.get(this); }
              public boolean swap(String a, String b) { return S.compareAndSet(this, a, b); }
              public static Stream<String> both(Stream<String> a, Stream<String> b) { return Stream.concat(a, b); }
              public static IntStream ints(IntStream a, IntStream b) { return IntStream.concat(a, b); }
            }
            """;

    @Test
    public void test() {
        TypeInfo X = javaInspector.parse("X", INPUT);
        new IteratingAnalyzerImpl(javaInspector, new IteratingAnalyzerImpl.ConfigurationBuilder().build())
                .analyze(prepWork(X));

        assertTrue(X.findUniqueMethod("inc", 0).isModifying(), "an updater's increment writes this.n");
        assertFalse(X.findUniqueMethod("read", 0).isModifying());
        assertTrue(X.findUniqueMethod("swap", 2).isModifying(), "compareAndSet writes this.s");

        MethodInfo both = X.findUniqueMethod("both", 2);
        assertTrue(both.parameters().get(0).isModified(), "concat consumes its first stream");
        assertTrue(both.parameters().get(1).isModified(), "concat consumes its second stream");
        MethodInfo ints = X.findUniqueMethod("ints", 2);
        assertTrue(ints.parameters().get(0).isModified());
        assertTrue(ints.parameters().get(1).isModified());
    }
}
