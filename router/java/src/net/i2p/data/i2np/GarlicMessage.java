package net.i2p.data.i2np;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import net.i2p.I2PAppContext;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.util.Log;

/**
 * Defines the wrapped garlic message
 *
 * @author jrandom
 */
public class GarlicMessage extends I2NPMessageImpl {
    private final static Log _log = new Log(GarlicMessage.class);
    public final static int MESSAGE_TYPE = 11;
    private byte[] _data;
    
    public GarlicMessage(I2PAppContext context) {
        super(context);
        setData(null);
    }
    
    public byte[] getData() { return _data; }
    public void setData(byte[] data) { _data = data; }
    
    public void readMessage(InputStream in, int type) throws I2NPMessageException, IOException {
        if (type != MESSAGE_TYPE) throw new I2NPMessageException("Message type is incorrect for this message");
        try {
            long len = DataHelper.readLong(in, 4);
            _data = new byte[(int)len];
            int read = read(in, _data);
            if (read != len)
                throw new I2NPMessageException("Incorrect size read [" + read + " read, expected " + len + "]");
        } catch (DataFormatException dfe) {
            throw new I2NPMessageException("Unable to load the message data", dfe);
        }
    }
    
    /** calculate the message body's length (not including the header and footer */
    protected int calculateWrittenLength() { 
        return 4 + _data.length;
    }
    /** write the message body to the output array, starting at the given index */
    protected int writeMessageBody(byte out[], int curIndex) throws I2NPMessageException {
        byte len[] = DataHelper.toLong(4, _data.length);
        System.arraycopy(len, 0, out, curIndex, 4);
        curIndex += 4;
        System.arraycopy(_data, 0, out, curIndex, _data.length);
        curIndex += _data.length;
        return curIndex;
    }
    
    public int getType() { return MESSAGE_TYPE; }
    
    public int hashCode() {
        return DataHelper.hashCode(getData());
    }
    
    public boolean equals(Object object) {
        if ( (object != null) && (object instanceof GarlicMessage) ) {
            GarlicMessage msg = (GarlicMessage)object;
            return DataHelper.eq(getData(),msg.getData());
        } else {
            return false;
        }
    }
    
    public String toString() {
        StringBuffer buf = new StringBuffer();
        buf.append("[GarlicMessage: ");
        buf.append("\n\tData length: ").append(getData().length).append(" bytes");
        buf.append("]");
        return buf.toString();
    }
}
