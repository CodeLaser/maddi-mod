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

import java.util.HashMap;
import java.util.Map;

// the locals in scope: a block, or a statement's own (for initializers, for-each variable, try resources)
record Scope(Scope parent, Map<String, Local> names) {
    Scope(Scope parent) {
        this(parent, new HashMap<>());
    }

    Local resolve(String name) {
        for (Scope s = this; s != null; s = s.parent) {
            Local local = s.names.get(name);
            if (local != null) return local;
        }
        return null;
    }
}
