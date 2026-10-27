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

package io.codelaser.maddi.modification.analyzer.eventual;

import io.codelaser.maddi.modification.analyzer.CommonTest;
import io.codelaser.maddi.cst.api.analysis.Value;
import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.impl.analysis.PropertyImpl;
import io.codelaser.maddi.cst.impl.analysis.ValueImpl;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code BinaryOperatorImpl} shape of the dogfood (quest R, 2026-08-03): an abstract base holding
 * PROTECTED final carrier fields of eventually immutable type, subclasses adding nothing of their own.
 * The privacy rule at the top of {@code TypeImmutableAnalyzerImpl.loopOverFieldsAndMethods} read such a
 * field's PLAIN immutability (@Mutable) with no after-mark participation -- the one rule in the loop
 * outside the excusal regime -- capping the base at {@code @FinalFields} after the mark ("buys nothing")
 * and, through the frozen write-once verdict, every subclass with it. After OUR mark the referent is
 * committed, and a committed referent bars mutation for any holder: who can read the reference no longer
 * matters. Finality stays required -- a non-private ASSIGNABLE field would let a package-mate bypass the
 * mark discipline entirely.
 */
public class TestEventualProtectedCarrier extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            import io.codelaser.maddi.support.EventuallyFinal;
            public class X {
              public static class Content {
                private final EventuallyFinal<String> data = new EventuallyFinal<>();
                public void commit(String s) { data.setFinal(s); }
                public String read() { return data.get(); }
              }
              public static abstract class Carrier {
                protected final Content content; // protected: the privacy rule's territory
                Carrier(Content content) { this.content = content; }
                public Content content() { return content; }
              }
              public static class Sub extends Carrier {
                public Sub(Content content) { super(content); }
              }
            }
            """;

    private Value.EventuallyImmutable ev(TypeInfo typeInfo) {
        return typeInfo.analysis().getOrDefault(PropertyImpl.EVENTUALLY_IMMUTABLE_TYPE,
                ValueImpl.EventuallyImmutableImpl.NOT_EVENTUAL);
    }

    @DisplayName("a protected final carrier field of committing type does not cap the after-mark verdict")
    @Test
    public void test() {
        TypeInfo X = javaInspector.parse("X", INPUT);
        List<Info> ao = prepWork(X);
        analyzer.go(ao);

        TypeInfo content = X.findSubType("Content");
        Value.EventuallyImmutable evContent = ev(content);
        assertTrue(evContent.isEventual(), "Content should be eventually immutable, is " + evContent);
        assertEquals("data", evContent.markLabel());

        TypeInfo carrier = X.findSubType("Carrier");
        Value.EventuallyImmutable evCarrier = ev(carrier);
        assertTrue(evCarrier.isEventual(), "Carrier should be eventually immutable, is " + evCarrier);
        assertEquals("content", evCarrier.markLabel());
        assertTrue(evCarrier.immutableAfterMark().isAtLeastImmutableHC(),
                "after the mark: " + evCarrier.immutableAfterMark());

        TypeInfo sub = X.findSubType("Sub");
        Value.EventuallyImmutable evSub = ev(sub);
        assertTrue(evSub.isEventual(), "Sub should inherit the carrier's transition, is " + evSub);
        assertEquals("content", evSub.markLabel());
    }
}
