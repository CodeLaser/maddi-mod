/*
 * maddi: a modification analyzer for duplication detection and immutability.
 * Copyright 2020-2025, Bart Naudts, https://github.com/CodeLaser/maddi
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Lesser General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE.  You should have received a copy of the GNU Lesser General Public
 * License along with this program.  If not, see <http://www.gnu.org/licenses/>.
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
