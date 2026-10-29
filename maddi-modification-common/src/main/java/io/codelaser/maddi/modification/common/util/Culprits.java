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

package io.codelaser.maddi.modification.common.util;

import io.codelaser.maddi.cst.api.analysis.Value;
import io.codelaser.maddi.cst.api.element.Element;
import io.codelaser.maddi.cst.api.element.Source;
import io.codelaser.maddi.cst.api.expression.ConstructorCall;
import io.codelaser.maddi.cst.api.expression.MethodCall;
import io.codelaser.maddi.cst.api.expression.MethodReference;
import io.codelaser.maddi.cst.api.expression.VariableExpression;
import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.statement.ExplicitConstructorInvocation;

import java.net.URI;
import java.util.List;

/**
 * Culprits: beside a verdict, the first statement responsible for it, followed into the callees ({@link
 * Value.Culprit}), for a developer asking "why?". Off by default: a run's {@code ModificationOptions.culprits}
 * switches it on (the IDE daemon asks for it), and the engine puts the previous value back afterwards. The writers
 * so far: the static-side-effect analyzer, and the reachability pass for {@code @Modified}.
 */
public final class Culprits {
    private Culprits() {
    }

    // non-final: the engine sets it per run
    public static boolean ENABLED = false;

    /** A step at element {@code e}, which lies in {@code in}'s compilation unit. */
    public static Value.Culprit.Step step(Info in, Element e, String text) {
        Source source = e == null ? null : e.source();
        URI uri = in.compilationUnit() == null ? null : in.compilationUnit().uri();
        return new Value.Culprit.Step(uri == null ? null : uri.toString(),
                source == null ? 0 : source.beginLine(), source == null ? 0 : source.beginPos(), text);
    }

    /** "calls Registry.register(..)", "calls items.add(..)", "new Box(..)", "references Box::fill". */
    public static String describeCall(Element e, MethodInfo callee) {
        String type = callee.typeInfo().simpleName();
        return switch (e) {
            case ConstructorCall _ -> "new " + type + "(..)";
            case ExplicitConstructorInvocation _ -> "calls " + type + "'s constructor";
            case MethodReference _ -> "references " + type + "::" + callee.name();
            case MethodCall mc when mc.objectIsImplicit() -> "calls " + callee.name() + "(..)";
            case MethodCall mc when mc.object() instanceof VariableExpression ve ->
                    "calls " + ve.variable().simpleName() + "." + callee.name() + "(..)";
            default -> "calls " + type + "." + callee.name() + "(..)";
        };
    }

    /**
     * Adds a step to a chain, replacing the last one when both are at the same place: a call that both implicates
     * the receiver and is the evidence itself is one statement, said once, with the later (more specific) text.
     */
    public static void add(List<Value.Culprit.Step> chain, Value.Culprit.Step step) {
        if (!chain.isEmpty()) {
            Value.Culprit.Step last = chain.getLast();
            if (last.line() == step.line() && last.column() == step.column()
                && java.util.Objects.equals(last.uri(), step.uri())) {
                chain.set(chain.size() - 1, step);
                return;
            }
        }
        chain.add(step);
    }
}
