package io.codelaser.maddi.modification.analyzer.kotlin;

import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.impl.analysis.PropertyImpl;
import io.codelaser.maddi.cst.impl.analysis.ValueImpl;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/*
 A bare `entries` in a member of its own enum is the static `Color.getEntries()`. The front end read it on `this`,
 prep isolated the method, and the IDE daemon (fault tolerant, with the reachability pass) showed it @Modified
 (maddi-kotlin-demo Color.next(), 2026-10-27).
 */
public class TestKotlinAnalyzerEnumEntries extends CommonKotlinAnalyzerTest {

    @Test
    public void entriesInAMemberIsNotAModification() {
        Analyzed a = analyze(List.of("X.kt", """
                package k
                enum class Color {
                    RED, GREEN, BLUE;
                    fun next(): Color = entries[(ordinal + 1) % entries.size]
                }
                """), List.of("X.java", "package j; class Dummy { }"), true);
        TypeInfo color = a.kotlinType("Color");
        assertTrue(method(color, "next").analysis()
                .getOrDefault(PropertyImpl.NON_MODIFYING_METHOD, ValueImpl.BoolImpl.FALSE).isTrue(), a.kotlin("Color"));
    }
}
