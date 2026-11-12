package io.codelaser.maddi.modification.link.io;

import static io.codelaser.maddi.modification.link.impl.MethodLinkedVariablesImpl.METHOD_LINKS;
import static io.codelaser.maddi.modification.prepwork.callgraph.ComputePartOfConstructionFinalField.PART_OF_CONSTRUCTION;

import java.util.*;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.jetbrains.annotations.NotNull;
import org.parsers.json.Node;
import org.parsers.json.ast.StringLiteral;

import io.codelaser.maddi.cst.api.analysis.Codec;
import io.codelaser.maddi.cst.api.analysis.Property;
import io.codelaser.maddi.cst.api.analysis.Value;
import io.codelaser.maddi.cst.api.element.SourceSet;
import io.codelaser.maddi.cst.api.expression.Expression;
import io.codelaser.maddi.cst.api.expression.IntConstant;
import io.codelaser.maddi.cst.api.info.*;
import io.codelaser.maddi.cst.api.runtime.Runtime;
import io.codelaser.maddi.cst.api.type.ParameterizedType;
import io.codelaser.maddi.cst.api.variable.DependentVariable;
import io.codelaser.maddi.cst.api.variable.Variable;
import io.codelaser.maddi.cst.impl.analysis.PropertyProviderImpl;
import io.codelaser.maddi.cst.impl.analysis.ValueImpl;
import io.codelaser.maddi.cst.io.CodecImpl;
import io.codelaser.maddi.inspection.api.integration.JavaInspector;
import io.codelaser.maddi.modification.link.impl.LinkedVariablesImpl;
import io.codelaser.maddi.modification.link.impl.MethodLinkedVariablesImpl;
import io.codelaser.maddi.modification.link.impl.Result;
import io.codelaser.maddi.modification.link.impl.localvar.AppliedFunctionalInterfaceVariable;
import io.codelaser.maddi.modification.link.impl.localvar.FunctionalInterfaceVariable;
import io.codelaser.maddi.modification.link.impl.localvar.IntermediateVariable;
import io.codelaser.maddi.modification.link.impl.localvar.MarkerVariable;
import io.codelaser.maddi.modification.link.impl.localvar.SharedVariable;
import io.codelaser.maddi.modification.link.vf.VirtualFieldComputer;
import io.codelaser.maddi.modification.prepwork.Util;
import io.codelaser.maddi.modification.prepwork.variable.Links;
import io.codelaser.maddi.modification.prepwork.variable.ReturnVariable;
import io.codelaser.maddi.modification.prepwork.variable.impl.LinksImpl;
import io.codelaser.maddi.modification.prepwork.variable.impl.ObjectCreationVariableImpl;
import io.codelaser.maddi.modification.prepwork.variable.impl.ReturnVariableImpl;

public class LinkCodec {

    private final Codec.TypeProvider typeProvider;
    private final Codec.DecoderProvider decoderProvider;
    private final Codec.PropertyProvider propertyProvider;
    private final Runtime runtime;
    private final SourceSet sourceSetOfRequest;

    public LinkCodec(JavaInspector javaInspector) {
        this(javaInspector, javaInspector.mainSources());
    }

    public LinkCodec(JavaInspector javaInspector, SourceSet sourceSetOfRequest) {
        this.typeProvider = fqn -> {
            TypeInfo primitive = javaInspector.runtime().primitiveByNameOrNull(fqn);
            if (primitive != null) return primitive;
            return javaInspector.compiledTypesManager().type(fqn, sourceSetOfRequest);
        };
        decoderProvider = new D();
        this.propertyProvider = new P();
        this.sourceSetOfRequest = sourceSetOfRequest;
        this.runtime = javaInspector.runtime();
    }

    public Codec codec() {
        return new C();
    }

    /** checkpoint-restore variant (task #34): already-present values win, decode fills the gaps */
    public Codec restoreCodec() {
        return new C() {
            @Override
            protected boolean skipExistingValues() {
                return true;
            }
        };
    }

    class C extends CodecImpl {
        public C() {
            super(runtime, propertyProvider, decoderProvider, typeProvider, sourceSetOfRequest);
        }

