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

package io.codelaser.maddi.modification.analyzer.integration;

import static io.codelaser.maddi.modification.link.impl.MethodLinkedVariablesImpl.METHOD_LINKS;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.Test;

import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.modification.analyzer.CommonTest;
import io.codelaser.maddi.modification.link.impl.MethodLinkedVariablesImpl;

/**
 * CodeLaser/maddi-mod#126: jenkins' BuildStepCompatibilityLayerTest.testPerform uses only locals, yet was reported
 * modifying. The overloads of 'perform' call each other (a recursive group), and the test calls one of them on an
 * anonymous subclass. In jenkins, the callee's modified set carried a 'this' of an unrelated type (hudson.model.Run,
 * from deep in its callees), which MethodModification passed on unchanged and the shadow pass read as the test
 * instance. This reduction of the shape does not produce that foreign 'this' (it passes without the fix as well);
 * it guards the verdict. The fix is measured on TestJenkinsCore.
 */
public class TestOverloadGroupLocals extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import java.io.IOException;
            import static org.junit.jupiter.api.Assertions.assertThrows;
            import static org.junit.jupiter.api.Assertions.assertTrue;
            class X {
                static class Env { }
                static class Launcher { }
                interface BuildListener { }
                static class FilePath { }
                static class AbstractBuild<P, R> {
                    FilePath ws;
                    Env env = new Env();
                    FilePath getWorkspace() { return ws; }
                    Env getEnvironment(BuildListener l) { return env; }
                }
                static class Build<P, R> extends AbstractBuild<P, R> { }
                static class FreeStyleBuild extends Build<String, String> { }
                interface SimpleBuildStep {
                    void perform(AbstractBuild<?, ?> run, FilePath ws, Env env, Launcher l, BuildListener bl)
                            throws InterruptedException, IOException;
                    default void perform(AbstractBuild<?, ?> run, Env env, BuildListener bl)
                            throws InterruptedException, IOException {
                        throw new AbstractMethodError();
                    }
                    default boolean requiresWorkspace() { return true; }
                }
                interface BuildStep {
                    boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener)
                            throws InterruptedException, IOException;
                }
                static boolean isOverridden(Class<?> base, Class<?> derived, String name) {
                    return base != derived;
                }
                static abstract class Layer implements BuildStep {
                    @Override
                    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener)
                            throws InterruptedException, IOException {
                        if (this instanceof SimpleBuildStep step) {
                            final FilePath workspace = build.getWorkspace();
                            if (step.requiresWorkspace() && workspace == null) {
                                throw new IOException("no workspace for " + build);
                            }
                            if (workspace != null) {
                                step.perform(build, workspace, build.getEnvironment(listener), launcher, listener);
                            } else {
                                step.perform(build, build.getEnvironment(listener), listener);
                            }
                            return true;
                        } else if (build instanceof Build) {
                            return perform((Build) build, launcher, listener);
                        } else {
                            return true;
                        }
                    }
                    @Deprecated
                    public boolean perform(Build<?, ?> build, Launcher launcher, BuildListener listener)
                            throws InterruptedException, IOException {
                        if (build != null && isOverridden(Layer.class, this.getClass(), "perform")) {
                            return perform((AbstractBuild<?, ?>) build, launcher, listener);
                        }
                        throw new AbstractMethodError();
                    }
                }
                static <T> T mock(Class<T> clazz, Object answer) { throw new UnsupportedOperationException(); }
                static final Object CALLS_REAL_METHODS = new Object();

                void testPerform() throws InterruptedException, IOException {
                    FreeStyleBuild mock = mock(FreeStyleBuild.class, CALLS_REAL_METHODS);
                    Layer bscl = new Layer() {
                        @Override
                        public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) {
                            return true;
                        }
                    };
                    assertTrue(bscl.perform(mock, null, null));
                }

                void testPerformExpectAbstractMethodError() {
                    FreeStyleBuild mock = mock(FreeStyleBuild.class, CALLS_REAL_METHODS);
                    Layer bscl = new Layer() { };
                    assertThrows(AbstractMethodError.class, () -> bscl.perform(mock, null, null));
                }
            }
            """;

    @Test
    public void test() {
        TypeInfo X = javaInspector.parse("a.b.X", INPUT);
        List<Info> analysisOrder = prepWork(X);
        analyzer.go(analysisOrder, 3);
        TypeInfo layer = X.findSubType("Layer");
        for (MethodInfo mi : layer.methods()) {
            System.out.println(mi + ": nonModifying=" + mi.isNonModifying() + " "
                               + mi.analysis().getOrNull(METHOD_LINKS, MethodLinkedVariablesImpl.class));
        }
        for (String name : new String[]{"testPerform", "testPerformExpectAbstractMethodError"}) {
            MethodInfo mi = X.findUniqueMethod(name, 0);
            System.out.println(mi + ": nonModifying=" + mi.isNonModifying() + " "
                               + mi.analysis().getOrNull(METHOD_LINKS, MethodLinkedVariablesImpl.class));
            assertTrue(mi.isNonModifying(), name + " uses only locals");
        }
    }
}
