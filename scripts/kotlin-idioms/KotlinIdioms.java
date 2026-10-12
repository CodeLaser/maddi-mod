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

import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys;
import org.jetbrains.kotlin.cli.common.messages.MessageCollector;
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles;
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer;
import org.jetbrains.kotlin.com.intellij.psi.PsiErrorElement;
import org.jetbrains.kotlin.config.CommonConfigurationKeys;
import org.jetbrains.kotlin.config.CompilerConfiguration;
import org.jetbrains.kotlin.lexer.KtTokens;
import org.jetbrains.kotlin.psi.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

/*
 Idiom counts for Kotlin produced by any Java -> Kotlin translator (CodeLaser/maddi-mod#35): the same numbers for
 IntelliJ J2K, J2K with Meta's published cleanups, and maddi's printer with and without verdicts. Syntax only, from the
 Kotlin compiler's own parser (PSI): a translator's output need not compile to be counted, and nothing here depends on
 how a translator names or lays out its code.

 Usage: run.sh [--files] <label>=<dir> ... ; every .kt file under each directory is counted. Output: one tab-separated
 row per label, the raw count and the count per 1,000 non-blank lines; --files adds one row per file.
 */
public class KotlinIdioms {

    // read-only collection interfaces against their mutable counterparts and the JDK classes J2K keeps
    static final Set<String> READ_ONLY = Set.of("List", "Set", "Map", "Collection", "Iterable");
    static final Set<String> MUTABLE = Set.of("MutableList", "MutableSet", "MutableMap", "MutableCollection",
            "MutableIterable", "ArrayList", "HashMap", "HashSet", "LinkedHashMap", "LinkedHashSet", "TreeMap",
            "TreeSet", "LinkedList", "ArrayDeque", "Deque", "Queue", "SortedMap", "SortedSet", "NavigableMap",
            "NavigableSet", "EnumMap", "EnumSet", "ConcurrentHashMap", "ConcurrentMap");

    static final List<String> KEYS = List.of(
            "files", "lines", "parseErrors",
            "notNullAssertions", "safeCalls", "elvis",
            "nullableParameterTypes", "nullablePropertyTypes", "nullableReturnTypes", "nullableTypeArguments",
            "valProperties", "varProperties", "valLocals", "varLocals", "lateinit",
            "readOnlyCollectionTypes", "mutableCollectionTypes",
            "runBlocks");
    // the counts that are not divided by the number of lines
    static final Set<String> NOT_PER_KLOC = Set.of("files", "lines");

    static final class Counts {
        final Map<String, Long> map = new LinkedHashMap<>();

        Counts() {
            KEYS.forEach(k -> map.put(k, 0L));
        }

        void inc(String key) {
            map.merge(key, 1L, Long::sum);
        }

        void add(Counts other) {
            other.map.forEach((k, v) -> map.merge(k, v, Long::sum));
        }
    }

    public static void main(String[] args) throws IOException {
        boolean perFile = false;
        List<String[]> inputs = new ArrayList<>();
        for (String arg : args) {
            if ("--files".equals(arg)) perFile = true;
            else {
                int eq = arg.indexOf('=');
                if (eq <= 0) throw new IllegalArgumentException("expected <label>=<dir>, got " + arg);
                inputs.add(new String[]{arg.substring(0, eq), arg.substring(eq + 1)});
            }
        }
        if (inputs.isEmpty()) {
            System.err.println("usage: KotlinIdioms [--files] <label>=<dir> ...");
            System.exit(2);
        }
        CompilerConfiguration configuration = new CompilerConfiguration();
        configuration.put(CommonConfigurationKeys.MESSAGE_COLLECTOR_KEY, MessageCollector.Companion.getNONE());
        configuration.put(CommonConfigurationKeys.MODULE_NAME, "kotlin-idioms");
        Project project = KotlinCoreEnvironment.Companion.createForProduction(Disposer.newDisposable(),
                configuration, EnvironmentConfigFiles.JVM_CONFIG_FILES).getProject();
        KtPsiFactory factory = new KtPsiFactory(project, false);

        header(perFile);
        for (String[] input : inputs) {
            Path root = Path.of(input[1]);
            List<Path> files;
            try (Stream<Path> walk = Files.walk(root)) {
                files = walk.filter(p -> p.toString().endsWith(".kt")).sorted().toList();
            }
            Counts total = new Counts();
            for (Path file : files) {
                String text = Files.readString(file).replace("\r\n", "\n");
                Counts counts = count(factory.createFile(file.getFileName().toString(), text), text);
                total.add(counts);
                if (perFile) row(input[0], root.relativize(file).toString(), counts);
            }
            row(input[0], perFile ? "TOTAL" : null, total);
        }
    }

