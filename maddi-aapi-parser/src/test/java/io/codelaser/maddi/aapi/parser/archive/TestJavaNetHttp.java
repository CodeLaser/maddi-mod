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
import io.codelaser.maddi.cst.api.analysis.Value;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.impl.analysis.ValueImpl;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static io.codelaser.maddi.cst.impl.analysis.PropertyImpl.*;
import static io.codelaser.maddi.cst.impl.analysis.ValueImpl.BoolImpl.FALSE;
import static io.codelaser.maddi.cst.impl.analysis.ValueImpl.BoolImpl.TRUE;
import static io.codelaser.maddi.cst.impl.analysis.ValueImpl.GetSetEquivalentImpl.EMPTY;
import static io.codelaser.maddi.cst.impl.analysis.ValueImpl.ImmutableImpl.MUTABLE;
import static io.codelaser.maddi.cst.impl.analysis.ValueImpl.IndependentImpl.DEPENDENT;
import static io.codelaser.maddi.cst.impl.analysis.ValueImpl.IndependentImpl.INDEPENDENT;
import static org.junit.jupiter.api.Assertions.*;

public class TestJavaNetHttp extends CommonTest {

    @Test
    public void testHttpRequestNewBuilder() {
        TypeInfo typeInfo = compiledTypesManager().typeIfLoaded(HttpRequest.class);
        MethodInfo methodInfo = typeInfo.findUniqueMethod("newBuilder", 0);
        assertFalse(methodInfo.allowsInterrupts());
        assertFalse(methodInfo.isModifying());
        assertSame(INDEPENDENT, methodInfo.analysis().getOrDefault(INDEPENDENT_METHOD, DEPENDENT));
        assertSame(MUTABLE, methodInfo.analysis().getOrDefault(IMMUTABLE_METHOD, MUTABLE));
    }

    @Test
    public void testHttpRequestNewBuilderUri() {
        TypeInfo uri = compiledTypesManager().typeIfLoaded(URI.class);
        TypeInfo typeInfo = compiledTypesManager().typeIfLoaded(HttpRequest.class);
        MethodInfo methodInfo = typeInfo.findUniqueMethod("newBuilder", uri);

        assertFalse(methodInfo.allowsInterrupts());
        assertFalse(methodInfo.isModifying());
        assertSame(INDEPENDENT, methodInfo.analysis().getOrDefault(INDEPENDENT_METHOD, DEPENDENT));
        assertSame(MUTABLE, methodInfo.analysis().getOrDefault(IMMUTABLE_METHOD, MUTABLE));

        MethodInfo zeroArgs = typeInfo.findUniqueMethod("newBuilder", 0);
        Value.GetSetEquivalent gse = methodInfo.analysis().getOrDefault(GET_SET_EQUIVALENT, EMPTY);
        assertSame(zeroArgs, gse.methodWithoutParameters());
    }

    @Test
    public void testHttpRequestBuilderGET() {
        TypeInfo typeInfo = compiledTypesManager().typeIfLoaded(HttpRequest.class).findSubType("Builder");
        MethodInfo methodInfo = typeInfo.findUniqueMethod("GET", 0);
        testFluent(methodInfo);
        testCommutable(methodInfo);
    }

    @Test
    public void testHttpRequestBuilderUri() {
        TypeInfo typeInfo = compiledTypesManager().typeIfLoaded(HttpRequest.class).findSubType("Builder");
        MethodInfo methodInfo = typeInfo.findUniqueMethod("uri", 1);
        testFluent(methodInfo);
        testCommutable(methodInfo);
    }

    @Test
    public void testHttpRequestBuilderTimeout() {
        TypeInfo typeInfo = compiledTypesManager().typeIfLoaded(HttpRequest.class).findSubType("Builder");
        MethodInfo methodInfo = typeInfo.findUniqueMethod("timeout", 1);
        testFluent(methodInfo);
        testCommutable(methodInfo);
    }

    private static void testFluent(MethodInfo methodInfo) {
        assertFalse(methodInfo.allowsInterrupts());
        assertTrue(methodInfo.isModifying());
        assertSame(TRUE, methodInfo.analysis().getOrDefault(FLUENT_METHOD, FALSE));
        assertSame(DEPENDENT, methodInfo.analysis().getOrDefault(INDEPENDENT_METHOD, DEPENDENT));
        assertSame(MUTABLE, methodInfo.analysis().getOrDefault(IMMUTABLE_METHOD, MUTABLE));
    }

    // HttpResponse<T> is an immutable read-only view of a response -> @Immutable(hc=true) + @Container.
    @Test
    public void testHttpResponseImmutableContainer() {
        TypeInfo typeInfo = compiledTypesManager().typeIfLoaded(HttpResponse.class);
        Value.Immutable imm = (Value.Immutable) typeInfo.analysis().getOrDefault(IMMUTABLE_TYPE, MUTABLE);
        assertTrue(imm.isAtLeastImmutableHC());
        assertSame(TRUE, typeInfo.analysis().getOrDefault(CONTAINER_TYPE, FALSE));
    }

    private void testCommutable(MethodInfo methodInfo) {
        Value.CommutableData cd = methodInfo.analysis().getOrNull(COMMUTABLE_METHODS, ValueImpl.CommutableData.class);
        assertTrue(cd.isBlankMultiParSeq());
    }
}
