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

package io.codelaser.maddi.modification.prepwork.io;

/**
 * A single normalization applied to the serialised analysis dump before it is hashed into an
 * {@link AnalysisFingerprint}. Each normalizer widens the class of source edits the fingerprint is invariant to,
 * by erasing from the dump some detail that does not affect what a <em>dependent</em> observes:
 * <ul>
 *   <li>{@link SourcePositionNormalizer} — source line:col coordinates (a line-shifting edit must not dirty an
 *       otherwise-unchanged type);</li>
 *   <li>(later) variable-name blanking for rename-invariance; canonicalisation of orderings; etc.</li>
 * </ul>
 * <p>
 * Normalizers compose as an ordered pipeline (see {@link AnalysisFingerprint}); many are expected over time, so
 * this is deliberately a small, open interface. The substrate is the serialised dump ({@code String}) — the one
 * representation every normalizer can share; a structurally-aware normalizer may parse and re-serialise internally.
 * <p>
 * <b>Soundness contract:</b> a normalizer may only erase detail a dependent cannot read. Erasing more than that
 * would make two genuinely-different results hash equal and cut off a recomputation that was actually needed.
 * Erasing positions is sound (no analysis reads a callee's source coordinates); erasing a verdict would not be.
 */
public interface FingerprintNormalizer {

    /** A short, stable, kebab-case identifier (for logging and profile composition). */
    String name();

    /** Return {@code encoded} with this normalizer's target detail erased; must be deterministic and idempotent. */
    String normalize(String encoded);
}
