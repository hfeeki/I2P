package net.i2p.client.streaming;

import net.i2p.I2PAppContext;
import net.i2p.util.Log;

/**
 * <p>Scheduler used after we've locally done a hard disconnect, 
 * but the final timeout hasn't passed.</p>
 *
 * <h2>Entry conditions:</h2><ul>
 * <li>Locally disconnected hard.</li>
 * <li>Less than the final timeout period has passed since the last ACK.</li>
 * </ul>
 *
 * <h2>Events:</h2><ul>
 * <li>Packets received</li>
 * <li>RESET received</li>
 * <li>Message sending fails (error talking to the session)</li>
 * </ul>
 *
 * <h2>Next states:</h2>
 * <li>{@link SchedulerDead dead} - after the final timeout passes</li>
 * </ul>
 *
 *
 */
class SchedulerHardDisconnected extends SchedulerImpl {
    private Log _log;
    public SchedulerHardDisconnected(I2PAppContext ctx) {
        super(ctx);
        _log = ctx.logManager().getLog(SchedulerHardDisconnected.class);
    }
    
    public boolean accept(Connection con) {
        if (con == null) return false;
        long timeSinceClose = _context.clock().now() - con.getCloseSentOn();
        boolean ok = (con.getHardDisconnected()) && 
                     (timeSinceClose < Connection.DISCONNECT_TIMEOUT);
        return ok;
    }
    
    public void eventOccurred(Connection con) {
        // noop.  we do the timeout through the simpleTimer anyway
    }
}
