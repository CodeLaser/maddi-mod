package io.codelaser.maddi.modification.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.codelaser.maddi.cst.api.info.TypeInfo;

// the visitor must descend into lambda bodies and anonymous class bodies: a reference reachable only there
// (here 'Helper.process') must still be stubbed, otherwise the printed frame would not reparse
public class TestIsolateMethod8LambdasAndAnonymousTypes extends CommonIsolateMethodTest {

    @Language("java")
    public static final String L1 = """
            package a.b;
            import java.util.List;
            public class X {
                void method(List<String> list) {
                    list.forEach(s -> Helper.process(s));
                }
            }
            class Helper { static void process(String s) { } }
            """;

    @DisplayName("reference only inside a lambda body is stubbed")
    @Test
    public void l1() {
        TypeInfo X = parse("a.b.X", L1);
        String m = """
                void method(List<String> list) {
                    list.forEach(s -> Helper.process(s));
                }""";
        String out = isolate(X, "method", 1, m);
        @Language("java")
        String expected = """
                import java.util.List;
                public class X_method {
                    static class Helper { static void process(String s) { } }
                    void method(List<String> list) {
                    list.forEach(s -> Helper.process(s));
                }
                }
                """;
        assertEquals(expected, out);
        javaInspector.invalidateAllSources();
        assertNotNull(javaInspector.parse("X_method", out));
    }

    @Language("java")
    public static final String A1 = """
            package a.b;
            public class X {
                Runnable method() {
                    return new Runnable() {
                        @Override
                        public void run() {
                            Helper.process("x");
                        }
                    };
                }
            }
            class Helper { static void process(String s) { } }
            """;

    @DisplayName("reference only inside an anonymous class body is stubbed")
    @Test
    public void a1() {
        TypeInfo X = parse("a.b.X", A1);
        String m = """
                Runnable method() {
                    return new Runnable() {
                        @Override
                        public void run() {
                            Helper.process("x");
                        }
                    };
                }""";
        String out = isolate(X, "method", 0, m);
        @Language("java")
        String expected = """
                public class X_method {
                    static class Helper { static void process(String s) { } }
                    Runnable method() {
                    return new Runnable() {
                        @Override
                        public void run() {
                            Helper.process("x");
                        }
                    };
                }
                }
                """;
        assertEquals(expected, out);
        javaInspector.invalidateAllSources();
        assertNotNull(javaInspector.parse("X_method", out));
    }

    @Language("java")
    public static final String A2 = """
            package a.b;
            public class X {
                Object method() {
                    return new Point(6) { };
                }
                Object plain() {
                    return new Point(7);
                }
            }
            class Point { Point(int x) { } Point(String s, int y) { } }
            """;

    /**
     * CodeLaser/maddi#214: {@code new Point(6) { }} calls {@code Point(int)}, so the stub of Point must declare it, as for
     * {@code new Point(7)}. Without it the stub has only its default constructor and the isolated unit does not
     * compile.
     */
    @DisplayName("the superclass constructor of an anonymous creation is stubbed")
    @Test
    public void a2() {
        TypeInfo X = parse("a.b.X", A2);
        String anonymous = isolate(X, "method", 0, """
                Object method() {
                    return new Point(6) { };
                }""");
        String plain = isolate(X, "plain", 0, """
                Object plain() {
                    return new Point(7);
                }""");
        String stubOfPlain = plain.lines().filter(l -> l.contains("class Point")).findFirst().orElseThrow();
        String stubOfAnonymous = anonymous.lines().filter(l -> l.contains("class Point")).findFirst().orElseThrow();
        assertEquals(stubOfPlain, stubOfAnonymous, anonymous);
        assertTrue(stubOfAnonymous.contains("Point(int"), anonymous);
        javaInspector.invalidateAllSources();
        assertNotNull(javaInspector.parse("X_method", anonymous));
    }
}
