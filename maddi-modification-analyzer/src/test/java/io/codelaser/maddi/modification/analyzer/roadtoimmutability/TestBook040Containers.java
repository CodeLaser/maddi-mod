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

package io.codelaser.maddi.modification.analyzer.roadtoimmutability;

import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;

/**
 * The examples of road-to-immutability §040, "Containers".
 */
public class TestBook040Containers extends BookTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import io.codelaser.maddi.annotation.Container;
            import io.codelaser.maddi.annotation.NotModified;
            import java.nio.ByteBuffer;
            import java.util.*;
            public class X {
                public static class Pair<K, V> {
                    public final K k;
                    public final V v;
                    public Pair(K k, V v) {
                        this.k = k;
                        this.v = v;
                    }
                    public K getK() { return k; }
                    public V getV() { return v; }
                }
                static class ErrorMessage {
                    private String message;
                    public ErrorMessage(String message) { this.message = message; }
                    public String getMessage() { return message; }
                    public void setMessage(String message) { this.message = message; }
                }
                @Container
                interface ErrorRegistry {
                    @NotModified
                    List<ErrorMessage> getErrors();
                    void addError(@NotModified ErrorMessage errorMessage);
                }
                static abstract class Expression { }
                static class NullConstant extends Expression { }
                static class BinaryExpression extends Expression {
                    public final Expression lhs;
                    public final Expression rhs;
                    BinaryExpression(Expression lhs, Expression rhs) {
                        this.lhs = lhs;
                        this.rhs = rhs;
                    }
                    public void evaluate(ErrorRegistry errorRegistry) {
                        if (lhs instanceof NullConstant || rhs instanceof NullConstant) {
                            errorRegistry.addError(new ErrorMessage("null"));
                        }
                    }
                }
                @Container
                interface Array<T> {
                    @NotModified
                    int length();
                    @NotModified
                    T get(int index);
                    void set(int index, T t);
                }
                static class IntArray implements Array<Integer> {
                    private final ByteBuffer byteBuffer;
                    private final int size;
                    public IntArray(int size) {
                        this.size = size;
                        byteBuffer = ByteBuffer.wrap(new byte[size * Integer.BYTES]);
                    }
                    @Override
                    public int length() { return size; }
                    @Override
                    public Integer get(int index) { return byteBuffer.getInt(index * Integer.BYTES); }
                    @Override
                    public void set(int index, Integer i) { byteBuffer.putInt(index * Integer.BYTES, i); }
                }
                static class ErrorRegistry2 {
                    private final List<ErrorMessage> messages = new ArrayList<>();
                    public void add(ErrorMessage message) { messages.add(message); }
                    public void changeFirst() {
                        if (!messages.isEmpty()) {
                            messages.get(0).setMessage("changed!");
                        }
                    }
                }
            }
            """;

    @DisplayName("§040, Containers")
    @Test
    public void test() throws IOException {
        Map<String, String> actual = verdicts("a.b.X", INPUT);
        assertVerdicts(actual,
                "Pair = @Container",
                "Pair.getK() = @NotModified",
                "Pair.getV() = @NotModified",
                "ErrorMessage = @Container",
                "ErrorMessage.getMessage() = @NotModified",
                "ErrorMessage.setMessage() = @Modified",
                "BinaryExpression = !@Container",
                "BinaryExpression.evaluate(errorRegistry) = @Modified",
                "IntArray = @FinalFields @Container",
                // ErrorRegistry2 is the class ErrorRegistry of the chapter's last example
                // DEVIATION: the book says not a @Container, with 'message' @Modified: the modification of the element
                // in changeFirst reaches the field, but is not carried back to the parameter that was added to it
                "ErrorRegistry2 = @Container",
                "ErrorRegistry2.messages = @Modified",
                "ErrorRegistry2.add() = @Modified",
                "ErrorRegistry2.add(message) = @NotModified",
                "ErrorRegistry2.changeFirst() = @Modified");
    }
}
