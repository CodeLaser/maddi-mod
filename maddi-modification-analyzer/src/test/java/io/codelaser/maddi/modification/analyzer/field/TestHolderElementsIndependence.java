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

package io.codelaser.maddi.modification.analyzer.field;

import static io.codelaser.maddi.cst.impl.analysis.PropertyImpl.IMMUTABLE_TYPE;
import static io.codelaser.maddi.cst.impl.analysis.PropertyImpl.INDEPENDENT_FIELD;
import static io.codelaser.maddi.cst.impl.analysis.PropertyImpl.INDEPENDENT_METHOD;
import static io.codelaser.maddi.cst.impl.analysis.PropertyImpl.INDEPENDENT_PARAMETER;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.stream.Collectors;

import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.codelaser.maddi.cst.api.info.FieldInfo;
import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.impl.analysis.ValueImpl;
import io.codelaser.maddi.modification.analyzer.CommonTest;
import io.codelaser.maddi.modification.analyzer.impl.IteratingAnalyzerImpl;

/**
 * CodeLaser/maddi-mod#159, the holder's view: the elements of a container are hidden content of the container, but to
 * the type that wrote {@code List<C> list} they are C objects, accessible content it can call {@code increment()} on.
 * A holder that copies the caller's mutable elements shares them with the caller, whatever the copy is: dependent,
 * and so {@code @FinalFields}. Before, the {@code copyOf} shapes were {@code @Immutable(hc=true)}, the object the field
 * holds (immutable with hidden content) deciding for the holder; a copy constructor's content link to the parameter
 * did not count; an array's {@code clone()} linked nothing. Elements of an immutable type ({@code String}) or of a
 * type parameter keep the verdicts they had.
 * <p>
 * Each line: the type's immutability and independence, then the constructor parameter and the getter.
 */
public class TestHolderElementsIndependence extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import java.util.*;
            import java.util.stream.Collectors;
            public class X {
                static class C { private int n; public void increment() { n++; } public int get() { return n; }
                                 public String name() { return "c"; } }
                static class ListCopyOf { private final List<C> list;
                    public ListCopyOf(List<C> in) { this.list = List.copyOf(in); }
                    public C get(int i) { return list.get(i); } }
                static class ArrayListCopy { private final List<C> list;
                    public ArrayListCopy(List<C> in) { this.list = new ArrayList<>(in); }
                    public C get(int i) { return list.get(i); } }
                static class ArrayListNoGetter { private final List<C> list;
                    public ArrayListNoGetter(List<C> in) { this.list = new ArrayList<>(in); }
                    public int size() { return list.size(); } }
                static class Direct { private final C c;
                    public Direct(C in) { this.c = in; }
                    public C get() { return c; } }
                static class CopyNoGetter { private final List<C> list;
                    public CopyNoGetter(List<C> in) { this.list = List.copyOf(in); }
                    public int size() { return list.size(); } }
                static class Generic<T> { private final List<T> list;
                    public Generic(List<T> in) { this.list = List.copyOf(in); }
                    public T get(int i) { return list.get(i); } }
                static class ArrayClone { private final C[] cs;
                    public ArrayClone(C[] in) { this.cs = in.clone(); }
                    public C get(int i) { return cs[i]; } }
                static class Reads { private final List<C> list;
                    public Reads(List<C> in) { this.list = List.copyOf(in); }
                    public int first() { return list.get(0).get(); } }
                static class MapCopy { private final Map<String, C> map;
                    public MapCopy(Map<String, C> in) { this.map = Map.copyOf(in); }
                    public C get(String name) { return map.get(name); } }
                static class CountersC { private final Map<String, C> counters;
                    public CountersC(Collection<C> in) {
                        this.counters = in.stream().collect(Collectors.toUnmodifiableMap(C::name, c -> c)); }
                    public C getCounter(String name) { return counters.get(name); } }
                static class Strings { private final List<String> list;
                    public Strings(List<String> in) { this.list = List.copyOf(in); }
                    public String get(int i) { return list.get(i); } }
            }
            """;

    @DisplayName("a copy of the caller's mutable elements makes the holder dependent")
    @Test
    public void test() {
        TypeInfo x = javaInspector.parse("a.b.X", INPUT);
        List<Info> ao = prepWork(x);
        new IteratingAnalyzerImpl(javaInspector, new IteratingAnalyzerImpl.ConfigurationBuilder()
                .setMaxIterations(10).build()).analyze(ao);
        String verdicts = x.subTypes().stream().filter(t -> !"C".equals(t.simpleName()))
                .map(TestHolderElementsIndependence::verdicts).sorted().collect(Collectors.joining("\n"));
        assertEquals("""
                ArrayClone @FinalFields field=@Dependent init=@Dependent get=@Dependent
                ArrayListCopy @FinalFields field=@Dependent init=@Dependent get=@Dependent
                ArrayListNoGetter @FinalFields field=@Dependent init=@Dependent size=@Independent
                CopyNoGetter @FinalFields field=@Dependent init=@Dependent size=@Independent
                CountersC @FinalFields field=@Dependent init=@Independent getCounter=@Dependent
                Direct @FinalFields field=@Dependent init=@Dependent get=@Dependent
                Generic @Immutable(hc=true) field=@Independent(hc=true) init=@Independent(hc=true) get=@Independent(hc=true)
                ListCopyOf @FinalFields field=@Dependent init=@Dependent get=@Dependent
                MapCopy @FinalFields field=@Dependent init=@Dependent get=@Dependent
                Reads @FinalFields field=@Dependent init=@Dependent first=@Independent
                Strings @Immutable(hc=true) field=@Independent init=@Independent get=@Independent(hc=true)""",
                verdicts);
        // KNOWN GAP: CountersC's constructor parameter. 'Collectors.toUnmodifiableMap(C::name, c -> c)' has a key and
        // a value function; LinkMethodCall.collectorReturnValue realizes only the accumulating collectors (Tier 1), so
        // the field's map links to nothing and the parameter reads independent while the getter is dependent.
    }

    private static String verdicts(TypeInfo t) {
        FieldInfo f = t.fields().getFirst();
        MethodInfo init = t.constructors().getFirst();
        MethodInfo m = t.methods().getFirst();
        return t.simpleName() + " " + t.analysis().getOrNull(IMMUTABLE_TYPE, ValueImpl.ImmutableImpl.class)
               + " field=" + f.analysis().getOrNull(INDEPENDENT_FIELD, ValueImpl.IndependentImpl.class)
               + " init=" + init.parameters().getFirst().analysis().getOrNull(INDEPENDENT_PARAMETER,
                ValueImpl.IndependentImpl.class)
               + " " + m.name() + "=" + m.analysis().getOrNull(INDEPENDENT_METHOD, ValueImpl.IndependentImpl.class);
    }
}
