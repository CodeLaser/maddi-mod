package io.codelaser.maddi.modification.analyzer.modification;

import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.modification.analyzer.CommonTest;
import io.codelaser.maddi.modification.analyzer.impl.IteratingAnalyzerImpl;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/*
 The shapes of langchain4j-core's JsonSchemaElementJsonUtils.fromMap(Map<String, Object> map), which only reads its
 map: recursion on the map's elements, directly and inside a forEach lambda, an identity helper, and a switch
 expression with bare and block arms. Each keeps the parameter unmodified, with the reachability pass on. On the
 corpus, the fixpoint agrees (unmodified); the reachability pass's final word makes it modified there, so the cause
 is the pass's view of fromMap's context, not its body (2026-10-09, TestLangchain4jCollections).
 */
public class TestRecursiveReadOnlyParameter extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import java.util.List;
            import java.util.Map;
            class R {
                // fromMap's shape: reads the map, recurses on its elements
                static int recursive(Map<String, Object> map) {
                    int n = map.size();
                    if (map.containsKey("anyOf")) {
                        for (Object element : (List<?>) map.get("anyOf")) {
                            n += recursive((Map<String, Object>) element);
                        }
                    }
                    return n;
                }
                // the same without the recursion
                static int flat(Map<String, Object> map) {
                    int n = map.size();
                    if (map.containsKey("anyOf")) {
                        for (Object element : (List<?>) map.get("anyOf")) {
                            n += ((Map<?, ?>) element).size();
                        }
                    }
                    return n;
                }
                // recursion inside a lambda given to forEach on the map's content (fromMap's "properties")
                static int viaForEach(Map<String, Object> map) {
                    int[] n = {map.size()};
                    Object props = map.get("properties");
                    if (props instanceof Map) {
                        ((Map<String, Object>) props).forEach((name, value) -> n[0] += viaForEach((Map<String, Object>) value));
                    }
                    return n[0];
                }
                // a lambda given to forEach on the content, without recursion
                static int forEachFlat(Map<String, Object> map) {
                    int[] n = {map.size()};
                    Object props = map.get("properties");
                    if (props instanceof Map) {
                        ((Map<String, Object>) props).forEach((name, value) -> n[0] += ((Map<?, ?>) value).size());
                    }
                    return n[0];
                }
                // ensureNotNull's shape: an identity helper
                static <T> T ensure(T t, String name) {
                    if (t == null) throw new IllegalArgumentException(name);
                    return t;
                }
                static int ensured(Map<String, Object> map) {
                    ensure(map, "map");
                    return map.size();
                }
                static boolean has(Map<String, Object> map, String key) { return map.containsKey(key); }
                // fromMap's tail: a switch expression whose arms are bare expressions using the parameter
                static int switchArms(Map<String, Object> map, String type) {
                    return switch (type) {
                        case "a" -> has(map, "x") ? 1 : 2;
                        case "b" -> map.size();
                        default -> has(map, "y") ? 3 : 4;
                    };
                }
                // the same with block arms
                static int switchBlocks(Map<String, Object> map, String type) {
                    return switch (type) {
                        case "a" -> { yield has(map, "x") ? 1 : 2; }
                        default -> { yield map.size(); }
                    };
                }
                // recursion on a value unrelated to the parameter
                static int unrelated(Map<String, Object> map, int depth) {
                    if (depth == 0) return map.size();
                    return unrelated(Map.of(), depth - 1) + map.size();
                }
            }
            """;

    @Test
    public void test() {
        TypeInfo R = javaInspector.parse("a.b.R", INPUT);
        List<Info> ao = prepWork(R);
        new IteratingAnalyzerImpl(javaInspector, new IteratingAnalyzerImpl.ConfigurationBuilder()
                .setMaxIterations(10)
                .setModificationViaReachability(true)
                .build()).analyze(ao);
        StringBuilder sb = new StringBuilder();
        for (String name : List.of("recursive", "flat", "unrelated", "viaForEach", "forEachFlat", "ensured", "switchArms", "switchBlocks")) {
            MethodInfo m = R.methods().stream().filter(mi -> mi.name().equals(name)).findFirst().orElseThrow();
            sb.append(name).append(": unmodified=").append(m.parameters().getFirst().isUnmodified()).append('\n');
        }
        System.out.println(sb);
        assertTrue(sb.toString().contains("flat: unmodified=true"), sb.toString());
        assertTrue(sb.toString().contains("unrelated: unmodified=true"), sb.toString());
        assertTrue(sb.toString().contains("recursive: unmodified=true"), sb.toString());
        assertTrue(sb.toString().contains("viaForEach: unmodified=true"), sb.toString());
        assertTrue(sb.toString().contains("forEachFlat: unmodified=true"), sb.toString());
        assertTrue(sb.toString().contains("ensured: unmodified=true"), sb.toString());
        assertTrue(sb.toString().contains("switchBlocks: unmodified=true"), sb.toString());
        assertTrue(sb.toString().contains("switchArms: unmodified=true"), sb.toString());
    }
}