        /**
         * One written definition of a marker variable: the element it was written under, and the definition
         * itself, as it appears in the file (null while it is being built, see the encode side).
         */
        private record MarkerDef(Info owner, String definition) {
        }

        /**
         * Marker-variable definitions, by name, then by owning element.
         * <p>
         * Marker variables are numbered PER METHOD by the link computer ($_ce0, $_fi0, $_afi0, ...; see the note
         * in {@code LinkComputerImpl.SourceMethodComputer}, whose "cross-method collisions are harmless" holds
         * inside the engine, where linking graphs are per-method, but not here, where one file carries every
         * method of every type). Keyed by the bare name for a whole file, as this was, the first $_ce0 written
         * won the name and every later one -- a different variable, of a different method -- decoded to it: 28
         * of the 44 back-references in the transform-support archive resolved across elements, one of them
         * handing a Loop method a marker typed Try.TryData.
         * <p>
         * So a definition belongs to the element it is written under, and only a repeat under that same element
         * may be the bare back-reference {@code ["m", name]}. A marker that genuinely recurs under another
         * element -- same name AND byte-identical definition -- still costs one definition: its repeats are
         * back-references that name their owner, {@code ["m", name, owner]}. The owner is omitted exactly when
         * it is the element being written, which is the common case.
         */
        private final Map<String, List<MarkerDef>> encodedMarkerVariables = new HashMap<>();

        // the virtual container types being written, so that one met inside its own definition is the short form
        private final Set<TypeInfo> containersInProgress = new HashSet<>();

        // the marker variables written so far: each is written once, in full, and referred back to afterwards
        @Override
        public Object encodingState() {
            Map<String, List<MarkerDef>> copy = new HashMap<>();
            encodedMarkerVariables.forEach((k, v) -> copy.put(k, new ArrayList<>(v)));
            return copy;
        }

        @SuppressWarnings("unchecked")
        @Override
        public void restoreEncodingState(Object state) {
            encodedMarkerVariables.clear();
            if (state != null) encodedMarkerVariables.putAll((Map<String, List<MarkerDef>>) state);
        }

        /**
         * The element a marker variable written here belongs to. A parameter shares its method's scope: they are
         * one evaluation, and the link computer numbers markers once per method.
         */
        private Info ownerOfMarkerVariables(Context context) {
            if (context.isEmpty()) return null; // no element on the stack: one file-wide scope, as before
            return switch (context.peek(0)) {
                case ParameterInfo pi -> pi.methodInfo();
                case TypeAndSorted tas -> tas.typeInfo();
                case Info info -> info;
                case Object o -> throw new UnsupportedOperationException("Unexpected codec context element " + o);
            };
        }

        /*
         ⛔ THE SCOPE OF A DECODED MARKER IS THE OWNER'S NAME, NOT THE OWNER OBJECT. A class can exist in two source
         sets: a project that parses transform-support's source and also loads its jar's analysis snapshot has two
         Loop.LoopDataImpl.withBreak(boolean), and MethodInfoImpl.equals tells them apart by source set. The definition
         ["M", "$_ce3M", ...] was put under the element being read (the jar's copy); the back-reference
         ["m", "$_ce3M", owner] in withBreakLevel resolves its owner by name, through the source-set disambiguation,
         to the parsed copy; the lookup missed and the load died on "Cannot find $_ce3M under ...". A marker's scope
         is one method's evaluation, which the fully qualified name (with the parameter types) identifies; the source
         set adds nothing to it. Found on a refactor server's maddi-audit project, 2026-11-09.
         */
        static String markerScope(Info owner) {
            return owner == null ? "" : owner.fullyQualifiedName();
        }

        private EncodedValue markerBackReference(Context context, String name, Info owner) {
            if (owner == null) {
                return encodeList(context, List.of(encodeString(context, "m"), encodeString(context, name)));
            }
            return encodeList(context, List.of(encodeString(context, "m"), encodeString(context, name),
                    encodeInfoOutOfContext(context, owner)));
        }

