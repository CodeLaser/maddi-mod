package io.codelaser.maddi.modification.link.impl.graph;

import static io.codelaser.maddi.modification.link.impl.LinkNatureImpl.IS_SUBSET_OF;
import static io.codelaser.maddi.modification.link.impl.LinkNatureImpl.OBJECT_GRAPH_OVERLAPS;
import static io.codelaser.maddi.modification.link.impl.LinkNatureImpl.SHARES_ELEMENTS;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.codelaser.maddi.modification.prepwork.variable.LinkNature;

/*
CodeLaser/maddi-mod#92: the implicit '∩' across a join. The second alternative creates 'x ∩ y' through a temporary
't' ('x ⊆ t', 't ~ y'); after the join 't' leaves the graph. The overlap must survive, whether the classes are
computed after every change or once per statement (setImplicitSnapshot).
 */
public class TestImplicitSnapshotJoin {

    private static IncrementalFixpointEngine<String, LinkNature> engine(boolean snapshot) {
        IncrementalFixpointEngine<String, LinkNature> engine = new IncrementalFixpointEngine<>(LinkNature::combine,
                LinkNature::best, LinkNature::valid, LinkNature::score, LinkNature::reverse,
                Object::toString, String::compareTo, _ -> true);
        engine.setImplicitLabel(OBJECT_GRAPH_OVERLAPS, _ -> true, (_, _) -> true);
        engine.setImplicitSnapshot(snapshot);
        return engine;
    }

    private static void joinAndRemove(boolean snapshot, boolean creatorIsFirst) {
        IncrementalFixpointEngine<String, LinkNature> before = engine(snapshot);
        before.addVertex("x");
        before.addVertex("y");
        before.refreshImplicit();

        IncrementalFixpointEngine<String, LinkNature> plain = before.copy();
        IncrementalFixpointEngine<String, LinkNature> creator = before.copy();
        creator.addSymmetricEdge("x", "t", IS_SUBSET_OF, "0");
        creator.addSymmetricEdge("t", "y", SHARES_ELEMENTS, "1");
        creator.refreshImplicit();
        assertEquals(Set.of("y"), creator.implicitSuccessors("x").keySet());
        plain.refreshImplicit();
        assertEquals(Set.of(), plain.implicitSuccessors("x").keySet());

        IncrementalFixpointEngine<String, LinkNature> joined = creatorIsFirst ? creator : plain;
        joined.unionWith(creatorIsFirst ? plain : creator);
        joined.removeVertices(Set.of("t"));
        joined.refreshImplicit();
        assertEquals(Set.of("y"), joined.implicitSuccessors("x").keySet());
        assertEquals(Set.of("x"), joined.implicitSuccessors("y").keySet());
    }

    // 't' links 'x' and 'y' and leaves again without a refresh in between (a variable collapsed into its group)
    private static void createAndRemove(boolean snapshot) {
        IncrementalFixpointEngine<String, LinkNature> engine = engine(snapshot);
        engine.addVertex("x");
        engine.addVertex("y");
        engine.refreshImplicit();
        engine.addSymmetricEdge("x", "t", IS_SUBSET_OF, "0");
        engine.addSymmetricEdge("t", "y", SHARES_ELEMENTS, "0");
        engine.removeVertices(Set.of("t"));
        engine.refreshImplicit();
        assertEquals(Set.of("y"), engine.implicitSuccessors("x").keySet());
    }

    @DisplayName("created and removed between two refreshes")
    @Test
    public void test4() {
        createAndRemove(false);
        createAndRemove(true);
    }

    @DisplayName("computed after every change")
    @Test
    public void test1() {
        joinAndRemove(false, true);
        joinAndRemove(false, false);
    }

    @DisplayName("computed once per statement, the creating alternative first")
    @Test
    public void test2() {
        joinAndRemove(true, true);
    }

    @DisplayName("computed once per statement, the creating alternative second")
    @Test
    public void test3() {
        joinAndRemove(true, false);
    }
}
