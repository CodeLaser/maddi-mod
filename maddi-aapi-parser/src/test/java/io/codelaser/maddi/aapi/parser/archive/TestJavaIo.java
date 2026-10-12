/*
 * maddi: a modification analyzer for duplication detection and immutability.
 * Copyright 2020-2025, Bart Naudts, https://github.com/CodeLaser/maddi
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Lesser General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License for
 * more details. You should have received a copy of the GNU Lesser General Public
 * License along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package io.codelaser.maddi.aapi.parser.archive;

import io.codelaser.maddi.aapi.parser.CommonTest;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.ParameterInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import org.junit.jupiter.api.Test;

import java.io.*;

import static io.codelaser.maddi.cst.impl.analysis.ValueImpl.NullabilityImpl.UNSPECIFIED;
import static io.codelaser.maddi.cst.impl.analysis.PropertyImpl.*;
import static io.codelaser.maddi.cst.impl.analysis.ValueImpl.BoolImpl.FALSE;
import static io.codelaser.maddi.cst.impl.analysis.ValueImpl.BoolImpl.TRUE;
import static io.codelaser.maddi.cst.impl.analysis.ValueImpl.ImmutableImpl.FINAL_FIELDS;
import static io.codelaser.maddi.cst.impl.analysis.ValueImpl.ImmutableImpl.MUTABLE;
import static io.codelaser.maddi.cst.impl.analysis.ValueImpl.IndependentImpl.*;
import static org.junit.jupiter.api.Assertions.*;

public class TestJavaIo extends CommonTest {

    // read-only arguments the library-call census (TestLibraryCallCensus, 2026-10-12) found read as modified
    @Test
    public void testReadOnlyArgumentsFromTheCensus() {
        TypeInfo file = compiledTypesManager().typeIfLoaded(File.class);
        assertTrue(file.isAtLeastImmutableHC(), "File is immutable like java.nio.file.Path");
        assertFalse(member(File.class, "java.io.File.<init>(java.io.File,String)").parameters().getFirst().isModified());
        assertFalse(member(FileInputStream.class, "java.io.FileInputStream.<init>(java.io.File)").parameters().getFirst().isModified());

        MethodInfo write = member(OutputStream.class, "java.io.OutputStream.write(byte[])");
        assertTrue(write.isModifying());
        assertFalse(write.parameters().getFirst().isModified());

        assertFalse(member(IOException.class, "java.io.IOException.<init>(Throwable)").parameters().getFirst().isModified());
        assertFalse(member(RuntimeException.class, "java.lang.RuntimeException.<init>(String,Throwable)").parameters().get(1).isModified());
    }

    private static MethodInfo member(Class<?> clazz, String fqn) {
        TypeInfo typeInfo = compiledTypesManager().typeIfLoaded(clazz);
        return typeInfo.constructorAndMethodStream().filter(m -> fqn.equals(m.fullyQualifiedName())).findFirst()
                .orElseThrow(() -> new AssertionError("no " + fqn));
    }
    @Test
    public void testSerializable() {
        TypeInfo typeInfo = compiledTypesManager().typeIfLoaded(Serializable.class);
        testImmutableContainer(typeInfo, true);
    }

    @Test
    public void testPrintStream() {
        TypeInfo typeInfo = compiledTypesManager().typeIfLoaded(PrintStream.class);
        assertSame(MUTABLE, typeInfo.analysis().getOrDefault(IMMUTABLE_TYPE, MUTABLE));
        assertSame(DEPENDENT, typeInfo.analysis().getOrDefault(INDEPENDENT_TYPE, DEPENDENT));
        assertSame(FALSE, typeInfo.analysis().getOrDefault(CONTAINER_TYPE, FALSE));
    }

    @Test
    public void testPrintStreamPrintInt() {
        TypeInfo typeInfo = compiledTypesManager().typeIfLoaded(PrintStream.class);
        TypeInfo intTypeInfo = runtime().intTypeInfo();
        MethodInfo methodInfo = typeInfo.findUniqueMethod("print", intTypeInfo);
        assertTrue(methodInfo.allowsInterrupts());

        assertTrue(methodInfo.isModifying());
        assertSame(INDEPENDENT, methodInfo.analysis().getOrDefault(INDEPENDENT_METHOD, DEPENDENT));

        ParameterInfo p0 = methodInfo.parameters().getFirst();
        assertSame(INDEPENDENT, p0.analysis().getOrDefault(INDEPENDENT_PARAMETER, DEPENDENT));
        assertEquals("N", p0.analysis().getOrDefault(NULLABILITY_PARAMETER, UNSPECIFIED).toString());
        assertSame(TRUE, p0.analysis().getOrDefault(UNMODIFIED_PARAMETER, FALSE));
    }

    @Test
    public void testPrintStreamPrintlnObject() {
        TypeInfo typeInfo = compiledTypesManager().typeIfLoaded(PrintStream.class);
        TypeInfo objectTypeInfo = runtime().objectTypeInfo();
        MethodInfo methodInfo = typeInfo.findUniqueMethod("println", objectTypeInfo);
        assertTrue(methodInfo.allowsInterrupts());

        assertTrue(methodInfo.isPublic());
        assertTrue(methodInfo.isPubliclyAccessible());
        assertFalse(methodInfo.isStatic());
        assertFalse(methodInfo.isDefault());
        assertFalse(methodInfo.isAbstract());

        assertTrue(methodInfo.isModifying());
        assertSame(INDEPENDENT, methodInfo.analysis().getOrDefault(INDEPENDENT_METHOD, DEPENDENT));

        ParameterInfo p0 = methodInfo.parameters().getFirst();
        assertSame(INDEPENDENT, p0.analysis().getOrDefault(INDEPENDENT_PARAMETER, DEPENDENT));
        assertEquals("U", p0.analysis().getOrDefault(NULLABILITY_PARAMETER, UNSPECIFIED).toString());
        assertSame(TRUE, p0.analysis().getOrDefault(UNMODIFIED_PARAMETER, FALSE));
    }

    @Test
    public void testWriterAppendChar() {
        TypeInfo typeInfo = compiledTypesManager().typeIfLoaded(Writer.class);
        TypeInfo charTypeInfo = runtime().charTypeInfo();
        MethodInfo methodInfo = typeInfo.findUniqueMethod("append", charTypeInfo);
        assertEquals("java.io.Writer.append(char)", methodInfo.fullyQualifiedName());
        assertTrue(methodInfo.allowsInterrupts());
        assertEquals(1, methodInfo.exceptionTypes().size());
        assertEquals("java.io.IOException", methodInfo.exceptionTypes().getFirst().fullyQualifiedName());
    }

    @Test
    public void testFilterOutputStream() {
        TypeInfo typeInfo = compiledTypesManager().typeIfLoaded(FilterOutputStream.class);
        assertSame(MUTABLE, typeInfo.analysis().getOrDefault(IMMUTABLE_TYPE, MUTABLE));
        assertSame(DEPENDENT, typeInfo.analysis().getOrDefault(INDEPENDENT_TYPE, DEPENDENT));
        assertSame(FALSE, typeInfo.analysis().getOrDefault(CONTAINER_TYPE, FALSE));
    }

    @Test
    public void testFilterOutputStreamConstructor() {
        TypeInfo typeInfo = compiledTypesManager().typeIfLoaded(FilterOutputStream.class);
        MethodInfo methodInfo = typeInfo.findConstructor(1);
        assertTrue(methodInfo.isConstructor());
        assertFalse(methodInfo.isCompactConstructor());
        assertFalse(methodInfo.allowsInterrupts());

        ParameterInfo p0 = methodInfo.parameters().getFirst();
        assertSame(DEPENDENT, p0.analysis().getOrDefault(INDEPENDENT_PARAMETER, DEPENDENT));
        assertEquals("N", p0.analysis().getOrDefault(NULLABILITY_PARAMETER, UNSPECIFIED).toString());
        assertSame(FALSE, p0.analysis().getOrDefault(UNMODIFIED_PARAMETER, FALSE));
    }

    @Test
    public void testByteArrayOutputStreamToByteArray() {
        TypeInfo typeInfo = compiledTypesManager().typeIfLoaded(ByteArrayOutputStream.class);
        MethodInfo methodInfo = typeInfo.findUniqueMethod("toByteArray", 0);
        assertFalse(methodInfo.allowsInterrupts());

        assertFalse(methodInfo.isModifying());
        assertSame(INDEPENDENT, methodInfo.analysis().getOrDefault(INDEPENDENT_METHOD, DEPENDENT));
        assertSame(FINAL_FIELDS, methodInfo.analysis().getOrDefault(IMMUTABLE_METHOD, MUTABLE));
    }


    @Test
    public void testWriterWriteCharArray() {
        TypeInfo typeInfo = compiledTypesManager().typeIfLoaded(Writer.class);
        MethodInfo methodInfo = typeInfo.methodStream()
                .filter(mi -> "write".equals(mi.name())
                              && mi.parameters().size() == 1
                              && mi.parameters().getFirst().parameterizedType().arrays() > 0)
                .findFirst().orElseThrow();
        assertEquals("java.io.Writer.write(char[])", methodInfo.fullyQualifiedName());

        assertTrue(methodInfo.allowsInterrupts());
        assertTrue(methodInfo.isModifying());

        ParameterInfo p0 = methodInfo.parameters().getFirst();
        assertSame(INDEPENDENT, p0.analysis().getOrDefault(INDEPENDENT_PARAMETER, DEPENDENT));
        assertEquals("N", p0.analysis().getOrDefault(NULLABILITY_PARAMETER, UNSPECIFIED).toString());
        assertSame(FALSE, p0.analysis().getOrDefault(UNMODIFIED_PARAMETER, FALSE));
    }

    @Test
    public void testDataInputStreamReadUTF() {
        TypeInfo typeInfo = compiledTypesManager().typeIfLoaded(DataInputStream.class);
        MethodInfo methodInfo = typeInfo.findUniqueMethod("readUTF", 0);

        assertTrue(methodInfo.isModifying());
    }

    @Test
    public void testInputStreamAvailable() {
        TypeInfo typeInfo = compiledTypesManager().typeIfLoaded(InputStream.class);
        MethodInfo methodInfo = typeInfo.findUniqueMethod("available", 0);

        assertFalse(methodInfo.isModifying());
    }

    // BufferedReader and BufferedWriter both wrap an external stream they are coupled to and modify,
    // so both are mutable, @Dependent and not a @Container (same as FilterOutputStream). BufferedReader
    // was previously (incorrectly) @Independent.
    @Test
    public void testBufferedReaderWriterAreDependent() {
        for (Class<?> c : new Class<?>[]{BufferedReader.class, BufferedWriter.class}) {
            TypeInfo typeInfo = compiledTypesManager().typeIfLoaded(c);
            assertSame(MUTABLE, typeInfo.analysis().getOrDefault(IMMUTABLE_TYPE, MUTABLE),
                    () -> c.getSimpleName() + " is mutable");
            assertSame(DEPENDENT, typeInfo.analysis().getOrDefault(INDEPENDENT_TYPE, DEPENDENT),
                    () -> c.getSimpleName() + " is @Dependent (wraps an external stream)");
            assertSame(FALSE, typeInfo.analysis().getOrDefault(CONTAINER_TYPE, FALSE),
                    () -> c.getSimpleName() + " is not a @Container");
        }

        // the wrapped Reader parameter is itself dependent (it is linked into the BufferedReader)
        TypeInfo br = compiledTypesManager().typeIfLoaded(BufferedReader.class);
        MethodInfo constructor = br.findConstructor(1);
        ParameterInfo in = constructor.parameters().getFirst();
        assertSame(DEPENDENT, in.analysis().getOrDefault(INDEPENDENT_PARAMETER, DEPENDENT));
    }
}
