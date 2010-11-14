package net.i2p.data;

/*
 * Public domain
 */

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import net.i2p.crypto.SHA256Generator;

/**
 * A SimpleDataStructure contains only a single fixed-length byte array.
 * The main reason to do this is to override
 * toByteArray() and fromByteArray(), which are used by toBase64(), fromBase64(),
 * and calculateHash() in DataStructureImpl - otherwise these would go through
 * a wasteful array-to-stream-to-array pass.
 * It also centralizes a lot of common code.
 *
 * Implemented in 0.8.2 and retrofitted over several of the classes in this package.
 *
 * @since 0.8.2
 * @author zzz
 */
public abstract class SimpleDataStructure extends DataStructureImpl {
    protected byte[] _data;
    /** this is just to avoid lots of calls to length() */
    protected final int _length;
    
    /** A new instance with the data set to null. Call readBytes(), setData(), or from ByteArray() after this to set the data */
    public SimpleDataStructure() {
        _length = length();
    }
    
    /** @throws IllegalArgumentException if data is not the legal number of bytes (but null is ok) */
    public SimpleDataStructure(byte data[]) {
        _length = length();
        setData(data);
    }

    /**
     * The legal length of the byte array in this data structure
     * @since 0.8.2
     */
    abstract public int length();

    /**
     * Get the data reference (not a copy)
     * @return the byte array, or null if unset
     */
    public byte[] getData() {
        return _data;
    }

    /**
     * Sets the data.
     * @param data of correct length, or null
     * @throws IllegalArgumentException if data is not the legal number of bytes (but null is ok)
     */
    public void setData(byte[] data) {
        if (data != null && data.length != _length)
            throw new IllegalArgumentException("Bad data length");
        _data = data;
    }

    public void readBytes(InputStream in) throws DataFormatException, IOException {
        _data = new byte[_length];
        int read = read(in, _data);
        if (read != _length) throw new DataFormatException("Not enough bytes to read the data");
    }
    
    public void writeBytes(OutputStream out) throws DataFormatException, IOException {
        if (_data == null) throw new DataFormatException("No data to write out");
        out.write(_data);
    }
    
    @Override
    public String toBase64() {
        if (_data == null)
            return null;
        return Base64.encode(_data);
    }

    @Override
    public void fromBase64(String data) throws DataFormatException {
        if (data == null) throw new DataFormatException("Null data passed in");
        _data = Base64.decode(data);
    }

    /** @return the SHA256 hash of the byte array, or null if the data is null */
    @Override
    public Hash calculateHash() {
        if (_data != null) return SHA256Generator.getInstance().calculateHash(_data);
        return null;
    }

    /**
     * Overridden for efficiency.
     * @return same thing as getData()
     */
    @Override
    public byte[] toByteArray() {
        return _data;
    }

    /**
     * Overridden for efficiency.
     * Does the same thing as getData() but null not allowed.
     * @param data non-null
     * @throws DataFormatException if null or wrong length
     */
    @Override
    public void fromByteArray(byte data[]) throws DataFormatException {
        if (data == null) throw new DataFormatException("Null data passed in");
        if (data.length != _length) throw new DataFormatException("Bad data length");
        _data = data;
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder(64);
        buf.append('[').append(getClass().getSimpleName()).append(": ");
        if (_data == null) {
            buf.append("null");
        } else if (_length <= 32) {
            buf.append(toBase64());
        } else {
            buf.append("size: ").append(Integer.toString(_length));
        }
        buf.append(']');
        return buf.toString();
    }

    /**
     * We assume the data has enough randomness in it, so use the first 4 bytes for speed.
     * If this is not the case, override in the extending class.
     */
    @Override
    public int hashCode() {
        if (_data == null)
            return 0;
        int rv = _data[0];
        for (int i = 1; i < 4; i++)
            rv ^= (_data[i] << (i*8));
        return rv;
    }

    @Override
    public boolean equals(Object obj) {
        if ((obj == null) || !(obj instanceof SimpleDataStructure)) return false;
        return DataHelper.eq(_data, ((SimpleDataStructure) obj)._data);
    }
    
}
