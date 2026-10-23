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

package io.codelaser.maddi.modification.analyzer.modification;

import io.codelaser.maddi.cst.api.info.FieldInfo;
import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.impl.analysis.PropertyImpl;
import io.codelaser.maddi.cst.impl.analysis.ValueImpl;
import io.codelaser.maddi.modification.analyzer.CommonTest;
import io.codelaser.maddi.modification.analyzer.impl.IteratingAnalyzerImpl;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A logger is a {@code @Container} whose log methods modify, but carry {@code @IgnoreModifications}: logging neither
 * makes the logger (nor the field holding it) modified, nor modifies its arguments. Configuring the logger
 * ({@code setLevel}) is a real modification. slf4j ({@code libs/log/OrgSlf4j}), java.util.logging and System.Logger.
 */
public class TestLoggerModification extends CommonTest {

    public TestLoggerModification() {
        super("jmod:java.logging"); // the lean default class path has no java.util.logging: its hints would not load
    }

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import java.util.List;
            import java.util.logging.Level;
            import org.slf4j.Logger;
            import org.slf4j.LoggerFactory;
            class X {
                private static final Logger LOGGER = LoggerFactory.getLogger(X.class);
                private final java.util.logging.Logger jul = java.util.logging.Logger.getLogger("x");
                private final System.Logger sys = System.getLogger("x");
                private final List<String> list;
                X(List<String> list) { this.list = list; }
                String get(int i) {
                    String s = list.get(i);
                    LOGGER.debug("get {} -> {}", i, s);
                    return s;
                }
                void report(Object o, Throwable t) { LOGGER.error("failed on {}", o, t); }
                boolean on() { return LOGGER.isDebugEnabled(); }
                void julLog(Object o, Throwable t) { jul.log(Level.WARNING, "failed on " + o, t); }
                void sysLog(Object o) { sys.log(System.Logger.Level.INFO, "x {0}", o); }
                void quiet() { jul.setLevel(Level.OFF); }
            }
            """;

    private static MethodInfo method(TypeInfo typeInfo, String name) {
        return typeInfo.methods().stream().filter(m -> m.name().equals(name)).findFirst().orElseThrow();
    }

    private static FieldInfo field(TypeInfo typeInfo, String name) {
        return typeInfo.fields().stream().filter(f -> f.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    public void test() {
        TypeInfo X = javaInspector.parse("a.b.X", INPUT);
        List<Info> ao = prepWork(X);
        new IteratingAnalyzerImpl(javaInspector, new IteratingAnalyzerImpl.ConfigurationBuilder()
                .setMaxIterations(10)
                .setModificationViaReachability(true)
                .build()).analyze(ao);
        StringBuilder sb = new StringBuilder();
        for (String name : List.of("get", "report", "on", "julLog", "sysLog", "quiet")) {
            MethodInfo m = method(X, name);
            sb.append(name).append('=').append(m.isNonModifying());
            m.parameters().forEach(p -> sb.append(' ').append(p.name()).append('=').append(p.isUnmodified()));
            sb.append('\n');
        }
        for (String name : List.of("LOGGER", "jul", "sys", "list")) {
            sb.append(name).append('=').append(field(X, name).isUnmodified()).append('\n');
        }
        assertEquals("""
                get=true i=true
                report=true o=true t=true
                on=true
                julLog=true o=true t=true
                sysLog=true o=true
                quiet=false
                LOGGER=true
                jul=false
                sys=true
                list=true
                """, sb.toString());

        // the hints themselves: a log call modifies, and the modification is ignored; a query does not modify;
        // the logger is a container, not immutable
        StringBuilder hints = new StringBuilder();
        for (String fqn : List.of("org.slf4j.Logger", "java.util.logging.Logger", "java.lang.System")) {
            TypeInfo t = javaInspector.compiledTypesManager().get(fqn, null);
            TypeInfo logger = fqn.equals("java.lang.System") ? t.findSubType("Logger") : t;
            hints.append(logger.simpleName()).append(" immutable=")
                    .append(logger.analysis().getOrNull(PropertyImpl.IMMUTABLE_TYPE, ValueImpl.ImmutableImpl.class))
                    .append(" container=").append(logger.analysis().getOrDefault(PropertyImpl.CONTAINER_TYPE,
                            ValueImpl.BoolImpl.FALSE).isTrue()).append('\n');
            logger.methods().stream()
                    .filter(m -> List.of("info", "log", "isLoggable", "isInfoEnabled", "setLevel").contains(m.name())
                                 && m.parameters().size() <= 2
                                 && m.parameters().stream().allMatch(p -> !p.parameterizedType().isFunctionalInterface()))
                    .sorted(java.util.Comparator.comparing(MethodInfo::fullyQualifiedName))
                    .forEach(m -> hints.append("  ").append(m.name()).append('/').append(m.parameters().size())
                            .append(" nonModifying=").append(m.isNonModifying())
                            .append(" ignore=").append(m.isIgnoreModification()).append('\n'));
        }
        assertEquals("""
                Logger immutable=null container=true
                  info/1 nonModifying=false ignore=true
                  info/2 nonModifying=false ignore=true
                  info/2 nonModifying=false ignore=true
                  info/2 nonModifying=false ignore=true
                  info/2 nonModifying=false ignore=true
                  isInfoEnabled/0 nonModifying=true ignore=false
                  isInfoEnabled/1 nonModifying=true ignore=false
                Logger immutable=null container=true
                  info/1 nonModifying=false ignore=true
                  isLoggable/1 nonModifying=true ignore=false
                  log/2 nonModifying=false ignore=true
                  log/1 nonModifying=false ignore=true
                  setLevel/1 nonModifying=false ignore=false
                Logger immutable=null container=true
                  isLoggable/1 nonModifying=true ignore=false
                  log/2 nonModifying=false ignore=true
                  log/2 nonModifying=false ignore=true
                """, hints.toString());
    }
}
