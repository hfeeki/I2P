package net.i2p.router.tunnel.pool;

import java.util.*;
import net.i2p.data.*;
import net.i2p.data.i2np.*;
import net.i2p.router.*;
import net.i2p.router.tunnel.*;
import net.i2p.router.peermanager.TunnelHistory;
import net.i2p.util.Log;

/**
 * Single threaded controller of the tunnel creation process, spanning all tunnel pools.
 * Essentially, this loops across the pools, sees which want to build tunnels, and fires 
 * off the necessary activities if the load allows.  If nothing wants to build any tunnels,
 * it waits for a short period before looping again (or until it is told that something
 * changed, such as a tunnel failed, new client started up, or tunnel creation was aborted).
 *
 */
class BuildExecutor implements Runnable {
    private RouterContext _context;
    private Log _log;
    private TunnelPoolManager _manager;
    /** list of TunnelCreatorConfig elements of tunnels currently being built */
    private List _currentlyBuilding;
    private boolean _isRunning;
    private BuildHandler _handler;
    private boolean _repoll;

    public BuildExecutor(RouterContext ctx, TunnelPoolManager mgr) {
        _context = ctx;
        _log = ctx.logManager().getLog(getClass());
        _manager = mgr;
        _currentlyBuilding = new ArrayList(10);
        _context.statManager().createRateStat("tunnel.concurrentBuilds", "How many builds are going at once", "Tunnels", new long[] { 60*1000, 5*60*1000, 60*60*1000 });
        _context.statManager().createRateStat("tunnel.concurrentBuildsLagged", "How many builds are going at once when we reject further builds, due to job lag (period is lag)", "Tunnels", new long[] { 60*1000, 5*60*1000, 60*60*1000 });
        _context.statManager().createRateStat("tunnel.buildExploratoryExpire", "How often an exploratory tunnel times out during creation", "Tunnels", new long[] { 60*1000, 10*60*1000 });
        _context.statManager().createRateStat("tunnel.buildClientExpire", "How often a client tunnel times out during creation", "Tunnels", new long[] { 60*1000, 10*60*1000 });
        _context.statManager().createRateStat("tunnel.buildExploratorySuccess", "How often an exploratory tunnel is fully built", "Tunnels", new long[] { 60*1000, 10*60*1000 });
        _context.statManager().createRateStat("tunnel.buildClientSuccess", "How often a client tunnel is fully built", "Tunnels", new long[] { 60*1000, 10*60*1000 });
        _context.statManager().createRateStat("tunnel.buildExploratoryReject", "How often an exploratory tunnel is rejected", "Tunnels", new long[] { 60*1000, 10*60*1000 });
        _context.statManager().createRateStat("tunnel.buildClientReject", "How often a client tunnel is rejected", "Tunnels", new long[] { 60*1000, 10*60*1000 });
        _context.statManager().createRateStat("tunnel.buildRequestTime", "How long it takes to build a tunnel request", "Tunnels", new long[] { 60*1000, 10*60*1000 });
        _context.statManager().createRateStat("tunnel.buildRequestZeroHopTime", "How long it takes to build a zero hop tunnel", "Tunnels", new long[] { 60*1000, 10*60*1000 });
        _context.statManager().createRateStat("tunnel.pendingRemaining", "How many inbound requests are pending after a pass (period is how long the pass takes)?", "Tunnels", new long[] { 60*1000, 10*60*1000 });
        _repoll = false;
        _handler = new BuildHandler(ctx, this);
    }
    
    // Estimated cost of one tunnel build attempt, bytes
    private static final int BUILD_BANDWIDTH_ESTIMATE_BYTES = 5*1024;

