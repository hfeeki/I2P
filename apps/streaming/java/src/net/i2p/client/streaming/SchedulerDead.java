package net.i2p.client.streaming;

import net.i2p.I2PAppContext;
import net.i2p.util.Log;

/**
 * <p>Scheduler used for after the final timeout has passed or the
 * connection was reset.</p>
 *
 * <h2>Entry conditions:</h2><ul>
 * <li>Both sides have closed and ACKed and the timeout has passed. <br />
 *     <b>or</b></li>
 * <li>A RESET was received</li>
 * </ul>
 *
 * <h2>Events:</h2><ul>
 * <li>None</li>
 * </ul>
 *
 * <h2>Next states:</h2>
 * <li>None</li>
 * </ul>
 *
 *
 */
class SchedulerDead extends SchedulerImpl {
    private Log _log;
    public SchedulerDead(I2PAppContext ctx) {
        super(ctx);
        _log = ctx.logManager().getLog(SchedulerDead.class);
    }
    
    public boolean accept(Connection con) {
        if (con == null) return false;
        long timeSinceClose = _context.clock().now() - con.getCloseSentOn();
        if (con.getResetSent())
            timeSinceClose = _context.clock().now() - con.getResetSentOn();
        boolean nothingLeftToDo = (con.getCloseSentOn() > 0) &&
                                  (con.getCloseReceivedOn() > 0) &&
                                  (con.getUnackedPacketsReceived() <= 0) &&
                                  (con.getUnackedPacketsSent() <= 0) &&
                                  (con.getResetSent()) &&
                                  (timeSinceClose >= Connection.DISCONNECT_TIMEOUT);
        boolean timedOut = (con.getOptions().getConnectTimeout() < con.getLifetime()) && 
                           con.getSendStreamId() == null &&
                           con.getLifetime() >= Connection.DISCONNECT_TIMEOUT;
        return con.getResetReceived() || nothingLeftToDo || timedOut;
    }
    
    public void eventOccurred(Connection con) {
        con.disconnectComplete();
    }
}
