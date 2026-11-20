package io.codelaser.maddi.modification.analyzer.kotlin;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.Test;

/*
 fernflower's ExprProcessor.processBlock: `val stack = data!!.getStack()`, then `stack.push(…)` inside J2K's translation
 of a switch with `break`s, `run switch1@ { when (opcode) { … return@switch1 … } }`. Java modifies `data`; Kotlin did
 not, because the `run` stayed a call with a lambda and the lambda's modification of the captured `stack` did not
 reach `data` (maddi-mod#145). maddi's K2 converter now lowers it into the labelled block `sw: { … break sw; … }`,
 as kotlinc inlines it (RunBlockLoweringTest). The getter, the `!!` and the `when { else -> … }` were ruled out.
 */
public class TestKotlinAnalyzerLabelledRun extends CommonKotlinAnalyzerTest {

    @Language("kotlin")
    private static final String KOTLIN = """
            package k
            import java.util.ArrayList
            open class Stk<T> : ArrayList<T>() {
                private var pointer: Int = 0
                open fun push(item: T) { add(item); pointer++ }
            }
            open class Pel {
                private val stack: Stk<String>
                constructor() : this(Stk<String>())
                private constructor(stack: Stk<String>) { this.stack = stack }
                open fun getStack(): Stk<String> = stack
            }
            class P {
                fun viaBang(data: Pel?) { val s = data!!.getStack(); s.push("a") }
                fun viaPlain(data: Pel) { val s = data.getStack(); s.push("a") }
                fun viaDirect(data: Pel?) { data!!.getStack().push("a") }
                fun viaRun(data: Pel?) { val s = data!!.getStack(); run sw@ { when (s.size) { 0 -> { s.push("a") } else -> return@sw } } }
                fun viaBlock(data: Pel?) { val s = data!!.getStack(); run sw@ { if (s.size == 0) { s.push("a"); return@sw }; s.push("b") } }
                fun viaWhenElse(data: Pel?) { val s = data!!.getStack(); when { else -> { s.push("a") } } }
            }
            """;

    @Language("java")
    private static final String JAVA = """
            package j;
            import java.util.ArrayList;
            class Stk<T> extends ArrayList<T> {
                private int pointer;
                void push(T item) { add(item); pointer++; }
            }
            class Pel {
                private final Stk<String> stack;
                Pel() { this(new Stk<>()); }
                private Pel(Stk<String> stack) { this.stack = stack; }
                Stk<String> getStack() { return stack; }
            }
            final class P {
                void viaBang(Pel data) { Stk<String> s = data.getStack(); s.push("a"); }
                void viaPlain(Pel data) { Stk<String> s = data.getStack(); s.push("a"); }
                void viaDirect(Pel data) { data.getStack().push("a"); }
                void viaRun(Pel data) { Stk<String> s = data.getStack(); sw: switch (s.size()) { case 0: s.push("a"); break; default: break sw; } }
                void viaBlock(Pel data) { Stk<String> s = data.getStack(); sw: { if (s.size() == 0) { s.push("a"); break sw; } s.push("b"); } }
                void viaWhenElse(Pel data) { Stk<String> s = data.getStack(); s.push("a"); }
            }
            """;

    @Test
    public void theOwnerIsModified() {
        Analyzed a = analyze(List.of("X.kt", KOTLIN), List.of("X.java", JAVA), true);
        StringBuilder sb = new StringBuilder();
        for (String m : new String[]{"viaBang", "viaPlain", "viaDirect", "viaRun", "viaBlock", "viaWhenElse"}) {
            sb.append(m).append(" java=").append(a.javaType("P").findUniqueMethod(m, 1).parameters().getFirst().isUnmodified())
              .append(" kotlin=").append(a.kotlinType("P").findUniqueMethod(m, 1).parameters().getFirst().isUnmodified()).append('\n');
        }
        assertEquals("""
                viaBang java=false kotlin=false
                viaPlain java=false kotlin=false
                viaDirect java=false kotlin=false
                viaRun java=false kotlin=false
                viaBlock java=false kotlin=false
                viaWhenElse java=false kotlin=false
                """, sb.toString());
    }
}
