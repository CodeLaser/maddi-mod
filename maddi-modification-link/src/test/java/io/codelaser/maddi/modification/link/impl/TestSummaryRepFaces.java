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

package io.codelaser.maddi.modification.link.impl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.Test;

import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.modification.link.CommonTest;
import io.codelaser.maddi.modification.link.LinkComputer;
import io.codelaser.maddi.modification.prepwork.PrepAnalyzer;
import io.codelaser.maddi.modification.prepwork.variable.MethodLinkedVariables;

/**
 * CodeLaser/maddi-mod#109: a return value's §m face toward a shared-variable group ('toByteArrayInternal.§m ≡
 * $__sv_buf.§m', guava's ByteStreams) reached the method summary, and the writer refused the whole summary.
 */
public class TestSummaryRepFaces extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import java.io.IOException;
            import java.io.InputStream;
            import java.util.Arrays;
            import java.util.Queue;
            class X {
                static byte[] toByteArrayInternal(InputStream in, Queue<byte[]> bufs, int totalLen) throws IOException {
                    for (int bufSize = 128; totalLen < 1000; bufSize = bufSize * 2) {
                        byte[] buf = new byte[Math.min(bufSize, 1000 - totalLen)];
                        bufs.add(buf);
                        int off = 0;
                        while (off < buf.length) {
                            int r = in.read(buf, off, buf.length - off);
                            if (r == -1) {
                                return combineBuffers(bufs, totalLen);
                            }
                            off += r;
                            totalLen += r;
                        }
                    }
                    if (in.read() == -1) {
                        return combineBuffers(bufs, 1000);
                    }
                    throw new OutOfMemoryError();
                }
                static byte[] combineBuffers(Queue<byte[]> bufs, int totalLen) {
                    if (bufs.isEmpty()) {
                        return new byte[0];
                    }
                    byte[] result = bufs.remove();
                    if (result.length == totalLen) {
                        return result;
                    }
                    int remaining = totalLen - result.length;
                    result = Arrays.copyOf(result, totalLen);
                    while (remaining > 0) {
                        byte[] buf = bufs.remove();
                        int bytesToCopy = Math.min(remaining, buf.length);
                        System.arraycopy(buf, 0, result, totalLen - remaining, bytesToCopy);
                        remaining -= bytesToCopy;
                    }
                    return result;
                }
            }
            """;

    @Test
    public void test() {
        TypeInfo X = javaInspector.parse("a.b.X", INPUT);
        new PrepAnalyzer(runtime, new PrepAnalyzer.Options.Builder().build()).doPrimaryType(X);
        LinkComputer tlc = new LinkComputerImpl(javaInspector, LinkComputer.Options.PRODUCTION);
        tlc.doPrimaryType(X);
        for (String name : new String[]{"toByteArrayInternal", "combineBuffers"}) {
            String s = summary(X, name);
            System.out.println(name + ": " + s);
            assertFalse(s.contains("$__sv_"), name + " summary carries a shared-variable rep: " + s);
        }
    }

    private static String summary(TypeInfo X, String name) {
        MethodInfo m = X.methods().stream().filter(mi -> mi.name().equals(name)).findFirst().orElseThrow();
        MethodLinkedVariables mlv = m.analysis().getOrNull(MethodLinkedVariablesImpl.METHOD_LINKS, MethodLinkedVariablesImpl.class);
        assertNotNull(mlv);
        return mlv.toString();
    }
}
