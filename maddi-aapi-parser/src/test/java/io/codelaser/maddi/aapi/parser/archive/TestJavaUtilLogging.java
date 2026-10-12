/*
 * maddi: a modification analyzer for duplication detection and immutability.
 * Copyright 2020-2025, Bart Naudts, https://github.com/CodeLaser/maddi
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Lesser General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License for
 * more details. You should have received a copy of the GNU Lesser General Public
 * License along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package io.codelaser.maddi.aapi.parser.archive;

import io.codelaser.maddi.aapi.parser.CommonTest;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import org.junit.jupiter.api.Test;

import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

public class TestJavaUtilLogging extends CommonTest {

    // jdk/JavaUtilLogging (2026-10-12): logging reads its arguments and does not modify the logger; configuring it does
    @Test
    public void testLogger() {
        TypeInfo logger = compiledTypesManager().typeIfLoaded(Logger.class);
        MethodInfo log = method(logger, "java.util.logging.Logger.log(java.util.logging.Level,String,Throwable)");
        assertFalse(log.isModifying());
        assertFalse(log.parameters().get(2).isModified());
        MethodInfo logArray = method(logger, "java.util.logging.Logger.log(java.util.logging.Level,String,Object[])");
        assertFalse(logArray.parameters().get(2).isModified());

        assertTrue(method(logger, "java.util.logging.Logger.setLevel(java.util.logging.Level)").isModifying());
        assertTrue(method(logger, "java.util.logging.Logger.log(java.util.logging.LogRecord)").parameters().getFirst().isModified());
    }

    private static MethodInfo method(TypeInfo typeInfo, String fqn) {
        return typeInfo.methodStream().filter(m -> fqn.equals(m.fullyQualifiedName())).findFirst()
                .orElseThrow(() -> new AssertionError("no " + fqn));
    }
}
