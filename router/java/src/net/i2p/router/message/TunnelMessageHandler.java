package net.i2p.router.message;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import net.i2p.data.Hash;
import net.i2p.data.RouterIdentity;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.data.i2np.TunnelMessage;
import net.i2p.router.HandlerJobBuilder;
import net.i2p.router.Job;
import net.i2p.router.RouterContext;

/**
 * HandlerJobBuilder to build jobs to handle TunnelMessages
 *
 */
public class TunnelMessageHandler implements HandlerJobBuilder {
    private RouterContext _context;
    
    public TunnelMessageHandler(RouterContext context) {
        _context = context;
    }
    public Job createJob(I2NPMessage receivedMessage, RouterIdentity from, Hash fromHash) {
        HandleTunnelMessageJob job = new HandleTunnelMessageJob(_context, (TunnelMessage)receivedMessage, from, fromHash);
        return job;
    }
    
}
