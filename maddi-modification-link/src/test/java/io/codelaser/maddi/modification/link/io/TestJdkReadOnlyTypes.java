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

package io.codelaser.maddi.modification.link.io;

import io.codelaser.maddi.cst.api.analysis.Value;
import io.codelaser.maddi.cst.api.element.SourceSet;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.ParameterInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.api.type.ParameterizedType;
import io.codelaser.maddi.cst.impl.analysis.ValueImpl;
import io.codelaser.maddi.inspection.api.integration.JavaInspector;
import io.codelaser.maddi.inspection.resource.SourceSetImpl;
import io.codelaser.maddi.modification.common.CommonTest;
import io.codelaser.maddi.modification.prepwork.io.LoadAnalysisResults;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.ReadOnlyBufferException;
import java.nio.ShortBuffer;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.codelaser.maddi.cst.impl.analysis.PropertyImpl.IMMUTABLE_TYPE;
import static io.codelaser.maddi.modification.prepwork.io.LoadAnalysisResults.ANALYZED_RESULTS_JDK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The JDK hints of two families of types the JDK itself marks as unchangeable, the step after
 * {@link TestJdkCollectionMutators}.
 * <ul>
 *     <li>The value-based classes, those annotated {@code jdk.internal.ValueBased} ({@code Integer},
 *     {@code java.time}, {@code Optional}, ...). The JDK promises they are "final and immutable (though may contain
 *     references to mutable objects)": the type is at least IMMUTABLE_HC, and no method modifies the receiver. The
 *     classes are found by scanning the JDK's exported packages, so a class the JDK adds is checked without editing
 *     this test. The promise says nothing about the parameters.</li>
 *     <li>The {@code java.nio} buffers: a read-only buffer throws {@link ReadOnlyBufferException} on exactly the
 *     methods that change its content. As for an iterator, the relative {@code get()} moves the buffer's position,
 *     which maddi counts as modifying, so the check goes one way: what throws must be modifying.
 *     {@code array()} and {@code arrayOffset()} throw too, but they hand out the backing array rather than change
 *     anything; they are left out. There is no {@code java.nio} hint file yet, so today every buffer method is
 *     modifying by default and this half passes trivially; it keeps a future hint honest.</li>
 * </ul>
 * The first run (2026-10-26) found 14 value-based classes without a hint of their own, and so mutable with every
 * method modifying: {@code OffsetDateTime}, {@code OffsetTime}, {@code Period}, {@code Year}, {@code YearMonth},
 * {@code MonthDay}, {@code ZoneOffset}, the four {@code java.time.chrono} dates, {@code HexFormat},
 * {@code OptionalLong} and {@code Runtime.Version}; fixed in the maddi hints of the same day.
 * The verdicts checked are the ones the engine uses after loading the JDK hints as the Java → Kotlin runs do.
 */
public class TestJdkReadOnlyTypes {

    private static final String VALUE_BASED = "jdk.internal.ValueBased";

    private static final Map<Class<?>, Supplier<Buffer>> READ_ONLY_BUFFERS = Map.of(
            ByteBuffer.class, () -> ByteBuffer.wrap(new byte[]{1, 2, 3, 4, 5, 6, 7, 8}).asReadOnlyBuffer(),
            CharBuffer.class, () -> CharBuffer.wrap(new char[]{'a', 'b', 'c', 'd'}).asReadOnlyBuffer(),
            ShortBuffer.class, () -> ShortBuffer.wrap(new short[]{1, 2, 3, 4}).asReadOnlyBuffer(),
            IntBuffer.class, () -> IntBuffer.wrap(new int[]{1, 2, 3, 4}).asReadOnlyBuffer(),
            LongBuffer.class, () -> LongBuffer.wrap(new long[]{1, 2, 3, 4}).asReadOnlyBuffer(),
            FloatBuffer.class, () -> FloatBuffer.wrap(new float[]{1, 2, 3, 4}).asReadOnlyBuffer(),
            DoubleBuffer.class, () -> DoubleBuffer.wrap(new double[]{1, 2, 3, 4}).asReadOnlyBuffer());

    private static final Set<String> HANDS_OUT_THE_ARRAY = Set.of("array", "arrayOffset");

    private static JavaInspector javaInspector;

    @BeforeAll
    public static void beforeAll() throws IOException {
        SourceSet sourceSet = SourceSetImpl.testProtocolSourceSet();
        javaInspector = CommonTest.javaInspectorFactory().withSources(sourceSet);
        javaInspector.onlyPreload();
        // the preload leaves out java.nio and some of java.time.chrono; load them before the hints, so that a type
        // without a hint file is judged by the defaults the hints loader gives it, as in a run that parses them
        valueBasedClasses().forEach(c -> javaInspector.compiledTypesManager().type(c));
        READ_ONLY_BUFFERS.keySet().forEach(c -> javaInspector.compiledTypesManager().type(c));
        new LoadAnalysisResults(javaInspector.runtime(), sourceSet).go(List.of(ANALYZED_RESULTS_JDK));
    }

