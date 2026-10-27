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

package io.codelaser.maddi.modification.analyzer;

import io.codelaser.maddi.cst.api.info.Info;

import java.util.List;

/**
 * The guard: verifies user-written contracts against the analyzer's computed values, after the iterating
 * analyzer has reached its fixed point. Emits explanatory findings (ERROR level, category "contract-violation")
 * into the analyzer's message collector, never property values into {@code analysis()}: contracts remain
 * authoritative for downstream computation, and violating code is reported rather than re-classified.
 */
public interface GuardAnalyzer {

    void go(List<Info> analysisOrder);
}
