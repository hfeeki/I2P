package net.i2p.data.i2np;
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
import java.util.Date;

import net.i2p.data.DataStructure;

/**
 * Base interface for all I2NP messages
 *
 * @author jrandom
 */
public interface I2NPMessage extends DataStructure {
    final long MAX_ID_VALUE = (1l<<32l)-1l;
    
    /**
     * Read the body into the data structures, after the initial type byte, using
     * the current class's format as defined by the I2NP specification
     *
     * @param in stream to read from
     * @param type I2NP message type
     * @param buffer scratch buffer to be used when reading and parsing
     * @return size of the message read (including headers)
     * @throws I2NPMessageException if the stream doesn't contain a valid message
     *          that this class can read.
     * @throws IOException if there is a problem reading from the stream
     */
    public int readBytes(InputStream in, int type, byte buffer[]) throws I2NPMessageException, IOException;
    public int readBytes(byte data[], int type, int offset) throws I2NPMessageException, IOException;

    /**
     * Read the body into the data structures, after the initial type byte and
     * the uniqueId / expiration, using the current class's format as defined by
     * the I2NP specification
     *
     * @param data data to read from
     * @param offset where to start in the data array
     * @param dataSize how long into the data to read
     * @param type I2NP message type
     * @throws I2NPMessageException if the stream doesn't contain a valid message
     *          that this class can read.
     * @throws IOException if there is a problem reading from the stream
     */
    public void readMessage(byte data[], int offset, int dataSize, int type) throws I2NPMessageException, IOException;
    
    /**
     * Return the unique identifier for this type of I2NP message, as defined in
     * the I2NP spec
     */
    public int getType();
    
    /**
     * Replay resistent message Id
     */
    public long getUniqueId(); 
    
    /**
     * Date after which the message should be dropped (and the associated uniqueId forgotten)
     *
     */
    public Date getMessageExpiration();
    
    /** How large the message is, including any checksums */
    public int getMessageSize();
    
    /** write the message to the buffer, returning the number of bytes written */
    public int toByteArray(byte buffer[]);
}