    @Test
    public void valueBased() throws IOException {
        List<Class<?>> classes = valueBasedClasses();
        List<String> wrong = new ArrayList<>();
        List<String> notFound = new ArrayList<>();
        int checked = 0;
        for (Class<?> c : classes) {
            TypeInfo typeInfo = typeInfo(c);
            if (typeInfo == null) {
                notFound.add(c.getName());
                continue;
            }
            Value.Immutable immutable = typeInfo.analysis().getOrDefault(IMMUTABLE_TYPE,
                    ValueImpl.ImmutableImpl.MUTABLE);
            if (!immutable.isAtLeastImmutableHC()) {
                wrong.add(c.getName() + ": the JDK says immutable, the hints " + immutable);
            }
            Method[] methods = c.getDeclaredMethods();
            Arrays.sort(methods, Comparator.comparing(Method::toString));
            for (Method m : methods) {
                if (!Modifier.isPublic(m.getModifiers()) || Modifier.isStatic(m.getModifiers()) || m.isBridge()
                    || m.isSynthetic()) continue;
                MethodInfo methodInfo = find(typeInfo, m);
                String where = c.getName() + "." + signature(m);
                if (methodInfo == null) {
                    notFound.add(where);
                    continue;
                }
                checked++;
                if (methodInfo.isModifying()) wrong.add(where + ": the JDK says immutable, the hints modifying");
            }
        }
        assertEquals(List.of(), notFound, "types and methods the CST does not have");
        assertTrue(classes.size() > 25, "only " + classes.size() + " value-based classes");
        assertTrue(checked > 500, "checked only " + checked);
        assertEquals("", String.join("\n", wrong));
    }

    @Test
    public void readOnlyBuffers() {
        List<String> wrong = new ArrayList<>();
        List<String> notFound = new ArrayList<>();
        int mutators = 0;
        for (Map.Entry<Class<?>, Supplier<Buffer>> e : READ_ONLY_BUFFERS.entrySet()) {
            Class<?> c = e.getKey();
            Set<String> seen = new HashSet<>();
            Method[] methods = c.getMethods();
            Arrays.sort(methods, Comparator.comparing(Method::toString));
            for (Method m : methods) {
                if (Modifier.isStatic(m.getModifiers()) || m.getDeclaringClass() == Object.class
                    || HANDS_OUT_THE_ARRAY.contains(m.getName())) continue;
                String signature = signature(m);
                if (!seen.add(signature)) continue;
                if (!throwsReadOnly(e.getValue(), m)) continue;
                mutators++;
                String where = c.getSimpleName() + "." + signature;
                TypeInfo typeInfo = typeInfo(m.getDeclaringClass());
                MethodInfo methodInfo = typeInfo == null ? null : find(typeInfo, m);
                if (methodInfo == null) {
                    notFound.add(where + " (declared in " + m.getDeclaringClass().getSimpleName() + ")");
                } else if (!methodInfo.isModifying()) {
                    wrong.add(where + ": the JDK says mutator, the hints non-modifying");
                }
            }
        }
        assertEquals(List.of(), notFound, "methods the CST does not have");
        assertTrue(mutators > 40, "only " + mutators + " mutators");
        assertEquals("", String.join("\n", wrong));
    }

    // ---- the value-based classes

    /** The public classes of the JDK's exported packages that carry {@code jdk.internal.ValueBased}. */
    private static List<Class<?>> valueBasedClasses() throws IOException {
        FileSystem jrt = FileSystems.getFileSystem(URI.create("jrt:/"));
        Set<String> names = new TreeSet<>();
        for (Module module : ModuleLayer.boot().modules()) {
            Path root = jrt.getPath("modules", module.getName());
            if (!Files.isDirectory(root)) continue;
            for (String pkg : module.getPackages()) {
                if (!module.isExported(pkg)) continue;
                Path dir = root.resolve(pkg.replace('.', '/'));
                if (!Files.isDirectory(dir)) continue;
                try (Stream<Path> files = Files.list(dir)) {
                    for (Path file : files.toList()) {
                        String fileName = file.getFileName().toString();
                        // the annotation's descriptor in the constant pool: cheap, and confirmed by reflection below
                        if (fileName.endsWith(".class") && !fileName.equals("module-info.class")
                            && contains(Files.readAllBytes(file), "Ljdk/internal/ValueBased;")) {
                            names.add(pkg + "." + fileName.substring(0, fileName.length() - 6).replace('/', '.'));
                        }
                    }
                }
            }
        }
        List<Class<?>> classes = new ArrayList<>();
        for (String name : names) {
            Class<?> c;
            try {
                c = Class.forName(name, false, ClassLoader.getSystemClassLoader());
            } catch (ClassNotFoundException e) {
                throw new UnsupportedOperationException(e);
            }
            if (Modifier.isPublic(c.getModifiers()) && isPublicChain(c) && Arrays.stream(c.getDeclaredAnnotations())
                    .map(Annotation::annotationType).map(Class::getName).anyMatch(VALUE_BASED::equals)) {
                classes.add(c);
            }
        }
        return classes;
    }

