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
 * An analysis-results file is not in the format this reader reads (CodeLaser/maddi-mod#3): it carries another
 * {@link WriteAnalysisResults#FORMAT_VERSION format version}, or its elements are not shaped as the format says.
 * Unchecked, so that it surfaces from every reading path; {@link LoadAnalysisResults#goDirTolerant} skips and
 * counts such a file, as it does any other unreadable one.
 */
public class AnalysisResultsFormatException extends RuntimeException {
    public AnalysisResultsFormatException(String message) {
        super(message);
    }

    public AnalysisResultsFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