    static void header(boolean perFile) {
        StringBuilder sb = new StringBuilder("label");
        if (perFile) sb.append("\tfile");
        for (String k : KEYS) {
            sb.append('\t').append(k);
            if (!NOT_PER_KLOC.contains(k)) sb.append('\t').append(k).append("/kLOC");
        }
        System.out.println(sb);
    }

    static void row(String label, String file, Counts counts) {
        StringBuilder sb = new StringBuilder(label);
        if (file != null) sb.append('\t').append(file);
        long lines = counts.map.get("lines");
        for (String k : KEYS) {
            long v = counts.map.get(k);
            sb.append('\t').append(v);
            if (!NOT_PER_KLOC.contains(k)) {
                sb.append('\t').append(lines == 0 ? "" : String.format(Locale.ROOT, "%.2f", 1000.0 * v / lines));
            }
        }
        System.out.println(sb);
    }

    static Counts count(KtFile file, String text) {
        Counts c = new Counts();
        c.inc("files");
        c.map.put("lines", text.lines().filter(l -> !l.isBlank()).count());
        file.accept(new KtTreeVisitorVoid() {
            @Override
            public void visitErrorElement(PsiErrorElement element) {
                c.inc("parseErrors");
                super.visitErrorElement(element);
            }

            @Override
            public void visitPostfixExpression(KtPostfixExpression expression) {
                if (expression.getOperationToken() == KtTokens.EXCLEXCL) c.inc("notNullAssertions");
                super.visitPostfixExpression(expression);
            }

            @Override
            public void visitSafeQualifiedExpression(KtSafeQualifiedExpression expression) {
                c.inc("safeCalls");
                super.visitSafeQualifiedExpression(expression);
            }

            @Override
            public void visitBinaryExpression(KtBinaryExpression expression) {
                if (expression.getOperationToken() == KtTokens.ELVIS) c.inc("elvis");
                super.visitBinaryExpression(expression);
            }

            @Override
            public void visitProperty(KtProperty property) {
                boolean local = property.isLocal();
                c.inc((property.isVar() ? "var" : "val") + (local ? "Locals" : "Properties"));
                if (property.hasModifier(KtTokens.LATEINIT_KEYWORD)) c.inc("lateinit");
                if (!local) declaredType(property.getTypeReference(), "nullablePropertyTypes");
                super.visitProperty(property);
            }

            @Override
            public void visitParameter(KtParameter parameter) {
                // a primary constructor's val/var parameter is a property
                if (parameter.hasValOrVar()) {
                    c.inc(parameter.isMutable() ? "varProperties" : "valProperties");
                }
                // lambda parameters and catch parameters are not declarations a translator chooses a type for
                if (parameter.getParent() instanceof KtParameterList list && list.getParent() instanceof KtDeclaration
                        && !(list.getParent() instanceof KtFunctionLiteral)) {
                    declaredType(parameter.getTypeReference(),
                            parameter.hasValOrVar() ? "nullablePropertyTypes" : "nullableParameterTypes");
                }
                super.visitParameter(parameter);
            }

            @Override
            public void visitNamedFunction(KtNamedFunction function) {
                declaredType(function.getTypeReference(), "nullableReturnTypes");
                super.visitNamedFunction(function);
            }

            @Override
            public void visitCallExpression(KtCallExpression expression) {
                if (expression.getCalleeExpression() instanceof KtNameReferenceExpression ref
                        && "run".equals(ref.getReferencedName())
                        && !(expression.getParent() instanceof KtQualifiedExpression)
                        && !expression.getLambdaArguments().isEmpty()) {
                    c.inc("runBlocks");
                }
                super.visitCallExpression(expression);
            }

            // the declared type of a property, parameter or function: nullable at the top, nullable type arguments,
            // read-only or mutable collection types at any depth
            private void declaredType(KtTypeReference ref, String nullableKey) {
                if (ref == null) return;
                KtTypeElement top = ref.getTypeElement();
                if (top instanceof KtNullableType) c.inc(nullableKey);
                ref.accept(new KtTreeVisitorVoid() {
                    @Override
                    public void visitNullableType(KtNullableType nullableType) {
                        if (nullableType != top) c.inc("nullableTypeArguments");
                        super.visitNullableType(nullableType);
                    }

                    @Override
                    public void visitUserType(KtUserType userType) {
                        String name = userType.getReferencedName();
                        if (READ_ONLY.contains(name)) c.inc("readOnlyCollectionTypes");
                        else if (MUTABLE.contains(name)) c.inc("mutableCollectionTypes");
                        super.visitUserType(userType);
                    }
                });
            }
        });
        return c;
    }
}
