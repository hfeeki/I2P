package net.i2p.data.i2np;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Date;

import net.i2p.I2PAppContext;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.DataStructureImpl;
import net.i2p.data.Hash;
import net.i2p.util.Log;

/**
 * Defines the base message implementation.
 *
 * @author jrandom
 */
public abstract class I2NPMessageImpl extends DataStructureImpl implements I2NPMessage {
    private Log _log;
    protected I2PAppContext _context;
    private Date _expiration;
    private long _uniqueId;
    
    public final static long DEFAULT_EXPIRATION_MS = 1*60*1000; // 1 minute by default
    
    public I2NPMessageImpl(I2PAppContext context) {
        _context = context;
        _log = context.logManager().getLog(I2NPMessageImpl.class);
        _expiration = new Date(_context.clock().now() + DEFAULT_EXPIRATION_MS);
        _uniqueId = _context.random().nextLong(MAX_ID_VALUE);
    }
    
    /**
     * Write out the payload part of the message (not including the initial
     * 1 byte type)
     *
     */
    protected abstract byte[] writeMessage() throws I2NPMessageException, IOException;
    
    /**
     * Read the body into the data structures, after the initial type byte and
     * the uniqueId / expiration, using the current class's format as defined by
     * the I2NP specification
     *
     * @param in stream to read from
     * @param type I2NP message type
     * @throws I2NPMessageException if the stream doesn't contain a valid message
     *          that this class can read.
     * @throws IOException if there is a problem reading from the stream
     */
    protected abstract void readMessage(InputStream in, int type) throws I2NPMessageException, IOException;
    
    public void readBytes(InputStream in) throws DataFormatException, IOException {
        try {
            readBytes(in, -1);
        } catch (I2NPMessageException ime) {
            throw new DataFormatException("Bad bytes", ime);
        }
    }
    public void readBytes(InputStream in, int type) throws I2NPMessageException, IOException {
        try {
            if (type < 0)
                type = (int)DataHelper.readLong(in, 1);
            _uniqueId = DataHelper.readLong(in, 4);
            _expiration = DataHelper.readDate(in);
            int size = (int)DataHelper.readLong(in, 2);
            Hash h = new Hash();
            h.readBytes(in);
            byte data[] = new byte[size];
            int read = DataHelper.read(in, data);
            if (read != size)
                throw new I2NPMessageException("Payload is too short [" + read + ", wanted " + size + "]");
            Hash calc = _context.sha().calculateHash(data);
            if (!calc.equals(h))
                throw new I2NPMessageException("Hash does not match");

            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Reading bytes: type = " + type + " / uniqueId : " + _uniqueId + " / expiration : " + _expiration);
            readMessage(new ByteArrayInputStream(data), type);
        } catch (DataFormatException dfe) {
            throw new I2NPMessageException("Error reading the message header", dfe);
        }
    }
    public void writeBytes(OutputStream out) throws DataFormatException, IOException {
        try {
            DataHelper.writeLong(out, 1, getType());
            DataHelper.writeLong(out, 4, _uniqueId);
            DataHelper.writeDate(out, _expiration);
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Writing bytes: type = " + getType() + " / uniqueId : " + _uniqueId + " / expiration : " + _expiration);
            byte[] data = writeMessage();
            DataHelper.writeLong(out, 2, data.length);
            Hash h = _context.sha().calculateHash(data);
            h.writeBytes(out);
            out.write(data);
        } catch (I2NPMessageException ime) {
            throw new DataFormatException("Error writing out the I2NP message data", ime);
        }
    }
    
    /**
     * Replay resistent message Id
     */
    public long getUniqueId() { return _uniqueId; }
    public void setUniqueId(long id) { _uniqueId = id; }
    
    /**
     * Date after which the message should be dropped (and the associated uniqueId forgotten)
     *
     */
    public Date getMessageExpiration() { return _expiration; }
    public void setMessageExpiration(Date exp) { _expiration = exp; }
    
    public int getSize() { 
        try {
            byte msg[] = writeMessage();
            return msg.length + 43;
        } catch (IOException ioe) {
            return 0;
        } catch (I2NPMessageException ime) {
            return 0;
        }
    }
}
