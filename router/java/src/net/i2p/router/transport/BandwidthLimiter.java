package net.i2p.router.transport;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import net.i2p.data.RouterIdentity;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 * Coordinate the bandwidth limiting across all classes of peers.  Currently
 * treats everything as open (aka doesn't limit)
 *
 */
public class BandwidthLimiter {
    private Log _log;
    protected RouterContext _context;
    
    protected Object _outboundWaitLock = new Object();
    protected Object _inboundWaitLock = new Object();
    
    protected BandwidthLimiter(RouterContext context) {
        _context = context;
        _log = context.logManager().getLog(BandwidthLimiter.class);
    }
    
    public long getTotalSendBytes() { return 0; }
    public long getTotalReceiveBytes() { return 0; }
    
    /**
     * Return how many milliseconds to wait before receiving/processing numBytes from the peer
     */
    public long calculateDelayInbound(RouterIdentity peer, int numBytes) {
        return 0;
    }
    
    /**
     * Return how many milliseconds to wait before sending numBytes to the peer
     */
    public long calculateDelayOutbound(RouterIdentity peer, int numBytes) {
        return 0;
    }
    
    /**
     * Note that numBytes have been read from the peer
     */
    public void consumeInbound(RouterIdentity peer, int numBytes) {}
    /**
     * Note that numBytes have been sent to the peer
     */
    public void consumeOutbound(RouterIdentity peer, int numBytes) {}
    
    /**
     * Delay the required amount of time before returning so that receiving numBytes
     * from the peer will not violate the bandwidth limits
     */
    public void delayInbound(RouterIdentity peer, int numBytes) {
        while (calculateDelayInbound(peer, numBytes) > 0) {
            try {
                synchronized (_inboundWaitLock) {
                    _inboundWaitLock.wait(10*1000);
                }
            } catch (InterruptedException ie) {}
        }
        synchronized (_inboundWaitLock) { _inboundWaitLock.notify(); }
        consumeInbound(peer, numBytes);
    }
    /**
     * Delay the required amount of time before returning so that sending numBytes
     * to the peer will not violate the bandwidth limits
     */
    public void delayOutbound(RouterIdentity peer, int numBytes) {
        while (calculateDelayOutbound(peer, numBytes) > 0) {
            try {
                synchronized (_outboundWaitLock) {
                    _outboundWaitLock.wait(10*1000);
                }
            } catch (InterruptedException ie) {}
        }
        synchronized (_outboundWaitLock) { _outboundWaitLock.notify(); }
        consumeOutbound(peer, numBytes);
    }
}