        private EncodedValue encodeMarkerVariableDefinition(Context context, MarkerVariable mv, String name) {
            if (mv instanceof AppliedFunctionalInterfaceVariable afi) {
                List<EncodedValue> list = new ArrayList<>();
                Collections.addAll(list, encodeString(context, "a"),
                        encodeString(context, name),
                        encodeType(context, afi.parameterizedType()),
                        encodeList(context, afi.params().stream()
                                .map(r -> encodeResult(context, r)).toList()));
                if (afi.sourceOfFunctionalInterface() != null) {
                    list.add(encodeInfoOutOfContext(context, afi.sourceOfFunctionalInterface()));
                }
                return encodeList(context, list);
            }
            if (mv instanceof FunctionalInterfaceVariable fiv) {
                return encodeList(context, List.of(encodeString(context, "f"),
                        encodeString(context, name),
                        encodeType(context, fiv.parameterizedType()),
                        encodeResult(context, fiv.result())
                ));
            }
            if (mv.isSomeValue()) {
                return encodeList(context, List.of(encodeString(context, "M"),
                        encodeString(context, name),
                        encodeType(context, mv.parameterizedType())));
            }
            assert mv.isConstant();
            return encodeList(context, List.of(encodeString(context, "M"),
                    encodeString(context, name),
                    encodeType(context, mv.parameterizedType()),
                    encodeExpression(context, mv.assignmentExpression())));
        }

        @Override
        public EncodedValue encodeVariable(Context context, Variable variable) {
            if (variable instanceof DependentVariable dv && dv.indexExpression()
                    instanceof IntConstant ic && ic.constant() < 0) {
                List<EncodedValue> list = new ArrayList<>();
                list.add(encodeString(context, "D"));
                list.add(encodeExpression(context, dv.arrayExpression()));
                list.add(encodeExpression(context, dv.indexExpression()));
                // slice!
                list.add(encodeType(context, dv.parameterizedType()));
                return encodeList(context, list);
            }
            if (variable instanceof ReturnVariable rv) {
                return encodeList(context, List.of(encodeString(context, "R"),
                        encodeMethodInfo(context, rv.methodInfo())));
            }
            if (variable instanceof ObjectCreationVariableImpl oc) {
                // the object a 'new' made: equal by its position and type, as the analysis made it; left out, every
                // link naming one was missing from a stored analysis (fernflower: 719 values, 2026-11-12)
                return encodeList(context, List.of(encodeString(context, "O"), encodeCreationMethod(context, oc),
                        encodeString(context, oc.index),
                        encodeType(context, oc.parameterizedType())));
            }
            if (variable instanceof MarkerVariable mv) {
                // because they may contain an extensive definition, we cache these marker variables
                // the name has an "M" appended, so that it does not clash with marker variables generated from
                // sources
                String name = mv.simpleName() + "M";
                Info owner = ownerOfMarkerVariables(context);
                List<MarkerDef> defs = encodedMarkerVariables.computeIfAbsent(name, _ -> new ArrayList<>());
                if (defs.stream().anyMatch(d -> Objects.equals(owner, d.owner()))) {
                    return markerBackReference(context, name, null); // repeat under its own element
                }
                // The pending entry goes in BEFORE the definition is built, so that a self-referential marker
                // (an applied functional interface whose own result mentions it) meets the back-reference above
                // instead of recursing. Building can register and nest further definitions, hence the snapshot:
                // dropping this definition below has to drop those with it, or they stand registered as written
                // while the only copy of them goes unwritten.
                Map<String, List<MarkerDef>> snapshot = new HashMap<>();
                encodedMarkerVariables.forEach((k, v) -> snapshot.put(k, new ArrayList<>(v)));
                defs.add(new MarkerDef(owner, null));
                EncodedValue definition = encodeMarkerVariableDefinition(context, mv, name);
                String written = ((E) definition).s();
                MarkerDef identical = encodedMarkerVariables.get(name).stream()
                        .filter(d -> written.equals(d.definition())).findFirst().orElse(null);
                if (identical != null) {
                    // the same marker, already written under another element: point at it, definition and all
                    encodedMarkerVariables.clear();
                    encodedMarkerVariables.putAll(snapshot);
                    return markerBackReference(context, name, identical.owner());
                }
                encodedMarkerVariables.get(name).replaceAll(d ->
                        d.definition() == null && Objects.equals(owner, d.owner()) ? new MarkerDef(owner, written) : d);
                return definition;
            }
            // SharedVariable/IntermediateVariable have no codec branch by design: they are kept out of
            // summaries by acceptForLinkedVariables filtering. If one leaks here, the fallback would
            // silently encode it as an ordinary local — fail at the cause instead.
            assert !(variable instanceof SharedVariable)
                   && !(variable instanceof IntermediateVariable)
                    : "engine-internal variable leaked into persistence: " + variable;
            return super.encodeVariable(context, variable);
        }

