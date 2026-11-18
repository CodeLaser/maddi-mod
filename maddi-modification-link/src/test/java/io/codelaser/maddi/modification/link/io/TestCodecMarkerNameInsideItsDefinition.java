package io.codelaser.maddi.modification.link.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.parsers.json.JSONParser;
import org.parsers.json.Node;

import io.codelaser.maddi.cst.api.analysis.Codec;
import io.codelaser.maddi.cst.api.info.FieldInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.api.type.ParameterizedType;
import io.codelaser.maddi.cst.api.variable.Variable;
import io.codelaser.maddi.cst.io.CodecImpl;
import io.codelaser.maddi.modification.link.CommonTest;
import io.codelaser.maddi.modification.link.impl.LinkedVariablesImpl;
import io.codelaser.maddi.modification.link.impl.Result;
import io.codelaser.maddi.modification.link.impl.localvar.FunctionalInterfaceVariable;
import io.codelaser.maddi.modification.prepwork.variable.impl.LinksImpl;

/**
 * A marker's definition that mentions a marker of the same name, under the same element. The encoder writes the inner
 * one as the bare back-reference {@code ["m", name]}; the decoder could not resolve it, because it registered the
 * definition only after decoding its body ("Cannot find $_fi32M under ...persister", hibernate-core's
 * AbstractOneToManyDecomposer: a field whose links hold a Function and a Consumer both named {@code $_fi32}, from two
 * methods).
 */
public class TestCodecMarkerNameInsideItsDefinition extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import java.util.function.*;
            class X {
                Function<String, String> f;
                Consumer<String> c;
            }
            """;

    @DisplayName("a marker named again inside its own definition decodes")
    @Test
    public void test() {
        TypeInfo X = javaInspector.parse("a.b.X", INPUT);
        FieldInfo f = X.getFieldByName("f", true);
        ParameterizedType function = f.type();
        ParameterizedType consumer = X.getFieldByName("c", true).type();

        FunctionalInterfaceVariable inner = new FunctionalInterfaceVariable("$_fi32", consumer, runtime,
                new Result(LinksImpl.EMPTY, LinkedVariablesImpl.EMPTY));
        Result outerResult = new Result(LinksImpl.EMPTY, LinkedVariablesImpl.EMPTY);
        outerResult.modified().put(inner, Set.of());
        FunctionalInterfaceVariable outer = new FunctionalInterfaceVariable("$_fi32", function, runtime, outerResult);

        Codec.Context write = new CodecImpl.ContextImpl();
        write.push(f);
        Codec codec = new LinkCodec(javaInspector).codec();
        Codec.EncodedValue encoded = codec.encodeVariable(write, outer);
        String text = ((CodecImpl.E) encoded).s();
        assertTrue(text.contains("\"m\""), "the inner marker is a back-reference: " + text);

        Codec.Context read = new CodecImpl.ContextImpl();
        read.push(f);
        Variable decoded = new LinkCodec(javaInspector).restoreCodec().decodeVariable(read, json(text));
        FunctionalInterfaceVariable fiv = assertInstanceOf(FunctionalInterfaceVariable.class, decoded);
        assertEquals("$_fi32M", fiv.simpleName());
        assertEquals(function, fiv.parameterizedType());
        Variable innerDecoded = fiv.result().modified().keySet().iterator().next();
        assertEquals("$_fi32M", innerDecoded.simpleName());
    }

    private static Codec.EncodedValue json(String s) {
        JSONParser parser = new JSONParser(s);
        parser.Root();
        Node root = parser.rootNode();
        return new CodecImpl.D(root.getFirstChild());
    }
}
