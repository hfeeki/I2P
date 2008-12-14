/*
 * Released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 */
package net.i2p.router.client;

import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.data.LeaseSet;
import net.i2p.data.i2cp.DestReplyMessage;
import net.i2p.data.i2cp.I2CPMessageException;
import net.i2p.router.JobImpl;
import net.i2p.router.RouterContext;

/**
 * Look up the lease of a hash, to convert it to a Destination for the client
 */
class LookupDestJob extends JobImpl {
    private ClientConnectionRunner _runner;
    private Hash _hash;

    public LookupDestJob(RouterContext context, ClientConnectionRunner runner, Hash h) {
        super(context);
        _runner = runner;
        _hash = h;
    }
    
    public String getName() { return "LeaseSet Lookup for Client"; }
    public void runJob() {
        DoneJob done = new DoneJob(getContext());
        getContext().netDb().lookupLeaseSet(_hash, done, done, 10*1000);
    }

    private class DoneJob extends JobImpl {
        public DoneJob(RouterContext enclosingContext) { 
            super(enclosingContext);
        }
        public String getName() { return "LeaseSet Lookup Reply to Client"; }
        public void runJob() {
            LeaseSet ls = getContext().netDb().lookupLeaseSetLocally(_hash);
            if (ls != null)
                returnDest(ls.getDestination());
            else
                returnDest(null);
        }
    }

    private void returnDest(Destination d) {
        DestReplyMessage msg = new DestReplyMessage(d);
        try {
            _runner.doSend(msg);
        } catch (I2CPMessageException ime) {}
    }
}
