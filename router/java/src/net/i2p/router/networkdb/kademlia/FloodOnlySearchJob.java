package net.i2p.router.networkdb.kademlia;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.i2p.data.Hash;
import net.i2p.data.i2np.DatabaseLookupMessage;
import net.i2p.data.i2np.DatabaseSearchReplyMessage;
import net.i2p.data.i2np.DatabaseStoreMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.router.Job;
import net.i2p.router.JobImpl;
import net.i2p.router.MessageSelector;
import net.i2p.router.OutNetMessage;
import net.i2p.router.ReplyJob;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelInfo;
import net.i2p.router.peermanager.PeerProfile;
import net.i2p.util.Log;

/**
 * Try sending a search to some floodfill peers, failing completely if we don't get
 * a match from one of those peers, with no fallback to the kademlia search
 *
 */
class FloodOnlySearchJob extends FloodSearchJob {
    private Log _log;
    private FloodfillNetworkDatabaseFacade _facade;
    private Hash _key;
    private List _onFind;
    private List _onFailed;
    private long _expiration;
    private int _timeoutMs;
    private long _origExpiration;
    private boolean _isLease;
    private volatile int _lookupsRemaining;
    private volatile boolean _dead;
    private long _created;
    
    private List _out;
    private MessageSelector _replySelector;
    private ReplyJob _onReply;
    private Job _onTimeout;
    public FloodOnlySearchJob(RouterContext ctx, FloodfillNetworkDatabaseFacade facade, Hash key, Job onFind, Job onFailed, int timeoutMs, boolean isLease) {
        super(ctx, facade, key, onFind, onFailed, timeoutMs, isLease);
        _log = ctx.logManager().getLog(FloodOnlySearchJob.class);
        _facade = facade;
        _key = key;
        _onFind = new ArrayList();
        _onFind.add(onFind);
        _onFailed = new ArrayList();
        _onFailed.add(onFailed);
        _timeoutMs = Math.min(timeoutMs, SearchJob.PER_FLOODFILL_PEER_TIMEOUT);
        _expiration = _timeoutMs + ctx.clock().now();
        _origExpiration = _timeoutMs + ctx.clock().now();
        _isLease = isLease;
        _lookupsRemaining = 0;
        _dead = false;
        _out = Collections.synchronizedList(new ArrayList(2));
        _replySelector = new FloodOnlyLookupSelector(getContext(), this);
        _onReply = new FloodOnlyLookupMatchJob(getContext(), this);
        _onTimeout = new FloodOnlyLookupTimeoutJob(getContext(), this);
        _created = System.currentTimeMillis();
    }
    void addDeferred(Job onFind, Job onFailed, long timeoutMs, boolean isLease) {
        if (_dead) {
            getContext().jobQueue().addJob(onFailed);
        } else {
            if (onFind != null) synchronized (_onFind) { _onFind.add(onFind); }
            if (onFailed != null) synchronized (_onFailed) { _onFailed.add(onFailed); }
        }
    }
    public long getExpiration() { return _expiration; }
    private static final int CONCURRENT_SEARCHES = 2;
    public void runJob() {
        // pick some floodfill peers and send out the searches
        List floodfillPeers = _facade.getFloodfillPeers();
        if (floodfillPeers == null) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Running netDb searches against the floodfill peers, but we don't know any");
            failed();
            return;
        }
        OutNetMessage out = getContext().messageRegistry().registerPending(_replySelector, _onReply, _onTimeout, _timeoutMs);
        synchronized (_out) { _out.add(out); }

