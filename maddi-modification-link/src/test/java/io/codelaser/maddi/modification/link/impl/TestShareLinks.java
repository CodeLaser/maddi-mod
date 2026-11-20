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

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.codelaser.maddi.cst.api.variable.LocalVariable;
import io.codelaser.maddi.modification.link.CommonTest;
import io.codelaser.maddi.modification.prepwork.variable.Link;
import io.codelaser.maddi.modification.prepwork.variable.Links;
import io.codelaser.maddi.modification.prepwork.variable.impl.LinksImpl;

/** Links of consecutive statements share their instances: whole when identical, link by link otherwise. */
public class TestShareLinks extends CommonTest {

    private LocalVariable local(String name) {
        return runtime.newLocalVariable(name, runtime.objectParameterizedType());
    }

    @Test
    public void test() {
        LocalVariable a = local("a");
        LocalVariable b = local("b");
        LocalVariable c = local("c");
        LocalVariable d = local("d");

        Links previous = new LinksImpl.Builder(a)
                .add(LinkNatureImpl.IS_ASSIGNED_FROM, b)
                .add(LinkNatureImpl.SHARES_FIELDS, c)
                .add(LinkNatureImpl.IS_IN_OBJECT_GRAPH, d)
                .build();

        // identical: the previous instance itself
        Links same = new LinksImpl.Builder(a)
                .add(LinkNatureImpl.IS_ASSIGNED_FROM, b)
                .add(LinkNatureImpl.SHARES_FIELDS, c)
                .add(LinkNatureImpl.IS_IN_OBJECT_GRAPH, d)
                .build();
        assertSame(previous, LinksImpl.shareLinks(same, previous));

        // one link changed nature, one dropped, order changed: the identical links are the previous instances
        Links changed = new LinksImpl.Builder(a)
                .add(LinkNatureImpl.IS_IN_OBJECT_GRAPH, d)
                .add(LinkNatureImpl.OBJECT_GRAPH_OVERLAPS, c)
                .build();
        Links shared = LinksImpl.shareLinks(changed, previous);
        assertNotSame(changed, shared);
        assertEquals(changed.toString(), shared.toString());
        assertTrue(changed.sameContent(shared));
        List<Link> s = shared.stream().toList();
        List<Link> p = previous.stream().toList();
        assertSame(p.get(2), s.get(0), "a ≤ d is the previous statement's link");
        assertSame(changed.stream().toList().get(1), s.get(1), "a ∩ c differs in nature: the candidate's own link");

        // nothing in common: the candidate itself
        Links other = new LinksImpl.Builder(a).add(LinkNatureImpl.IS_ASSIGNED_FROM, c).build();
        assertSame(other, LinksImpl.shareLinks(other, previous));
        assertSame(other, LinksImpl.shareLinks(other, null));
    }
}
