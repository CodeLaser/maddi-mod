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

package io.codelaser.maddi.modification.analyzer.shadow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;

import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.codelaser.maddi.cst.api.analysis.Value;
import io.codelaser.maddi.cst.api.info.FieldInfo;
import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.impl.analysis.PropertyImpl;
import io.codelaser.maddi.cst.impl.analysis.ValueImpl;
import io.codelaser.maddi.modification.analyzer.CommonTest;
import io.codelaser.maddi.modification.analyzer.IteratingAnalyzer;
import io.codelaser.maddi.modification.analyzer.impl.IteratingAnalyzerImpl;

/**
 * CodeLaser/maddi-mod#101, fernflower's VarType: a class immutable but for its extensibility (a subclass adds state
 * and modifies it), whose constants are passed where a subclass object is modified. The constants' objects are of
 * exactly that class, constructed by their initializers, so they cannot change: unmodifiedField under MODREACH,
 * which reached them through the parameter and, as the single writer under the freeze, left them undecided.
 */
public class TestImmutableObjectOfInitializer extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import java.util.ArrayList;
            import java.util.List;
            public class X {
                public static class VarType {
                    public static final VarType VARTYPE_INT = new VarType(1);
                    public static final VarType VARTYPE_CHAR = new VarType(2);
                    public static final VarType[] EMPTY_ARRAY = {};
                    private final int type;
                    private final String value;
                    public VarType(int type) { this.type = type; this.value = null; }
                    public int getType() { return type; }
                    public boolean isGeneric() { return false; }
                }
                // fernflower's GenericType: extends VarType, and modifies its own state where VarType does not
                public static class GenericType extends VarType {
                    private final List<String> arguments = new ArrayList<>();
                    public GenericType(int type) { super(type); }
                    @Override public boolean isGeneric() { return true; }
                    public void appendCastName(StringBuilder sb) { arguments.add("x"); sb.append(arguments); }
                }
                public static class ConstExprent {
                    private VarType constType;
                    public void setConstType(VarType t) { this.constType = t; }
                    public void adjust() { setConstType(VarType.VARTYPE_CHAR); }
                    public void print(StringBuilder sb) {
                        if (constType.isGeneric()) ((GenericType) constType).appendCastName(sb);
                    }
                }
                // ExprProcessor.getTypeName: a VarType parameter, modified when it is a GenericType
                public static String name(VarType type) {
                    StringBuilder sb = new StringBuilder();
                    if (type.isGeneric()) ((GenericType) type).appendCastName(sb);
                    else sb.append(type.getType());
                    return sb.toString();
                }
                public static String intName() { return name(VarType.VARTYPE_INT); }
                public static VarType[] none() { return VarType.EMPTY_ARRAY; }
            }
            """;

    @DisplayName("a final field's initializer constructs an object of an exactly immutable class: unmodified")
    @Test
    public void test() throws IOException {
        AnalyzerBundle bundle = buildAnalyzerBundle();
        TypeInfo x = bundle.javaInspector().parse("a.b.X", INPUT);
        List<Info> analysisOrder = bundle.prepAnalyzer().doPrimaryType(x);
        IteratingAnalyzer analyzer = new IteratingAnalyzerImpl(bundle.javaInspector(),
                new IteratingAnalyzerImpl.ConfigurationBuilder().setMaxIterations(10)
                        .setModificationViaReachability(true).build());
        analyzer.analyze(analysisOrder);
        TypeInfo varType = x.findSubType("VarType");
        // the premise: VarType is immutable only with hidden content, and name's parameter is modified
        Value.Immutable immutable = varType.analysis().getOrNull(PropertyImpl.IMMUTABLE_TYPE,
                ValueImpl.ImmutableImpl.class);
        assertEquals(ValueImpl.ImmutableImpl.IMMUTABLE_HC, immutable);
        assertFalse(unmodified(x.findUniqueMethod("name", 1).parameters().getFirst().analysis()
                .getOrNull(PropertyImpl.UNMODIFIED_PARAMETER, ValueImpl.BoolImpl.class)),
                "a GenericType passed as the parameter is modified");
        for (String name : List.of("VARTYPE_INT", "VARTYPE_CHAR", "EMPTY_ARRAY")) {
            FieldInfo f = varType.getFieldByName(name, true);
            assertTrue(unmodified(f.analysis().getOrNull(PropertyImpl.UNMODIFIED_FIELD, ValueImpl.BoolImpl.class)),
                    name + " holds an object nothing can modify");
        }
        // a field that may hold a GenericType stays modified
        assertFalse(unmodified(x.findSubType("ConstExprent").getFieldByName("constType", true).analysis()
                .getOrNull(PropertyImpl.UNMODIFIED_FIELD, ValueImpl.BoolImpl.class)));
    }

    private static boolean unmodified(Value.Bool value) {
        return value != null && value.isTrue();
    }
}