        /*
         The method an object creation belongs to plays no part in its equality (position and type do); when it is one
         the codec cannot name (the constructor javac makes for an array creation, which is no member of its type),
         it is left out and read back as null.
         */
        private EncodedValue encodeCreationMethod(Context context, ObjectCreationVariableImpl oc) {
            if (oc.methodInfo() == null) return encodeList(context, List.of());
            try {
                return encodeMethodInfo(context, oc.methodInfo());
            } catch (RuntimeException e) {
                return encodeList(context, List.of());
            }
        }

        // links, extra, modified
        public Result decodeResult(Context context, EncodedValue encodedValue) {
            List<EncodedValue> list = decodeList(context, encodedValue);
            Links links = MethodLinkedVariablesImpl.decodeLinks(this, context, list.get(0));
            List<List<EncodedValue>> extraList = decodeList(context, list.get(1)).stream().map(ev ->
                    decodeList(context, ev)).toList();
            Map<Variable, Links> extra = extraList.stream().collect(Collectors.toUnmodifiableMap(
                    evList -> decodeVariable(context, evList.getFirst()),
                    evList -> MethodLinkedVariablesImpl.decodeLinks(this, context,
                            evList.getLast())));
            List<List<EncodedValue>> modifiedList = decodeList(context, list.get(2))
                    .stream().map(ev -> decodeList(context, ev)).toList();
            Map<Variable, Set<MethodInfo>> modified = modifiedList.stream().collect(Collectors.toUnmodifiableMap(
                    l -> decodeVariable(context, l.getFirst()),
                    l -> l.size() == 1 ? Set.of() : decodeList(context, l.get(1)).stream()
                            .map(ev -> (MethodInfo) decodeInfoOutOfContext(context, ev))
                            .collect(Collectors.toUnmodifiableSet())));
            // optional 4th element (#25): the deep-only subset of the modified variables; absent in older files
            Set<Variable> deepOnly = list.size() <= 3 ? new HashSet<>()
                    : decodeList(context, list.get(3)).stream().map(ev -> decodeVariable(context, ev))
                    .filter(modified::containsKey).collect(Collectors.toCollection(HashSet::new));
            return new Result(links, new LinkedVariablesImpl(extra), modified, deepOnly, List.of(), Map.of(), Set.of(),
                    Set.of());
        }

        public EncodedValue encodeResult(Context context, Result result) {
            EncodedValue links = result.links().encode(this, context);
            EncodedValue extra = encodeList(context, result.extra().stream()
                    .map(e -> encodeList(context, List.of(encodeVariable(context, e.getKey()),
                            e.getValue().encode(this, context)))).toList());
            EncodedValue modified = encodeList(context, result.modified().entrySet().stream()
                    .map(e -> encodeList(context, e.getValue() == null
                            ? List.of(encodeVariable(context, e.getKey()))
                            : List.of(encodeVariable(context, e.getKey()),
                            encodeList(context, e.getValue().stream()
                                    .map(mi -> encodeInfoOutOfContext(context, mi)).toList())))).toList());
            if (result.deepOnlyModified().isEmpty()) return encodeList(context, List.of(links, extra, modified));
            EncodedValue deepOnly = encodeList(context, result.deepOnlyModified().stream().sorted()
                    .map(v -> encodeVariable(context, v)).toList());
            return encodeList(context, List.of(links, extra, modified, deepOnly));
        }

