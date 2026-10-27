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

package io.codelaser.maddi.modification.analyzer.rewire;

import io.codelaser.maddi.cst.api.analysis.PropertyValueMap;
import io.codelaser.maddi.cst.impl.analysis.PropertyImpl;
import io.codelaser.maddi.cst.impl.analysis.PropertyValueMapImpl;
import io.codelaser.maddi.cst.impl.analysis.ValueImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The early-cutoff skip's <em>clear-before-recompute</em> primitive ({@code PropertyValueMap.removeIf}). When the
 * worklist carries a REWIRE type optimistically and then finds it dirty, re-analysis may need to <em>lower</em> a
 * carried verdict — which the monotonic-overwrite guard rejects. Clearing the carried value first unblocks it. See
 * {@code docs/design/analysis-rewiring.md}.
 */
public class TestClearBeforeRecompute {

    @DisplayName("removeIf clears a carried value so a lowering re-analysis no longer hits the monotonic guard")
    @Test
    public void test() {
        PropertyValueMap analysis = new PropertyValueMapImpl();

        // a carried (optimistic) high value: @Immutable
        analysis.set(PropertyImpl.IMMUTABLE_TYPE, ValueImpl.ImmutableImpl.IMMUTABLE);
        assertSame(ValueImpl.ImmutableImpl.IMMUTABLE,
                analysis.getOrNull(PropertyImpl.IMMUTABLE_TYPE, ValueImpl.ImmutableImpl.class));

        // re-analysis lowers it (IMMUTABLE -> MUTABLE): the strictly-increasing overwrite guard rejects that
        assertThrows(UnsupportedOperationException.class, () ->
                analysis.setAllowControlledOverwrite(PropertyImpl.IMMUTABLE_TYPE, ValueImpl.ImmutableImpl.MUTABLE));

        // clear-before-recompute: removeIf clears the carried value, so re-analysis can set the correct lower value
        analysis.removeIf(PropertyImpl.IMMUTABLE_TYPE::equals);
        assertNull(analysis.getOrNull(PropertyImpl.IMMUTABLE_TYPE, ValueImpl.ImmutableImpl.class));
        assertTrue(analysis.setAllowControlledOverwrite(PropertyImpl.IMMUTABLE_TYPE, ValueImpl.ImmutableImpl.MUTABLE));
        assertSame(ValueImpl.ImmutableImpl.MUTABLE,
                analysis.getOrNull(PropertyImpl.IMMUTABLE_TYPE, ValueImpl.ImmutableImpl.class));

        // and removeIf can clear a whole tier at once (a predicate), leaving the rest intact
        analysis.set(PropertyImpl.CONTAINER_TYPE, ValueImpl.BoolImpl.TRUE);
        analysis.removeIf(p -> "immutableType".equals(p.key()));
        assertNull(analysis.getOrNull(PropertyImpl.IMMUTABLE_TYPE, ValueImpl.ImmutableImpl.class));
        assertSame(ValueImpl.BoolImpl.TRUE, analysis.getOrNull(PropertyImpl.CONTAINER_TYPE, ValueImpl.BoolImpl.class));
    }
}
