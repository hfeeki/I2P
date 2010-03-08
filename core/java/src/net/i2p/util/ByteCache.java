package net.i2p.util;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;

import net.i2p.I2PAppContext;
import net.i2p.data.ByteArray;

/**
 * Cache the objects frequently used to reduce memory churn.  The ByteArray 
 * should be held onto as long as the  data referenced in it is needed.
 *
 */
public final class ByteCache {
    private final static Map _caches = new HashMap(16);
    /**
     * Get a cache responsible for objects of the given size
     *
     * @param cacheSize how large we want the cache to grow before using on 
     *                  demand allocation
     * @param size how large should the objects cached be?
     */
    public static ByteCache getInstance(int cacheSize, int size) {
        Integer sz = Integer.valueOf(size);
        ByteCache cache = null;
        synchronized (_caches) {
            if (!_caches.containsKey(sz))
                _caches.put(sz, new ByteCache(cacheSize, size));
            cache = (ByteCache)_caches.get(sz);
        }
        cache.resize(cacheSize);
        return cache;
    }
    private Log _log;
    /** list of available and available entries */
    private Queue<ByteArray> _available;
    private int _maxCached;
    private int _entrySize;
    private long _lastOverflow;
    
    /** do we actually want to cache? Warning - setting to false may NPE, this should be fixed or removed */
    private static final boolean _cache = true;
    
    /** how often do we cleanup the cache */
    private static final int CLEANUP_FREQUENCY = 30*1000;
    /** if we haven't exceeded the cache size in 2 minutes, cut our cache in half */
    private static final long EXPIRE_PERIOD = 2*60*1000;
    
    private ByteCache(int maxCachedEntries, int entrySize) {
        if (_cache)
            _available = new LinkedBlockingQueue(maxCachedEntries);
        _maxCached = maxCachedEntries;
        _entrySize = entrySize;
        _lastOverflow = -1;
        SimpleScheduler.getInstance().addPeriodicEvent(new Cleanup(), CLEANUP_FREQUENCY);
        _log = I2PAppContext.getGlobalContext().logManager().getLog(ByteCache.class);
    }
    
    private void resize(int maxCachedEntries) {
        if (_maxCached >= maxCachedEntries) return;
        _maxCached = maxCachedEntries;
        // make a bigger one, move the cached items over
        Queue newLBQ = new LinkedBlockingQueue(maxCachedEntries);
        ByteArray ba;
        while ((ba = _available.poll()) != null)
            newLBQ.offer(ba);
        _available = newLBQ;
    }
    
    /**
     * Get the next available structure, either from the cache or a brand new one
     *
     */
    public final ByteArray acquire() {
        if (_cache) {
            ByteArray rv = _available.poll();
            if (rv != null)
                return rv;
        }
        _lastOverflow = System.currentTimeMillis();
        byte data[] = new byte[_entrySize];
        ByteArray rv = new ByteArray(data);
        rv.setValid(0);
        rv.setOffset(0);
        return rv;
    }
    
    /**
     * Put this structure back onto the available cache for reuse
     *
     */
    public final void release(ByteArray entry) {
        release(entry, true);
    }
    public final void release(ByteArray entry, boolean shouldZero) {
        if (_cache) {
            if ( (entry == null) || (entry.getData() == null) )
                return;
            
            entry.setValid(0);
            entry.setOffset(0);
            
            if (shouldZero)
                Arrays.fill(entry.getData(), (byte)0x0);
            _available.offer(entry);
        }
    }
    
    private class Cleanup implements SimpleTimer.TimedEvent {
        public void timeReached() {
            if (System.currentTimeMillis() - _lastOverflow > EXPIRE_PERIOD) {
                // we haven't exceeded the cache size in a few minutes, so lets
                // shrink the cache 
                    int toRemove = _available.size() / 2;
                    for (int i = 0; i < toRemove; i++)
                        _available.poll();
                    if ( (toRemove > 0) && (_log.shouldLog(Log.DEBUG)) )
                        _log.debug("Removing " + toRemove + " cached entries of size " + _entrySize);
            }
        }
    }
}
