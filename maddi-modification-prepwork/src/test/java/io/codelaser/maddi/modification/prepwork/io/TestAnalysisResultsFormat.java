package io.codelaser.maddi.modification.prepwork.io;

import io.codelaser.maddi.cst.api.analysis.Codec;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.impl.analysis.PropertyImpl;
import io.codelaser.maddi.cst.impl.analysis.ValueImpl;
import io.codelaser.maddi.modification.prepwork.CommonTest;
import io.codelaser.maddi.util.Trie;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/*
 The analysis-results format carries a marker (CodeLaser/maddi-mod#3): a file written by another release is refused
 with a message that says so, a file written before the marker existed still loads, and an element that is not
 shaped as the format says is reported as format drift, naming the element, not as a ClassCastException.
 */
public class TestAnalysisResultsFormat extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            class X {
                int m() { return 1; }
            }
            """;

    private static final String MARKER = "{\"format\": \"maddi-analysis-results\", \"version\": 1}";

    private Codec codec;
    private io.codelaser.maddi.cst.api.element.SourceSet sourceSet;

    private String write() throws IOException {
        TypeInfo X = javaInspector.parse(ABX, INPUT);
        X.analysis().set(PropertyImpl.IMMUTABLE_TYPE, ValueImpl.ImmutableImpl.IMMUTABLE);
        sourceSet = X.compilationUnit().sourceSet();
        codec = new PrepWorkCodec(runtime, sourceSet).codec();
        Trie<TypeInfo> trie = new Trie<>();
        trie.add(new String[]{"a", "b"}, X);
        File dir = new File("build/analysis-results-format");
        new WriteAnalysisResults(runtime).write(dir, trie, codec);
        return Files.readString(new File(dir, "AB.json").toPath());
    }

    // onto a fresh parse: a verdict cannot be applied twice to the same type
    private int load(String content) {
        javaInspector.invalidateAllSources();
        TypeInfo X = javaInspector.parse(ABX, INPUT);
        int loaded = new LoadAnalysisResults(runtime, sourceSet).go(codec, content);
        if (loaded == 1) {
            assertSame(ValueImpl.ImmutableImpl.IMMUTABLE, X.analysis().getOrNull(PropertyImpl.IMMUTABLE_TYPE,
                    ValueImpl.ImmutableImpl.class));
        }
        return loaded;
    }

    private String refused(String content) {
        return assertThrows(AnalysisResultsFormatException.class, () -> load(content)).getMessage();
    }

    @Test
    public void marker() throws IOException {
        String written = write();
        assertTrue(written.startsWith("[\n" + MARKER + ",\n\n{\"name\": \"Ta.b.X\""), written);
        assertEquals(WriteAnalysisResults.formatMarker(), MARKER);
        assertEquals(1, load(written));

        // written before the marker existed: version 0, still read
        assertEquals(1, load(written.replace(MARKER + ",\n", "")));

        // another release's version, another format: refused with the reason, not with a ClassCastException
        String otherVersion = written.replace("\"version\": 1", "\"version\": 99");
        assertEquals("written in analysis-results format version 99, this reader reads version 1: regenerate the "
                     + "file with this release", refused(otherVersion));
        assertEquals("not an analysis-results file: format 'something-else', expected 'maddi-analysis-results'",
                refused(written.replace("maddi-analysis-results", "something-else")));

        // shape drift inside an element
        assertEquals("expected \"name\" as entry 1 of a primary type's element, found \"nam\"",
                refused(written.replace("\"name\": \"Ta.b.X\"", "\"nam\": \"Ta.b.X\"")));
        assertEquals("expected \"data\" as entry 2 of element Ta.b.X, found \"dat\"",
                refused(written.replace("\"data\":{\"immutableType\"", "\"dat\":{\"immutableType\"")));
        assertEquals("expected an array of analysis results, found JSONObject", refused("{\"a\": 1}"));
    }

    @Test
    public void fileAndDirectory() throws IOException {
        String written = write();
        Path dir = Files.createDirectories(Path.of("build/analysis-results-format/other-version"));
        Path file = dir.resolve("AB.json");
        Files.writeString(file, written.replace("\"version\": 1", "\"version\": 99"));

        // the file is named
        LoadAnalysisResults loader = new LoadAnalysisResults(runtime, sourceSet);
        String message = assertThrows(AnalysisResultsFormatException.class, () -> loader.go(codec, file)).getMessage();
        assertTrue(message.startsWith(file + ": written in analysis-results format version 99"), message);

        // a checkpoint of another release: skipped and counted, never fatal, so the run re-analyzes
        assertEquals(0, loader.goDirTolerant(codec, dir.toFile()));
        assertThrows(AnalysisResultsFormatException.class, () -> loader.goDir(codec, dir.toFile()));
    }
}