        /**
         * Decoded marker variables, by owning element, then by name -- mirroring {@link #encodedMarkerVariables},
         * whose note explains why the name alone is not a key. The reader pushes the same elements the writer
         * did ({@code LoadAnalysisResults.processSub}), so the owner of an unqualified back-reference is simply
         * the element being read.
         */
        private final Map<String, Map<String, MarkerVariable>> decodedMarkerVariables = new HashMap<>();

        private void putDecodedMarkerVariable(Context context, String name, MarkerVariable mv) {
            decodedMarkerVariables.computeIfAbsent(markerScope(ownerOfMarkerVariables(context)), _ -> new HashMap<>())
                    .put(name, mv);
        }

        @Override
        public Variable decodeVariable(Context context, String s, List<EncodedValue> list) {
            if ("D".equals(s) && list.size() == 4) {
                Expression a = decodeExpression(context, list.get(1));
                Expression i = decodeExpression(context, list.get(2));
                ParameterizedType pt = decodeType(context, list.get(3));
                return runtime.newDependentVariable(a, i, pt);
            }
            if ("R".equals(s)) {
                MethodInfo methodInfo = decodeMethodInfo(context, list.get(1));
                return new ReturnVariableImpl(methodInfo);
            }
            if ("O".equals(s)) {
                MethodInfo methodInfo = decodeList(context, list.get(1)).isEmpty() ? null
                        : decodeMethodInfo(context, list.get(1));
                return new ObjectCreationVariableImpl(methodInfo, decodeString(context, list.get(2)),
                        decodeType(context, list.get(3)));
            }
            if ("m".equals(s)) {
                String name = decodeString(context, list.get(1));
                // a third element names the element the definition was written under; without it, this one
                Info owner = list.size() > 2 ? decodeInfoOutOfContext(context, list.get(2))
                        : ownerOfMarkerVariables(context);
                MarkerVariable mv = decodedMarkerVariables.getOrDefault(markerScope(owner), Map.of()).get(name);
                assert mv != null : "Cannot find " + name + " under " + owner;
                return mv;
            }
            if ("a".equals(s)) {
                String name = decodeString(context, list.get(1));
                ParameterizedType type = decodeType(context, list.get(2));
                List<Result> params = decodeList(context, list.get(3)).stream()
                        .map(ev -> decodeResult(context, ev)).toList();
                ParameterInfo source = list.size() > 4
                        ? (ParameterInfo) decodeInfoOutOfContext(context, list.get(4)) : null;
                AppliedFunctionalInterfaceVariable afi = new AppliedFunctionalInterfaceVariable(name, type, runtime,
                        source, params);
                putDecodedMarkerVariable(context, name, afi);
                return afi;
            }
            if ("f".equals(s)) {
                String name = decodeString(context, list.get(1));
                ParameterizedType type = decodeType(context, list.get(2));
                Result result = decodeResult(context, list.get(3));
                FunctionalInterfaceVariable fiv = new FunctionalInterfaceVariable(name, type, runtime, result);
                putDecodedMarkerVariable(context, name, fiv);
                return fiv;
            }
            if ("M".equals(s)) {
                String name = decodeString(context, list.get(1));
                ParameterizedType pt = decodeType(context, list.get(2));
                if (list.size() == 3) {
                    // cache like the other branches: the encode side emits an "m" back-reference for a
                    // repeated marker name, and someValue markers all share the literal name "$_v" —
                    // without this put, the second occurrence hit the "Cannot find" assert above
                    MarkerVariable emptyMv = new MarkerVariable(name, pt, runtime.newEmptyExpression());
                    putDecodedMarkerVariable(context, name, emptyMv);
                    return emptyMv;
                }
                Expression ae = decodeExpression(context, list.get(3));
                MarkerVariable mv = new MarkerVariable(name, pt, ae);
                putDecodedMarkerVariable(context, name, mv);
                return mv;
            }
            return super.decodeVariable(context, s, list);
        }

