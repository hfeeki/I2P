package net.i2p.client.streaming;

import java.util.ArrayList;
import java.util.List;

import net.i2p.I2PAppContext;
import net.i2p.client.I2PSession;
import net.i2p.client.I2PSessionListener;
import net.i2p.client.I2PSessionException;
import net.i2p.util.Log;

/**
 * Receive raw information from the I2PSession and turn it into
 * Packets, if we can.
 *
 */
public class MessageHandler implements I2PSessionListener {
    private ConnectionManager _manager;
    private I2PAppContext _context;
    private Log _log;
    private List _listeners;
    
    public MessageHandler(I2PAppContext ctx, ConnectionManager mgr) {
        _manager = mgr;
        _context = ctx;
        _listeners = new ArrayList(1);
        _log = ctx.logManager().getLog(MessageHandler.class);
        _context.statManager().createRateStat("stream.packetReceiveFailure", "When do we fail to decrypt or otherwise receive a packet sent to us?", "Stream", new long[] { 60*60*1000, 24*60*60*1000 });
    }
        
    /** Instruct the client that the given session has received a message with
     * size # of bytes.
     * @param session session to notify
     * @param msgId message number available
     * @param size size of the message
     */
    public void messageAvailable(I2PSession session, int msgId, long size) {
        byte data[] = null;
        try {
            data = session.receiveMessage(msgId);
        } catch (I2PSessionException ise) {
            _context.statManager().addRateData("stream.packetReceiveFailure", 1, 0);
            if (_log.shouldLog(Log.WARN))
                _log.warn("Error receiving the message", ise);
            return;
        }
        if (data == null) return;
        Packet packet = new Packet();
        try {
            packet.readPacket(data, 0, data.length);
            _manager.getPacketHandler().receivePacket(packet);
        } catch (IllegalArgumentException iae) {
            _context.statManager().addRateData("stream.packetReceiveFailure", 1, 0);
            if (_log.shouldLog(Log.WARN))
                _log.warn("Received an invalid packet", iae);
        }
    }

    /** Instruct the client that the session specified seems to be under attack
     * and that the client may wish to move its destination to another router.
     * @param session session to report abuse to
     * @param severity how bad the abuse is
     */
    public void reportAbuse(I2PSession session, int severity) {
        if (_log.shouldLog(Log.ERROR))
            _log.error("Abuse reported with severity " + severity);
        _manager.disconnectAllHard();
    }

    /**
     * Notify the client that the session has been terminated
     *
     */
    public void disconnected(I2PSession session) {
        if (_log.shouldLog(Log.ERROR))
            _log.error("I2PSession disconnected");
        _manager.disconnectAllHard();
        
        List listeners = null;
        synchronized (_listeners) {
            listeners = new ArrayList(_listeners);
            _listeners.clear();
        }
        for (int i = 0; i < listeners.size(); i++) {
            I2PSocketManager.DisconnectListener lsnr = (I2PSocketManager.DisconnectListener)listeners.get(i);
            lsnr.sessionDisconnected();
        }
    }

    /**
     * Notify the client that some error occurred
     *
     */
    public void errorOccurred(I2PSession session, String message, Throwable error) {
        if (_log.shouldLog(Log.WARN))
            _log.warn("error occurred: " + message + "- " + error.getMessage()); 
        if (_log.shouldLog(Log.WARN))
            _log.warn("cause", error);
        //_manager.disconnectAllHard();
    }
    
    public void addDisconnectListener(I2PSocketManager.DisconnectListener lsnr) { 
        synchronized (_listeners) {
            _listeners.add(lsnr);
        }
    }
    public void removeDisconnectListener(I2PSocketManager.DisconnectListener lsnr) {
        synchronized (_listeners) {
            _listeners.remove(lsnr);
        }
    }
}
