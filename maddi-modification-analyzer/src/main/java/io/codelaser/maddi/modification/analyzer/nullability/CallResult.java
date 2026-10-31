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

package io.codelaser.maddi.modification.analyzer.nullability;

import io.codelaser.maddi.cst.api.expression.MethodCall;

/** The result of a library call in between, a node of its own (a library view's receiver); by identity of the call. */
public record CallResult(MethodCall call) {
    @Override
    public boolean equals(Object o) {
        return o instanceof CallResult(MethodCall c) && c == call;
    }

    @Override
    public int hashCode() {
        return System.identityHashCode(call);
    }

    @Override
    public String toString() {
        return "result of " + call;
    }
}