        @Override
        public Stream<EncodedValue> encodeInfoOutOfContextStream(Context context, TypeAndSorted tas, Info info) {
            if (info instanceof TypeInfo ti && Util.isContainerType(ti)) {
                String s = "U" + ti.simpleName();
                List<EncodedValue> pre = encodeInfoOutOfContextStream(context,
                        null, // tas unused for a type recursion: the base recomputes it from the info
                        ti.compilationUnitOrEnclosingType().getRight()).toList();
                /*
                 In full wherever it occurs, and its fields as ["v", name, type], without their owner, which is the
                 container itself. Once per file, as before 2026-11-12, a type's own values -- encoded after its
                 members' but written before them -- referred to a definition the reader had not met yet; and a field
                 written with its owner as the prefix referred back to the container being read, which the reader had
                 not made yet (guava: about 120 types' results lost). Inside its own definition (a field whose type is
                 the container) it is the short form, 0 fields, which the reader resolves: it registers the container
                 before it reads the fields.
                 */
                if (!containersInProgress.add(ti)) {
                    return Stream.concat(pre.stream(), Stream.of(encodeString(context, s), encodeInt(context, 0)));
                }
                try {
                    List<EncodedValue> post = ti.fields().stream().map(f -> encodeList(context, List.of(
                            encodeString(context, "v"), encodeString(context, f.name()),
                            encodeType(context, f.type())))).toList();
                    return Stream.concat(Stream.concat(pre.stream(), Stream.of(encodeString(context, s))),
                            Stream.concat(Stream.of(encodeInt(context, post.size())), post.stream()));
                } finally {
                    containersInProgress.remove(ti);
                }
            }
            if (info instanceof FieldInfo fi && Util.virtual(fi)) {
                String s = "V" + fi.name();
                return streamSyntheticFieldDetails(context, fi, s);
            }
            if (info instanceof FieldInfo fi) {
                int fieldIndex = fieldIndexOrNegative(fi);
                if (fieldIndex < 0) {
                    // LinkGraph.makeComparableSub has changed the owner...
                    String s = "G" + fi.name();
                    return streamSyntheticFieldDetails(context, fi, s);
                }
            }
            // the base now derives the sub-type index from the enclosing type itself, so we no longer need to
            // pre-compute the enclosing TypeAndSorted here (this used to work around a base-class bug). For a
            // method/field/parameter the base needs the owning type's TypeAndSorted, which is info.typeInfo().
            return super.encodeInfoOutOfContextStream(context, new TypeAndSorted(info.typeInfo()), info);
        }

        private @NotNull Stream<EncodedValue> streamSyntheticFieldDetails(Context context, FieldInfo fi, String s) {
            Stream<EncodedValue> pre = encodeInfoOutOfContextStream(context,
                    null, // tas unused for a type recursion: the base recomputes it from the info
                    fi.owner());
            Stream<EncodedValue> post = Stream.of(encodeType(context, fi.type()));
            return Stream.concat(Stream.concat(pre, Stream.of(encodeString(context, s))), post);
        }

        public int fieldIndexOrNegative(FieldInfo fieldInfo) {
            int i = 0;
            for (FieldInfo fi : fieldInfo.owner().fields()) {
                if (fi == fieldInfo) return i;
                ++i;
            }
            return -1;
        }

        private final Map<String, TypeInfo> virtualTypes = new HashMap<>();

        /*
         A container's name does not identify it: HashMap's §$$ of String and Holder and HashMap's §$$ of Statement and
         List<StatEdge> are both 'java.util.HashMap.§$$'. Reused by name, the second definition read came back as the
         first one's container, with the first one's field types, and the restored links of every method that named it
         were wrong (fernflower, 2026-11-12: JADNameProvider.addParentContext, and from there the independence of
         IVariableNameProvider and ConstantPool after a restore). One object per definition, as the analysis made it.
         */
        private final Map<String, TypeInfo> containersByDefinition = new HashMap<>();

        private static String definitionKey(TypeInfo owner, String name, List<EncodedValue> fieldSpecs) {
            StringBuilder sb = new StringBuilder(owner.fullyQualifiedName()).append('.').append(name);
            for (EncodedValue spec : fieldSpecs) {
                sb.append('|').append(spec instanceof D(Node s) ? s.getSource() : spec.toString());
            }
            return sb.toString();
        }

