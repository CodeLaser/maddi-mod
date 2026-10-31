package io.codelaser.maddi.modification.analyzer.nullability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link Switches}: a gate switched off in-process.
 */
public class TestSwitches extends NullabilityFixtures {

    @DisplayName("Switches: a gate switched off in-process changes the next pass, as its environment variable does")
    @Test
    public void gateSwitchedOffInProcess() {
        java.util.function.Function<String, String> local = name -> {
            Map<String, String> l = new java.util.TreeMap<>();
            run("a.b." + name, """
                    package a.b;
                    import java.util.ArrayList;
                    import java.util.List;
                    import java.util.Objects;
                    import java.util.Set;
                    import java.util.stream.Collectors;
                    class %s {
                        Set<String> nonNull(List<String> values) {
                            List<String> additions = new ArrayList<>(values);
                            additions.add(null);
                            Set<String> nonNullAdditions = additions.stream().filter(Objects::nonNull)
                                    .collect(Collectors.toSet());
                            return nonNullAdditions;
                        }
                    }
                    """.formatted(name), Policy.KOTLIN)
                    .locals().forEach((lv, pt) -> l.put(lv.name(), k(pt)));
            return l.get("nonNullAdditions");
        };
        assertEquals("Set<String>", local.apply("GS1"));
        Switches.offForNextPasses(java.util.Set.of("NONULLFILTERS"));
        try {
            assertEquals("Set<String?>", local.apply("GS2"));
        } finally {
            Switches.offForNextPasses(java.util.Set.of());
        }
        assertEquals("Set<String>", local.apply("GS3"));
    }
}
