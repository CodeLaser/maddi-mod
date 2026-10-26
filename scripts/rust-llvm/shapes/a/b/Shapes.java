package a.b;

import java.util.ArrayList;
import java.util.List;

public class Shapes {
    // parameters
    public static int size(List<String> list) { return list.size(); }
    public static void setFirst(List<String> list, String s) { list.set(0, s); }
    public static void add(List<String> list, String s) { list.add(s); }
    public static void touchAll(List<Counter> counters) { for (Counter c : counters) c.inc(); }
    public static String first(List<String> list) { return list.get(0); }
    public static List<String> copy(List<String> list) { return new ArrayList<>(list); }
    public static void copyInto(List<String> from, List<String> to) { to.addAll(from); }
    public static int readsOnly(List<Counter> counters) { int s = 0; for (Counter c : counters) s += c.get(); return s; }

    public static int use() {
        List<String> list = new ArrayList<>();
        list.add("a");
        add(list, "b");
        setFirst(list, "c");
        List<Counter> counters = new ArrayList<>();
        counters.add(new Counter());
        touchAll(counters);
        Copier c = new Copier(list);
        return size(list) + first(list).length() + copy(list).size() + readsOnly(counters) + c.get().size();
    }

    public static int useHolder() {
        List<String> list = new ArrayList<>();
        list.add("a");
        Holder h = new Holder(list);
        return h.get().size();
    }
}

class Counter {
    private int n;
    public int get() { return n; }
    public void inc() { n++; }
    public void reset() { n = 0; }
}

class Holder {
    private final List<String> list;
    Holder(List<String> list) { this.list = list; }
    List<String> get() { return list; }
    String head() { return list.get(0); }
    void clear() { list.clear(); }
}

class Copier {
    private final List<String> list;
    Copier(List<String> list) { this.list = new ArrayList<>(list); }
    List<String> get() { return new ArrayList<>(list); }
    int size() { return list.size(); }
}

class Registry {
    private static int count;
    static void register() { count++; }
    static int count() { return count; }
}
