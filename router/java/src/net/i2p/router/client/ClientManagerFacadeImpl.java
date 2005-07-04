package net.i2p.router.client;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.IOException;
import java.io.Writer;
import java.util.Collections;
import java.util.Iterator;
import java.util.Set;

import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.data.LeaseSet;
import net.i2p.data.i2cp.MessageId;
import net.i2p.data.i2cp.SessionConfig;
import net.i2p.router.ClientManagerFacade;
import net.i2p.router.ClientMessage;
import net.i2p.router.Job;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 * Base impl of the client facade
 *
 * @author jrandom
 */
public class ClientManagerFacadeImpl extends ClientManagerFacade {
    private final static Log _log = new Log(ClientManagerFacadeImpl.class);
    private ClientManager _manager; 
    private RouterContext _context;
    public final static String PROP_CLIENT_PORT = "i2cp.port";
    public final static int DEFAULT_PORT = 7654;
    public final static String PROP_CLIENT_HOST = "i2cp.hostname";
    public final static String DEFAULT_HOST = "127.0.0.1";
    
    public ClientManagerFacadeImpl(RouterContext context) {
        _context = context;
        _manager = null;
        _log.debug("Client manager facade created");
    }
    
    public void startup() {
        _log.info("Starting up the client subsystem");
        String portStr = _context.router().getConfigSetting(PROP_CLIENT_PORT);
        if (portStr != null) {
            try {
                int port = Integer.parseInt(portStr);
                _manager = new ClientManager(_context, port);
            } catch (NumberFormatException nfe) {
                _log.error("Error setting the port: " + portStr + " is not valid", nfe);
                _manager = new ClientManager(_context, DEFAULT_PORT);
            }
        } else {
            _manager = new ClientManager(_context, DEFAULT_PORT);
        }
    }    
    
    public void shutdown() {
        if (_manager != null)
            _manager.shutdown();
    }
    
    public void restart() {
        if (_manager != null)
            _manager.restart();
        else
            startup();
    }
    
    private static final long MAX_TIME_TO_REBUILD = 5*60*1000;
    public boolean verifyClientLiveliness() {
        if (_manager == null) return true;
        boolean lively = true;
        for (Iterator iter = _manager.getRunnerDestinations().iterator(); iter.hasNext(); ) {
            Destination dest = (Destination)iter.next();
            ClientConnectionRunner runner = _manager.getRunner(dest);
            if ( (runner == null) || (runner.getIsDead())) continue;
            LeaseSet ls = runner.getLeaseSet();
            if (ls == null)
                continue; // still building
            long howLongAgo = _context.clock().now() - ls.getEarliestLeaseDate();
            if (howLongAgo > MAX_TIME_TO_REBUILD) {
                if (_log.shouldLog(Log.ERROR))
                    _log.error("Client " + dest.calculateHash().toBase64().substring(0,6)
                               + " has a leaseSet that expired " + DataHelper.formatDuration(howLongAgo));
                lively = false;
            }
        }
        return lively;
    }
    
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
    public void requestLeaseSet(Destination dest, LeaseSet set, long timeout, Job onCreateJob, Job onFailedJob) {
        if (_manager != null)
            _manager.requestLeaseSet(dest, set, timeout, onCreateJob, onFailedJob);
        else
            _log.error("Null manager on requestLeaseSet!");
    }
    
    public void requestLeaseSet(Hash dest, LeaseSet set) { 
        if (_manager != null)
            _manager.requestLeaseSet(dest, set);
    }

    
    /**
     * Instruct the client (or all clients) that they are under attack.  This call
     * does not block.
     *
     * @param dest Destination under attack, or null if all destinations are affected
     * @param reason Why the router thinks that there is abusive behavior
     * @param severity How severe the abuse is, with 0 being not severe and 255 is the max
     */
    public void reportAbuse(Destination dest, String reason, int severity) {
        if (_manager != null)
            _manager.reportAbuse(dest, reason, severity);
        else
            _log.error("Null manager on reportAbuse!");
    }
    /**
     * Determine if the destination specified is managed locally.  This call
     * DOES block.
     * 
     * @param dest Destination to be checked
     */
    public boolean isLocal(Destination dest) {
        if (_manager != null)
            return _manager.isLocal(dest);
        else {
            _log.debug("Null manager on isLocal(dest)!");
            return false;
        }
    }
    /**
     * Determine if the destination specified is managed locally.  This call
     * DOES block.
     * 
     * @param destHash Hash of Destination to be checked
     */
    public boolean isLocal(Hash destHash) {
        if (_manager != null)
            return _manager.isLocal(destHash);
        else {
            _log.debug("Null manager on isLocal(hash)!");
            return false;
        }
    }
    
    public void messageDeliveryStatusUpdate(Destination fromDest, MessageId id, boolean delivered) {
        if (_manager != null)
            _manager.messageDeliveryStatusUpdate(fromDest, id, delivered);
        else
            _log.error("Null manager on messageDeliveryStatusUpdate!");
    }
    
    public void messageReceived(ClientMessage msg) { 
        if (_manager != null)
            _manager.messageReceived(msg); 
        else
            _log.error("Null manager on messageReceived!");
    }
    
    /**
     * Return the client's current config, or null if not connected
     *
     */
    public SessionConfig getClientSessionConfig(Destination dest) {
        if (_manager != null)
            return _manager.getClientSessionConfig(dest);
        else {
            _log.error("Null manager on getClientSessionConfig!");
            return null;
        }
    }
    
    public void renderStatusHTML(Writer out) throws IOException { 
        if (_manager != null)
            _manager.renderStatusHTML(out); 
    }
    
    /**
     * Return the list of locally connected clients
     *
     * @return set of Destination objects
     */
    public Set listClients() {
        if (_manager != null)
            return _manager.listClients();
        else
            return Collections.EMPTY_SET;
    }
}