    private static boolean isPublicChain(Class<?> c) {
        for (Class<?> e = c; e != null; e = e.getEnclosingClass()) {
            if (!Modifier.isPublic(e.getModifiers())) return false;
        }
        return true;
    }

    private static boolean contains(byte[] bytes, String text) {
        byte[] wanted = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        outer:
        for (int i = 0; i <= bytes.length - wanted.length; i++) {
            for (int j = 0; j < wanted.length; j++) {
                if (bytes[i + j] != wanted[j]) continue outer;
            }
            return true;
        }
        return false;
    }

    // ---- the buffers

    /** Calls {@code m} on a fresh read-only buffer with harmless arguments. */
    private static boolean throwsReadOnly(Supplier<Buffer> buffer, Method m) {
        Object[] args = Arrays.stream(m.getParameterTypes()).map(TestJdkReadOnlyTypes::argument).toArray();
        try {
            m.invoke(buffer.get(), args);
            return false;
        } catch (InvocationTargetException e) {
            return e.getCause() instanceof ReadOnlyBufferException;
        } catch (IllegalAccessException e) {
            throw new UnsupportedOperationException(e);
        }
    }

    // index 0, length 1; a source of the buffer's own kind; a value of the element's type
    private static Object argument(Class<?> type) {
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == short.class) return (short) 0;
        if (type == byte.class) return (byte) 0;
        if (type == char.class) return 'a';
        if (type == float.class) return 0f;
        if (type == double.class) return 0d;
        if (type == boolean.class) return false;
        if (type == byte[].class) return new byte[1];
        if (type == char[].class) return new char[1];
        if (type == short[].class) return new short[1];
        if (type == int[].class) return new int[1];
        if (type == long[].class) return new long[1];
        if (type == float[].class) return new float[1];
        if (type == double[].class) return new double[1];
        if (type == String.class || type == CharSequence.class) return "a";
        if (type == ByteBuffer.class) return ByteBuffer.allocate(1);
        if (type == CharBuffer.class) return CharBuffer.allocate(1);
        if (type == ShortBuffer.class) return ShortBuffer.allocate(1);
        if (type == IntBuffer.class) return IntBuffer.allocate(1);
        if (type == LongBuffer.class) return LongBuffer.allocate(1);
        if (type == FloatBuffer.class) return FloatBuffer.allocate(1);
        if (type == DoubleBuffer.class) return DoubleBuffer.allocate(1);
        if (type == java.nio.ByteOrder.class) return java.nio.ByteOrder.BIG_ENDIAN;
        return null;
    }

    // ---- finding the CST's members

    private static String signature(Method m) {
        return m.getName() + Arrays.stream(m.getParameterTypes()).map(Class::getTypeName)
                .collect(Collectors.joining(",", "(", ")"));
    }

    private static MethodInfo find(TypeInfo typeInfo, Method m) {
        List<String> wanted = Arrays.stream(m.getParameterTypes()).map(Class::getTypeName).toList();
        return typeInfo.methods().stream()
                .filter(mi -> mi.name().equals(m.getName()))
                .filter(mi -> mi.parameters().stream().map(ParameterInfo::parameterizedType)
                        .map(TestJdkReadOnlyTypes::erasure).toList().equals(wanted))
                .findFirst().orElse(null);
    }

    private static TypeInfo typeInfo(Class<?> clazz) {
        if (clazz.getEnclosingClass() == null) return javaInspector.compiledTypesManager().typeIfLoaded(clazz);
        TypeInfo enclosing = typeInfo(clazz.getEnclosingClass());
        return enclosing == null ? null : enclosing.findSubType(clazz.getSimpleName(), false);
    }

    // Class.getTypeName() writes a nested class with '$'; a type parameter erases to its first bound
    private static String erasure(ParameterizedType pt) {
        if (pt.isTypeParameter()) {
            List<ParameterizedType> bounds = pt.typeParameter().typeBounds();
            return (bounds.isEmpty() ? "java.lang.Object" : erasure(bounds.getFirst())) + "[]".repeat(pt.arrays());
        }
        return binaryName(pt.typeInfo()) + "[]".repeat(pt.arrays());
    }

    private static String binaryName(TypeInfo typeInfo) {
        if (typeInfo.compilationUnitOrEnclosingType().isLeft()) return typeInfo.fullyQualifiedName();
        return binaryName(typeInfo.compilationUnitOrEnclosingType().getRight()) + "$" + typeInfo.simpleName();
    }
}
