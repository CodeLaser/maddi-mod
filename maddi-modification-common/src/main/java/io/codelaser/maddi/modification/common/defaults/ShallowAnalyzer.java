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

import io.codelaser.maddi.cst.api.analysis.Message;
import io.codelaser.maddi.cst.api.analysis.Property;
import io.codelaser.maddi.cst.api.element.Element;
import io.codelaser.maddi.cst.api.element.SourceSet;
import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.api.runtime.Runtime;
import io.codelaser.maddi.graph.G;
import io.codelaser.maddi.graph.ImmutableGraph;
import io.codelaser.maddi.graph.op.Linearize;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.codelaser.maddi.cst.impl.analysis.PropertyImpl.DEFAULTS_ANALYZER;
import static io.codelaser.maddi.cst.impl.analysis.ValueImpl.BoolImpl.TRUE;

public class ShallowAnalyzer {
    private final Runtime runtime;
    private final AnnotationProvider annotationProvider;
    private final List<Message> messages = new LinkedList<>();
    private final boolean onlyPublic;
    private final DebugVisitor debugVisitor;

    /*
    from type = from return type, field type, parameter type
    from owner = from type containing method, field, parameter
    from field = for final field, ...
    from parameter = for identity
    from method = for identity, fluent
     */
    public enum AnnotationOrigin {ANNOTATED, FROM_OVERRIDE, FROM_TYPE, FROM_OWNER, FROM_PARAMETER, FROM_METHOD, FROM_FIELD, DEFAULT}

    public record InfoData(Map<Property, AnnotationOrigin> originMap) {
        public AnnotationOrigin origin(Property property) {
            return originMap.getOrDefault(property, AnnotationOrigin.DEFAULT);
        }

        public void put(Property property, AnnotationOrigin origin) {
            originMap.put(property, origin);
        }
    }

    public record Result(Map<Element, InfoData> dataMap,
                         List<TypeInfo> allTypes,
                         G<TypeInfo> typeGraph,
                         List<TypeInfo> sorted,
                         List<Message> messages) {
    }

    public ShallowAnalyzer(Runtime runtime, AnnotationProvider annotationProvider, boolean onlyPublic) {
        this(runtime, annotationProvider, onlyPublic, null);
    }

    public ShallowAnalyzer(Runtime runtime, AnnotationProvider annotationProvider, boolean onlyPublic,
                           DebugVisitor debugVisitor) {
        this.runtime = runtime;
        this.annotationProvider = annotationProvider;
        this.onlyPublic = onlyPublic;
        this.debugVisitor = debugVisitor;
    }

    private boolean acceptAccess(Info info) {
        return !onlyPublic || info.access().isPublic();
    }

    /**
     * A type the caller NAMED is in scope whatever its access; [onlyPublic] prunes the closure this walks into
     * (sub- and supertypes), not what it was asked to analyse.
     * <p>
     * ⛔ Without this an annotated API for a package-private type yields NO properties, only the ANNOTATED_API
     * marker, and says nothing about it. Every multifile facade's part class is package-private by construction --
     * `kotlin.collections.CollectionsKt___CollectionsKt` is where `map` is declared, and the facade that IS public
     * declares nothing at all -- so a contract for the Kotlin stdlib could be written, resolved, and quietly do
     * nothing.
     */
    private boolean inScope(TypeInfo typeInfo, Set<TypeInfo> requested) {
        return requested.contains(typeInfo) || acceptAccess(typeInfo);
    }

    public Result go(List<TypeInfo> types) {
        Set<TypeInfo> requested = Collections.newSetFromMap(new IdentityHashMap<>());
        requested.addAll(types);
        Set<SourceSet> stayWithinSourceSets = types.stream()
                .map(typeInfo -> typeInfo.compilationUnit().sourceSet()).collect(Collectors.toUnmodifiableSet());
        if(debugVisitor != null) debugVisitor.inputTypes(types);
        List<TypeInfo> allTypes = types.stream().flatMap(TypeInfo::recursiveSubTypeStream)
                .filter(t -> inScope(t, requested))
                .flatMap(t -> Stream.concat(Stream.of(t), t.recursiveSuperTypeStream()))
                .distinct()
                .filter(t -> stayWithinSourceSets.contains(t.compilationUnit().sourceSet()))
                .toList();
        if (debugVisitor != null) debugVisitor.allTypes(allTypes);

        G.Builder<TypeInfo> graphBuilder = new ImmutableGraph.Builder<>(Long::sum);
        for (TypeInfo typeInfo : allTypes) {
            List<TypeInfo> allSuperTypes = typeInfo.recursiveSuperTypeStream()
                    .filter(this::acceptAccess)
                    .filter(t -> stayWithinSourceSets.contains(t.compilationUnit().sourceSet()))
                    .toList();
            graphBuilder.add(typeInfo, allSuperTypes);
        }
        G<TypeInfo> typeGraph = graphBuilder.build();
        if (debugVisitor != null) debugVisitor.typeGraph(typeGraph);
        Linearize.Result<TypeInfo> linearize = Linearize.linearize(typeGraph, Linearize.LinearizationMode.ALL);
        List<TypeInfo> sorted = linearize.asList(Comparator.comparing(TypeInfo::fullyQualifiedName));
        if (debugVisitor != null) debugVisitor.sortedLinearized(sorted);
        Map<Element, InfoData> dataMap = new HashMap<>();

        ShallowTypeAnalyzer shallowTypeAnalyzer = new ShallowTypeAnalyzer(runtime, annotationProvider, onlyPublic);
        for (TypeInfo typeInfo : sorted) {
            dataMap.putAll(shallowTypeAnalyzer.analyze(typeInfo));
        }
        if (debugVisitor != null) debugVisitor.dataMapAfterTypeAnalyzer(dataMap);

        ShallowMethodAnalyzer shallowMethodAnalyzer = new ShallowMethodAnalyzer(runtime, annotationProvider);
        for (TypeInfo typeInfo : sorted) {
            dataMap.putAll(shallowTypeAnalyzer.analyzeFields(typeInfo));
            typeInfo.constructorAndMethodStream()
                    .filter(this::acceptAccess)
                    .forEach(mi -> dataMap.putAll(shallowMethodAnalyzer.analyze(mi)));
        }
        if (debugVisitor != null) debugVisitor.dataMapAfterFieldMethodAnalyzer(dataMap);
        for (TypeInfo typeInfo : sorted) {
            if (inScope(typeInfo, requested)) {
                shallowTypeAnalyzer.check(typeInfo);
                typeInfo.analysis().set(DEFAULTS_ANALYZER, TRUE);
            }
        }
        messages.addAll(shallowMethodAnalyzer.messages());
        messages.addAll(shallowTypeAnalyzer.messages());
        return new Result(dataMap, allTypes, typeGraph, sorted, messages);
    }
}
