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
import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.ParameterInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import org.junit.jupiter.api.Test;

import java.security.MessageDigest;
import java.security.Principal;
import java.security.SecureRandom;
import java.util.stream.Collectors;

import static io.codelaser.maddi.cst.impl.analysis.PropertyImpl.CONTAINER_TYPE;
import static io.codelaser.maddi.cst.impl.analysis.ValueImpl.BoolImpl.FALSE;
import static io.codelaser.maddi.cst.impl.analysis.ValueImpl.BoolImpl.TRUE;
import static org.junit.jupiter.api.Assertions.*;

public class TestJavaSecurity extends CommonTest {

    @Test
    public void testSecureRandomNextBytes() {
        TypeInfo typeInfo = compiledTypesManager().typeIfLoaded(SecureRandom.class);
        MethodInfo methodInfo = typeInfo.findUniqueMethod("nextBytes", 1);
        assertFalse(methodInfo.allowsInterrupts());
        assertTrue(methodInfo.isModifying());
        ParameterInfo p0 = methodInfo.parameters().getFirst();
        assertFalse(p0.isUnmodified());
    }

    @Test
    public void testMessageDigestUpdate() {
        TypeInfo typeInfo = compiledTypesManager().typeIfLoaded(MessageDigest.class);
        assertEquals("""
                java.security.MessageDigest.update(byte), java.security.MessageDigest.update(byte[]), \
                java.security.MessageDigest.update(byte[],int,int), java.security.MessageDigest.update(java.nio.ByteBuffer)\
                """, typeInfo.methods().stream().filter(m -> "update".equals(m.name()))
                .map(Info::fullyQualifiedName)
                .sorted()
                .collect(Collectors.joining(", ")));
        MethodInfo methodInfo = typeInfo.findUniqueMethod("update", 3);
        assertFalse(methodInfo.allowsInterrupts());
        assertTrue(methodInfo.isModifying());
        ParameterInfo p0 = methodInfo.parameters().getFirst();
        assertFalse(p0.isModified());
    }

    @Test
    public void testMessageDigestDigest() {
        TypeInfo typeInfo = compiledTypesManager().typeIfLoaded(MessageDigest.class);
        MethodInfo methodInfo = typeInfo.findUniqueMethod("digest", 3);
        assertFalse(methodInfo.allowsInterrupts());
        assertTrue(methodInfo.isModifying());
        ParameterInfo p0 = methodInfo.parameters().getFirst();
        assertFalse(p0.isModified());
    }

    @Test
    public void testMessageDigestGetDigestLength() {
        TypeInfo typeInfo = compiledTypesManager().typeIfLoaded(MessageDigest.class);
        MethodInfo methodInfo = typeInfo.findUniqueMethod("getDigestLength", 0);
        assertFalse(methodInfo.allowsInterrupts());
        assertFalse(methodInfo.isModifying());
    }

    // Principal is a read-only identity interface: getName()/implies(Subject) never modify a
    // parameter, so it is a @Container.
    @Test
    public void testPrincipalContainer() {
        TypeInfo typeInfo = compiledTypesManager().typeIfLoaded(Principal.class);
        assertSame(TRUE, typeInfo.analysis().getOrDefault(CONTAINER_TYPE, FALSE));
    }
}
