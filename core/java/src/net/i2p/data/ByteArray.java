package net.i2p.data;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.Serializable;

/**
 * Wrap up an array of bytes so that they can be compared and placed in hashes,
 * maps, and the like.
 *
 */
public class ByteArray implements Serializable, Comparable {
    private byte[] _data;
    private int _valid;

    public ByteArray() {
        this(null);
    }

    public ByteArray(byte[] data) {
        _data = data;
        _valid = 0;
    }

    public final byte[] getData() {
        return _data;
    }

    public void setData(byte[] data) {
        _data = data;
    }
    
    /** 
     * how many of the bytes in the array are 'valid'?  
     * this property does not necessarily have meaning for all byte 
     * arrays.
     */
    public final int getValid() { return _valid; }
    public final void setValid(int valid) { _valid = valid; }

    public final boolean equals(Object o) {
        if (o == null) return false;
        if (o instanceof ByteArray) {
            return compare(getData(), ((ByteArray) o).getData());
        }

        try {
            byte val[] = (byte[]) o;
            return compare(getData(), val);
        } catch (Throwable t) {
            return false;
        }
    }

    private static final boolean compare(byte[] lhs, byte[] rhs) {
        return DataHelper.eq(lhs, rhs);
    }
    
    public final int compareTo(Object obj) {
        if (obj.getClass() != getClass()) throw new ClassCastException("invalid object: " + obj);
        return DataHelper.compareTo(_data, ((ByteArray)obj).getData());
    }

    public final int hashCode() {
        return DataHelper.hashCode(getData());
    }

    public final String toString() {
        return DataHelper.toString(getData(), 32);
    }
}