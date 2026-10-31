package io.codelaser.maddi.modification.analyzer.nullability;

import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link OutsideCallers}: the world policy, and the parameters code outside the analysis can call with null.
 */
public class TestOutsideCallers extends NullabilityFixtures {

    @Language("java")
    private static final String WORLD = """
            package a.b;
            import java.util.Comparator;
            public class W {
                private String stored = "";
                public void store(String s) { this.stored = s; }
                public int length(String s) { return s.length(); }
                public String echo(String s) { return s; }
                public String checked(String s) {
                    if (s == null) throw new IllegalArgumentException();
                    return s;
                }
                public String early(String s, boolean b) {
                    if (b) return "x";
                    return s.trim();
                }
                int internal(String s) { return 1; }
                private String helper(String s) { return s; }
                public String use() { return helper("y") + internal("z") + stored; }
                static final class Hidden implements Comparator<String> {
                    public int compare(String a, String b) { return 0; }
                    public void m(String s) { }
                }
            }
            """;

    @DisplayName("world: closed decides from the analysed calls; open makes outside-callable parameters unspecified")
    @Test
    public void world() {
        String closed = verdicts(run("a.b.W", WORLD));
        assertEquals("""
                checked(): String
                checked(0:s): String?
                compare(): int
                compare(0:a): String?
                compare(1:b): String?
                early(): String
                early(0:s): String
                early(1:b): boolean
                echo(): String
                echo(0:s): String
                helper(): String
                helper(0:s): String
                internal(): int
                internal(0:s): String
                length(): int
                length(0:s): String
                m(0:s): String
                store(0:s): String
                stored: String
                use(): String""", closed);
        // a public parameter no analysed call passes null to: unspecified, and so is what it flows into; the
        // package-private and private methods, and the public method of a package-private type, stay closed-world; an
        // override of a library method is called by the library. 'compare' ignores both: nullable (maddi-mod#68; Hidden
        // is final, so this is no hook)
        assertEquals("""
                checked(): String
                checked(0:s): String?
                compare(): int
                compare(0:a): String?
                compare(1:b): String?
                early(): String
                early(0:s): String!
                early(1:b): boolean
                echo(): String!
                echo(0:s): String!
                helper(): String
                helper(0:s): String
                internal(): int
                internal(0:s): String
                length(): int
                length(0:s): String!
                m(0:s): String
                store(0:s): String!
                stored: String!
                use(): String""", verdicts(new NullabilityPass(
                Policy.NULL_MARKED.withWorld(World.OPEN_VISIBILITY)).go(analysisOrder)));
        // with preconditions: dereferenced on every normal exit is non-null; 'early' returns before the dereference
        assertEquals("""
                checked(): String
                checked(0:s): String?
                compare(): int
                compare(0:a): String?
                compare(1:b): String?
                early(): String
                early(0:s): String!
                early(1:b): boolean
                echo(): String!
                echo(0:s): String!
                helper(): String
                helper(0:s): String
                internal(): int
                internal(0:s): String
                length(): int
                length(0:s): String
                m(0:s): String
                store(0:s): String!
                stored: String!
                use(): String""", verdicts(new NullabilityPass(
                Policy.NULL_MARKED.withWorld(World.OPEN)).go(analysisOrder)));
    }
}
