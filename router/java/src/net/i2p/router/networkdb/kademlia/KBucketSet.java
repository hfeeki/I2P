package net.i2p.router.networkdb.kademlia;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.math.BigInteger;
import java.util.HashSet;
import java.util.Set;

import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.util.Log;
import net.i2p.I2PAppContext;

/**
 * In memory storage of buckets sorted by the XOR metric from the local router's
 * identity, with bucket N containing routers BASE^N through BASE^N+1 away, up through
 * 2^256 bits away (since we use SHA256).
 *
 */
class KBucketSet {
    private Log _log;
    private I2PAppContext _context;
    private Hash _us;
    private KBucket _buckets[];
    
    public final static int BASE = 8; // must go into KEYSIZE_BITS evenly
    public final static int KEYSIZE_BITS = Hash.HASH_LENGTH * 8;
    public final static int NUM_BUCKETS = KEYSIZE_BITS/BASE;
    private final static BigInteger BASE_I = new BigInteger(""+(1<<BASE));
    public final static int BUCKET_SIZE = 500; // # values at which we start periodic trimming (500 ~= 250Kb)
    
    public KBucketSet(I2PAppContext context, Hash us) {
        _us = us;
        _context = context;
        _log = context.logManager().getLog(KBucketSet.class);
        createBuckets();
    }
    
    /**
     * Return true if the peer is new to the bucket it goes in, or false if it was
     * already in it
     */
    public boolean add(Hash peer) {
        int bucket = pickBucket(peer);
        if (bucket >= 0) {
            int oldSize = _buckets[bucket].getKeyCount();
            int numInBucket = _buckets[bucket].add(peer);
            if (numInBucket > BUCKET_SIZE) {
                // perhaps queue up coallesce job?  naaahh.. lets let 'er grow for now
            }
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Peer " + peer + " added to bucket " + bucket);
            return oldSize != numInBucket;
        } else {
            throw new IllegalArgumentException("Unable to pick a bucket.  wtf!");
        }
    }
    
    public int size() {
        int size = 0;
        for (int i = 0; i < _buckets.length; i++)
            size += _buckets[i].getKeyCount();
        return size;
    }
    
    public boolean remove(Hash entry) {
        int bucket = pickBucket(entry);
        KBucket kbucket = getBucket(bucket);
        boolean removed = kbucket.remove(entry);
        return removed;
    }
    
    public Set getAll() { return getAll(new HashSet()); }
    public Set getAll(Set toIgnore) {
        HashSet all = new HashSet(1024);
        for (int i = 0; i < _buckets.length; i++) {
            all.addAll(_buckets[i].getEntries(toIgnore));
        }
        return all;
    }
    
    public int pickBucket(Hash key) {
        for (int i = 0; i < NUM_BUCKETS; i++) {
            if (_buckets[i].shouldContain(key))
                return i;
        }
        _log.error("Key does not fit in any bucket?! WTF!\nKey  : [" + toString(key.getData()) + "]\nDelta: ["+ toString(DataHelper.xor(_us.getData(), key.getData())) + "]\nUs   : [" + toString(_us.getData()) + "]", new Exception("WTF"));
        displayBuckets();
        return -1;
    }
    
    public KBucket getBucket(int bucket) { return _buckets[bucket]; }
    
    protected void createBuckets() {
        _buckets = new KBucket[NUM_BUCKETS];
        for (int i = 0; i < NUM_BUCKETS-1; i++) {
            _buckets[i] = createBucket(i*BASE, (i+1)*BASE);
        }
        _buckets[NUM_BUCKETS-1] = createBucket(BASE*(NUM_BUCKETS-1), BASE*(NUM_BUCKETS) + 1);
    }
    
    protected KBucket createBucket(int start, int end) {
        KBucket bucket = new KBucketImpl(_context, _us);
        bucket.setRange(start, end);
        _log.debug("Creating a bucket from " + start + " to " + (end));
        return bucket;
    }
    
    public void displayBuckets() {
        _log.info(toString());
    }
    
    public String toString() {
        BigInteger us = new BigInteger(1, _us.getData());
        StringBuffer buf = new StringBuffer(1024);
        buf.append("Bucket set rooted on: ").append(us.toString()).append(" (aka ").append(us.toString(2)).append("): \n");
        for (int i = 0; i < NUM_BUCKETS; i++) {
            buf.append("* Bucket ").append(i).append("/").append(NUM_BUCKETS-1).append(": )\n");
            buf.append("Start:  ").append("2^").append(_buckets[i].getRangeBegin()).append(")\n");
            buf.append("End:    ").append("2^").append(_buckets[i].getRangeEnd()).append(")\n");
            buf.append("Contents:").append(_buckets[i].toString()).append("\n");
        }
        
        return buf.toString();
    }
    
    
    final static String toString(byte b[]) {
        byte val[] = new byte[Hash.HASH_LENGTH];
        if (b.length < 32)
            System.arraycopy(b, 0, val, Hash.HASH_LENGTH-b.length-1, b.length);
        else
            System.arraycopy(b, Hash.HASH_LENGTH-b.length, val, 0, val.length);
        StringBuffer buf = new StringBuffer(KEYSIZE_BITS);
        for (int i = 0; i < val.length; i++) {
            for (int j = 7; j >= 0; j--) {
                boolean bb = (0 != (val[i] & (1<<j)));
                if (bb)
                    buf.append("1");
                else
                    buf.append("0");
            }
            buf.append(" ");
        }
        //    buf.append(Integer.toBinaryString(val[i]));
        return buf.toString();
    }
}
