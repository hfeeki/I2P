package net.i2p.router;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.data.LeaseSet;
import net.i2p.data.i2cp.MessageId;
import net.i2p.data.i2cp.SessionConfig;
import net.i2p.router.client.ClientManagerFacadeImpl;

/**
 * Manage all interactions with clients 
 *
 * @author jrandom
 */
public abstract class ClientManagerFacade implements Service {
    
    /**
     * Request that a particular client authorize the Leases contained in the 
     * LeaseSet, after which the onCreateJob is queued up.  If that doesn't occur
     * within the timeout specified, queue up the onFailedJob.  This call does not
     * block.
     *
     * @param dest Destination from which the LeaseSet's authorization should be requested
     * @param set LeaseSet with requested leases - this object must be updated to contain the 
     *            signed version (as well as any changed/added/removed Leases)
     * @param timeout ms to wait before failing
     * @param onCreateJob Job to run after the LeaseSet is authorized
     * @param onFailedJob Job to run after the timeout passes without receiving authorization
     */
    public abstract void requestLeaseSet(Destination dest, LeaseSet set, long timeout, Job onCreateJob, Job onFailedJob);
    /**
     * Instruct the client (or all clients) that they are under attack.  This call
     * does not block.
     *
     * @param dest Destination under attack, or null if all destinations are affected
     * @param reason Why the router thinks that there is abusive behavior
     * @param severity How severe the abuse is, with 0 being not severe and 255 is the max
     */
    public abstract void reportAbuse(Destination dest, String reason, int severity);
    /**
     * Determine if the destination specified is managed locally.  This call
     * DOES block.
     * 
     * @param dest Destination to be checked
     */
    public abstract boolean isLocal(Destination dest);
    /**
     * Determine if the destination hash specified is managed locally.  This call
     * DOES block.
     * 
     * @param destHash Hash of Destination to be checked
     */
    public abstract boolean isLocal(Hash destHash);
    public abstract void messageDeliveryStatusUpdate(Destination fromDest, MessageId id, boolean delivered);
    
    public abstract void messageReceived(ClientMessage msg);
    
    /**
     * Return the client's current config, or null if not connected
     *
     */
    public abstract SessionConfig getClientSessionConfig(Destination dest);
    public String renderStatusHTML() { return ""; }
}

class DummyClientManagerFacade extends ClientManagerFacade {
    private RouterContext _context;
    public DummyClientManagerFacade(RouterContext ctx) {
        _context = ctx;
    }
    public boolean isLocal(Hash destHash) { return true; }
    public boolean isLocal(Destination dest) { return true; }
    public void reportAbuse(Destination dest, String reason, int severity) { }
    public void messageReceived(ClientMessage msg) {}
    public void requestLeaseSet(Destination dest, LeaseSet set, long timeout, 
                                Job onCreateJob, Job onFailedJob) { 
        _context.jobQueue().addJob(onFailedJob);
    }
    public void startup() {}    
    public void stopAcceptingClients() { }
    public void shutdown() {}
    
    public void messageDeliveryStatusUpdate(Destination fromDest, MessageId id, boolean delivered) {}
    
    public SessionConfig getClientSessionConfig(Destination _dest) { return null; }
}
