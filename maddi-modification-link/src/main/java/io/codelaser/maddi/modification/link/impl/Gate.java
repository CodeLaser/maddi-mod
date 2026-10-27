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

import java.util.Map;

/**
 * Cached environment gates. The process environment is immutable, but System.getenv(String) allocates on
 * every lookup (ProcessEnvironment$Variable) — the NOACM gate alone, evaluated per graph vertex, accounted
 * for 7% of a corpus run's allocations (async-profiler round 2). Copy the env once; gates are then
 * allocation-free map reads.
 */
public final class Gate {
    private Gate() {
    }

    private static final Map<String, String> ENV = Map.copyOf(System.getenv());

    public static boolean isSet(String name) {
        return ENV.get(name) != null;
    }

    public static String get(String name) {
        return ENV.get(name);
    }
}
