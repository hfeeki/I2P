package net.i2p.data;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import net.i2p.crypto.KeyGenerator;
import net.i2p.util.Log;

/**
 * Defines the PrivateKey as defined by the I2P data structure spec.
 * A private key is 256byte Integer. The private key represents only the 
 * exponent, not the primes, which are constant and defined in the crypto spec.
 *
 * @author jrandom
 */
public class PrivateKey extends DataStructureImpl {
    private final static Log _log = new Log(PrivateKey.class);
    private byte[] _data;

    public final static int KEYSIZE_BYTES = 256;

    public PrivateKey() {
        setData(null);
    }
    public PrivateKey(byte data[]) { setData(data); }

    /** constructs from base64
     * @param base64Data a string of base64 data (the output of .toBase64() called
     * on a prior instance of PrivateKey
     */
    public PrivateKey(String base64Data) throws DataFormatException {
        this();
        fromBase64(base64Data);
    }

    public byte[] getData() {
        return _data;
    }

    public void setData(byte[] data) {
        _data = data;
    }
    
    @Override
    public void readBytes(InputStream in) throws DataFormatException, IOException {
        _data = new byte[KEYSIZE_BYTES];
        int read = read(in, _data);
        if (read != KEYSIZE_BYTES) throw new DataFormatException("Not enough bytes to read the private key");
    }
    
    @Override
    public void writeBytes(OutputStream out) throws DataFormatException, IOException {
        if (_data == null) throw new DataFormatException("No data in the private key to write out");
        if (_data.length != KEYSIZE_BYTES)
            throw new DataFormatException("Invalid size of data in the private key [" + _data.length + "]");
        out.write(_data);
    }
    
    @Override
    public boolean equals(Object obj) {
        if ((obj == null) || !(obj instanceof PrivateKey)) return false;
        return DataHelper.eq(_data, ((PrivateKey) obj)._data);
    }
    
    @Override
    public int hashCode() {
        return DataHelper.hashCode(_data);
    }
    
    @Override
    public String toString() {
        StringBuffer buf = new StringBuffer(64);
        buf.append("[PrivateKey: ");
        if (_data == null) {
            buf.append("null key");
        } else {
            buf.append("size: ").append(_data.length);
            //int len = 32;
            //if (len > _data.length) len = _data.length;
            //buf.append(" first ").append(len).append(" bytes: ");
            //buf.append(DataHelper.toString(_data, len));
        }
        buf.append("]");
        return buf.toString();
    }

    /** derives a new PublicKey object derived from the secret contents
     * of this PrivateKey
     * @return a PublicKey object
     */
    public PublicKey toPublic() {
        return KeyGenerator.getPublicKey(this);
    }

}