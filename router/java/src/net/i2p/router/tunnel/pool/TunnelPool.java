package net.i2p.router.tunnel.pool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Properties;

import net.i2p.data.Hash;
import net.i2p.data.Lease;
import net.i2p.data.LeaseSet;
import net.i2p.data.TunnelId;

import net.i2p.router.JobImpl;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelPoolSettings;
import net.i2p.router.TunnelInfo;
import net.i2p.router.tunnel.HopConfig;
import net.i2p.util.Log;

/**
 *
 */
public class TunnelPool {
    private RouterContext _context;
    private Log _log;
    private TunnelPoolSettings _settings;
    private ArrayList _tunnels;
    private TunnelPeerSelector _peerSelector;
    private TunnelPoolManager _manager;
    private boolean _alive;
    private long _lifetimeProcessed;
    private TunnelInfo _lastSelected;
    private long _lastSelectionPeriod;
    private int _expireSkew;
    
    public TunnelPool(RouterContext ctx, TunnelPoolManager mgr, TunnelPoolSettings settings, TunnelPeerSelector sel) {
        _context = ctx;
        _log = ctx.logManager().getLog(TunnelPool.class);
        _manager = mgr;
        _settings = settings;
        _tunnels = new ArrayList(settings.getLength() + settings.getBackupQuantity());
        _peerSelector = sel;
        _alive = false;
        _lastSelectionPeriod = 0;
        _lastSelected = null;
        _lifetimeProcessed = 0;
        _expireSkew = _context.random().nextInt(90*1000);
        refreshSettings();
    }
    
    public void startup() {
        _alive = true;
        _manager.getExecutor().repoll();
        if (_settings.isInbound() && (_settings.getDestination() != null) ) {
            // we just reconnected and didn't require any new tunnel builders.
            // however, we /do/ want a leaseSet, so build one
            LeaseSet ls = null;
            synchronized (_tunnels) {
                ls = locked_buildNewLeaseSet();
            }

            if (ls != null)
                _context.clientManager().requestLeaseSet(_settings.getDestination(), ls);
        }
    }
    
    public void shutdown() {
        _alive = false;
        _lastSelectionPeriod = 0;
        _lastSelected = null;
    }

    TunnelPoolManager getManager() { return _manager; }
    
    void refreshSettings() {
        if (_settings.getDestination() != null) {
            return; // don't override client specified settings
        } else {
            if (_settings.isExploratory()) {
                Properties props = _context.router().getConfigMap();
                if (_settings.isInbound())
                    _settings.readFromProperties(TunnelPoolSettings.PREFIX_INBOUND_EXPLORATORY, props);
                else
                    _settings.readFromProperties(TunnelPoolSettings.PREFIX_OUTBOUND_EXPLORATORY, props);
            }
        }
    }
    
    /** 
     * when selecting tunnels, stick with the same one for a brief 
     * period to allow batching if we can.
     */
    private long curPeriod() {
        long period = _context.clock().now();
        long ms = period % 1000;
        if (ms > 500)
            period = period - ms + 500;
        else
            period = period - ms;
        return period;
    }
    
    /**
     * Pull a random tunnel out of the pool.  If there are none available but
     * the pool is configured to allow 0hop tunnels, this builds a fake one
     * and returns it.
     *
     */
    public TunnelInfo selectTunnel() { return selectTunnel(true); }
    private TunnelInfo selectTunnel(boolean allowRecurseOnFail) {
        boolean avoidZeroHop = ((getSettings().getLength() + getSettings().getLengthVariance()) > 0);
        
        long period = curPeriod();
        synchronized (_tunnels) {
            if (_lastSelectionPeriod == period) {
                if ( (_lastSelected != null) && 
                     (_lastSelected.getExpiration() > period) &&
                     (_tunnels.contains(_lastSelected)) )
                    return _lastSelected;
            }
            _lastSelectionPeriod = period;
            _lastSelected = null;

            if (_tunnels.size() <= 0) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn(toString() + ": No tunnels to select from");
            } else {
                Collections.shuffle(_tunnels, _context.random());
                
                // if there are nonzero hop tunnels and the zero hop tunnels are fallbacks, 
                // avoid the zero hop tunnels
                if (avoidZeroHop) {
                    for (int i = 0; i < _tunnels.size(); i++) {
                        TunnelInfo info = (TunnelInfo)_tunnels.get(i);
                        if ( (info.getLength() > 1) && (info.getExpiration() > _context.clock().now()) ) {
                            _lastSelected = info;
                            return info;
                        }
                    }
                }
                // ok, either we are ok using zero hop tunnels, or only fallback tunnels remain.  pick 'em
                // randomly
                for (int i = 0; i < _tunnels.size(); i++) {
                    TunnelInfo info = (TunnelInfo)_tunnels.get(i);
                    if (info.getExpiration() > _context.clock().now()) {
                        //_log.debug("Selecting tunnel: " + info + " - " + _tunnels);
                        _lastSelected = info;
                        return info;
                    }
                }
                if (_log.shouldLog(Log.WARN))
                    _log.warn(toString() + ": after " + _tunnels.size() + " tries, no unexpired ones were found: " + _tunnels);
            }
        }
        
