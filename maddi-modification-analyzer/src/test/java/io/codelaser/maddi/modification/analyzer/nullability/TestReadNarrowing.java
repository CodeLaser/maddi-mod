package io.codelaser.maddi.modification.analyzer.nullability;

import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import io.codelaser.maddi.cst.api.expression.MethodCall;
import io.codelaser.maddi.cst.api.expression.VariableExpression;
import io.codelaser.maddi.cst.api.variable.Variable;
import io.codelaser.maddi.cst.api.statement.Statement;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link ReadNarrowing}: a read narrowed by the classes that can reach it.
 */
public class TestReadNarrowing extends NullabilityFixtures {

    /*
     CodeLaser/maddi-mod#22 gap 3, nacos ClientOperationEvent: 'super(clientId, null)' in one subclass made the event's
     service nullable, and with it every use, also where the event is known to be another subclass. A local read
     from 'x.getF()' is non-null where 'x instanceof T' holds and no class an instance of T can be leaves null in F.
     */
    @DisplayName("narrowed reads: a getter's value on an instance of a subclass that never stores null (gap 3)")
    @Test
    public void narrowedReads() {
        Report report = run("a.b.EV", """
                package a.b;
                import java.util.*;
                class EV {
                    static class Service { void touch() { } }
                    static class Op {
                        private final String clientId;
                        final Service service;
                        Op(String clientId, Service service) { this.clientId = clientId; this.service = service; }
                        Service getService() { return service; }
                        String getClientId() { return clientId; }
                    }
                    static class Register extends Op { Register(Service s, String id) { super(id, s); } }
                    static class Fuzzy extends Op { Fuzzy(String id) { super(id, null); } }
                    static class Changed { private final Service service; Changed(Service service) { this.service = service; service.touch(); } }
                    static List<Object> published = new ArrayList<>();
                    static void publish(Op other) {
                        Op[] all = { new Register(new Service(), "x"), new Fuzzy("y"), new Op("z", new Service()) };
                        for (Op op : all) { after(op); early(op); pattern(op); inside(op); broad(op); field(op); moved(op, other); }
                    }
                    static void after(Op event) {
                        Service service = event.getService();
                        if (event instanceof Register) {
                            add(service);
                        }
                    }
                    static void early(Op event) {
                        Service service = event.getService();
                        if (!(event instanceof Register)) return;
                        addEarly(service);
                    }
                    static void pattern(Op event) {
                        Service service = event.getService();
                        if (event instanceof Register r && r.getClientId() != null) addPattern(service);
                    }
                    static void inside(Op event) {
                        if (event instanceof Register) { Service s = event.getService(); addInside(s); }
                    }
                    static void broad(Op event) {
                        Service service = event.getService();
                        if (event instanceof Op) addBroad(service);
                    }
                    static void field(Op event) {
                        Service service = event.service;
                        if (event instanceof Register) addField(service);
                    }
                    static void moved(Op event, Op other) {
                        Service service = event.getService();
                        event = other;
                        if (event instanceof Register) addMoved(service);
                    }
                    static void add(Service service) { published.add(new Changed(service)); }
                    static void addEarly(Service service) { published.add(service); }
                    static void addPattern(Service service) { published.add(service); }
                    static void addInside(Service service) { published.add(service); }
                    static void addBroad(Service service) { published.add(service); }
                    static void addField(Service service) { published.add(service); }
                    static void addMoved(Service service) { published.add(service); }
                }
                """);
        System.out.println(explain(report));
        Map<String, String> byName = byName(report);
        String p = "(a.b.EV.Service):0:service";
        assertEquals("Service", byName.get("a.b.EV.add" + p), "after the read, in the branch");
        assertEquals("Service", byName.get("a.b.EV.Changed.service"));
        assertEquals("Service", byName.get("a.b.EV.addEarly" + p), "after 'if (!(x instanceof T)) return'");
        assertEquals("Service", byName.get("a.b.EV.addPattern" + p));
        assertEquals("Service", byName.get("a.b.EV.addInside" + p), "the read inside the branch");
        assertEquals("Service", byName.get("a.b.EV.addField" + p), "a field read");
        assertEquals("Service?", byName.get("a.b.EV.addBroad" + p), "a Fuzzy is an Op");
        assertEquals("Service?", byName.get("a.b.EV.addMoved" + p), "the event tested is not the one read");
        // the declarations keep the null of the Fuzzy events
        assertEquals("Service?", byName.get("a.b.EV.Op.service"));
        assertEquals("Service?", byName.get("a.b.EV.Op.getService()"));

        // a subclass of the narrowed type that stores null, an overriding getter, a non-final field: no narrowing
        Report sub = run("a.b.EW", """
                package a.b;
                import java.util.*;
                class EW {
                    static class Service { }
                    static class Op {
                        private final Service service;
                        private Service mutable;
                        Op(Service service) { this.service = service; this.mutable = service; }
                        Service getService() { return service; }
                        Service getMutable() { return mutable; }
                        void setMutable(Service s) { this.mutable = s; }
                    }
                    static class Register extends Op { Register(Service s) { super(s); } }
                    static class Late extends Register { Late() { super(null); } }
                    static class Other extends Op { Other() { super(new Service()); } }
                    static class Weird extends Other { Weird() { setMutable(null); } @Override Service getService() { return null; } }
                    static List<Object> published = new ArrayList<>();
                    static void publish() { Op[] all = { new Register(new Service()), new Late(), new Other(), new Weird() }; for (Op op : all) handle(op); }
                    static void handle(Op event) {
                        Service service = event.getService();
                        Service mutable = event.getMutable();
                        if (event instanceof Register) addRegister(service);
                        if (event instanceof Other) { addOther(service); addMutable(mutable); }
                    }
                    static void addRegister(Service service) { published.add(service); }
                    static void addOther(Service service) { published.add(service); }
                    static void addMutable(Service service) { published.add(service); }
                }
                """);
        Map<String, String> s = byName(sub);
        assertEquals("Service?", s.get("a.b.EW.addRegister(a.b.EW.Service):0:service"), "a Late is a Register");
        assertEquals("Service?", s.get("a.b.EW.addOther(a.b.EW.Service):0:service"), "Weird overrides the getter");
        assertEquals("Service?", s.get("a.b.EW.addMutable(a.b.EW.Service):0:service"), "not a final field");
    }

