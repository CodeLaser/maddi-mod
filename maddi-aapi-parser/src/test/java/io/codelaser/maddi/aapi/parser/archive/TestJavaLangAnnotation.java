/*
 * maddi: a modification analyzer for duplication detection and immutability.
 * Copyright 2020-2026, CodeLaser BV, https://github.com/CodeLaser/maddi
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.codelaser.maddi.aapi.parser.archive;

import io.codelaser.maddi.aapi.parser.CommonTest;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.annotation.AnnotationTypeMismatchException;
import java.lang.annotation.ElementType;
import java.lang.annotation.IncompleteAnnotationException;
import java.lang.annotation.RetentionPolicy;

import static io.codelaser.maddi.cst.impl.analysis.PropertyImpl.CONTAINER_TYPE;
import static io.codelaser.maddi.cst.impl.analysis.PropertyImpl.IMMUTABLE_METHOD;
import static io.codelaser.maddi.cst.impl.analysis.PropertyImpl.IMMUTABLE_TYPE;
import static io.codelaser.maddi.cst.impl.analysis.PropertyImpl.INDEPENDENT_METHOD;
import static io.codelaser.maddi.cst.impl.analysis.PropertyImpl.INDEPENDENT_TYPE;
import static io.codelaser.maddi.cst.impl.analysis.ValueImpl.BoolImpl.FALSE;
import static io.codelaser.maddi.cst.impl.analysis.ValueImpl.ImmutableImpl.IMMUTABLE;
import static io.codelaser.maddi.cst.impl.analysis.ValueImpl.ImmutableImpl.IMMUTABLE_HC;
import static io.codelaser.maddi.cst.impl.analysis.ValueImpl.ImmutableImpl.MUTABLE;
import static io.codelaser.maddi.cst.impl.analysis.ValueImpl.IndependentImpl.DEPENDENT;
import static io.codelaser.maddi.cst.impl.analysis.ValueImpl.IndependentImpl.INDEPENDENT;
import static io.codelaser.maddi.cst.impl.analysis.ValueImpl.IndependentImpl.INDEPENDENT_HC;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestJavaLangAnnotation extends CommonTest {

    @Test
    public void testAnnotationAnnotationType() {
        TypeInfo typeInfo = compiledTypesManager().typeIfLoaded(Annotation.class);
        MethodInfo methodInfo = typeInfo.findUniqueMethod("annotationType", 0);
        assertFalse(methodInfo.isModifying());
        assertSame(INDEPENDENT, methodInfo.analysis().getOrDefault(INDEPENDENT_METHOD, INDEPENDENT));
        assertSame(IMMUTABLE, methodInfo.analysis().getOrDefault(IMMUTABLE_METHOD, IMMUTABLE_HC));
    }

    // Annotation is an interface (extensible by definition), so @ImmutableContainer(hc=true)
    // must land as IMMUTABLE_HC. Because it carries hidden content, its independence is the
    // HC variant (@Independent(hc=true)), not deep @Independent -- hence we assert directly
    // rather than via testImmutableContainer(), which expects deep independence.
    @Test
    public void testAnnotationIsImmutableHc() {
        TypeInfo typeInfo = compiledTypesManager().typeIfLoaded(Annotation.class);
        assertSame(IMMUTABLE_HC, typeInfo.analysis().getOrDefault(IMMUTABLE_TYPE, MUTABLE));
        assertSame(INDEPENDENT_HC, typeInfo.analysis().getOrDefault(INDEPENDENT_TYPE, DEPENDENT));
        assertTrue(typeInfo.analysis().getOrDefault(CONTAINER_TYPE, FALSE).isTrue());
    }

    // ElementType is a final enum with no type parameters: not extensible, no hidden content.
    // @ImmutableContainer must therefore land as deep IMMUTABLE (hc=false).
    @Test
    public void testElementTypeIsImmutable() {
        TypeInfo typeInfo = compiledTypesManager().typeIfLoaded(ElementType.class);
        testImmutableContainer(typeInfo, false);
        assertFalse(typeInfo.findUniqueMethod("values", 0).isModifying());
        assertFalse(typeInfo.findUniqueMethod("valueOf", 1).isModifying());
    }

    // RetentionPolicy: same reasoning as ElementType.
    @Test
    public void testRetentionPolicyIsImmutable() {
        TypeInfo typeInfo = compiledTypesManager().typeIfLoaded(RetentionPolicy.class);
        testImmutableContainer(typeInfo, false);
        assertFalse(typeInfo.findUniqueMethod("values", 0).isModifying());
        assertFalse(typeInfo.findUniqueMethod("valueOf", 1).isModifying());
    }

    // Throwable subclasses are left MUTABLE, so their accessors need an explicit @NotModified
    // to override the @Modified method default. Confirm the override reaches analysis().
    @Test
    public void testAnnotationTypeMismatchExceptionAccessors() {
        TypeInfo typeInfo = compiledTypesManager().typeIfLoaded(AnnotationTypeMismatchException.class);
        assertFalse(typeInfo.findUniqueMethod("element", 0).isModifying());
        assertFalse(typeInfo.findUniqueMethod("foundType", 0).isModifying());
    }

    @Test
    public void testIncompleteAnnotationExceptionAccessors() {
        TypeInfo typeInfo = compiledTypesManager().typeIfLoaded(IncompleteAnnotationException.class);
        assertFalse(typeInfo.findUniqueMethod("annotationType", 0).isModifying());
        assertFalse(typeInfo.findUniqueMethod("elementName", 0).isModifying());
    }

}
