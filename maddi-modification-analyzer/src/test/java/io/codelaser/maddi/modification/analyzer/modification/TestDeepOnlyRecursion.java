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

import io.codelaser.maddi.cst.api.info.FieldInfo;
import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.modification.analyzer.CommonTest;
import io.codelaser.maddi.modification.analyzer.impl.IteratingAnalyzerImpl;
import io.codelaser.maddi.modification.analyzer.shadow.ShadowModificationPass;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * CodeLaser/maddi#202: fernflower's GenericType. An element of 'this.arguments' is handed to a static helper that
 * recurses back into the element's own appendCastName; nothing in the cycle modifies anything but the StringBuilder.
 * The fixpoint keeps the cycle deep-modifying, and the reachability pass imported that through the deep-only summary
 * entries of the walkable bodies (#59's rule). Under DEEPWALK=1 the walk re-derives them.
 */
public class TestDeepOnlyRecursion extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import java.util.ArrayList;
            import java.util.List;
            public class Gen {
                public static class GT {
                    private final String value;
                    private final GT parent;
                    private final List<GT> arguments;
                    public GT(String value, GT parent, List<GT> arguments) {
                        this.value = value;
                        this.parent = parent;
                        this.arguments = arguments == null ? new ArrayList<>() : arguments;
                    }
                    public String getValue() { return value; }
                    public List<GT> getArguments() { return arguments; }
                    public void appendCastName(StringBuilder sb) {
                        if (parent != null) {
                            parent.appendCastName(sb);
                            sb.append('.');
                        }
                        sb.append(value);
                        appendTypeArguments(sb);
                    }
                    private void appendTypeArguments(StringBuilder sb) {
                        if (!arguments.isEmpty()) {
                            sb.append('<');
                            for (int i = 0; i < arguments.size(); i++) {
                                if (i > 0) sb.append(", ");
                                GT par = arguments.get(i);
                                sb.append(Writer.getCastTypeName(par));
                            }
                            sb.append('>');
                        }
                    }
                    public GT resize(String v) { return new GT(v, parent, arguments); }
                }
                public static class Writer {
                    public static String getCastTypeName(GT type) { return getTypeName(type); }
                    public static String getTypeName(GT type) {
                        StringBuilder sb = new StringBuilder();
                        type.appendCastName(sb);
                        return sb.toString();
                    }
                }
            }
            """;

    @DisplayName("DEEPWALK=1: the recursion through an element of a field does not make the field deep-modified")
    @Test
    public void deepWalk() {
        boolean before = ShadowModificationPass.DEEP_WALK;
        ShadowModificationPass.DEEP_WALK = true;
        try {
            TypeInfo x = javaInspector.parse("a.b.Gen", INPUT);
            List<Info> ao = prepWork(x);
            new IteratingAnalyzerImpl(javaInspector, new IteratingAnalyzerImpl.ConfigurationBuilder()
                    .setMaxIterations(10).setModificationViaReachability(true).build()).analyze(ao);
            TypeInfo gt = x.findSubType("GT");
            TypeInfo writer = x.findSubType("Writer");
            assertEquals("true/true", m(gt, "appendCastName"));
            assertEquals("true/true", m(gt, "appendTypeArguments"));
            assertEquals("true/true", m(gt, "resize"));
            assertEquals("true/true", p(writer, "getCastTypeName"));
            assertEquals("true/true", p(writer, "getTypeName"));
            FieldInfo arguments = gt.getFieldByName("arguments", true);
            assertEquals("true", "" + arguments.isUnmodified());
        } finally {
            ShadowModificationPass.DEEP_WALK = before;
        }
    }

    private static String m(TypeInfo typeInfo, String name) {
        MethodInfo mi = typeInfo.methods().stream().filter(m -> m.name().equals(name)).findFirst().orElseThrow();
        return mi.isNonModifying() + "/" + mi.isStructurallyNonModifying();
    }

    private static String p(TypeInfo typeInfo, String name) {
        MethodInfo mi = typeInfo.methods().stream().filter(m -> m.name().equals(name)).findFirst().orElseThrow();
        return mi.parameters().getFirst().isUnmodified() + "/" + mi.parameters().getFirst().isStructurallyUnmodified();
    }
}