    // timefold BavetAbstractConstraintStream (CodeLaser/maddi-mod#27 2): the source constructor stores a null parent,
    // the subclass only chains to the one that dereferences its argument and stores it
    @DisplayName("narrowed reads: a final field of this, in a subclass that never reaches the constructor storing null")
    @Test
    public void narrowedThisField() {
        Report report = run("a.b.BS", """
                package a.b;
                class BS {
                    abstract static class Stream {
                        protected final Stream parent;
                        protected final String name;
                        protected Stream(Stream parent) {
                            this.name = parent.name + "'";
                            this.parent = parent;
                        }
                        protected Stream() {
                            this.parent = null;
                            this.name = "source";
                        }
                        String parentName() {
                            String n = parent.name;
                            return n;
                        }
                    }
                    static final class Source extends Stream {
                        Source() { super(); }
                    }
                    static final class Child extends Stream {
                        Child(Stream parent) { super(parent); }
                        String up() {
                            String n = parent.name;
                            return n;
                        }
                        Runnable later() {
                            return () -> System.out.println(parent.name);
                        }
                    }
                    static String make() { return new Child(new Source()).up(); }
                }
                """);
        TypeInfo stream = parsed.subTypes().stream().filter(t -> "Stream".equals(t.simpleName())).findFirst().orElseThrow();
        TypeInfo child = parsed.subTypes().stream().filter(t -> "Child".equals(t.simpleName())).findFirst().orElseThrow();
        assertEquals(true, parentKnown(report, child.findUniqueMethod("up", 0)), "Child never stores a null parent");
        assertEquals(false, parentKnown(report, stream.findUniqueMethod("parentName", 0)), "a Source may be this");
    }

