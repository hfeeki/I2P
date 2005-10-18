package net.i2p.crypto;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.SessionKey;

import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.digests.MD5Digest;
import org.bouncycastle.crypto.macs.HMac;

/**
 * Calculate the HMAC-SHA256 of a key+message.  All the good stuff occurs
 * in {@link org.bouncycastle.crypto.macs.HMac} and 
 * {@link org.bouncycastle.crypto.digests.SHA256Digest}.  Alternately, if 
 * the context property "i2p.HMACMD5" is set to true, then this whole HMAC
 * generator will be transformed into HMACMD5, maintaining the same size and
 * using {@link org.bouncycastle.crypto.digests.MD5Digest}.
 *
 */
public class HMACSHA256Generator {
    private I2PAppContext _context;
    /** set of available HMAC instances for calculate */
    private List _available;
    /** set of available byte[] buffers for verify */
    private List _availableTmp;
    private boolean _useMD5;
    private int _macSize;
    
    public static final boolean DEFAULT_USE_MD5 = true;
    
    public HMACSHA256Generator(I2PAppContext context) {
        _context = context;
        _available = new ArrayList(32);
        _availableTmp = new ArrayList(32);
        if ("true".equals(context.getProperty("i2p.HMACMD5", Boolean.toString(DEFAULT_USE_MD5).toLowerCase())))
            _useMD5 = true;
        else
            _useMD5 = false;
        if ("true".equals(context.getProperty("i2p.HMACBrokenSize", "true")))
            _macSize = 32;
        else
            _macSize = (_useMD5 ? 16 : 32);
    }
    
    public static HMACSHA256Generator getInstance() {
        return I2PAppContext.getGlobalContext().hmac();
    }
    
    /**
     * Calculate the HMAC of the data with the given key
     */
    public Hash calculate(SessionKey key, byte data[]) {
        if ((key == null) || (key.getData() == null) || (data == null))
            throw new NullPointerException("Null arguments for HMAC");
        byte rv[] = new byte[Hash.HASH_LENGTH];
        calculate(key, data, 0, data.length, rv, 0);
        return new Hash(rv);
    }
    
    /**
     * Calculate the HMAC of the data with the given key
     */
    /*
    public Hash calculate(SessionKey key, byte data[], int offset, int length) {
        if ((key == null) || (key.getData() == null) || (data == null))
            throw new NullPointerException("Null arguments for HMAC");
        
        HMac mac = acquire();
        mac.init(key.getData());
        mac.update(data, offset, length);
        byte rv[] = new byte[Hash.HASH_LENGTH];
        mac.doFinal(rv, 0);
        release(mac);
        return new Hash(rv);
    }
    */
    
    /**
     * Calculate the HMAC of the data with the given key
     */
    public void calculate(SessionKey key, byte data[], int offset, int length, byte target[], int targetOffset) {
        if ((key == null) || (key.getData() == null) || (data == null))
            throw new NullPointerException("Null arguments for HMAC");
        
        HMac mac = acquire();
        mac.init(key.getData());
        mac.update(data, offset, length);
        //byte rv[] = new byte[Hash.HASH_LENGTH];
        mac.doFinal(target, targetOffset);
        release(mac);
        //return new Hash(rv);
    }
    
    /**
     * Verify the MAC inline, reducing some unnecessary memory churn.
     *
     * @param key session key to verify the MAC with
     * @param curData MAC to verify
     * @param curOffset index into curData to MAC
     * @param curLength how much data in curData do we want to run the HMAC over
     * @param origMAC what do we expect the MAC of curData to equal
     * @param origMACOffset index into origMAC
     * @param origMACLength how much of the MAC do we want to verify
     */
    public boolean verify(SessionKey key, byte curData[], int curOffset, int curLength, byte origMAC[], int origMACOffset, int origMACLength) {
        if ((key == null) || (key.getData() == null) || (curData == null))
            throw new NullPointerException("Null arguments for HMAC");
        
        HMac mac = acquire();
        mac.init(key.getData());
        mac.update(curData, curOffset, curLength);
        byte rv[] = acquireTmp();
        //byte rv[] = new byte[Hash.HASH_LENGTH];
        mac.doFinal(rv, 0);
        release(mac);
        
        boolean eq = DataHelper.eq(rv, 0, origMAC, origMACOffset, origMACLength);
        releaseTmp(rv);
        return eq;
    }
    
    private HMac acquire() {
        synchronized (_available) {
            if (_available.size() > 0)
                return (HMac)_available.remove(0);
        }
        if (_useMD5)
            return new HMac(new MD5Digest());
        else
        return new HMac(new SHA256Digest());
    }
    private void release(HMac mac) {
        synchronized (_available) {
            if (_available.size() < 64)
                _available.add(mac);
        }
    }

    // temp buffers for verify(..)
    private byte[] acquireTmp() {
        byte rv[] = null;
        synchronized (_availableTmp) {
            if (_availableTmp.size() > 0)
                rv = (byte[])_availableTmp.remove(0);
        }
        if (rv != null)
            Arrays.fill(rv, (byte)0x0);
        else
            rv = new byte[Hash.HASH_LENGTH];
        return rv;
    }
    private void releaseTmp(byte tmp[]) {
        synchronized (_availableTmp) {
            if (_availableTmp.size() < 64)
                _availableTmp.add((Object)tmp);
        }
    }
}