        @Override
        protected ParameterizedType decodeSimpleType(Context context, StringLiteral sl) {
            String fqn = unquote(sl.getSource()).substring(1);
            TypeInfo virtualType = virtualTypes.get(fqn);
            if (virtualType != null) {
                // virtual types have no type parameters
                return virtualType.asSimpleParameterizedType();
            }
            return super.decodeSimpleType(context, sl);
        }

        @Override
        protected DR decodeInfo(Context context,
                                Info currentType,
                                TypeAndSorted typeAndSorted,
                                char type,
                                String name,
                                List<EncodedValue> list,
                                int pos) {
            if ('U' == type) {
                // decode virtual container type
                List<EncodedValue> tail = list.subList(pos + 1, list.size());
                int numFields = decodeInt(context, tail.getFirst());
                TypeInfo containerType;
                if (numFields == 0) {
                    String fqn = currentType.typeInfo().fullyQualifiedName() + "." + name;
                    containerType = virtualTypes.get(fqn);
                    assert containerType != null : "Cannot find the virtual container type " + fqn + " among "
                                                   + virtualTypes.keySet();
                } else if (isLocalFieldSpec(context, tail.get(1))) {
                    // since 2026-11-12: the container first, registered, then its fields, owned by it
                    TypeInfo owner = currentType.typeInfo();
                    String key = definitionKey(owner, name, tail.subList(1, 1 + numFields));
                    TypeInfo known = containersByDefinition.get(key);
                    if (known != null) {
                        containerType = known;
                    } else {
                        containerType = runtime.newTypeInfo(owner, name.startsWith("§") ? name : "§" + name);
                        containersByDefinition.put(key, containerType);
                        // the short form inside the definition below refers to this one
                        virtualTypes.put(owner.fullyQualifiedName() + "." + name, containerType);
                        virtualTypes.put(containerType.fullyQualifiedName(), containerType);
                        List<FieldInfo> fields = new ArrayList<>(numFields);
                        for (EncodedValue spec : tail.subList(1, 1 + numFields)) {
                            List<EncodedValue> parts = decodeList(context, spec);
                            fields.add(VirtualFieldComputer.newFieldKeepName(runtime, decodeString(context, parts.get(1)),
                                    decodeType(context, parts.get(2)), containerType));
                        }
                        VirtualFieldComputer.completeContainer(runtime, containerType, fields);
                    }
                } else {
                    List<FieldInfo> fields = tail.stream().skip(1).limit(numFields)
                            .map(ev -> (FieldInfo) decodeInfoOutOfContext(context, ev))
                            .toList();
                    String typeName = fields.stream().map(this::nameComponent).collect(Collectors.joining());
                    TypeInfo owner = currentType.typeInfo();
                    // the same container, written in full again: the one made before, one object per definition
                    String key = definitionKey(owner, name, tail.subList(1, 1 + numFields));
                    TypeInfo known = containersByDefinition.get(key);
                    containerType = known != null ? known
                            : VirtualFieldComputer.makeContainer(runtime, owner, typeName, fields);
                    containersByDefinition.put(key, containerType);
                }
                virtualTypes.put(containerType.fullyQualifiedName(), containerType);
                // also under the name the writer gave it, which a later reference uses, and which the name made
                // from the fields here need not equal
                virtualTypes.put(currentType.typeInfo().fullyQualifiedName() + "." + name, containerType);
                return new DR(containerType, new TypeAndSorted(containerType));
            }
            if ('V' == type || 'G' == type) {
                // decode synthetic field (virtual, or created via makeComparableSub)
                TypeInfo owner = currentType.typeInfo();
                assert list.size() == pos + 2; // pre, this, one extra
                ParameterizedType fieldType = decodeType(context, list.get(pos + 1));
                // a container's field is the container's own, now that the container is one object per name
                FieldInfo own = 'V' == type && Util.isContainerType(owner) ? owner.fields().stream()
                        .filter(f -> f.name().equals(name)).findFirst().orElse(null) : null;
                FieldInfo newField = own != null ? own
                        : VirtualFieldComputer.newFieldKeepName(runtime, name, fieldType, owner);
                return new DR(newField, typeAndSorted);
            }
            return super.decodeInfo(context, currentType, typeAndSorted, type, name, list, pos);
        }