    // timefold BavetBiGroupBiConstraintStream.buildNode (CodeLaser/maddi-mod#30 1): the narrowedThisField shape three
    // levels deep, with an abstract middle class that has both constructors, the root's parent-taking constructor
    // delegating to a grandparent with a dereference of its argument, and generic types throughout
    @DisplayName("narrowed reads: this's final field through an abstract middle class with both constructors (timefold Bavet)")
    @Test
    public void narrowedThisFieldThreeLevels() {
        Report report = run("a.b.BV", """
                package a.b;
                class BV {
                    enum Semantics { STANDARD, LEGACY }
                    abstract static class Grand<S> {
                        protected final Semantics semantics;
                        protected Grand(Semantics semantics) { this.semantics = semantics; }
                        Semantics getSemantics() { return semantics; }
                    }
                    abstract static class Root<S> extends Grand<S> {
                        protected final Root<S> parent;
                        protected final String factory;
                        protected Root(String factory, Root<S> parent) {
                            super(parent.getSemantics());
                            this.factory = factory;
                            this.parent = parent;
                        }
                        protected Root(String factory, Semantics semantics) {
                            super(semantics);
                            this.factory = factory;
                            this.parent = null;
                        }
                        Object getTupleSource() {
                            if (parent == null) throw new IllegalStateException("no parent");
                            return parent.getTupleSource();
                        }
                        abstract Object buildNode();
                    }
                    abstract static class AbstractBi<S, A, B> extends Root<S> {
                        protected AbstractBi(String factory, Root<S> parent) { super(factory, parent); }
                        protected AbstractBi(String factory, Semantics semantics) { super(factory, semantics); }
                    }
                    static final class ForEach<S, A, B> extends AbstractBi<S, A, B> {
                        ForEach(String factory, Semantics semantics) { super(factory, semantics); }
                        @Override Object getTupleSource() { return this; }
                        @Override Object buildNode() { return "source"; }
                    }
                    static final class Group<S, A, B, X, Y> extends AbstractBi<S, X, Y> {
                        private final String nodeConstructor;
                        Group(String factory, AbstractBi<S, A, B> parent, String nodeConstructor) {
                            super(factory, parent);
                            this.nodeConstructor = nodeConstructor;
                        }
                        @Override Object buildNode() {
                            Object n = parent.getTupleSource();
                            return n;
                        }
                    }
                    static Object make() {
                        var source = new ForEach<String, Integer, Integer>("f", Semantics.STANDARD);
                        return new Group<String, Integer, Integer, Long, Long>("f", source, "c").buildNode();
                    }
                }
                """);
        System.out.println(explain(report));
        TypeInfo group = parsed.subTypes().stream().filter(t -> "Group".equals(t.simpleName())).findFirst().orElseThrow();
        MethodInfo buildNode = group.findUniqueMethod("buildNode", 0);
        Statement read = buildNode.methodBody().statements().getFirst();
        io.codelaser.maddi.cst.api.variable.Variable parent = ((io.codelaser.maddi.cst.api.statement.LocalVariableCreation) read)
                .localVariable().assignmentExpression() instanceof io.codelaser.maddi.cst.api.expression.MethodCall mc
                && mc.object() instanceof VariableExpression ve ? ve.variable() : null;
        assertEquals(true, report.useSites().nonNullAt(read, parent), "Group never stores a null parent");
    }

    // CodeLaser/maddi-mod#30, the diagnostics' open world: outside code calls the constructors it sees and extends the
    // classes it sees, nothing more. A parameter dereferenced before it is stored is non-null whoever passes it.
    @DisplayName("narrowed reads in an open world: by the constructors outside code can call or extend")
    @Test
    public void narrowedThisFieldOpenWorld() {
        Report report = run("a.b.OW", """
                package a.b;
                public class OW {
                    public enum Semantics { A }
                    public abstract static class Grand {
                        protected final Semantics semantics;
                        protected Grand(Semantics semantics) { this.semantics = semantics; }
                    }
                    public abstract static class Root extends Grand {
                        protected final Root parent;
                        // timefold BavetAbstractConstraintStream: the dereference is in the explicit super(...) call
                        protected Root(Root parent) { super(parent.semantics); this.parent = parent; }
                        protected Root(Root parent, Semantics s) { super(s); this.parent = parent; }
                        protected Root(Semantics s) { super(s); this.parent = null; }
                    }
                    public static final class Source extends Root { public Source() { super(Semantics.A); } }
                    public static final class Group extends Root {
                        public Group(Root parent) { super(parent); }
                        public Semantics up() { Semantics n = parent.semantics; return n; }
                    }
                    public static final class Loose extends Root {
                        public Loose(Root parent) { super(parent, Semantics.A); }
                        public Semantics up() { Semantics n = parent.semantics; return n; }
                    }
                    public static class Extensible extends Root {
                        public Extensible(Root parent) { super(parent); }
                        public Semantics up() { Semantics n = parent.semantics; return n; }
                    }
                    static Semantics make() { return new Group(new Source()).up(); }
                }
                """, Policy.NULL_MARKED_FLOW_ONLY.withoutContracts()
                .withWorld(World.OPEN));
        System.out.println(explain(report));
        assertEquals(true, parentReadNonNull(report, "Group"), "every Group constructor dereferences its parent");
        assertEquals(false, parentReadNonNull(report, "Loose"), "outside code may call new Loose(null)");
        assertEquals(true, parentReadNonNull(report, "Extensible"),
                "an unseen subclass of Extensible runs its one constructor, which dereferences the parent");
    }

