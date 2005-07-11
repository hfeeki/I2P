package net.i2p.util;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2005 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import net.i2p.I2PAppContext;
import net.i2p.crypto.EntropyHarvester;

/**
 * Maintain a set of PRNGs to feed the apps
 */
public class PooledRandomSource extends RandomSource {
    private Log _log;
    protected RandomSource _pool[];
    protected volatile int _nextPool;

    public static final int POOL_SIZE = 16;
    /**
     * How much random data will we precalculate and feed from (as opposed to on demand
     * reseeding, etc).  If this is not set, a default will be used (4MB), or if it is 
     * set to 0, no buffer will be used, otherwise the amount specified will be allocated
     * across the pooled PRNGs.
     *
     */
    public static final String PROP_BUFFER_SIZE = "i2p.prng.totalBufferSizeKB";
    
    public PooledRandomSource(I2PAppContext context) {
        super(context);
        _log = context.logManager().getLog(PooledRandomSource.class);
        initializePool(context);
    }
    
    protected void initializePool(I2PAppContext context) {
        _pool = new RandomSource[POOL_SIZE];
        
        String totalSizeProp = context.getProperty(PROP_BUFFER_SIZE);
        int totalSize = -1;
        if (totalSizeProp != null) {
            try {
                totalSize = Integer.parseInt(totalSizeProp);
            } catch (NumberFormatException nfe) {
                totalSize = -1;
            }
        }
        for (int i = 0; i < POOL_SIZE; i++) {
            if (totalSize < 0)
                _pool[i] = new BufferedRandomSource(context);
            else if (totalSize > 0)
                _pool[i] = new BufferedRandomSource(context, (totalSize*1024) / POOL_SIZE);
            else
                _pool[i] = new RandomSource(context);
            _pool[i].nextBoolean();
        }
        _nextPool = 0;
    }

    private final RandomSource pickPRNG() {
        int i = _nextPool % POOL_SIZE;
        _nextPool = (++_nextPool) % POOL_SIZE;
        return _pool[i];
    }
    
    /**
     * According to the java docs (http://java.sun.com/j2se/1.4.1/docs/api/java/util/Random.html#nextInt(int))
     * nextInt(n) should return a number between 0 and n (including 0 and excluding n).  However, their pseudocode,
     * as well as sun's, kaffe's, and classpath's implementation INCLUDES NEGATIVE VALUES.
     * WTF.  Ok, so we're going to have it return between 0 and n (including 0, excluding n), since 
     * thats what it has been used for.
     *
     */
    public int nextInt(int n) {
        RandomSource prng = pickPRNG();
        synchronized (prng) {
            return prng.nextInt(n);
        }
    }

    /**
     * Like the modified nextInt, nextLong(n) returns a random number from 0 through n,
     * including 0, excluding n.
     */
    public long nextLong(long n) {
        RandomSource prng = pickPRNG();
        synchronized (prng) {
            return prng.nextLong(n);
        }
    }

    /**
     * override as synchronized, for those JVMs that don't always pull via
     * nextBytes (cough ibm)
     */
    public boolean nextBoolean() { 
        RandomSource prng = pickPRNG();
        synchronized (prng) {
            return prng.nextBoolean();
        }
    }
    /**
     * override as synchronized, for those JVMs that don't always pull via
     * nextBytes (cough ibm)
     */
    public void nextBytes(byte buf[]) { 
        RandomSource prng = pickPRNG();
        synchronized (prng) {
            prng.nextBytes(buf);
        }
    }
    /**
     * override as synchronized, for those JVMs that don't always pull via
     * nextBytes (cough ibm)
     */
    public double nextDouble() { 
        RandomSource prng = pickPRNG();
        synchronized (prng) {
            return prng.nextDouble();
        }
    }
    /**
     * override as synchronized, for those JVMs that don't always pull via
     * nextBytes (cough ibm)
     */
    public float nextFloat() { 
        RandomSource prng = pickPRNG();
        synchronized (prng) {
            return prng.nextFloat();
        }
    }
    /**
     * override as synchronized, for those JVMs that don't always pull via
     * nextBytes (cough ibm)
     */
    public double nextGaussian() { 
        RandomSource prng = pickPRNG();
        synchronized (prng) {
            return prng.nextGaussian();
        }
    }
    /**
     * override as synchronized, for those JVMs that don't always pull via
     * nextBytes (cough ibm)
     */
    public int nextInt() { 
        RandomSource prng = pickPRNG();
        synchronized (prng) {
            return prng.nextInt();
        }
    }
    /**
     * override as synchronized, for those JVMs that don't always pull via
     * nextBytes (cough ibm)
     */
    public long nextLong() { 
        RandomSource prng = pickPRNG();
        synchronized (prng) {
            return prng.nextLong();
        }
    }
    
    public EntropyHarvester harvester() { 
        RandomSource prng = pickPRNG();
        return prng.harvester();
    }
    
    public static void main(String args[]) {
        PooledRandomSource prng = new PooledRandomSource(I2PAppContext.getGlobalContext());
        int size = 8*1024*1024;
        try {
            java.io.FileOutputStream out = new java.io.FileOutputStream("random.file");
            for (int i = 0; i < size; i++) {
                out.write(prng.nextInt());
            }
            out.close();
        } catch (Exception e) { e.printStackTrace(); }
        System.out.println("Written to random.file");
    }    
}
