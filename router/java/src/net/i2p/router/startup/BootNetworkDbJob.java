package net.i2p.router.startup;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import net.i2p.router.JobImpl;
import net.i2p.router.JobQueue;
import net.i2p.router.NetworkDatabaseFacade;
import net.i2p.util.Log;
import net.i2p.router.RouterContext;

public class BootNetworkDbJob extends JobImpl {
    private static Log _log = new Log(BootNetworkDbJob.class);
    
    public BootNetworkDbJob(RouterContext ctx) {
        super(ctx);
    }
    
    public String getName() { return "Boot Network Database"; }
    
    public void runJob() {
        // start up the network database
        
        _context.netDb().startup();
        
        _context.jobQueue().addJob(new StartAcceptingClientsJob(_context));
    }
}
