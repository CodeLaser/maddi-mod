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

package io.codelaser.maddi.aapi.parser;

import io.codelaser.maddi.modification.common.defaults.ShallowAnalyzer;
import io.codelaser.maddi.cst.api.element.Element;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.inspection.api.integration.JavaInspector;
import io.codelaser.maddi.util.Trie;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public class AnalysisHintsWriter {
    private static final Logger LOGGER = LoggerFactory.getLogger(AnalysisHintsWriter.class);
    private final JavaInspector javaInspector;
    private final Function<Element, AnalysisHintsParser.Data> dataProvider;
    private final Function<Element, ShallowAnalyzer.InfoData> infoDataProvider;

    public AnalysisHintsWriter(JavaInspector javaInspector,
                               Function<Element, AnalysisHintsParser.Data> dataProvider,
                               Function<Element, ShallowAnalyzer.InfoData> infoDataProvider) {
        this.javaInspector = javaInspector;
        this.dataProvider = dataProvider;
        this.infoDataProvider = infoDataProvider;
    }

    public void write(String destinationDirectory, Trie<TypeInfo> typeTrie, String destinationPackage) throws IOException {
        File directory = new File(destinationDirectory);
        if (directory.mkdirs()) {
            LOGGER.info("Created directory {}", directory.getAbsolutePath());
        }
        try {
            typeTrie.visitThrowing(new String[]{}, (parts, list)
                    -> write(directory, parts, list, destinationPackage));
        } catch (RuntimeException re) {
            if (re.getCause() instanceof IOException ioe) {
                throw ioe;
            }
            throw re;
        }
    }

    private void write(File directory, String[] packageParts, List<TypeInfo> list, String destinationPackage) throws IOException {
        if (list.isEmpty()) return;
        String compressedPackages = Arrays.stream(packageParts).map(AnalysisHintsWriter::capitalize)
                .collect(Collectors.joining());
        File outputFile = new File(directory, compressedPackages + ".json");
        LOGGER.info("Writing {} type(s) to {}", list.size(), outputFile.getAbsolutePath());
        AnalysisHintsComposer analysisHintsComposer = new AnalysisHintsComposer(javaInspector, set -> destinationPackage, w -> true);
        Collection<TypeInfo> apiTypes = analysisHintsComposer.compose(list);

        Map<Element, Element> dollarMap = analysisHintsComposer.translateFromDollarToReal();
        analysisHintsComposer.write(apiTypes, directory, new DecoratorWithComments(javaInspector.runtime(),
                javaInspector.mainSources(), dollarMap, infoDataProvider, dataProvider));

    }

    private static String capitalize(String s) {
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
