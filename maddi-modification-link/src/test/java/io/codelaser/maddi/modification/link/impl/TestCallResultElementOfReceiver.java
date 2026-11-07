package io.codelaser.maddi.modification.link.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.modification.link.CommonTest;
import io.codelaser.maddi.modification.prepwork.PrepAnalyzer;
import io.codelaser.maddi.modification.prepwork.variable.MethodLinkedVariables;

/*
 The element counterpart of TestCallResultFieldOfReceiver (CodeLaser/maddi-mod#100): a getter hands out an element of
 a map or list held in a field ('getClass ∈ this.classes.§..'), and the caller modifies it. The element is the field's
 hidden content: modifying it modifies 'this', deep-only, as the direct 'classes.get(n)' does. Found by the rust-llvm
 oracle on fernflower's StructContext.instanceOf ('getClass(valclass)', then the lazily loading 'getPool()').
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class TestCallResultElementOfReceiver extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import java.util.ArrayList;
            import java.util.HashMap;
            import java.util.List;
            import java.util.Map;
            public class Ctx {
                public static class C {
                    private Object pool;
                    public Object getPool() { if (pool == null) pool = new Object(); return pool; }
                }
                private final Map<String, C> classes = new HashMap<>();
                private final List<C> list = new ArrayList<>();
                public C getClass(String n) { return classes.get(n); }
                public C first() { return list.get(0); }
                public boolean direct(String n) { C c = classes.get(n); return c.getPool() != null; }
                public boolean viaGetter(String n) { C c = getClass(n); return c.getPool() != null; }
                public boolean viaGetterChain(String n) { return getClass(n).getPool() != null; }
                public boolean viaFirst() { return first().getPool() != null; }
                public boolean reads(String n) { C c = getClass(n); return c != null; }
            }
            """;

    private Map<String, String> cache;

    private String mlv(String name) {
        if (cache == null) {
            TypeInfo c = javaInspector.parse("a.b.Ctx", INPUT);
            new PrepAnalyzer(runtime, new PrepAnalyzer.Options.Builder().build()).doPrimaryType(c);
            LinkComputerImpl lc = new LinkComputerImpl(javaInspector);
            cache = new LinkedHashMap<>();
            c.recursiveSubTypeStream().flatMap(TypeInfo::constructorAndMethodStream).forEach(m -> {
                MethodLinkedVariables mlv = lc.doMethod(m);
                cache.put(m.name(), mlv + "  MOD[" + mlv.sortedModifiedString() + "]");
            });
        }
        return cache.get(name);
    }

    @DisplayName("modifying an element handed out by a getter modifies 'this', as the direct form does")
    @Test
    public void viaGetter() {
        assertEquals("[-] --> -  MOD[this.classes]", mlv("direct"));
        assertEquals("[-] --> -  MOD[this]", mlv("viaGetter"));
        assertEquals("[-] --> -  MOD[this]", mlv("viaGetterChain"));
        assertEquals("[] --> -  MOD[this]", mlv("viaFirst"));
    }

    @DisplayName("the getters themselves and a method that only reads the element modify nothing")
    @Test
    public void readsOnly() {
        assertEquals("[-] --> getClass∈this.classes.§$$s[-2]  MOD[]", mlv("getClass"));
        assertEquals("[] --> first∈this.list.§$s  MOD[]", mlv("first"));
        assertEquals("[-] --> -  MOD[]", mlv("reads"));
    }
}
