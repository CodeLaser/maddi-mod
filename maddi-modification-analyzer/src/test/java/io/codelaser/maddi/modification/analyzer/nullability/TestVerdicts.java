package io.codelaser.maddi.modification.analyzer.nullability;

import io.codelaser.maddi.cst.api.info.MethodInfo;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link Verdicts}: what the verdicts make of the closure, stored-only parameters and asserted locals.
 */
public class TestVerdicts extends NullabilityFixtures {

    @Language("java")
    private static final String ASSERTED = """
            package a.b;
            import java.util.*;
            class AD {
                static class Block {
                    void touch() { }
                }
                private final Map<Integer, Block> blocks = new HashMap<>();
                Block getWithKey(int j) { return blocks.containsKey(j) ? blocks.get(j) : null; }
                void edges(int from, int to, List<Block> range) {
                    for (int j = from; j < to; j++) {
                        Block block = getWithKey(j);
                        range.add(block);
                        block.touch();
                    }
                }
                void guarded(int j, List<Block> out) {
                    Block block = getWithKey(j);
                    out.add(block);
                    if (j > 0) block.touch();
                }
                void leaves(int j, List<Block> out2) {
                    Block block = getWithKey(j);
                    out2.add(block);
                    if (j < 0) return;
                    block.touch();
                }
                void reassigned(int j, List<Block> out3) {
                    Block block = getWithKey(j);
                    out3.add(block);
                    block.touch();
                    block = getWithKey(j + 1);
                }
                void nonNull(List<Block> out4) {
                    Block block = new Block();
                    out4.add(block);
                    block.touch();
                }
            }
            """;

    @DisplayName("Kotlin: a local its block dereferences unconditionally is asserted at its declaration")
    @Test
    public void assertedAtDeclaration() {
        Report report = run("a.b.AD", ASSERTED, Policy.KOTLIN);
        System.out.println(explain(report));
        Map<String, String> byName = report.verdicts().entrySet().stream()
                .collect(Collectors.toMap(e -> e.getKey().fullyQualifiedName(), e -> k(e.getValue())));
        assertEquals("List<Block>", byName.get("a.b.AD.edges(int,int,java.util.List):2:range"),
                "fernflower ControlFlowGraph.setExceptionEdges");
        assertEquals("List<Block?>", byName.get("a.b.AD.guarded(int,java.util.List):1:out"), "a conditional dereference");
        assertEquals("List<Block?>", byName.get("a.b.AD.leaves(int,java.util.List):1:out2"), "a return before it");
        assertEquals("List<Block?>", byName.get("a.b.AD.reassigned(int,java.util.List):1:out3"), "assigned again");
        assertEquals("""
                edges.block: Block
                edges.j: int
                guarded.block: Block?
                leaves.block: Block?
                nonNull.block: Block
                reassigned.block: Block?""", locals(report));
        // the printer's lookup, by declaring element
        List<String> asserted = new java.util.ArrayList<>();
        for (MethodInfo mi : parsed.methods()) {
            mi.methodBody().visit(e -> {
                if (e instanceof io.codelaser.maddi.cst.api.statement.LocalVariableCreation lvc) {
                    lvc.localVariableStream().filter(lv -> report.assertedAtDeclaration(mi, lvc, lv))
                            .forEach(lv -> asserted.add(mi.name() + "." + lv.simpleName()));
                }
                return true;
            });
        }
        assertEquals("[edges.block]", asserted.toString(), "not where nothing null arrives (nonNull)");

        Report java = run("a.b.AD2", ASSERTED.replace("class AD ", "class AD2 "));
        Map<String, String> javaByName = java.verdicts().entrySet().stream()
                .collect(Collectors.toMap(e -> e.getKey().fullyQualifiedName(), e -> k(e.getValue())));
        assertEquals("List<Block?>", javaByName.get("a.b.AD2.edges(int,int,java.util.List):2:range"),
                "Java annotations say what can happen");
    }

    @DisplayName("Kotlin: a local a for loop's init clause or condition dereferences is asserted at its declaration")
    @Test
    public void assertedBeforeForLoop() {
        Report report = run("a.b.AF", """
                package a.b;
                class AF {
                    static class Block { void touch() { } }
                    static Block[] holes(int n) { return n > 0 ? new Block[n] : null; }
                    void clear(int n) {
                        Block[] arr = holes(n);
                        int[] counts = new int[3];
                        for (int i = arr.length - 1; i >= 0; i--) {
                            if (arr[i] != null) { arr[i].touch(); counts[0]++; }
                        }
                    }
                    void viaCondition(int n) {
                        Block[] arr = holes(n);
                        for (int i = 0; i < arr.length; i++) { System.out.println(arr[i]); }
                    }
                    void notCertain(int n) {
                        Block[] arr = holes(n);
                        for (int i = 0; i < n; i++) { System.out.println(arr.length); }
                    }
                }
                """, Policy.KOTLIN);
        List<String> asserted = new java.util.ArrayList<>();
        for (MethodInfo mi : parsed.methods()) {
            mi.methodBody().visit(e -> {
                if (e instanceof io.codelaser.maddi.cst.api.statement.LocalVariableCreation lvc) {
                    lvc.localVariableStream().filter(lv -> report.assertedAtDeclaration(mi, lvc, lv))
                            .forEach(lv -> asserted.add(mi.name() + "." + lv.simpleName()));
                }
                return true;
            });
        }
        assertEquals("[clear.arr, viaCondition.arr]", asserted.toString(), "not a dereference only in the body");
    }