        if (_alive && _settings.getAllowZeroHop())
            buildFallback();
        if (allowRecurseOnFail)
            return selectTunnel(false); 
        else
            return null;
    }
    
    public TunnelInfo getTunnel(TunnelId gatewayId) {
        synchronized (_tunnels) {
            for (int i = 0; i < _tunnels.size(); i++) {
                TunnelInfo info = (TunnelInfo)_tunnels.get(i);
                if (_settings.isInbound()) {
                    if (info.getReceiveTunnelId(0).equals(gatewayId))
                        return info;
                } else {
                    if (info.getSendTunnelId(0).equals(gatewayId))
                        return info;
                }
            }
        }
        return null;
    }
    
    /**
     * Return a list of tunnels in the pool
     *
     * @return list of TunnelInfo objects
     */
    public List listTunnels() {
        synchronized (_tunnels) {
            return new ArrayList(_tunnels);
        }
    }
    
    /** list of tunnelInfo instances of tunnels currently being built */
    public List listPending() { synchronized (_inProgress) { return new ArrayList(_inProgress); } }
    
    int getTunnelCount() { synchronized (_tunnels) { return _tunnels.size(); } }
    
    public TunnelPoolSettings getSettings() { return _settings; }
    public void setSettings(TunnelPoolSettings settings) { 
        _settings = settings; 
        if (_settings != null) {
            if (_log.shouldLog(Log.INFO))
                _log.info(toString() + ": Settings updated on the pool: " + settings);
            _manager.getExecutor().repoll(); // in case we need more
        }
    }
    public TunnelPeerSelector getSelector() { return _peerSelector; }
    public boolean isAlive() { return _alive; }
    public int size() { 
        synchronized (_tunnels) {
            return _tunnels.size();
        }
    }
    
    public void addTunnel(TunnelInfo info) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug(toString() + ": Adding tunnel " + info, new Exception("Creator"));
        LeaseSet ls = null;
        synchronized (_tunnels) {
            _tunnels.add(info);
            if (_settings.isInbound() && (_settings.getDestination() != null) )
                ls = locked_buildNewLeaseSet();
        }
        
        if (ls != null)
            _context.clientManager().requestLeaseSet(_settings.getDestination(), ls);
    }
    
    public void removeTunnel(TunnelInfo info) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug(toString() + ": Removing tunnel " + info);
        int remaining = 0;
        LeaseSet ls = null;
        synchronized (_tunnels) {
            _tunnels.remove(info);
            if (_settings.isInbound() && (_settings.getDestination() != null) )
                ls = locked_buildNewLeaseSet();
            remaining = _tunnels.size();
            if (_lastSelected == info) {
                _lastSelected = null;
                _lastSelectionPeriod = 0;
            }
        }
        
        _lifetimeProcessed += info.getProcessedMessagesCount();
        
        long lifetimeConfirmed = info.getVerifiedBytesTransferred();
        long lifetime = 10*60*1000;
        for (int i = 0; i < info.getLength(); i++)
            _context.profileManager().tunnelLifetimePushed(info.getPeer(i), lifetime, lifetimeConfirmed);
        
        if (_settings.isInbound() && (_settings.getDestination() != null) ) {
            if (ls != null) {
                _context.clientManager().requestLeaseSet(_settings.getDestination(), ls);
            } else {
                if (_log.shouldLog(Log.WARN))
                    _log.warn(toString() + ": unable to build a new leaseSet on removal (" + remaining 
                              + " remaining), request a new tunnel");
                if (_settings.getAllowZeroHop())
                    buildFallback();
            }
        }

        _manager.getExecutor().repoll();
        
        boolean connected = true;
        if ( (_settings.getDestination() != null) && (!_context.clientManager().isLocal(_settings.getDestination())) )
            connected = false;
        if ( (getTunnelCount() <= 0) && (!connected) ) {
            _manager.removeTunnels(_settings.getDestination());
            return;
        }
    }

    public void tunnelFailed(PooledTunnelCreatorConfig cfg) {
        if (_log.shouldLog(Log.WARN))
            _log.warn(toString() + ": Tunnel failed: " + cfg);
        int remaining = 0;
        LeaseSet ls = null;
        synchronized (_tunnels) {
            _tunnels.remove(cfg);
            if (_settings.isInbound() && (_settings.getDestination() != null) )
                ls = locked_buildNewLeaseSet();
            remaining = _tunnels.size();
            if (_lastSelected == cfg) {
                _lastSelected = null;
                _lastSelectionPeriod = 0;
            }
        }
        
        _manager.tunnelFailed();
        
        _lifetimeProcessed += cfg.getProcessedMessagesCount();
        
        if (_settings.isInbound() && (_settings.getDestination() != null) ) {
            if (ls != null) {
                _context.clientManager().requestLeaseSet(_settings.getDestination(), ls);
            }
        }
    }

    void refreshLeaseSet() {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug(toString() + ": refreshing leaseSet on tunnel expiration (but prior to grace timeout)");
        int remaining = 0;
        LeaseSet ls = null;
        if (_settings.isInbound() && (_settings.getDestination() != null) ) {
            synchronized (_tunnels) {
                ls = locked_buildNewLeaseSet();
                remaining = _tunnels.size();
            }
            if (ls != null) {
                _context.clientManager().requestLeaseSet(_settings.getDestination(), ls);
            }
        }
    }

    /**
     * Return true if a fallback tunnel is built
     *
     */
    boolean buildFallback() {
        int quantity = _settings.getBackupQuantity() + _settings.getQuantity();
        int usable = 0;
        synchronized (_tunnels) {
            usable = _tunnels.size();
        }
        if (usable > 0)
            return false;

        if (_settings.getAllowZeroHop()) {
            if ( (_settings.getLength() + _settings.getLengthVariance() > 0) && 
                 (_settings.getDestination() != null) &&
                 (_context.profileOrganizer().countActivePeers() > 0) ) {
                // if it is a client tunnel pool and our variance doesn't allow 0 hop, prefer failure to
                // 0 hop operation (unless our router is offline)
                return false;
            }
            if (_log.shouldLog(Log.INFO))
                _log.info(toString() + ": building a fallback tunnel (usable: " + usable + " needed: " + quantity + ")");
            
            // runs inline, since its 0hop
            _manager.getExecutor().buildTunnel(this, configureNewTunnel(true));
            return true;
        }
        return false;
    }
    
    /**
     * Build a leaseSet with all of the tunnels that aren't about to expire
     *
     */
    private LeaseSet locked_buildNewLeaseSet() {
        long expireAfter = _context.clock().now() + _settings.getRebuildPeriod();
        
        LeaseSet ls = new LeaseSet();
        for (int i = 0; i < _tunnels.size(); i++) {
            TunnelInfo tunnel = (TunnelInfo)_tunnels.get(i);
            if (tunnel.getExpiration() <= expireAfter)
                continue; // expires too soon, skip it
            
            TunnelId inId = tunnel.getReceiveTunnelId(0);
            Hash gw = tunnel.getPeer(0);
            if ( (inId == null) || (gw == null) ) {
                _log.error(toString() + ": wtf, tunnel has no inbound gateway/tunnelId? " + tunnel);
                continue;
            }
            Lease lease = new Lease();
            lease.setEndDate(new Date(tunnel.getExpiration()));
            lease.setTunnelId(inId);
            lease.setGateway(gw);
            ls.addLease(lease);
        }
        
        int wanted = _settings.getQuantity();
        
        if (ls.getLeaseCount() < wanted) {
            if (_log.shouldLog(Log.WARN))
                _log.warn(toString() + ": Not enough leases (" + ls.getLeaseCount() + ", wanted " + wanted + ")");
            return null;
        } else {
            // linear search to trim down the leaseSet, removing the ones that 
            // will expire the earliest.  cheaper than a tree for this size
            while (ls.getLeaseCount() > wanted) {
                int earliestIndex = -1;
                long earliestExpiration = -1;
                for (int i = 0; i < ls.getLeaseCount(); i++) {
                    Lease cur = ls.getLease(i);
                    if ( (earliestExpiration < 0) || (cur.getEndDate().getTime() < earliestExpiration) ) {
                        earliestIndex = i;
                        earliestExpiration = cur.getEndDate().getTime();
                    }
                }
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug(toString() + ": Dropping older lease from the leaseSet: " + earliestIndex + " out of " + ls.getLeaseCount());
                ls.removeLease(earliestIndex);
            }
            return ls;
        }
    }
    
    public long getLifetimeProcessed() { return _lifetimeProcessed; }
    
    /**
     * Gather the data to see how many tunnels to build, and then actually compute that value (delegated to
     * the countHowManyToBuild function below)
     *
     */
    public int countHowManyToBuild() {
        int wanted = getSettings().getBackupQuantity() + getSettings().getQuantity();
        
        boolean allowZeroHop = ((getSettings().getLength() + getSettings().getLengthVariance()) <= 0);
          
        long expireAfter = _context.clock().now() + (2 * _settings.getRebuildPeriod());
        expireAfter += _expireSkew;
        
        long earliestExpire = -1;
        int live = 0;
        int fallback = 0;
        int usable = 0;
        synchronized (_tunnels) {
            boolean enough = _tunnels.size() > wanted;
            for (int i = 0; i < _tunnels.size(); i++) {
                TunnelInfo info = (TunnelInfo)_tunnels.get(i);
                if (info.getExpiration() > expireAfter) {
                    if (allowZeroHop || (info.getLength() > 1)) {
                        usable++;
                        if ( (info.getExpiration() < earliestExpire) || (earliestExpire < 0) )
                            earliestExpire = info.getExpiration();
                    }
                }
                live++;
                if ( (info.getLength() <= 1) && (info.getExpiration() > expireAfter) )
                    fallback++;
            }
        }
        
        if (usable < wanted) { 
            // if we are short on tunnels, build fast
            earliestExpire = 0;
        }
        
        int inProgress = 0;
        synchronized (_inProgress) {
            inProgress = _inProgress.size();
            for (int i = 0; i < _inProgress.size(); i++) {
                PooledTunnelCreatorConfig cfg = (PooledTunnelCreatorConfig)_inProgress.get(i);
                if (cfg.getLength() <= 1)
                    fallback++;
            }
        }
      
        return countHowManyToBuild(allowZeroHop, earliestExpire, usable, wanted, inProgress, fallback);
    }
    
    /**
     * This is the big scary function determining how many new tunnels we want to try to build at this
     * point in time, as used by the BuildExecutor
     *
     * @param allowZeroHop do we normally allow zero hop tunnels?  If true, treat fallback tunnels like normal ones
     * @param earliestExpire how soon do some of our usable tunnels expire, or, if we are missing tunnels, -1
     * @param usable how many tunnels will be around for a while (may include fallback tunnels)
     * @param standardAmount how many tunnels we want to have, in general
     * @param inProgress how many tunnels are being built for this pool right now (may include fallback tunnels)
     * @param fallback how many zero hop tunnels do we have, or are being built
     */
    private int countHowManyToBuild(boolean allowZeroHop, long earliestExpire, int usable, int standardAmount, 
                                    int inProgress, int fallback) {
        int howMany = 0;
        if (allowZeroHop)
            howMany = standardAmount - usable;
        else
            howMany = standardAmount - (usable - fallback);
        
        int concurrentBuildWeight = 1;
        if (howMany > 0) {
            long now = _context.clock().now();
            if (earliestExpire - now < 60*1000)
                concurrentBuildWeight = 4; // right before expiration, allow up to 4x quantity tunnels to be pending
            else if (earliestExpire - now < 120*1000)
                concurrentBuildWeight = 3; // allow up to 3x quantity tunnels to be pending from 1-2m
            else if (earliestExpire - now < 180*1000)
                concurrentBuildWeight = 2; // allow up to 2x quantity tunnels to be pending from 2-3m

            // e.g. we want 3 tunnels, but only have 1 usable, we'd want 2 more.  however, if the tunnels
            // expire in 90 seconds, we'd act like we wanted 6 (and assume 4 would fail building).
            howMany = (howMany * concurrentBuildWeight) - inProgress;
        }
        
        int rv = howMany;
        // ok, we're actually swamped with tunnels, so lets hold off on replacing the
        // fallback ones for a bit
        if ( (usable + inProgress + fallback > 2*standardAmount) && (howMany > 0) )
            rv = 0;
        
        if (allowZeroHop && (rv > standardAmount))
            rv = standardAmount;
        
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Count: rv: " + rv + " howMany " + howMany + " concurrentWeight " + concurrentBuildWeight 
                       + " allow? " + allowZeroHop + " usable " + usable 
                       + " std " + standardAmount + " inProgress " + inProgress + " fallback " + fallback 
                       + " for " + toString());
        
        if (rv < 0)
            return 0;
        return rv;
    }
    
    PooledTunnelCreatorConfig configureNewTunnel() { return configureNewTunnel(false); }
    private PooledTunnelCreatorConfig configureNewTunnel(boolean forceZeroHop) {
        TunnelPoolSettings settings = getSettings();
        List peers = null;
        long expiration = _context.clock().now() + settings.getDuration();

        if (!forceZeroHop) {
            peers = _peerSelector.selectPeers(_context, settings);
            if ( (peers == null) || (peers.size() <= 0) ) {
                // no inbound or outbound tunnels to send the request through, and 
                // the pool is refusing 0 hop tunnels
                if (peers == null) {
                    if (_log.shouldLog(Log.ERROR))
                        _log.error("No peers to put in the new tunnel! selectPeers returned null!  boo, hiss!");
                } else {
                    if (_log.shouldLog(Log.ERROR))
                        _log.error("No peers to put in the new tunnel! selectPeers returned an empty list?!");
                }
                return null;
            }
        } else {
            peers = new ArrayList(1);
            peers.add(_context.routerHash());
        }
        PooledTunnelCreatorConfig cfg = new PooledTunnelCreatorConfig(_context, peers.size(), settings.isInbound(), settings.getDestination());
        // peers[] is ordered endpoint first, but cfg.getPeer() is ordered gateway first
        for (int i = 0; i < peers.size(); i++) {
            int j = peers.size() - 1 - i;
            cfg.setPeer(j, (Hash)peers.get(i));
            HopConfig hop = cfg.getConfig(j);
            hop.setExpiration(expiration);
            hop.setIVKey(_context.keyGenerator().generateSessionKey());
            hop.setLayerKey(_context.keyGenerator().generateSessionKey());
            // tunnelIds will be updated during building, and as the creator, we
            // don't need to worry about prev/next hop
        }
        cfg.setExpiration(expiration);
        
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Config contains " + peers + ": " + cfg);
        synchronized (_inProgress) {
            _inProgress.add(cfg);
        }
        return cfg;
    }
    
    private List _inProgress = new ArrayList();
    void buildComplete(PooledTunnelCreatorConfig cfg) {
        synchronized (_inProgress) { _inProgress.remove(cfg); }
    }
    
    public String toString() {
        if (_settings.isExploratory()) {
            if (_settings.isInbound())
                return "Inbound exploratory pool";
            else
                return "Outbound exploratory pool";
        } else {
            StringBuffer rv = new StringBuffer(32);
            if (_settings.isInbound())
                rv.append("Inbound client pool for ");
            else
                rv.append("Outbound client pool for ");
            if (_settings.getDestinationNickname() != null)
                rv.append(_settings.getDestinationNickname());
            else
                rv.append(_settings.getDestination().toBase64().substring(0,4));
            return rv.toString();
        }
            
    }
}
