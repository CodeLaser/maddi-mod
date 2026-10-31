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

/**
 * Whether the analysed invocations are all the invocations. A parameter's verdict comes from the null that
 * reaches it from the calls the pass sees; code outside the analysis (a library's users; the JDK calling back an
 * override) may pass null where no analysed call does.
 */
public enum World {
    /** An application analysed with all its callers: a parameter no analysed call passes null to is non-null. */
    CLOSED,
    /**
     * A library: a parameter of a method callable from outside (public, or protected in an extensible type, of a
     * type reachable from outside; or overriding such a method, or a library method that does not declare its
     * parameter non-null) that no null reaches is UNSPECIFIED, and so is every declaration that parameter flows
     * into. For measurement: {@link #OPEN} is the useful form.
     */
    OPEN_VISIBILITY,
    /**
     * {@link #OPEN_VISIBILITY}, except that a parameter the body makes non-null on every normal exit (dereferenced,
     * passed to a non-null parameter, rejected by {@code if (p == null) throw}) is non-null: a precondition,
     * whoever the caller (docs/design/nullability.md §8).
     */
    OPEN
}