        // We need to randomize our ff selection, else we stay with the same ones since
        // getFloodfillPeers() is sorted by closest distance. Always using the same
        // ones didn't help reliability.
        // Also, query the unheard-from, unprofiled, failing, unreachable and shitlisted ones last.
        // We should hear from floodfills pretty frequently so set a 30m time limit.
        // If unprofiled we haven't talked to them in a long time.
        // We aren't contacting the peer directly, so shitlist doesn't strictly matter,
        // but it's a bad sign, and we often shitlist a peer before we fail it...
        if (floodfillPeers.size() > CONCURRENT_SEARCHES) {
            Collections.shuffle(floodfillPeers, getContext().random());
            List ffp = new ArrayList(floodfillPeers.size());
            int failcount = 0;
            long before = getContext().clock().now() - 30*60*1000;
            for (int i = 0; i < floodfillPeers.size(); i++) {
                 Hash peer = (Hash)floodfillPeers.get(i);
                 PeerProfile profile = getContext().profileOrganizer().getProfile(peer);
                 if (profile == null || profile.getLastHeardFrom() < before ||
                     profile.getIsFailing() || getContext().shitlist().isShitlisted(peer) ||
                     getContext().commSystem().wasUnreachable(peer)) {
                     failcount++;
                     ffp.add(peer);
                 } else
                     ffp.add(0, peer);
            }
            if (_log.shouldLog(Log.INFO) && failcount > 0)
                _log.info(getJobId() + ": " + failcount + " of " + floodfillPeers.size() + " floodfills are not heard from, unprofiled, failing, unreachable or shitlisted");
            floodfillPeers = ffp;
        }

        int count = 0; // keep a separate count since _lookupsRemaining could be decremented elsewhere
        for (int i = 0; _lookupsRemaining < CONCURRENT_SEARCHES && i < floodfillPeers.size(); i++) {
            Hash peer = (Hash)floodfillPeers.get(i);
            if (peer.equals(getContext().routerHash()))
                continue;
            
            DatabaseLookupMessage dlm = new DatabaseLookupMessage(getContext(), true);
            TunnelInfo replyTunnel = getContext().tunnelManager().selectInboundTunnel();
            TunnelInfo outTunnel = getContext().tunnelManager().selectOutboundTunnel();
            if ( (replyTunnel == null) || (outTunnel == null) ) {
                failed();
                return;
            }
            dlm.setFrom(replyTunnel.getPeer(0));
            dlm.setMessageExpiration(getContext().clock().now()+10*1000);
            dlm.setReplyTunnel(replyTunnel.getReceiveTunnelId(0));
            dlm.setSearchKey(_key);
            
            if (_log.shouldLog(Log.INFO))
                _log.info(getJobId() + ": Floodfill search for " + _key.toBase64() + " to " + peer.toBase64());
            getContext().tunnelDispatcher().dispatchOutbound(dlm, outTunnel.getSendTunnelId(0), peer);
            count++;
            _lookupsRemaining++;
        }
        
        if (count <= 0) {
            if (_log.shouldLog(Log.INFO))
                _log.info(getJobId() + ": Floodfill search for " + _key.toBase64() + " had no peers to send to");
            // no floodfill peers, fail
            failed();
        }
    }
    public String getName() { return "NetDb flood search (phase 1)"; }
    
    Hash getKey() { return _key; }
    void decrementRemaining() { if (_lookupsRemaining > 0) _lookupsRemaining--; }
    int getLookupsRemaining() { return _lookupsRemaining; }
    
    void failed() {
        synchronized (this) {
            if (_dead) return;
            _dead = true;
        }
        List outBuf = null;
        synchronized (_out) { outBuf = new ArrayList(_out); }
        for (int i = 0; i < outBuf.size(); i++) {
            OutNetMessage out = (OutNetMessage)outBuf.get(i);
            getContext().messageRegistry().unregisterPending(out);
        }
        int timeRemaining = (int)(_origExpiration - getContext().clock().now());
        if (_log.shouldLog(Log.INFO))
            _log.info(getJobId() + ": Floodfill search for " + _key.toBase64() + " failed with " + timeRemaining + " remaining after " + (System.currentTimeMillis()-_created));
        _facade.complete(_key);
        getContext().statManager().addRateData("netDb.failedTime", System.currentTimeMillis()-_created, System.currentTimeMillis()-_created);
        synchronized (_onFailed) {
            for (int i = 0; i < _onFailed.size(); i++) {
                Job j = (Job)_onFailed.remove(0);
                getContext().jobQueue().addJob(j);
            }
        }
    }
    void success() {
        synchronized (this) {
            if (_dead) return;
            _dead = true;
        }
        if (_log.shouldLog(Log.INFO))
            _log.info(getJobId() + ": Floodfill search for " + _key.toBase64() + " successful");
        _facade.complete(_key);
        getContext().statManager().addRateData("netDb.successTime", System.currentTimeMillis()-_created, System.currentTimeMillis()-_created);
        synchronized (_onFind) {
            while (_onFind.size() > 0)
                getContext().jobQueue().addJob((Job)_onFind.remove(0));
        }
    }
}

