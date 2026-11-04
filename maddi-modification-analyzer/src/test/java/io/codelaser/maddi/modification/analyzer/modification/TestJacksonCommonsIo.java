package io.codelaser.maddi.modification.analyzer.modification;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.apache.commons.io.IOUtils;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.codelaser.maddi.cst.api.element.SourceSet;
import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.inspection.resource.SourceSetImpl;
import io.codelaser.maddi.modification.analyzer.CommonTest;
import io.codelaser.maddi.modification.analyzer.impl.IteratingAnalyzerImpl;

/**
 * The commons-io and Jackson 2 hints (libs/commonsio, libs/jackson). A mapper that reads or writes is not modified;
 * one that is configured is. The data commons-io writes is read; the stream it reads from is consumed.
 */
public class TestJacksonCommonsIo extends CommonTest {

    @Override
    protected List<SourceSet> extraClassPathParts() {
        return List.of(SourceSetImpl.sourceSetOf(IOUtils.class),
                SourceSetImpl.sourceSetOf(ObjectMapper.class),
                SourceSetImpl.sourceSetOf(JsonParser.class),
                SourceSetImpl.sourceSetOf(JsonInclude.class));
    }

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import com.fasterxml.jackson.databind.DeserializationFeature;
            import com.fasterxml.jackson.databind.JsonNode;
            import com.fasterxml.jackson.databind.ObjectMapper;
            import com.fasterxml.jackson.databind.SerializationFeature;
            import org.apache.commons.io.IOUtils;
            import java.io.IOException;
            import java.io.InputStream;
            import java.io.Writer;
            import java.util.List;
            class X {
                JsonNode readTree(ObjectMapper mapper, InputStream in) throws IOException { return mapper.readTree(in); }
                String write(ObjectMapper mapper, List<String> value) throws IOException { return mapper.writeValueAsString(value); }
                void configure(ObjectMapper mapper, SerializationFeature f) { mapper.enable(f); }
                void enableAll(ObjectMapper mapper, DeserializationFeature[] features) { mapper.enable(features[0], features); }
                void writeLines(List<String> lines, Writer writer) throws IOException { IOUtils.writeLines(lines, "\\n", writer); }
                String slurp(InputStream in, String unused) throws IOException { return IOUtils.toString(in, "UTF-8"); }
            }
            """;

    @Test
    public void test() {
        TypeInfo X = javaInspector.parse("a.b.X", INPUT);
        List<Info> ao = prepWork(X);
        var iterating = new IteratingAnalyzerImpl(javaInspector,
                new IteratingAnalyzerImpl.ConfigurationBuilder()
                        .setMaxIterations(10)
                        .setModificationViaReachability(true)
                        .build());
        iterating.analyze(ao);
        StringBuilder sb = new StringBuilder();
        for (String m : List.of("readTree", "write", "configure", "enableAll", "writeLines", "slurp")) {
            var params = X.findUniqueMethod(m, 2).parameters();
            sb.append(m).append('=').append(params.get(0).isUnmodified() ? "U" : "M")
                    .append(params.get(1).isUnmodified() ? "U" : "M").append(' ');
        }
        // readTree: the mapper is not modified, the stream is consumed; write: neither; configure: the mapper is;
        // enableAll: the features array is read; writeLines: the lines are read, the writer written; slurp: consumed
        assertEquals("readTree=UM write=UU configure=MU enableAll=MU writeLines=UM slurp=MU ", sb.toString());
    }
}
