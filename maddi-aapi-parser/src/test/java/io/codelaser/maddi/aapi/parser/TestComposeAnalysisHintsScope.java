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

package io.codelaser.maddi.aapi.parser;

import io.codelaser.maddi.cst.impl.analysis.ValueImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The two knobs that scope a library's hints to what a consumer sees: {@code -Pmaddi.compose.exclude} (Eclipse
 * Collections' 1,340 generated primitive specialisations and its functional interfaces stay out, 12 MB of hints
 * become 1 MB) and a bare {@code @Container} in the report's expected column (a MUTABLE container: EC's
 * {@code Mutable*} collections store their arguments and never modify them).
 */
public class TestComposeAnalysisHintsScope {

    @DisplayName("exclude: a package prefix, or a dotted package segment anywhere in the name")
    @Test
    public void exclude() {
        List<String> exclude = List.of("org.eclipse.collections.api.block", ".primitive");
        assertTrue(ComposeAnalysisHints.excluded("org.eclipse.collections.api.block", exclude));
        assertTrue(ComposeAnalysisHints.excluded("org.eclipse.collections.api.block.function", exclude));
        assertFalse(ComposeAnalysisHints.excluded("org.eclipse.collections.api.blocks", exclude), "a prefix is a package, not a string");
        assertTrue(ComposeAnalysisHints.excluded("org.eclipse.collections.api.list.primitive", exclude));
        assertTrue(ComposeAnalysisHints.excluded("org.eclipse.collections.api.map.primitive.x", exclude));
        assertFalse(ComposeAnalysisHints.excluded("org.eclipse.collections.api.primitives", exclude), "a segment, not a string");
        assertFalse(ComposeAnalysisHints.excluded("org.eclipse.collections.api.list", exclude));
        assertFalse(ComposeAnalysisHints.excluded("org.eclipse.collections.api.list", List.of()));
    }

    @DisplayName("a bare @Container is a mutable container; @ImmutableContainer(hc = true) an immutable one")
    @Test
    public void mutableContainer() {
        ComposeAnalysisHints.ExpectedType mutable = ComposeAnalysisHints.parseExpectedType("@Container");
        assertNotNull(mutable);
        assertSame(ValueImpl.ImmutableImpl.MUTABLE, mutable.immutable());
        assertEquals(Boolean.TRUE, mutable.container());
        assertNull(mutable.independent(), "independence is not stated by a mutable container");

        ComposeAnalysisHints.ExpectedType immutable = ComposeAnalysisHints.parseExpectedType(
                "@ImmutableContainer(hc = true) @Independent(hc = true)");
        assertNotNull(immutable);
        assertSame(ValueImpl.ImmutableImpl.IMMUTABLE_HC, immutable.immutable());
        assertEquals(Boolean.TRUE, immutable.container());
        assertSame(ValueImpl.IndependentImpl.INDEPENDENT_HC, immutable.independent());

        assertNull(ComposeAnalysisHints.parseExpectedType("no claim: a utility class"), "words are not a verdict");
        assertNull(ComposeAnalysisHints.parseExpectedType("@Dependent"), "independence alone states no immutability");
    }
}
