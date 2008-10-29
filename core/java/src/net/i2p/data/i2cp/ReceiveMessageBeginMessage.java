package net.i2p.data.i2cp;

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

import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.util.Log;

/**
 * Defines the message a client sends to a router when asking the 
 * router to start sending a message to it.
 *
 * @author jrandom
 */
public class ReceiveMessageBeginMessage extends I2CPMessageImpl {
    private final static Log _log = new Log(ReceiveMessageBeginMessage.class);
    public final static int MESSAGE_TYPE = 6;
    private long _sessionId;
    private long _messageId;

    public ReceiveMessageBeginMessage() {
        setSessionId(-1);
        setMessageId(-1);
    }

    public long getSessionId() {
        return _sessionId;
    }

    public void setSessionId(long id) {
        _sessionId = id;
    }

    public long getMessageId() {
        return _messageId;
    }

    public void setMessageId(long id) {
        _messageId = id;
    }

    protected void doReadMessage(InputStream in, int size) throws I2CPMessageException, IOException {
        try {
            _sessionId = DataHelper.readLong(in, 2);
            _messageId = DataHelper.readLong(in, 4);
        } catch (DataFormatException dfe) {
            throw new I2CPMessageException("Unable to load the message data", dfe);
        }
    }

    protected byte[] doWriteMessage() throws I2CPMessageException, IOException {
        throw new UnsupportedOperationException("This shouldn't be called... use writeMessage(out)");
    }

    
    /** 
     * Override to reduce mem churn
     * @throws IOException 
     */
    @Override
    public void writeMessage(OutputStream out) throws I2CPMessageException, IOException {
        int len = 2 + // sessionId
                  4; // messageId
        
        try {
            DataHelper.writeLong(out, 4, len);
            DataHelper.writeLong(out, 1, getType());
            DataHelper.writeLong(out, 2, _sessionId);
            DataHelper.writeLong(out, 4, _messageId);
        } catch (DataFormatException dfe) {
            throw new I2CPMessageException("Unable to write the message length or type", dfe);
        }
    }
    
    
    public int getType() {
        return MESSAGE_TYPE;
    }

    @Override
    public boolean equals(Object object) {
        if ((object != null) && (object instanceof ReceiveMessageBeginMessage)) {
            ReceiveMessageBeginMessage msg = (ReceiveMessageBeginMessage) object;
            return DataHelper.eq(getSessionId(), msg.getSessionId())
                   && DataHelper.eq(getMessageId(), msg.getMessageId());
        }
            
        return false;
    }

    @Override
    public String toString() {
        StringBuffer buf = new StringBuffer();
        buf.append("[ReceiveMessageBeginMessage: ");
        buf.append("\n\tSessionId: ").append(getSessionId());
        buf.append("\n\tMessageId: ").append(getMessageId());
        buf.append("]");
        return buf.toString();
    }
}