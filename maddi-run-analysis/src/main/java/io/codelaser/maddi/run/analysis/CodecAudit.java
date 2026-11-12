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

package io.codelaser.maddi.run.analysis;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.codelaser.maddi.cst.api.analysis.Codec;
import io.codelaser.maddi.cst.api.element.SourceSet;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.inspection.api.integration.JavaInspector;
import io.codelaser.maddi.modification.link.io.LinkCodec;
import io.codelaser.maddi.modification.prepwork.io.LoadAnalysisResults;
import io.codelaser.maddi.modification.prepwork.io.WriteAnalysisResults;
import io.codelaser.maddi.util.Trie;

/**
 * CODECAUDIT (environment gate): after the analysis, every primary type's results are encoded as a checkpoint
 * writes them ({@link LinkCodec}, a fresh codec per type) and decoded back as a restore reads them
 * ({@link LinkCodec#restoreCodec()}), onto the same model. Every value that does not encode and every type that does
 * not decode is a gap in what a stored analysis can bring back; they are counted by cause, with one stack trace per
 * cause. Nothing is written to the model that is not there already: the restore codec keeps present values.
 */
public class CodecAudit {
    private static final Logger LOGGER = LoggerFactory.getLogger(CodecAudit.class);
    private static final Pattern NAMES = Pattern.compile("[a-z][a-zA-Z0-9_$]*(\\.[a-zA-Z0-9_$<>]+)+(\\([^)]*\\))?");

    private record Cause(String where, String what) {
    }

    private static final class Count {
        int n;
        String example;
        Throwable first;
    }

    private final Map<Cause, Count> encodeFailures = new LinkedHashMap<>();
    private final Map<Cause, Count> decodeFailures = new LinkedHashMap<>();
    private int typesEncoded;
    private int typesDecoded;
    private int valuesNotEncoded;

    public void run(JavaInspector javaInspector, SourceSet sourceSet, Collection<TypeInfo> primaryTypes) {
        Path directory;
        try {
            directory = Files.createTempDirectory("maddi-codec-audit");
        } catch (IOException e) {
            LOGGER.error("CODECAUDIT: no temporary directory", e);
            return;
        }
        for (TypeInfo primary : primaryTypes.stream().sorted(Comparator.comparing(TypeInfo::fullyQualifiedName))
                .toList()) {
            Path typeDirectory = directory.resolve("t" + typesEncoded + "-" + decodeFailures.size() + "-"
                                                   + Math.abs(primary.fullyQualifiedName().hashCode()));
            try {
                Trie<TypeInfo> trie = new Trie<>();
                trie.add(primary.fullyQualifiedName().split("\\."), primary);
                new WriteAnalysisResults(javaInspector.runtime())
                        .setEncodeFailureListener((key, e) -> {
                            ++valuesNotEncoded;
                            record(encodeFailures, key, e, primary.fullyQualifiedName());
                        })
                        .write(typeDirectory.toFile(), trie, new LinkCodec(javaInspector, sourceSet).codec());
                ++typesEncoded;
            } catch (IOException | RuntimeException | AssertionError | StackOverflowError e) {
                record(encodeFailures, "<type>", e, primary.fullyQualifiedName());
                continue;
            }
            try (Stream<Path> files = Files.walk(typeDirectory)) {
                Codec restore = new LinkCodec(javaInspector, sourceSet).restoreCodec();
                for (Path file : files.filter(f -> f.toString().endsWith(".json")).toList()) {
                    new LoadAnalysisResults(javaInspector.runtime(), sourceSet).go(restore, file);
                }
                ++typesDecoded;
            } catch (IOException | RuntimeException | AssertionError | StackOverflowError e) {
                record(decodeFailures, "<type>", e, primary.fullyQualifiedName());
            }
        }
        try (Stream<Path> walk = Files.walk(directory)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException e) {
            LOGGER.debug("CODECAUDIT: cannot remove {}", directory);
        }
        LOGGER.info("CODECAUDIT {} primary types: {} encoded, {} values not encoded, {} types decoded",
                primaryTypes.size(), typesEncoded, valuesNotEncoded, typesDecoded);
        report("ENCODE", encodeFailures);
        report("DECODE", decodeFailures);
    }

    private static void record(Map<Cause, Count> map, String key, Throwable e, String type) {
        Count count = map.computeIfAbsent(new Cause(key + " @ " + origin(e), what(e)), _ -> new Count());
        if (count.n++ == 0) {
            count.example = type;
            count.first = e;
        }
    }

    // the first frame in maddi code, under the codec's own helpers where possible
    private static String origin(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        for (StackTraceElement ste : root.getStackTrace()) {
            if (ste.getClassName().startsWith("io.codelaser.maddi")) {
                return ste.getClassName().substring(ste.getClassName().lastIndexOf('.') + 1) + "."
                       + ste.getMethodName() + ":" + ste.getLineNumber();
            }
        }
        return "?";
    }

    // the message with names blanked, so that one cause counts once
    private static String what(Throwable e) {
        String message = e.getMessage() == null ? "" : e.getMessage();
        message = NAMES.matcher(message).replaceAll("N");
        if (message.length() > 120) message = message.substring(0, 120);
        return e.getClass().getSimpleName() + (message.isEmpty() ? "" : ": " + message);
    }

    private static void report(String label, Map<Cause, Count> map) {
        map.entrySet().stream().sorted((a, b) -> b.getValue().n - a.getValue().n).forEach(entry -> {
            Count count = entry.getValue();
            StringWriter trace = new StringWriter();
            count.first.printStackTrace(new PrintWriter(trace));
            String shortTrace = trace.toString().lines().limit(25).reduce((a, b) -> a + "\n" + b).orElse("");
            LOGGER.info("CODECAUDIT {} {}x {} | {} (first in {})\n{}", label, count.n, entry.getKey().where(),
                    entry.getKey().what(), count.example, shortTrace);
        });
    }
}
