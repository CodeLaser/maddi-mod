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

package io.codelaser.maddi.modification.link.io;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Set;

import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.codelaser.maddi.cst.api.analysis.Codec;
import io.codelaser.maddi.cst.api.info.FieldInfo;
import io.codelaser.maddi.cst.api.info.ParameterInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.impl.analysis.PropertyImpl;
import io.codelaser.maddi.cst.impl.analysis.ValueImpl;
import io.codelaser.maddi.modification.link.CommonTest;
import io.codelaser.maddi.modification.prepwork.io.LoadAnalysisResults;
import io.codelaser.maddi.modification.prepwork.io.WriteAnalysisResults;
import io.codelaser.maddi.util.Trie;

/**
 * The ownership feed stored with the results (CodeLaser/maddi-mod#131): a constructor parameter handed over into a
 * field of its type survives the round-trip, and an incremental run that reloads the results starts from it.
 */
public class TestWriteAnalysisHandedOver extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import java.util.List;
            public class Y {
                private final List<String> l;
                public Y(List<String> l) { this.l = l; }
                public void other(List<String> m) { }
            }
            """;

    @DisplayName("HANDED_OVER_PARAMETER survives the round-trip; an empty one is not written")
    @Test
    public void test() throws IOException {
        TypeInfo y = javaInspector.parse("a.b.Y", INPUT);
        prepWork(y);
        ParameterInfo p = y.findConstructor(1).parameters().getFirst();
        FieldInfo l = y.getFieldByName("l", true);
        p.analysis().set(PropertyImpl.HANDED_OVER_PARAMETER, new ValueImpl.AssignedToFieldImpl(Set.of(l)));

        Trie<TypeInfo> typeTrie = new Trie<>();
        typeTrie.add(y.fullyQualifiedName().split("\\."), y);
        File dest = new File("build/json-handed-over");
        if (dest.mkdirs()) {
            // fresh directory for this test
        }
        Codec codec = new LinkCodec(javaInspector).codec();
        new WriteAnalysisResults(runtime).write(dest, typeTrie, codec);
        String written = Files.readString(new File(dest, "ABY.json").toPath());
        assertTrue(written.contains(PropertyImpl.HANDED_OVER_PARAMETER.key()), written);

        javaInspector.invalidateAllSources();
        TypeInfo y2 = javaInspector.parse("a.b.Y", INPUT);
        new LoadAnalysisResults(javaInspector.runtime(), javaInspector.mainSources()).go(codec, written);
        ParameterInfo p2 = y2.findConstructor(1).parameters().getFirst();
        assertEquals(Set.of(y2.getFieldByName("l", true)), p2.analysis()
                .getOrDefault(PropertyImpl.HANDED_OVER_PARAMETER, ValueImpl.AssignedToFieldImpl.EMPTY).fields(),
                "the field must survive the round-trip: " + written);
        ParameterInfo m2 = y2.findUniqueMethod("other", 1).parameters().getFirst();
        assertTrue(m2.analysis().getOrDefault(PropertyImpl.HANDED_OVER_PARAMETER,
                ValueImpl.AssignedToFieldImpl.EMPTY).isDefault());
    }
}
