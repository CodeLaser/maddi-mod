package io.codelaser.maddi.modification.link.io;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.util.List;

import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.parsers.json.JSONParser;
import org.parsers.json.Node;

import io.codelaser.maddi.cst.api.analysis.Codec;
import io.codelaser.maddi.cst.api.element.CompilationUnit;
import io.codelaser.maddi.cst.api.element.SourceSet;
import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.api.variable.Variable;
import io.codelaser.maddi.cst.io.CodecImpl;
import io.codelaser.maddi.inspection.resource.SourceSetImpl;
import io.codelaser.maddi.modification.link.CommonTest;

/**
 * A marker variable's back-reference that names its owner, {@code ["m", "$_ce3M", owner]}, must find the definition
 * when the owner exists in two source sets. A refactor server's project parsed transform-support's source AND loaded
 * its jar's analysis snapshot: the definition was put under the jar's copy of {@code Loop.LoopDataImpl.withBreak},
 * the back-reference's owner resolved by name to the parsed copy, and {@code MethodInfoImpl.equals} tells the two
 * apart by source set, so the load died on "Cannot find $_ce3M under ..." (2026-11-09).
 */
public class TestMarkerAcrossSourceSets extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            public class X {
                boolean withBreak(boolean b) { return b; }
            }
            """;

    @DisplayName("the definition, read under one copy, is found through the other copy's name")
    @Test
    public void test() {
        TypeInfo x = javaInspector.parse("a.b.X", INPUT);
        MethodInfo read = x.findUniqueMethod("withBreak", 1);
        MethodInfo other = copyInOtherSourceSet(x, read);
        assertEquals(read.fullyQualifiedName(), other.fullyQualifiedName());
        assertNotEquals(read, other, "the two copies must be different keys, as on the server");

        LinkCodec linkCodec = new LinkCodec(javaInspector);
        LinkCodec.C codec = linkCodec.new C() {
            @Override
            public Info decodeInfoOutOfContext(Codec.Context context, Codec.EncodedValue encodedValue) {
                return other; // the source-set disambiguation's answer
            }
        };
        Codec.Context context = new CodecImpl.ContextImpl();
        context.push(read);
        String type = ((CodecImpl.E) codec.encodeType(context, runtime.booleanParameterizedType())).s();
        // as in the server's snapshot: the definition under the element being read, then a back-reference that
        // names its owner (the owner's encoding is irrelevant here: decodeInfoOutOfContext answers 'other')
        Variable defined = codec.decodeVariable(context, json("[\"M\",\"$_ce3M\"," + type + "]"));
        Variable back = codec.decodeVariable(context, json("[\"m\",\"$_ce3M\",[\"Ta.b.X\"]]"));
        assertSame(defined, back);
    }

    private static Codec.EncodedValue json(String s) {
        JSONParser parser = new JSONParser(s);
        parser.Root();
        Node root = parser.rootNode();
        return new CodecImpl.D(root.getFirstChild());
    }

    private MethodInfo copyInOtherSourceSet(TypeInfo x, MethodInfo original) {
        SourceSet jar = new SourceSetImpl.Builder().setName("transform.jar").setUri(URI.create("file:/transform.jar"))
                .setSourceDirectories(List.of()).build();
        CompilationUnit cu = runtime.newCompilationUnitBuilder().setPackageName("a.b").setSourceSet(jar).build();
        TypeInfo copy = runtime.newTypeInfo(cu, "X");
        copy.builder().setTypeNature(runtime.typeNatureClass()).setParentClass(runtime.objectParameterizedType())
                .setAccess(runtime.accessPublic()).setSource(x.source());
        MethodInfo method = runtime.newMethod(copy, original.name(), runtime.methodTypeMethod());
        method.builder().setReturnType(original.returnType()).setAccess(runtime.accessPackage())
                .setMethodBody(runtime.emptyBlock());
        method.builder().addParameter("b", runtime.booleanParameterizedType());
        method.builder().commitParameters().commit();
        copy.builder().addMethod(method).commit();
        return method;
    }
}