class FloodOnlyLookupTimeoutJob extends JobImpl {
    private FloodSearchJob _search;
    private Log _log;
    public FloodOnlyLookupTimeoutJob(RouterContext ctx, FloodOnlySearchJob job) {
        super(ctx);
        _search = job;
        _log = ctx.logManager().getLog(getClass());
    }
    public void runJob() {
        if (_log.shouldLog(Log.INFO))
            _log.info(_search.getJobId() + ": search timed out");
        _search.failed();
    }
    public String getName() { return "NetDb flood search (phase 1) timeout"; }
}

class FloodOnlyLookupMatchJob extends JobImpl implements ReplyJob {
    private Log _log;
    private FloodOnlySearchJob _search;
    public FloodOnlyLookupMatchJob(RouterContext ctx, FloodOnlySearchJob job) {
        super(ctx);
        _log = ctx.logManager().getLog(getClass());
        _search = job;
    }
    public void runJob() { 
        if ( (getContext().netDb().lookupLeaseSetLocally(_search.getKey()) != null) ||
             (getContext().netDb().lookupRouterInfoLocally(_search.getKey()) != null) ) {
            if (_log.shouldLog(Log.INFO))
                _log.info(_search.getJobId() + ": search match and found locally");
            _search.success();
        } else {
            int remaining = _search.getLookupsRemaining();
            if (_log.shouldLog(Log.INFO))
                _log.info(_search.getJobId() + ": got a DatabasSearchReply when we were looking for " 
                          + _search.getKey().toBase64() + ", with " + remaining + " outstanding searches");
            // netDb reply pointing us at other people
            _search.failed();
        }
    }
    public String getName() { return "NetDb flood search (phase 1) match"; }
    public void setMessage(I2NPMessage message) {
        if (message instanceof DatabaseSearchReplyMessage) {
            // a dsrm is only passed in when there are no more lookups remaining
            _search.failed();
            return;
        }
        try {
            DatabaseStoreMessage dsm = (DatabaseStoreMessage)message;
            if (dsm.getValueType() == DatabaseStoreMessage.KEY_TYPE_LEASESET)
                getContext().netDb().store(dsm.getKey(), dsm.getLeaseSet());
            else
                getContext().netDb().store(dsm.getKey(), dsm.getRouterInfo());
        } catch (IllegalArgumentException iae) {
            if (_log.shouldLog(Log.WARN))
                _log.warn(_search.getJobId() + ": Received an invalid store reply", iae);
        }
    }
}

class FloodOnlyLookupSelector implements MessageSelector {
    private RouterContext _context;
    private FloodOnlySearchJob _search;
    private boolean _matchFound;
    private Log _log;
    public FloodOnlyLookupSelector(RouterContext ctx, FloodOnlySearchJob search) {
        _context = ctx;
        _search = search;
        _log = ctx.logManager().getLog(getClass());
        _matchFound = false;
    }
    public boolean continueMatching() { 
        return _search.getLookupsRemaining() > 0 && !_matchFound && _context.clock().now() < getExpiration(); 
    }
    public long getExpiration() { return (_matchFound ? -1 : _search.getExpiration()); }
    public boolean isMatch(I2NPMessage message) {
        if (message == null) return false;
        if (message instanceof DatabaseStoreMessage) {
            DatabaseStoreMessage dsm = (DatabaseStoreMessage)message;
            // is it worth making sure the reply came in on the right tunnel?
            if (_search.getKey().equals(dsm.getKey())) {
                _search.decrementRemaining();
                _matchFound = true;
                return true;
            }
        } else if (message instanceof DatabaseSearchReplyMessage) {
            DatabaseSearchReplyMessage dsrm = (DatabaseSearchReplyMessage)message;
            if (_search.getKey().equals(dsrm.getSearchKey())) {
                _search.decrementRemaining();
                if (_search.getLookupsRemaining() <= 0)
                    return true; // ok, no more left, so time to fail
                else
                    return false;
            }
        }
        return false;
    }   
}