    private int allowed() {
        StringBuffer buf = null;
        if (_log.shouldLog(Log.DEBUG)) {
            buf = new StringBuffer(128);
            buf.append("Allowed: ");
        }

        int maxKBps = _context.bandwidthLimiter().getOutboundKBytesPerSecond();
        int allowed = maxKBps / 6; // Max. 1 concurrent build per 6 KB/s outbound
        if (allowed < 2) allowed = 2; // Never choke below 2 builds (but congestion may)
        if (allowed > 10) allowed = 10; // Never go beyond 10, that is uncharted territory (old limit was 5)

        String prop = _context.getProperty("router.tunnelConcurrentBuilds");
        if (prop != null)
            try { allowed = Integer.valueOf(prop).intValue(); } catch (NumberFormatException nfe) {}

        List expired = null;
        int concurrent = 0;
        long expireBefore = _context.clock().now() + 10*60*1000 - BuildRequestor.REQUEST_TIMEOUT;
        synchronized (_currentlyBuilding) {
            // expire any old requests
            for (int i = 0; i < _currentlyBuilding.size(); i++) {
                TunnelCreatorConfig cfg = (TunnelCreatorConfig)_currentlyBuilding.get(i);
                if (cfg.getExpiration() <= expireBefore) {
                    _currentlyBuilding.remove(i);
                    i--;
                    if (expired == null)
                        expired = new ArrayList();
                    expired.add(cfg);
                }
            }
            concurrent = _currentlyBuilding.size();
            allowed -= concurrent;
            if (buf != null)
                buf.append(allowed).append(" ").append(_currentlyBuilding.toString());
        }
        
        if (expired != null) {
            for (int i = 0; i < expired.size(); i++) {
                PooledTunnelCreatorConfig cfg = (PooledTunnelCreatorConfig)expired.get(i);
                // note the fact that this tunnel request timed out in the peers' profiles.
                // or... not.
                if (_log.shouldLog(Log.INFO))
                    _log.info("Timed out waiting for reply asking for " + cfg);
                TunnelPool pool = cfg.getTunnelPool();
                if (pool != null)
                    pool.buildComplete(cfg);
                if (cfg.getDestination() == null)
                    _context.statManager().addRateData("tunnel.buildExploratoryExpire", 1, 0);
                else
                    _context.statManager().addRateData("tunnel.buildClientExpire", 1, 0);
                for (int j = 0; j < cfg.getLength(); j++)
                    didNotReply(cfg.getReplyMessageId(), cfg.getPeer(j));
            }
        }
        
        if (buf != null)
            _log.debug(buf.toString());
        
        _context.statManager().addRateData("tunnel.concurrentBuilds", concurrent, 0);
        
        long lag = _context.jobQueue().getMaxLag();
        if ( (lag > 2000) && (_context.router().getUptime() > 5*60*1000) ) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Too lagged [" + lag + "], don't allow building");
            _context.statManager().addRateData("tunnel.concurrentBuildsLagged", concurrent, lag);
            return 0; // if we have a job heavily blocking our jobqueue, ssllloowww dddooowwwnnn
        }
        
        if (isOverloaded()) {
            int used1s = _context.router().get1sRate(true);
            // If 1-second average indicates we could manage building one tunnel
            if ((maxKBps*1024) - used1s > BUILD_BANDWIDTH_ESTIMATE_BYTES) {
                // Allow one
                if (_log.shouldLog(Log.WARN))
                    _log.warn("We had overload, but 1s bandwidth was " + used1s + " so allowed building 1.");
                return 1;
            } else {
                // Allow none
                if (_log.shouldLog(Log.WARN))
                    _log.warn("We had serious overload, so allowed building 0.");
                return 0;
            }
        }

