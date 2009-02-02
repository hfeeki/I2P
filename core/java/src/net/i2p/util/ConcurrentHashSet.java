package net.i2p.util;

import java.util.AbstractSet;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 *  Implement on top of a ConcurrentHashMap with a dummy value.
 *
 *  @author zzz
 */
public class ConcurrentHashSet<E> extends AbstractSet<E> implements Set<E> {
    private static final Object DUMMY = new Object();
    private Map<E, Object> _map;

    public ConcurrentHashSet() {
        _map = new ConcurrentHashMap();
    }
    public ConcurrentHashSet(int capacity) {
        _map = new ConcurrentHashMap(capacity);
    }

    public boolean add(E o) {
        return _map.put(o, DUMMY) == null;
    }

    public void clear() {
        _map.clear();
    }

    public boolean contains(Object o) {
        return _map.containsKey(o);
    }

    public boolean isEmpty() {
        return _map.isEmpty();
    }

    public boolean remove(Object o) {
        return _map.remove(o) != null;
    }

    public int size() {
        return _map.size();
    }

    public Iterator<E> iterator() {
        return _map.keySet().iterator();
    }
}
