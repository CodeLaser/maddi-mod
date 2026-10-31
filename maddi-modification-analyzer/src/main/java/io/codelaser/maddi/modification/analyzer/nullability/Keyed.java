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
 * The values a keyed map field holds under one constant key: nacos's {@code MapperContext.whereParamMap}, a
 * {@code Map<String, Object>} written and read through {@code putWhereParameter(KEY, value)} and
 * {@code getWhereParameter(KEY)}, where a null {@code dataId} put under one key made every key's value nullable.
 * The key is the constant's value ({@link NullabilityPass#keyOf}); a null key stands for the values put under a key
 * not known, which every keyed node receives. A keyed node flows into the field's value slot, which keeps every value.
 */
public record Keyed(Arg slot, Object key) {
    @Override
    public String toString() {
        return Report.label(this);
    }
}