        return allowed;
    }

    /**
     * Don't even try to build tunnels if we're saturated
     */
    private boolean isOverloaded() {
        //if (true) return false;
        // dont include the inbound rates when throttling tunnel building, since
        // that'd expose a pretty trivial attack.
        int maxKBps = _context.bandwidthLimiter().getOutboundKBytesPerSecond(); 
        int used1s = 0; // dont throttle on the 1s rate, its too volatile
        int used1m = _context.router().get1mRate(true);
        int used5m = 0; //get5mRate(_context); // don't throttle on the 5m rate, as that'd hide available bandwidth
        int used = Math.max(Math.max(used1s, used1m), used5m);
        if ((maxKBps * 1024) - used <= 0) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Too overloaded to build our own tunnels (used=" + used + ", maxKBps=" + maxKBps + ", 1s=" + used1s + ", 1m=" + used1m + ")");
            return true;
        } else {
            return false;
        }
    }
    
    public void run() {
        _isRunning = true;
        List wanted = new ArrayList(8);
        List pools = new ArrayList(8);
        
        int pendingRemaining = 0;
        
        long loopBegin = 0;
        long beforeHandleInboundReplies = 0;
        long afterHandleInboundReplies = 0;
        long afterBuildZeroHop = 0;
        long afterBuildReal = 0;
        long afterHandleInbound = 0;
                
        while (!_manager.isShutdown()){
            loopBegin = System.currentTimeMillis();
            try {
                _repoll = pendingRemaining > 0; // resets repoll to false unless there are inbound requeusts pending
                _manager.listPools(pools);
                for (int i = 0; i < pools.size(); i++) {
                    TunnelPool pool = (TunnelPool)pools.get(i);
                    int howMany = pool.countHowManyToBuild();
                    for (int j = 0; j < howMany; j++)
                        wanted.add(pool);
                }

                beforeHandleInboundReplies = System.currentTimeMillis();
                _handler.handleInboundReplies();
                afterHandleInboundReplies = System.currentTimeMillis();
                
                // allowed() also expires timed out requests (for new style requests)
                int allowed = allowed();
                
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Allowed: " + allowed + " wanted: " + wanted);

                // zero hop ones can run inline
                allowed = buildZeroHopTunnels(wanted, allowed);
                afterBuildZeroHop = System.currentTimeMillis();
                
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Zero hops built, Allowed: " + allowed + " wanted: " + wanted);

                int realBuilt = 0;
                TunnelManagerFacade mgr = _context.tunnelManager();
                if ( (mgr == null) || (mgr.selectInboundTunnel() == null) || (mgr.selectOutboundTunnel() == null) ) {
                    // we don't have either inbound or outbound tunnels, so don't bother trying to build
                    // non-zero-hop tunnels
                    synchronized (_currentlyBuilding) {
                        if (!_repoll) {
                            if (_log.shouldLog(Log.DEBUG))
                                _log.debug("No tunnel to build with (allowed=" + allowed + ", wanted=" + wanted.size() + ", pending=" + pendingRemaining + "), wait for a while");
                            _currentlyBuilding.wait(1*1000+_context.random().nextInt(1*1000));
                        }
                    }
                } else {
                    if ( (allowed > 0) && (wanted.size() > 0) ) {
                        Collections.shuffle(wanted, _context.random());
                        
                        // force the loops to be short, since 20 consecutive tunnel build requests can take
                        // a long, long time
                        if (allowed > 5)
                            allowed = 5;
                        
                        for (int i = 0; (i < allowed) && (wanted.size() > 0); i++) {
                            TunnelPool pool = (TunnelPool)wanted.remove(0);
                            //if (pool.countWantedTunnels() <= 0)
                            //    continue;
                            PooledTunnelCreatorConfig cfg = pool.configureNewTunnel();
                            if (cfg != null) {
                                if (_log.shouldLog(Log.DEBUG))
                                    _log.debug("Configuring new tunnel " + i + " for " + pool + ": " + cfg);
                                synchronized (_currentlyBuilding) {
                                    _currentlyBuilding.add(cfg);
                                }
                                buildTunnel(pool, cfg);
                                realBuilt++;
                                // 0hops are taken care of above, these are nonstandard 0hops
                                //if (cfg.getLength() <= 1)
                                //    i--; //0hop, we can keep going, as there's no worry about throttling
                                
                                // we want replies to go to the top of the queue
                                _handler.handleInboundReplies();
                            } else {
                                i--;
                            }
                        }
                    } else {
                        try {
                            synchronized (_currentlyBuilding) {
                                if (!_repoll) {
                                    if (_log.shouldLog(Log.DEBUG))
                                        _log.debug("Nothin' doin (allowed=" + allowed + ", wanted=" + wanted.size() + ", pending=" + pendingRemaining + "), wait for a while");
                                    //if (allowed <= 0)
                                        _currentlyBuilding.wait(_context.random().nextInt(1*1000));
                                    //else // wanted <= 0
                                    //    _currentlyBuilding.wait(_context.random().nextInt(30*1000));
                                }
                            }
                        } catch (InterruptedException ie) {
                            // someone wanted to build something
                        }
                    }
                }
                
                afterBuildReal = System.currentTimeMillis();
                
                pendingRemaining = _handler.handleInboundRequests();
                afterHandleInbound = System.currentTimeMillis();
                
                if (pendingRemaining > 0)
                    _context.statManager().addRateData("tunnel.pendingRemaining", pendingRemaining, afterHandleInbound-afterBuildReal);

                if (_log.shouldLog(Log.INFO))
                    _log.info("build loop complete, tot=" + (afterHandleInbound-loopBegin) + 
                              " inReply=" + (afterHandleInboundReplies-beforeHandleInboundReplies) +
                              " zeroHop=" + (afterBuildZeroHop-afterHandleInboundReplies) +
                              " real=" + (afterBuildReal-afterBuildZeroHop) +
                              " in=" + (afterHandleInbound-afterBuildReal) + 
                              " built=" + realBuilt +
                              " pending=" + pendingRemaining);
                
                wanted.clear();
                pools.clear();
            } catch (Exception e) {
                if (_log.shouldLog(Log.CRIT))
                    _log.log(Log.CRIT, "B0rked in the tunnel builder", e);
            }
        }
        
        if (_log.shouldLog(Log.WARN))
            _log.warn("Done building");
        _isRunning = false;
    }
    
    /**
     * iterate over the 0hop tunnels, running them all inline regardless of how many are allowed
     * @return number of tunnels allowed after processing these zero hop tunnels (almost always the same as before)
     */
    private int buildZeroHopTunnels(List wanted, int allowed) {
        for (int i = 0; i < wanted.size(); i++) {
            TunnelPool pool = (TunnelPool)wanted.get(0);
            if (pool.getSettings().getLength() == 0) {
                PooledTunnelCreatorConfig cfg = pool.configureNewTunnel();
                if (cfg != null) {
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Configuring short tunnel " + i + " for " + pool + ": " + cfg);
                    synchronized (_currentlyBuilding) {
                        _currentlyBuilding.add(cfg);
                    }
                    buildTunnel(pool, cfg);
                    if (cfg.getLength() > 1) {
                        allowed--; // oops... shouldn't have done that, but hey, its not that bad...
                    }
                    wanted.remove(i);
                    i--;
                } else {
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Configured a null tunnel");
                }
            }
        }
        return allowed;
    }
    
    public boolean isRunning() { return _isRunning; }
    
    void buildTunnel(TunnelPool pool, PooledTunnelCreatorConfig cfg) {
        long beforeBuild = System.currentTimeMillis();
        BuildRequestor.request(_context, pool, cfg, this);
        long buildTime = System.currentTimeMillis() - beforeBuild;
        if (cfg.getLength() <= 1)
            _context.statManager().addRateData("tunnel.buildRequestZeroHopTime", buildTime, buildTime);
        else
            _context.statManager().addRateData("tunnel.buildRequestTime", buildTime, buildTime);
        long id = cfg.getReplyMessageId();
        if (id > 0) {
            synchronized (_recentBuildIds) { 
                while (_recentBuildIds.size() > 64)
                    _recentBuildIds.remove(0);
                _recentBuildIds.add(new Long(id));
            }
        }
    }
    
    public void buildComplete(PooledTunnelCreatorConfig cfg, TunnelPool pool) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Build complete for " + cfg);
        pool.buildComplete(cfg);
        synchronized (_currentlyBuilding) { 
            _currentlyBuilding.remove(cfg);
            _currentlyBuilding.notifyAll();
        }
        
        long expireBefore = _context.clock().now() + 10*60*1000 - BuildRequestor.REQUEST_TIMEOUT;
        if (cfg.getExpiration() <= expireBefore) {
            if (_log.shouldLog(Log.INFO))
                _log.info("Build complete for expired tunnel: " + cfg);
        }
    }
    
    private List _recentBuildIds = new ArrayList(100);
    public boolean wasRecentlyBuilding(long replyId) {
        synchronized (_recentBuildIds) {
            return _recentBuildIds.contains(new Long(replyId));
        }
    }
    
    public void buildSuccessful(PooledTunnelCreatorConfig cfg) {
        _manager.buildComplete(cfg);
    }
    
    public void repoll() { 
        synchronized (_currentlyBuilding) { 
            _repoll = true;
            _currentlyBuilding.notifyAll(); 
        }
    }
    
    private void didNotReply(long tunnel, Hash peer) {
        if (_log.shouldLog(Log.INFO))
            _log.info(tunnel + ": Peer " + peer.toBase64() + " did not reply to the tunnel join request");
    }
    
    List locked_getCurrentlyBuilding() { return _currentlyBuilding; }
    public int getInboundBuildQueueSize() { return _handler.getInboundBuildQueueSize(); }
}
