package net.i2p.router.transport.tcp;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Date;

import net.i2p.data.DataFormatException;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.data.i2np.DeliveryStatusMessage;
import net.i2p.router.OutNetMessage;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;

/**
 * Push out I2NPMessages across the wire
 *
 */
class ConnectionRunner implements Runnable {
    private Log _log;
    private RouterContext _context;
    private TCPConnection _con;
    private boolean _keepRunning;
    private byte _writeBuffer[];
    private long _lastTimeSend;
    
    private static final long TIME_SEND_FREQUENCY = 60*1000;
    
    public ConnectionRunner(RouterContext ctx, TCPConnection con) {
        _context = ctx;
        _log = ctx.logManager().getLog(ConnectionRunner.class);
        _con = con;
        _keepRunning = false;
    }
    
    public void startRunning() {
        _keepRunning = true;
        _writeBuffer = new byte[38*1024]; // expansion factor 
        _lastTimeSend = -1;
        
        String name = "TCP " + _context.routerHash().toBase64().substring(0,6) 
                      + " to " 
                      + _con.getRemoteRouterIdentity().calculateHash().toBase64().substring(0,6);
        I2PThread t = new I2PThread(this, name);
        t.start();
    }
    
    public void stopRunning() {
        _keepRunning = false;
    }
    
    public void run() {
        while (_keepRunning && !_con.getIsClosed()) {
            OutNetMessage msg = _con.getNextMessage();
            if (msg == null) {
                if (_keepRunning)
                    _log.error("next message is null but we should keep running?");
                _con.closeConnection();
                return;
            } else {
                sendMessage(msg);
            }
        }
    }
    
    private void sendMessage(OutNetMessage msg) {
        byte buf[] = _writeBuffer;
        int written = 0;
        try {
            written = msg.getMessageData(_writeBuffer);
        } catch (ArrayIndexOutOfBoundsException aioobe) {
            I2NPMessage m = msg.getMessage();
            if (m != null) {
                buf = m.toByteArray();
                written = buf.length;
            }
        } catch (Exception e) {
            _log.log(Log.CRIT, "getting the message data", e);
            _con.closeConnection();
            return;
        }
        if (written <= 0) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("message " + msg.getMessageType() + "/" + msg.getMessageId() 
                          + " expired before it could be sent");
            
            msg.timestamp("ConnectionRunner.sendMessage noData");
            _con.sent(msg, false, 0);
            return;
        }
        
        msg.timestamp("ConnectionRunner.sendMessage data");

        I2NPMessage timeMessage = null;
        if (_lastTimeSend < _context.clock().now() - TIME_SEND_FREQUENCY) {
            timeMessage = buildTimeMessage();
            _lastTimeSend = _context.clock().now();
        }
        
        OutputStream out = _con.getOutputStream();
        boolean ok = false;
        long before = -1;
        long after = -1;
        try {
            synchronized (out) {
                before = _context.clock().now();
                out.write(buf, 0, written);
                if (timeMessage != null)
                    out.write(timeMessage.toByteArray());
                out.flush();
                after = _context.clock().now();
            }
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Just sent message " + msg.getMessageId() + " to " 
                         + msg.getTarget().getIdentity().getHash().toBase64().substring(0,6)
                         + " writeTime = " + (after-before) +"ms"
                         + " lifetime = " + msg.getLifetime() + "ms");
            
            ok = true;
        } catch (IOException ioe) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Error writing out the message", ioe);
            _con.closeConnection();
        }
        _con.sent(msg, ok, after - before);
    }

    /**
     * Build up a new message to be sent with the current router's time
     *
     */
    private I2NPMessage buildTimeMessage() {
        // holy crap this is a kludge - strapping ourselves into a 
        // deliveryStatusMessage
        DeliveryStatusMessage tm = new DeliveryStatusMessage(_context);
        tm.setArrival(new Date(_context.clock().now()));
        tm.setMessageId(0);
        tm.setUniqueId(0);
        return tm;
    }
}