    @DisplayName("Kotlin: an asserted local is unobserved before its dereference only with the links' proof")
    @Test
    public void unobservedBeforeDereference() {
        Report report = run("a.b.UO", """
                package a.b;
                import java.util.*;
                class UO {
                    static class Block {
                        int id;
                        Block next;
                        void touch() { }
                    }
                    static String key(Map<Integer, Block> instrBlocks, int a, int b) {
                        Block from = instrBlocks.get(a);
                        Block to = instrBlocks.get(b);
                        return from.id + ":" + to.id;
                    }
                    static Block find(int j) { return j < 0 ? null : new Block(); }
                    static void range(int n, List<Block> protectedRange) {
                        for (int j = 0; j < n; j++) {
                            Block block = find(j);
                            protectedRange.add(block);
                            block.touch();
                        }
                    }
                    static void alias(Block holder, int j) {
                        Block block = holder.next;
                        System.out.println(holder.next);
                        block.touch();
                    }
                    static void next(int j) {
                        Block block = find(j);
                        block.touch();
                    }
                }
                """, Policy.KOTLIN);
        System.out.println(explain(report));
        List<String> asserted = new java.util.ArrayList<>();
        List<String> unobserved = new java.util.ArrayList<>();
        for (MethodInfo mi : parsed.methods()) {
            mi.methodBody().visit(e -> {
                if (e instanceof io.codelaser.maddi.cst.api.statement.LocalVariableCreation lvc) {
                    lvc.localVariableStream().forEach(lv -> {
                        if (report.assertedAtDeclaration(mi, lvc, lv)) asserted.add(mi.name() + "." + lv.simpleName());
                        if (report.unobservedBeforeDereference(mi, lvc, lv)) {
                            unobserved.add(mi.name() + "." + lv.simpleName());
                        }
                    });
                }
                return true;
            });
        }
        java.util.Collections.sort(asserted);
        java.util.Collections.sort(unobserved);
        assertEquals("[alias.block, key.from, key.to, next.block, range.block]", asserted.toString());
        // key.from: fernflower ControlFlowGraph:337, 'to = instrBlocks.get(...)' reads the container, not 'from';
        // range.block: ControlFlowGraph:351, 'protectedRange.add(block)' refers to it;
        // alias.block: 'holder.next' is the same value
        assertEquals("[key.from, key.to, next.block]", unobserved.toString());
    }

    /*
     A setter whose parameter is only stored into a field that is nullable anyway: null means what not calling it
     means (langchain4j DefaultRetrievalAugmentorBuilder.executor, which its tests call with null). Not a setter that
     checks its argument, nor one of a field that is never null.
     */
    @DisplayName("a parameter only stored into a nullable field takes null")
    @Test
    public void storedOnlyIntoNullableField() {
        Report report = run("a.b.RA", """
                package a.b;
                import java.util.Objects;
                import java.util.concurrent.Executor;
                import java.util.function.Supplier;
                class RA {
                    private final Executor executor;
                    RA(Executor executor) { this.executor = orDefault(executor, RA::defaultExecutor); }
                    static <T> T orDefault(T value, Supplier<T> supplier) { return value != null ? value : supplier.get(); }
                    static Executor defaultExecutor() { return Runnable::run; }
                    Executor executor() { return executor; }
                    static Builder builder() { return new Builder(); }
                    static class Builder {
                        private Executor executor;
                        private String name;
                        private String label = "none";
                        Builder executor(Executor executor) { this.executor = executor; return this; }
                        Builder name(String name) { this.name = Objects.requireNonNull(name); return this; }
                        Builder label(String label) { this.label = label; return this; }
                        RA build() { return new RA(executor); }
                    }
                }
                """, Policy.KOTLIN);
        Map<String, String> s = new java.util.TreeMap<>();
        report.verdicts().forEach((info, pt) -> s.put(info.fullyQualifiedName(), k(pt)));
        assertEquals("Executor?", s.get("a.b.RA.Builder.executor(java.util.concurrent.Executor):0:executor"), s.toString());
        assertEquals("String", s.get("a.b.RA.Builder.name(String):0:name"), s.toString());
        assertEquals("String", s.get("a.b.RA.Builder.label(String):0:label"), s.toString());
    }

    // fernflower Statement.replaceExprent / SwitchStatement: the rule does not split an override from its overridden
    // method, in either direction
    @DisplayName("a parameter only stored into a nullable field: not in an override hierarchy")
    @Test
    public void storedOnlyIntoNullableFieldOverride() {
        Report report = run("a.b.SO", """
                package a.b;
                class SO {
                    static class Statement {
                        void replace(String oldValue, String newValue) { throw new UnsupportedOperationException(); }
                    }
                    static class Switch extends Statement {
                        private String value;
                        @Override void replace(String oldValue, String newValue) { this.value = newValue; }
                        String value() { return value; }
                    }
                    static class Base {
                        private String name;
                        void name(String name) { this.name = name; }
                        String name() { return name; }
                    }
                    static class Derived extends Base {
                        @Override void name(String name) { super.name(name.trim()); }
                    }
                }
                """, Policy.KOTLIN);
        Map<String, String> s = new java.util.TreeMap<>();
        report.verdicts().forEach((info, pt) -> s.put(info.fullyQualifiedName(), k(pt)));
        assertEquals("String", s.get("a.b.SO.Switch.replace(String,String):1:newValue"), s.toString());
        assertEquals("String", s.get("a.b.SO.Statement.replace(String,String):1:newValue"), s.toString());
        assertEquals("String", s.get("a.b.SO.Base.name(String):0:name"), s.toString());
    }
}
