package net.i2p.data.i2np;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

import net.i2p.I2PAppContext;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.util.Log;

/**
 * Handle messages from router to router
 *
 */
public class I2NPMessageHandler {
    private Log _log;
    private I2PAppContext _context;
    private long _lastReadBegin;
    private long _lastReadEnd;
    public I2NPMessageHandler(I2PAppContext context) {
        _context = context;
        _log = context.logManager().getLog(I2NPMessageHandler.class);
    }
    
    /**
     * Read an I2NPMessage from the stream and return the fully populated object.
     *
     * @throws IOException if there is an IO problem reading from the stream
     * @throws I2NPMessageException if there is a problem handling the particular
     *          message - if it is an unknown type or has improper formatting, etc.
     */
    public I2NPMessage readMessage(InputStream in) throws IOException, I2NPMessageException {
        try {
            int type = (int)DataHelper.readLong(in, 1);
            _lastReadBegin = System.currentTimeMillis();
            I2NPMessage msg = createMessage(in, type);
            msg.readBytes(in, type);
            _lastReadEnd = System.currentTimeMillis();
            return msg;
        } catch (DataFormatException dfe) {
            throw new I2NPMessageException("Error reading the message", dfe);
        }
    }
    
    public long getLastReadTime() { return _lastReadEnd - _lastReadBegin; }
    
    /**
     * Yes, this is fairly ugly, but its the only place it ever happens.
     *
     */
    private I2NPMessage createMessage(InputStream in, int type) throws IOException, I2NPMessageException {
        switch (type) {
            case DatabaseStoreMessage.MESSAGE_TYPE:
                return new DatabaseStoreMessage(_context);
            case DatabaseLookupMessage.MESSAGE_TYPE:
                return new DatabaseLookupMessage(_context);
            case DatabaseSearchReplyMessage.MESSAGE_TYPE:
                return new DatabaseSearchReplyMessage(_context);
            case DeliveryStatusMessage.MESSAGE_TYPE:
                return new DeliveryStatusMessage(_context);
            case GarlicMessage.MESSAGE_TYPE:
                return new GarlicMessage(_context);
            case TunnelMessage.MESSAGE_TYPE:
                return new TunnelMessage(_context);
            case DataMessage.MESSAGE_TYPE:
                return new DataMessage(_context);
            case SourceRouteReplyMessage.MESSAGE_TYPE:
                return new SourceRouteReplyMessage(_context);
            case TunnelCreateMessage.MESSAGE_TYPE:
                return new TunnelCreateMessage(_context);
            case TunnelCreateStatusMessage.MESSAGE_TYPE:
                return new TunnelCreateStatusMessage(_context);
            default:
                throw new I2NPMessageException("The type "+ type + " is an unknown I2NP message");
        }
    }
    
    public static void main(String args[]) {
        try {
            I2NPMessage msg = new I2NPMessageHandler(I2PAppContext.getGlobalContext()).readMessage(new FileInputStream(args[0]));
            System.out.println(msg);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