    // 'Semantics n = parent.semantics;' in up(): is 'this.parent' non-null there?
    private Boolean parentReadNonNull(Report report, String type) {
        TypeInfo t = parsed.subTypes().stream().filter(x -> type.equals(x.simpleName())).findFirst().orElseThrow();
        Statement read = t.findUniqueMethod("up", 0).methodBody().statements().getFirst();
        var value = ((io.codelaser.maddi.cst.api.statement.LocalVariableCreation) read).localVariable().assignmentExpression();
        var parent = ((io.codelaser.maddi.cst.api.variable.FieldReference) ((VariableExpression) value).variable())
                .scopeVariable();
        return report.useSites().nonNullAt(read, parent);
    }

    // timefold AbstractExhaustiveSearchDecider.moveRepository (CodeLaser/maddi-mod#30 3): Mixed stores null, overrides
    // every lifecycle method that reads the field, and never receives the protected expand the others call on this
    @DisplayName("narrowed reads: this's final field in a method no null-storing class can run (timefold exhaustive search)")
    @Test
    public void narrowedThisFieldByMethod() {
        Report report = run("a.b.ED", """
                package a.b;
                import java.util.List;
                class ED {
                    interface Listener { void started(); }
                    abstract static class Decider implements Listener {
                        private final List<String> moves;
                        Decider(List<String> moves) { this.moves = moves; }
                        public void started() {
                            int n = moves.size();
                            System.out.println(n);
                        }
                        protected void expand(int depth) {
                            int n = moves.size();
                            System.out.println(n + depth);
                        }
                        public void done() {
                            int n = moves.size();
                            System.out.println(n);
                        }
                        public void report() {
                            int n = moves.size();
                            System.out.println(n);
                        }
                        public abstract void expand();
                    }
                    static final class Basic extends Decider {
                        Basic(List<String> moves) { super(moves); }
                        @Override public void expand() { expand(1); }
                    }
                    static final class Plain extends Decider {
                        Plain(List<String> moves) { super(moves); }
                        @Override public void expand() { expand(2); }
                        @Override public void done() { super.done(); }
                    }
                    static final class Mixed extends Decider {
                        private final Decider current;
                        Mixed(Decider current) { super(null); this.current = current; }
                        @Override public void started() { current.started(); }
                        @Override public void expand() { current.expand(); }
                        @Override public void done() { current.done(); }
                    }
                    static void run(Listener l, Decider d) { l.started(); d.expand(); d.done(); }
                    static void make() {
                        var b = new Basic(List.of("m"));
                        run(b, b);
                        b.report();
                        var m = new Mixed(new Plain(List.of("p")));
                        run(m, m);
                    }
                }
                """);
        TypeInfo decider = parsed.subTypes().stream().filter(t -> "Decider".equals(t.simpleName())).findFirst().orElseThrow();
        assertEquals(true, movesKnown(report, decider.findUniqueMethod("started", 0)), "Mixed overrides started");
        assertEquals(true, movesKnown(report, decider.findUniqueMethod("expand", 1)), "called on this by Basic and Plain only");
        assertEquals(false, movesKnown(report, decider.findUniqueMethod("done", 0)),
                "a super call to done: not excluded by Mixed's override (conservative)");
        assertEquals(false, movesKnown(report, decider.findUniqueMethod("report", 0)),
                "public: reflection may call it on a Mixed, although only a Basic receives it here");
    }

    private static boolean movesKnown(Report report, MethodInfo method) {
        Statement read = method.methodBody().statements().getFirst();
        io.codelaser.maddi.cst.api.expression.Expression size = ((io.codelaser.maddi.cst.api.statement.LocalVariableCreation) read).localVariable().assignmentExpression();
        Variable moves = ((VariableExpression) ((MethodCall) size).object()).variable();
        return report.useSites().nonNullAt(read, moves);
    }

    // the facts on 'parent' when the method's 'String n = parent.name;' starts
    private static boolean parentKnown(Report report, MethodInfo method) {
        Statement read = method.methodBody().statements().getFirst();
        io.codelaser.maddi.cst.api.variable.FieldReference name = (io.codelaser.maddi.cst.api.variable.FieldReference)
                ((VariableExpression) ((io.codelaser.maddi.cst.api.statement.LocalVariableCreation) read).localVariable()
                        .assignmentExpression()).variable();
        return report.useSites().nonNullAt(read, ((VariableExpression) name.scope()).variable());
    }
}
