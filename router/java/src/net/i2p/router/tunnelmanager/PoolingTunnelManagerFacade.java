package net.i2p.router.tunnelmanager;

import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.data.TunnelId;
import net.i2p.data.i2np.TunnelCreateMessage;
import net.i2p.router.ClientTunnelSettings;
import net.i2p.router.InNetMessagePool;
import net.i2p.router.TunnelInfo;
import net.i2p.router.TunnelManagerFacade;
import net.i2p.router.TunnelSelectionCriteria;
import net.i2p.stat.StatManager;
import net.i2p.util.Clock;
import net.i2p.util.Log;

/**
 * Main interface to the pool
 *
 */
public class PoolingTunnelManagerFacade extends TunnelManagerFacade {
    private final static Log _log = new Log(PoolingTunnelManagerFacade.class);
    private TunnelPool _pool;
    private TunnelTestManager _testManager;
    
    static {
	StatManager.getInstance().createFrequencyStat("tunnel.acceptRequestFrequency", "How often do we accept requests to join a tunnel?", "Tunnels", new long[] { 60*1000l, 60*60*1000l, 24*60*60*1000l });
	StatManager.getInstance().createFrequencyStat("tunnel.rejectRequestFrequency", "How often do we reject requests to join a tunnel?", "Tunnels", new long[] { 60*1000l, 60*60*1000l, 24*60*60*1000l });
    }
    
    public PoolingTunnelManagerFacade() {
	super();
	InNetMessagePool.getInstance().registerHandlerJobBuilder(TunnelCreateMessage.MESSAGE_TYPE, new TunnelCreateMessageHandler());
    }
    
    public void startup() {
	if (_pool == null)
	    _pool = new TunnelPool();
	_pool.startup();
	_testManager = new TunnelTestManager(_pool);
    }
    
    public void shutdown() {
	_pool.shutdown();
	_testManager.stopTesting();
	_testManager = null;
    }
    
    /** 
     * React to a request to join the specified tunnel.
     *
     * @return true if the router will accept participation, else false.
     */
    public boolean joinTunnel(TunnelInfo info) {
	if (info == null) {
	    _log.error("Null tunnel", new Exception("Null tunnel"));
	    StatManager.getInstance().updateFrequency("tunnel.rejectRequestFrequency");
	    return false;
	}
	if (info.getSettings() == null) {
	    _log.error("Null settings!", new Exception("settings are null"));
	    StatManager.getInstance().updateFrequency("tunnel.rejectRequestFrequency");
	    return false;
	}
	if (info.getSettings().getExpiration() == 0) {
	    _log.info("No expiration for tunnel " + info.getTunnelId().getTunnelId(), new Exception("No expiration"));
	    StatManager.getInstance().updateFrequency("tunnel.rejectRequestFrequency");
	    return false;
	} else {
	    if (info.getSettings().getExpiration() < Clock.getInstance().now()) {
		_log.warn("Already expired - " + new Date(info.getSettings().getExpiration()), new Exception("Already expired"));
		StatManager.getInstance().updateFrequency("tunnel.rejectRequestFrequency");
		return false;
	    }
	}
	
	_log.debug("Joining tunnel: " + info);
	boolean ok = _pool.addParticipatingTunnel(info);
	if (!ok)
	    StatManager.getInstance().updateFrequency("tunnel.rejectRequestFrequency");
	else
	    StatManager.getInstance().updateFrequency("tunnel.acceptRequestFrequency");
	return ok;
    }
    /**
     * Retrieve the information related to a particular tunnel
     *
     */
    public TunnelInfo getTunnelInfo(TunnelId id) {
	return _pool.getTunnelInfo(id);
    }
    /**
     * Retrieve a set of tunnels from the existing ones for various purposes
     */
    public List selectOutboundTunnelIds(TunnelSelectionCriteria criteria) {
	return PoolingTunnelSelector.selectOutboundTunnelIds(_pool, criteria);
    }
    /**
     * Retrieve a set of tunnels from the existing ones for various purposes
     */
    public List selectInboundTunnelIds(TunnelSelectionCriteria criteria) {
	return PoolingTunnelSelector.selectInboundTunnelIds(_pool, criteria);
    }
    
    /**
     * Make sure appropriate outbound tunnels are in place, builds requested
     * inbound tunnels, then fire off a job to ask the ClientManagerFacade to 
     * validate the leaseSet, then publish it in the network database.
     *
     */
    public void createTunnels(Destination destination, ClientTunnelSettings clientSettings, long timeoutMs) {
	ClientTunnelPool pool = _pool.getClientPool(destination);
	if (pool != null) {
	    pool.setClientSettings(clientSettings);
	} else {
	    _pool.createClientPool(destination, clientSettings);
	}
    }
    
    /**
     * Called when a peer becomes unreachable - go through all of the current
     * tunnels and rebuild them if we can, or drop them if we can't.
     *
     */
    public void peerFailed(Hash peer) {
	int numFailed = 0;
	for (Iterator iter = _pool.getManagedTunnelIds().iterator(); iter.hasNext(); ) {
	    TunnelId id = (TunnelId)iter.next();
	    TunnelInfo info = (TunnelInfo)_pool.getTunnelInfo(id);
	    if (isParticipant(info, peer)) {
		_log.info("Peer " + peer.toBase64() + " failed and they participate in tunnel " + id.getTunnelId() + ".  Marking the tunnel as not ready!");
		info.setIsReady(false);
		numFailed++;
		
		long lifetime = Clock.getInstance().now() - info.getCreated();
		StatManager.getInstance().addRateData("tunnel.failAfterTime", lifetime, lifetime);
	    }
	}
	
	_log.info("On peer " + peer.toBase64() + " failure, " + numFailed + " tunnels were killed");
    }
    
    private boolean isParticipant(TunnelInfo info, Hash peer) {
	if ( (info == null) || (peer == null) ) return false;
	TunnelInfo cur = info;
	while (cur != null) {
	    if (peer.equals(cur.getThisHop())) return true;
	    if (peer.equals(cur.getNextHop())) return true;
	    cur = cur.getNextHopInfo();
	}
	return false;
    }
    
    /**
     * True if the peer currently part of a tunnel
     *
     */
    public boolean isInUse(Hash peer) {
	if (isInUse(peer, _pool.getManagedTunnelIds())) {
	    if (_log.shouldLog(Log.INFO))
		_log.debug("Peer is in a managed tunnel: " + peer.toBase64());
	    return true;
	}
	if (isInUse(peer, _pool.getPendingTunnels())) {
	    if (_log.shouldLog(Log.INFO))
		_log.debug("Peer is in a pending tunnel: " + peer.toBase64());
	    return true;
	}
	if (isInUse(peer, _pool.getParticipatingTunnels())) {
	    if (_log.shouldLog(Log.INFO))
		_log.debug("Peer is in a participating tunnel: " + peer.toBase64());
	    return true;
	}
	return false;
    }
    
    private boolean isInUse(Hash peer, Set tunnelIds) {
	for (Iterator iter = tunnelIds.iterator(); iter.hasNext(); ) {
	    TunnelId id = (TunnelId)iter.next();
	    TunnelInfo info = _pool.getTunnelInfo(id);
	    if (isParticipant(info, peer))
		return true;
	}
	return false;
    }
    
    /**
     * Aint she pretty?
     *
     */
    public String renderStatusHTML() {
	if (_pool != null)
	    return _pool.renderStatusHTML();
	else
	    return "<h2>Tunnel Manager not initialized</h2>\n";
    }
}