        // ["v", name, type]: a field of a container's own definition
        private boolean isLocalFieldSpec(Context context, EncodedValue ev) {
            if (!isList(ev)) return false;
            List<EncodedValue> list = decodeList(context, ev);
            return list.size() == 3 && list.getFirst() instanceof D(Node s) && s instanceof StringLiteral sl
                   && "v".equals(unquote(sl.getSource()));
        }

        private String nameComponent(FieldInfo fieldInfo) {
            String base;
            if (fieldInfo.type().typeParameter() != null) {
                base = fieldInfo.type().typeParameter().simpleName();
            } else {
                base = VirtualFieldComputer.VF_CONCRETE;
            }
            return base + "S".repeat(fieldInfo.type().arrays());
        }

        @Override
        public EncodedValue encodeType(Context context, ParameterizedType type) {
            if (type.typeInfo() != null && Util.isContainerType(type.typeInfo())) {
                // always in full, as encodeInfoOutOfContextStream: the "R" back-reference of older files is still read
                Stream<EncodedValue> name = Stream.of(encodeString(context, "V" + type.typeInfo().fullyQualifiedName()));
                Stream<EncodedValue> arrays = Stream.of(encodeInt(context, type.arrays()));
                Stream<EncodedValue> typeStream = encodeInfoOutOfContextStream(context,
                        null, // tas unused for a type recursion: the base recomputes it from the info
                        type.typeInfo());
                List<EncodedValue> list = Stream.concat(Stream.concat(name, arrays), typeStream).toList();
                return encodeList(context, list);
            }
            return super.encodeType(context, type);
        }

        @Override
        protected ParameterizedType decodeComplexType(Context context, List<EncodedValue> list) {
            if (list.getFirst() instanceof D(Node s) && s instanceof StringLiteral sl) {
                String fqn = unquote(sl.getSource());
                char first = fqn.charAt(0);
                if ('V' == first) {
                    int arrays = decodeInt(context, list.get(1));
                    TypeInfo typeInfo = (TypeInfo) decodeInfoOutOfContext(context, list.subList(2, list.size()));
                    return runtime.newParameterizedType(typeInfo, arrays);
                } else if ('R' == first) {
                    TypeInfo typeInfo = virtualTypes.get(fqn.substring(1));
                    int arrays = decodeInt(context, list.get(1));
                    return runtime.newParameterizedType(typeInfo, arrays);
                }
            }
            return super.decodeComplexType(context, list);
        }
    }

    private static final Map<String, Property> PROPERTY_MAP = Map.of(
            PART_OF_CONSTRUCTION.key(), PART_OF_CONSTRUCTION,
            METHOD_LINKS.key(), METHOD_LINKS,
            LinksImpl.LINKS.key(), LinksImpl.LINKS);
    // recursiveMethod (the call graph's, present on checkpointed methods, task #34) is decoded by the
    // fallback below: it is declared in PropertyImpl and registered in PropertyProviderImpl since 2026-09-30.

    static class P implements Codec.PropertyProvider {
        @Override
        public Property get(String propertyName) {
            Property inMap = PROPERTY_MAP.get(propertyName);
            if (inMap != null) return inMap;
            return PropertyProviderImpl.get(propertyName);
        }
    }

    static class D implements Codec.DecoderProvider {

        @Override
        public BiFunction<Codec.DI, Codec.EncodedValue, Value> decoder(Class<? extends Value> clazz) {
            if (MethodLinkedVariablesImpl.class.equals(clazz)) {
                return (di, ev) -> MethodLinkedVariablesImpl.decode(di.codec(), di.context(), ev);
            }
            if (LinksImpl.class.equals(clazz)) {
                return (di, ev) -> MethodLinkedVariablesImpl.decodeLinks(di.codec(), di.context(), ev);
            }
            // part of construction uses "set of info", which is in ValueImpl.
            return ValueImpl.decoder(clazz);
        }
    }

}

