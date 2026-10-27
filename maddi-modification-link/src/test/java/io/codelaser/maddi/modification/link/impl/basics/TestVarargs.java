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

package io.codelaser.maddi.modification.link.impl.basics;


import io.codelaser.maddi.modification.link.CommonTest;
import io.codelaser.maddi.modification.link.LinkComputer;
import io.codelaser.maddi.modification.link.impl.LinkComputerImpl;
import io.codelaser.maddi.modification.prepwork.PrepAnalyzer;
import io.codelaser.maddi.modification.prepwork.variable.MethodLinkedVariables;
import io.codelaser.maddi.modification.prepwork.variable.Stage;
import io.codelaser.maddi.modification.prepwork.variable.VariableData;
import io.codelaser.maddi.modification.prepwork.variable.VariableInfo;
import io.codelaser.maddi.modification.prepwork.variable.impl.VariableDataImpl;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.ParameterInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.codelaser.maddi.modification.link.impl.MethodLinkedVariablesImpl.METHOD_LINKS;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class TestVarargs extends CommonTest {
    @Language("java")
    private static final String INPUT1 = """
            import java.io.Closeable;
            
            public class B {
            
                public static void closeAndIgnoreErrors(Closeable... closeables) {
                    for (Closeable closeable : closeables) {
                        closeAndIgnoreErrors(closeable);
                    }
                }
            }
            """;

    @DisplayName("varargs 1")
    @Test
    public void test1() {
        TypeInfo B = javaInspector.parse("B", INPUT1);
        PrepAnalyzer analyzer = new PrepAnalyzer(runtime, new PrepAnalyzer.Options.Builder().build());
        analyzer.doPrimaryType(B);
        LinkComputer tlc = new LinkComputerImpl(javaInspector);

        MethodInfo closeAndIgnoreErrors = B.findUniqueMethod("closeAndIgnoreErrors", 1);
        MethodLinkedVariables mlv = closeAndIgnoreErrors.analysis().getOrCreate(METHOD_LINKS,
                () -> tlc.doMethod(closeAndIgnoreErrors));
        assertEquals("[-] --> -", mlv.toString());
    }

    @Language("java")
    private static final String INPUT2 = """
            import java.util.Map;
            import java.util.Vector;
            
            public class X {
            
                public Map<String, Comparable<?>> executeAutoitFile(String fullPath, String workDir,
                    String autoItLocation, int timeout, Object... params) throws Exception {
                    Vector<Object> parameters = new Vector<Object>();
                    if (params.length == 1 && params[0] instanceof Vector) {
                        parameters = (Vector<Object>) params[0];
                    } else {
                        for (Object param : params) {
                            parameters.add(param);
                        }
                    }
                    return executeAutoitFile(fullPath, workDir, autoItLocation, timeout, parameters);
                }
            }
            
            """;

    @DisplayName("varargs 2")
    @Test
    public void test2() {
        TypeInfo B = javaInspector.parse("X", INPUT2);
        PrepAnalyzer analyzer = new PrepAnalyzer(runtime, new PrepAnalyzer.Options.Builder().build());
        analyzer.doPrimaryType(B);
        LinkComputer tlc = new LinkComputerImpl(javaInspector);
        MethodInfo method = B.findUniqueMethod("executeAutoitFile", 5);
        MethodLinkedVariables mlv = method.analysis().getOrCreate(METHOD_LINKS, () -> tlc.doMethod(method));
        assertEquals("[-, -, -, -, -] --> -", mlv.toString());
    }


    @Language("java")
    private static final String INPUT3a = """
            import java.util.Collection;
            
            public class B {
            
                public static <I> Collection<I> combine(Collection<I> target, Collection<Collection<I>> collections) {
                    for (Collection<I> collection : collections) {
                        target.addAll(collection);
                    }
                    return target;
                }
            }
            """;

    @DisplayName("collection instead of varargs")
    @Test
    public void test3a() {
        TypeInfo B = javaInspector.parse("B", INPUT3a);
        PrepAnalyzer analyzer = new PrepAnalyzer(runtime, new PrepAnalyzer.Options.Builder().build());
        analyzer.doPrimaryType(B);
        LinkComputer tlc = new LinkComputerImpl(javaInspector);
        MethodInfo method = B.findUniqueMethod("combine", 2);
        ParameterInfo target = method.parameters().getFirst();
        MethodLinkedVariables mlv = method.analysis().getOrCreate(METHOD_LINKS, () -> tlc.doMethod(method));

        VariableData vd0 = VariableDataImpl.of(method.methodBody().statements().getFirst());
        VariableInfo viCollection0E = vd0.variableInfo("collection", Stage.EVALUATION);
        assertEquals("collection∈∈1:collections.§iss", viCollection0E.linkedVariables().toString());

        VariableData vd000 = VariableDataImpl.of(method.methodBody().statements().getFirst().block().statements().getFirst());
        VariableInfo viTarget000 = vd000.variableInfo(target);
        assertEquals("0:target.§is~collection.§is,0:target.§is∩1:collections.§iss",
                viTarget000.linkedVariables().toString());

        VariableInfo viTargetM = vd0.variableInfo(target);
        assertEquals("0:target.§is~collection.§is,0:target.§is∩1:collections.§iss",
                viTargetM.linkedVariables().toString());

        assertEquals("""
                [0:target*.§is∩1:collections.§iss, 1:collections.§iss∩0:target*.§is] --> combine←0:target*,combine.§is←0:target*.§is,combine.§is∩1:collections.§iss\
                """, mlv.toString());
    }


    @Language("java")
    private static final String INPUT3b = """
            import java.util.Collection;
            
            public class B {
            
                public static <I> Collection<I> combine(Collection<I> target, Collection<I>[] collections) {
                    for (Collection<I> collection : collections) {
                        target.addAll(collection);
                    }
                    return target;
                }
            }
            """;

    @DisplayName("array instead of varargs")
    @Test
    public void test3b() {
        TypeInfo B = javaInspector.parse("B", INPUT3b);
        PrepAnalyzer analyzer = new PrepAnalyzer(runtime, new PrepAnalyzer.Options.Builder().build());
        analyzer.doPrimaryType(B);
        LinkComputer tlc = new LinkComputerImpl(javaInspector);
        MethodInfo method = B.findUniqueMethod("combine", 2);
        ParameterInfo target = method.parameters().getFirst();
        MethodLinkedVariables mlv = method.analysis().getOrCreate(METHOD_LINKS, () -> tlc.doMethod(method));

        VariableData vd0 = VariableDataImpl.of(method.methodBody().statements().getFirst());
        VariableInfo viCollection0E = vd0.variableInfo("collection", Stage.EVALUATION);
        assertEquals("collection∈∈1:collections.§iss", viCollection0E.linkedVariables().toString());

        VariableData vd000 = VariableDataImpl.of(method.methodBody().statements().getFirst().block().statements().getFirst());
        VariableInfo viTarget000 = vd000.variableInfo(target);
        assertEquals("0:target.§is~collection.§is,0:target.§is∩1:collections.§iss",
                viTarget000.linkedVariables().toString());

        VariableInfo viTargetM = vd0.variableInfo(target);
        assertEquals("0:target.§is~collection.§is,0:target.§is∩1:collections.§iss",
                viTargetM.linkedVariables().toString());

        assertEquals("""
                [0:target*.§is∩1:collections.§iss, 1:collections.§iss∩0:target*.§is] --> combine←0:target*,combine.§is←0:target*.§is,combine.§is∩1:collections.§iss\
                """, mlv.toString());
    }


    @Language("java")
    private static final String INPUT3c = """
            import java.util.Collection;
            
            public class B {
            
                public static <I> Collection<I> combine(Collection<I> target, Collection<I>... collections) {
                    for (Collection<I> collection : collections) {
                        target.addAll(collection);
                    }
                    return target;
                }
            }
            """;

    @DisplayName("varargs of a collection")
    @Test
    public void test3c() {
        TypeInfo B = javaInspector.parse("B", INPUT3c);
        PrepAnalyzer analyzer = new PrepAnalyzer(runtime, new PrepAnalyzer.Options.Builder().build());
        analyzer.doPrimaryType(B);
        LinkComputer tlc = new LinkComputerImpl(javaInspector);
        MethodInfo method = B.findUniqueMethod("combine", 2);
        ParameterInfo target = method.parameters().getFirst();
        MethodLinkedVariables mlv = method.analysis().getOrCreate(METHOD_LINKS, () -> tlc.doMethod(method));

        VariableData vd0 = VariableDataImpl.of(method.methodBody().statements().getFirst());
        VariableInfo viCollection0E = vd0.variableInfo("collection", Stage.EVALUATION);
        assertEquals("collection∈∈1:collections.§iss", viCollection0E.linkedVariables().toString());

        VariableData vd000 = VariableDataImpl.of(method.methodBody().statements().getFirst().block().statements().getFirst());
        VariableInfo viTarget000 = vd000.variableInfo(target);
        assertEquals("0:target.§is~collection.§is,0:target.§is∩1:collections.§iss",
                viTarget000.linkedVariables().toString());

        VariableInfo viTargetM = vd0.variableInfo(target);
        assertEquals("""
                0:target.§is~collection.§is,0:target.§is∩1:collections.§iss\
                """, viTargetM.linkedVariables().toString());

        assertEquals("""
                [0:target*.§is∩1:collections.§iss, 1:collections.§iss∩0:target*.§is] --> combine←0:target*,combine.§is←0:target*.§is,combine.§is∩1:collections.§iss\
                """, mlv.toString());
    }


    @Language("java")
    private static final String INPUT4 = """
            import java.util.Collection;
            
            public class B {
            
                public static <D extends Collection<I>, I> D combine(D target, Collection<I>... collections) {
                    for (Collection<I> collection : collections) {
                        target.addAll(collection);
                    }
                    return target;
                }
            }
            """;

    @DisplayName("varargs and type parameters, shallow")
    @Test
    public void test4a() {
        TypeInfo B = javaInspector.parse("B", INPUT4);
        PrepAnalyzer analyzer = new PrepAnalyzer(runtime, new PrepAnalyzer.Options.Builder().build());
        analyzer.doPrimaryType(B);
        LinkComputer tlc = new LinkComputerImpl(javaInspector, LinkComputer.Options.FORCE_SHALLOW);
        MethodInfo method = B.findUniqueMethod("combine", 2);
        MethodLinkedVariables mlv = method.analysis().getOrCreate(METHOD_LINKS, () -> tlc.doMethod(method));
        assertEquals("[-, -] --> combine←0:target", mlv.toString());
        // not looking at cross-parameters!
    }

    @DisplayName("varargs and type parameters")
    @Test
    public void test4() {
        TypeInfo B = javaInspector.parse("B", INPUT4);
        PrepAnalyzer analyzer = new PrepAnalyzer(runtime, new PrepAnalyzer.Options.Builder().build());
        analyzer.doPrimaryType(B);
        LinkComputer tlc = new LinkComputerImpl(javaInspector);
        MethodInfo method = B.findUniqueMethod("combine", 2);
        ParameterInfo target = method.parameters().getFirst();
        MethodLinkedVariables mlv = method.analysis().getOrCreate(METHOD_LINKS, () -> tlc.doMethod(method));

        VariableData vd0 = VariableDataImpl.of(method.methodBody().statements().getFirst());
        VariableInfo viCollection0E = vd0.variableInfo("collection", Stage.EVALUATION);
        assertEquals("collection∈∈1:collections.§iss", viCollection0E.linkedVariables().toString());

        VariableData vd000 = VariableDataImpl.of(method.methodBody().statements().getFirst().block().statements().getFirst());
        VariableInfo viTarget000 = vd000.variableInfo(target);
        assertEquals("0:target.§es~collection.§es,0:target.§es∩1:collections.§iss",
                viTarget000.linkedVariables().toString());

        VariableInfo viTargetM = vd0.variableInfo(target);
        assertEquals("0:target.§es~collection.§es,0:target.§es∩1:collections.§iss",
                viTargetM.linkedVariables().toString());

        assertEquals("""
                [0:target*.§es∩1:collections.§iss, 1:collections.§iss∩0:target*.§es] --> combine←0:target*,combine.§es←0:target*.§es,combine.§es∩1:collections.§iss\
                """, mlv.toString());
    }
}
