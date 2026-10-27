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

package io.codelaser.maddi.modification.prepwork.callgraph;

import io.codelaser.maddi.modification.prepwork.CommonTest;
import io.codelaser.maddi.modification.prepwork.PrepAnalyzer;
import io.codelaser.maddi.cst.api.analysis.Value;
import io.codelaser.maddi.cst.api.info.FieldInfo;
import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.impl.analysis.PropertyImpl;
import io.codelaser.maddi.cst.impl.analysis.ValueImpl;
import io.codelaser.maddi.graph.G;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static io.codelaser.maddi.modification.prepwork.callgraph.ComputePartOfConstructionFinalField.EMPTY_PART_OF_CONSTRUCTION;
import static io.codelaser.maddi.modification.prepwork.callgraph.ComputePartOfConstructionFinalField.PART_OF_CONSTRUCTION;
import static io.codelaser.maddi.cst.impl.analysis.ValueImpl.BoolImpl.FALSE;
import static io.codelaser.maddi.cst.impl.analysis.ValueImpl.BoolImpl.TRUE;
import static org.junit.jupiter.api.Assertions.*;

public class TestComputePartOfConstruction extends CommonTest {
    // A copy of TestCallGraph.INPUT3: that test moved to maddi-callgraph with the call graph (split stage 2),
    // and a test class of another module is not on this class path.
    private static final String CALL_GRAPH_INPUT3 = """
            package a.b;
            import java.util.ArrayList;
            import java.util.List;
            
            class X {
                private List<String> list;
                X(int i) {
                    initList(i+"");
                    print();
                    sleep();
                }
                private void initList(String i) {
                    list = new ArrayList<>();
                    list.add(i);
                }
                private void sleep() {
                    list.clear();
                }
                void print() {
                    System.out.println("print!");
                }
                public void rest() {
                    sleep();
                    System.out.println("awake");
                }
            }
            """;


    @DisplayName("part of construction of CallGraph test 3")
    @Test
    public void test1() {
        TypeInfo X = javaInspector.parse(ABX, CALL_GRAPH_INPUT3);
        PrepAnalyzer prepAnalyzer = new PrepAnalyzer(runtime);
        prepAnalyzer.doPrimaryType(X);

        Value.SetOfInfo setOfInfo = X.analysis().getOrNull(PART_OF_CONSTRUCTION, ValueImpl.SetOfInfoImpl.class);
        assertNotNull(setOfInfo);
        assertEquals("[a.b.X.<init>(int), a.b.X.initList(String)]",
                setOfInfo.infoSet().stream().map(Object::toString).sorted().toList().toString());

        FieldInfo list = X.getFieldByName("list", true);
        assertSame(TRUE, list.analysis().getOrDefault(PropertyImpl.FINAL_FIELD, FALSE));
    }


    @Language("java")
    private static final String INPUT2 = """
            package a.b;
            class X {
                interface Exit { }
            
                record ExceptionThrown(Exception exception) implements Exit { }
            
                interface LoopData {
                    LoopData withException(Exception e);
                }
            
                static class LoopDataImpl implements LoopData {
                    private Exit exit;
                    LoopDataImpl(Exit exit) {
                        this.exit = exit;
                    }
            
                    @Override
                    public LoopData withException(Exception e) {
                        Exit ee = new ExceptionThrown(e);
                        return new LoopDataImpl(ee);
                    }
                }
            }
            """;

    @DisplayName("final field")
    @Test
    public void test2() {
        TypeInfo X = javaInspector.parse(ABX, INPUT2);
        PrepAnalyzer prepAnalyzer = new PrepAnalyzer(runtime);
        prepAnalyzer.doPrimaryType(X);

        TypeInfo exceptionThrown = X.findSubType("ExceptionThrown");
        assertEquals("SetOfInfoImpl[infoSet=[a.b.X.ExceptionThrown.<init>(Exception)]]",
                exceptionThrown.analysis().getOrDefault(PART_OF_CONSTRUCTION, EMPTY_PART_OF_CONSTRUCTION).toString());

        TypeInfo loopDataImpl = X.findSubType("LoopDataImpl");
        assertEquals("SetOfInfoImpl[infoSet=[a.b.X.LoopDataImpl.<init>(a.b.X.Exit)]]",
                loopDataImpl.analysis().getOrDefault(PART_OF_CONSTRUCTION, EMPTY_PART_OF_CONSTRUCTION).toString());

        FieldInfo exit = loopDataImpl.getFieldByName("exit", true);
        assertTrue(exit.isPropertyFinal());
    }


    @Language("java")
    private static final String INPUT3 = """
            package a.b;
            class X {
                private int i;
                public X() {
                    update();
                }
                public void init() {
                    Runnable r = ()->update();
                    r.run();
                }
            
                private void update() {
                    ++i;
                }
            }
            """;

    @DisplayName("lambda and part of construction")
    @Test
    public void test3() {
        TypeInfo X = javaInspector.parse(ABX, INPUT3);
        PrepAnalyzer prepAnalyzer = new PrepAnalyzer(runtime);
        prepAnalyzer.doPrimaryType(X);

        assertEquals("[a.b.X.<init>()]",
                X.analysis().getOrDefault(PART_OF_CONSTRUCTION, EMPTY_PART_OF_CONSTRUCTION).infoSet().toString());
    }


    @Language("java")
    private static final String INPUT4 = """
            package a.b;
            class X {
                private int i;
                public X() {
                    update(); 
                }
                public void init() {
                    Runnable r = new Runnable() {
                        @Override public void run() {
                            update();
                        }
                    };
                    r.run();
                }
            
                private void update() {
                    ++i;
                }
            }
            """;

    @DisplayName("anonymous type and part of construction")
    @Test
    public void test4() {
        TypeInfo X = javaInspector.parse(ABX, INPUT4);
        PrepAnalyzer prepAnalyzer = new PrepAnalyzer(runtime);
        prepAnalyzer.doPrimaryType(X);

        assertEquals("[a.b.X.<init>()]",
                X.analysis().getOrDefault(PART_OF_CONSTRUCTION, EMPTY_PART_OF_CONSTRUCTION).infoSet().toString());
    }
}
