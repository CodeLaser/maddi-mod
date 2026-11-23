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

package io.codelaser.maddi.modification.common.defaults;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import io.codelaser.maddi.cst.api.element.Element;
import io.codelaser.maddi.cst.api.expression.AnnotationExpression;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.ParameterInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.impl.analysis.PropertyImpl;
import io.codelaser.maddi.cst.impl.analysis.ValueImpl;
import io.codelaser.maddi.inspection.api.integration.JavaInspector;
import io.codelaser.maddi.inspection.resource.SourceSetImpl;
import io.codelaser.maddi.modification.common.CommonTest;

/**
 * CodeLaser/maddi-mod#103: the link computer runs the shallow analyzer on a library method only when the method has
 * no DEFAULTS_ANALYZER yet, then reads its verdicts. The analyzer claimed the method (set DEFAULTS_ANALYZER) before it
 * wrote them, so a second thread reaching the same method in that window skipped the analysis and read the defaults:
 * modified. In TestIncrementalFernflower about one cold analysis in thirteen had String's methods read so, and String
 * parameters of fernflower methods modified (InvocationExprent's className). A reader of the claim must wait for the
 * verdicts.
 */
public class TestShallowMethodAnalyzerClaim {

    private static final String INPUT = """
            package a;
            import io.codelaser.maddi.annotation.*;
            public class X {
                public void m(@NotModified Object o) { }
            }
            """;

    @Test
    public void aReaderOfTheClaimSeesTheVerdicts() throws Exception {
        JavaInspector javaInspector = CommonTest.javaInspectorFactory()
                .withSources(SourceSetImpl.testProtocolSourceSet());
        TypeInfo x = javaInspector.parse(Map.of("a.X", INPUT), new JavaInspector.ParseOptions.Builder()
                .setFailFast(true).setIgnoreModule(true).build()).parseResult().findType("a.X");
        MethodInfo m = x.findUniqueMethod("m", 1);
        ParameterInfo o = m.parameters().getFirst();

        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean blocked = new AtomicBoolean();
        AnnotationProvider blocking = (Element e) -> {
            List<AnnotationExpression> annotations = e.annotations();
            if (e == m && blocked.compareAndSet(false, true)) {
                inside.countDown(); // the claim is taken, no verdict has been written
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
            return annotations;
        };
        ShallowMethodAnalyzer analyzer = new ShallowMethodAnalyzer(javaInspector.runtime(), blocking);

        Thread a = new Thread(() -> analyzer.analyze(m), "A");
        a.start();
        assertTrue(inside.await(5, TimeUnit.SECONDS));

        // what LinkComputerImpl.doMethodInScope does for a shallow method
        AtomicReference<Boolean> seen = new AtomicReference<>();
        Thread b = new Thread(() -> {
            if (!m.analysis().haveAnalyzedValueFor(PropertyImpl.DEFAULTS_ANALYZER)) analyzer.analyze(m);
            seen.set(o.analysis().getOrDefault(PropertyImpl.UNMODIFIED_PARAMETER, ValueImpl.BoolImpl.FALSE).isTrue());
        }, "B");
        b.start();
        b.join(300); // before the fix, B is done here, having read the default
        release.countDown();
        a.join(5000);
        b.join(5000);

        assertTrue(o.analysis().getOrDefault(PropertyImpl.UNMODIFIED_PARAMETER, ValueImpl.BoolImpl.FALSE).isTrue(),
                "the analysis itself writes the annotated verdict");
        assertEquals(Boolean.TRUE, seen.get(), "B read the parameter while A held the claim without the verdict");
    }
}
