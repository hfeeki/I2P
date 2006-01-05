package net.i2p.router.networkdb.kademlia;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import net.i2p.data.DataHelper;
import net.i2p.data.DataStructure;
import net.i2p.data.Hash;
import net.i2p.data.LeaseSet;
import net.i2p.data.RouterInfo;
import net.i2p.data.TunnelId;
import net.i2p.data.i2np.DatabaseLookupMessage;
import net.i2p.data.i2np.DatabaseSearchReplyMessage;
import net.i2p.data.i2np.DatabaseStoreMessage;
import net.i2p.router.Job;
import net.i2p.router.JobImpl;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelInfo;
import net.i2p.router.message.SendMessageDirectJob;
import net.i2p.stat.Rate;
import net.i2p.stat.RateStat;
import net.i2p.util.Log;

/**
 * Search for a particular key iteratively until we either find a value or we 
 * run out of peers
 *
 */
class SearchJob extends JobImpl {
    private Log _log;
    protected KademliaNetworkDatabaseFacade _facade;
    private SearchState _state;
    private Job _onSuccess;
    private Job _onFailure;
    private long _expiration;
    private long _timeoutMs;
    private boolean _keepStats;
    private boolean _isLease;
    private Job _pendingRequeueJob;
    private PeerSelector _peerSelector;
    private List _deferredSearches;
    private boolean _deferredCleared;
    private long _startedOn;
    
    private static final int SEARCH_BREDTH = 3; // 10 peers at a time 
    private static final int SEARCH_PRIORITY = 400; // large because the search is probably for a real search
    /** only send the 10 closest "dont tell me about" refs */
    static final int MAX_CLOSEST = 10;
    
    /**
     * How long will we give each peer to reply to our search? 
     *
     */
    private static final int PER_PEER_TIMEOUT = 5*1000;
    
    /** 
     * give ourselves 30 seconds to send out the value found to the closest 
     * peers /after/ we get a successful match.  If this fails, no biggie, but
     * this'll help heal the network so subsequent searches will find the data.
     *
     */
    private static final long RESEND_TIMEOUT = 30*1000;
    
    /** 
     * When we're just waiting for something to change, requeue the search status test
     * every second.
     *
     */
    private static final long REQUEUE_DELAY = 1000;
    
    /**
     * Create a new search for the routingKey specified
     * 
     */
    public SearchJob(RouterContext context, KademliaNetworkDatabaseFacade facade, Hash key, Job onSuccess, Job onFailure, long timeoutMs, boolean keepStats, boolean isLease) {
        super(context);
        if ( (key == null) || (key.getData() == null) ) 
            throw new IllegalArgumentException("Search for null key?  wtf");
        _log = getContext().logManager().getLog(SearchJob.class);
        _facade = facade;
        _state = new SearchState(getContext(), key);
        _onSuccess = onSuccess;
        _onFailure = onFailure;
        _timeoutMs = timeoutMs;
        _keepStats = keepStats;
        _isLease = isLease;
        _deferredSearches = new ArrayList(0);
        _deferredCleared = false;
        _peerSelector = facade.getPeerSelector();
        _startedOn = -1;
        _expiration = getContext().clock().now() + timeoutMs;
        getContext().statManager().createRateStat("netDb.successTime", "How long a successful search takes", "NetworkDatabase", new long[] { 60*60*1000l, 24*60*60*1000l });
        getContext().statManager().createRateStat("netDb.failedTime", "How long a failed search takes", "NetworkDatabase", new long[] { 60*60*1000l, 24*60*60*1000l });
        getContext().statManager().createRateStat("netDb.successPeers", "How many peers are contacted in a successful search", "NetworkDatabase", new long[] { 60*60*1000l, 24*60*60*1000l });
        getContext().statManager().createRateStat("netDb.failedPeers", "How many peers fail to respond to a lookup?", "NetworkDatabase", new long[] { 60*60*1000l, 24*60*60*1000l });
        getContext().statManager().createRateStat("netDb.searchCount", "Overall number of searches sent", "NetworkDatabase", new long[] { 5*60*1000l, 10*60*1000l, 60*60*1000l, 3*60*60*1000l, 24*60*60*1000l });
        getContext().statManager().createRateStat("netDb.searchMessageCount", "Overall number of mesages for all searches sent", "NetworkDatabase", new long[] { 5*60*1000l, 10*60*1000l, 60*60*1000l, 3*60*60*1000l, 24*60*60*1000l });
        getContext().statManager().createRateStat("netDb.searchReplyValidated", "How many search replies we get that we are able to validate (fetch)", "NetworkDatabase", new long[] { 5*60*1000l, 10*60*1000l, 60*60*1000l, 3*60*60*1000l, 24*60*60*1000l });
        getContext().statManager().createRateStat("netDb.searchReplyNotValidated", "How many search replies we get that we are NOT able to validate (fetch)", "NetworkDatabase", new long[] { 5*60*1000l, 10*60*1000l, 60*60*1000l, 3*60*60*1000l, 24*60*60*1000l });
        getContext().statManager().createRateStat("netDb.searchReplyValidationSkipped", "How many search replies we get from unreliable peers that we skip?", "NetworkDatabase", new long[] { 5*60*1000l, 10*60*1000l, 60*60*1000l, 3*60*60*1000l, 24*60*60*1000l });
        getContext().statManager().createRateStat("netDb.republishQuantity", "How many peers do we need to send a found leaseSet to?", "NetworkDatabase", new long[] { 10*60*1000l, 60*60*1000l, 3*60*60*1000l, 24*60*60*1000l });

        getContext().statManager().addRateData("netDb.searchCount", 1, 0);
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Search (" + getClass().getName() + " for " + key.toBase64(), new Exception("Search enqueued by"));
    }

    public void runJob() {
        if (_startedOn <= 0) 
            _startedOn = getContext().clock().now();
        if (_log.shouldLog(Log.INFO))
            _log.info(getJobId() + ": Searching for " + _state.getTarget()); // , getAddedBy());
        searchNext();
    }
    
    protected SearchState getState() { return _state; }
    protected KademliaNetworkDatabaseFacade getFacade() { return _facade; }
    public long getExpiration() { return _expiration; }
    public long getTimeoutMs() { return _timeoutMs; }
    
    /**
     * Let each peer take up to the average successful search RTT
     *
     */
    protected int getPerPeerTimeoutMs() {
        if (true)
            return PER_PEER_TIMEOUT;
        int rv = -1;
        RateStat rs = getContext().statManager().getRate("netDb.successTime");
        if (rs != null)
            rv = (int)rs.getLifetimeAverageValue();
        
        rv <<= 1; // double it to give some leeway.  (bah, too lazy to record stdev)
        if ( (rv <= 0) || (rv > PER_PEER_TIMEOUT) )
            return PER_PEER_TIMEOUT;
        else
            return rv + 1025; // tunnel delay
    }
    
    /**
     * Send the next search, or stop if its completed
     */
    protected void searchNext() {
        if (_state.completed()) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug(getJobId() + ": Already completed");
            return;
        }
        if (_log.shouldLog(Log.INFO))
            _log.info(getJobId() + ": Searching: " + _state);
        if (isLocal()) {
            if (_log.shouldLog(Log.INFO))
                _log.info(getJobId() + ": Key found locally");
            _state.complete(true);
            succeed();
        } else if (isExpired()) {
            if (_log.shouldLog(Log.INFO))
                _log.info(getJobId() + ": Key search expired");
            _state.complete(true);
            fail();
        } else {
            //_log.debug("Continuing search");
            continueSearch();
        }
    }

    /**
     * True if the data is already locally stored
     *
     */
    private boolean isLocal() { return _facade.getDataStore().isKnown(_state.getTarget()); }

    private boolean isExpired() { 
        return getContext().clock().now() >= _expiration;
    }

    /** max # of concurrent searches */
    protected int getBredth() { return SEARCH_BREDTH; }
    
    /**
     * Send a series of searches to the next available peers as selected by
     * the routing table, but making sure no more than SEARCH_BREDTH are outstanding
     * at any time
     *
     */
    protected void continueSearch() { 
        if (_state.completed()) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug(getJobId() + ": Search already completed", new Exception("already completed"));
            return;
        }
        int toCheck = getBredth() - _state.getPending().size();
        if (toCheck <= 0) {
            // too many already pending
            if (_log.shouldLog(Log.INFO))
                _log.info(getJobId() + ": Too many searches already pending (pending: " 
                          + _state.getPending().size() + " max: " + getBredth() + ")");
            requeuePending();
            return;
        } 
        int sent = 0;
        Set attempted = _state.getAttempted();
        while (sent <= 0) {
            List closestHashes = getClosestRouters(_state.getTarget(), toCheck, attempted);
            if ( (closestHashes == null) || (closestHashes.size() <= 0) ) {
                if (_state.getPending().size() <= 0) {
                    // we tried to find some peers, but there weren't any and no one else is going to answer
                    if (_log.shouldLog(Log.INFO))
                        _log.info(getJobId() + ": No peers left, and none pending!  Already searched: " 
                                  + _state.getAttempted().size() + " failed: " + _state.getFailed().size());
                    fail();
                } else {
                    // no more to try, but we might get data or close peers from some outstanding requests
                    if (_log.shouldLog(Log.INFO))
                        _log.info(getJobId() + ": No peers left, but some are pending!  Pending: " 
                                  + _state.getPending().size() + " attempted: " + _state.getAttempted().size() 
                                  + " failed: " + _state.getFailed().size());
                    requeuePending();
                }
                return;
            } else {
                attempted.addAll(closestHashes);
                for (Iterator iter = closestHashes.iterator(); iter.hasNext(); ) {
                    Hash peer = (Hash)iter.next();
                    DataStructure ds = _facade.getDataStore().get(peer);
                    if (ds == null) {
                        if (_log.shouldLog(Log.INFO))
                            _log.info("Next closest peer " + peer + " was only recently referred to us, sending a search for them");
                        getContext().netDb().lookupRouterInfo(peer, null, null, _timeoutMs);
                    } else if (!(ds instanceof RouterInfo)) {
                        if (_log.shouldLog(Log.WARN))
                            _log.warn(getJobId() + ": Error selecting closest hash that wasnt a router! " 
                                      + peer + " : " + (ds == null ? "null" : ds.getClass().getName()));
                        _state.replyTimeout(peer);
                    } else {
                        if (((RouterInfo)ds).isHidden() ||
                            getContext().shitlist().isShitlisted(peer)) {
                            // dont bother
                        } else {
                            _state.addPending(peer);
                            sendSearch((RouterInfo)ds);
                            sent++;
                        }
                    }
                }
                /*
                if (sent <= 0) {
                    // the (potentially) last peers being searched for could not be,
                    // er, searched for, so lets retry ASAP (causing either another 
                    // peer to be selected, or the whole search to fail)
                    if (_log.shouldLog(Log.INFO))
                        _log.info(getJobId() + ": No new peer queued up, so we are going to requeue " +
                                  "ourselves in our search for " + _state.getTarget().toBase64());
                    requeuePending(0);
                }
                 */
            }
        }
    }
    
    private void requeuePending() {
        // timeout/2 to average things out (midway through)
        long perPeerTimeout = getPerPeerTimeoutMs()/2;
        if (perPeerTimeout < REQUEUE_DELAY)
            requeuePending(perPeerTimeout);
        else
            requeuePending(REQUEUE_DELAY);
    }
    private void requeuePending(long ms) {
        if (_pendingRequeueJob == null)
            _pendingRequeueJob = new RequeuePending(getContext());
        long now = getContext().clock().now();
        if (_pendingRequeueJob.getTiming().getStartAfter() < now)
            _pendingRequeueJob.getTiming().setStartAfter(now+ms);
        getContext().jobQueue().addJob(_pendingRequeueJob);
    }

    private class RequeuePending extends JobImpl {
        public RequeuePending(RouterContext enclosingContext) {
            super(enclosingContext);
        }
        public String getName() { return "Requeue search with pending"; }
        public void runJob() { searchNext(); }
    }
    
    /**
     * Set of Hash structures for routers we want to check next.  This is the 'interesting' part of
     * the algorithm.  But to keep you on your toes, we've refactored it to the PeerSelector.selectNearestExplicit  
     *
     * @return ordered list of Hash objects
     */
    private List getClosestRouters(Hash key, int numClosest, Set alreadyChecked) {
        Hash rkey = getContext().routingKeyGenerator().getRoutingKey(key);
        if (_log.shouldLog(Log.DEBUG))
            _log.debug(getJobId() + ": Current routing key for " + key + ": " + rkey);
        return _peerSelector.selectNearestExplicit(rkey, numClosest, alreadyChecked, _facade.getKBuckets());
    }
    
    /**
     * Send a search to the given peer
     *
     */
    protected void sendSearch(RouterInfo router) {
        if (router.getIdentity().equals(getContext().router().getRouterInfo().getIdentity())) {
            // don't search ourselves
            if (_log.shouldLog(Log.ERROR))
                _log.error(getJobId() + ": Dont send search to ourselves - why did we try?");
            return;
        } else {
            if (_log.shouldLog(Log.INFO))
                _log.info(getJobId() + ": Send search to " + router.getIdentity().getHash().toBase64()
                          + " for " + _state.getTarget().toBase64());
        }

        getContext().statManager().addRateData("netDb.searchMessageCount", 1, 0);

        if (_isLease || false) // moo
            sendLeaseSearch(router);
        else
            sendRouterSearch(router);
    }
    
    
    /** 
     * we're (probably) searching for a LeaseSet, so to be (overly) cautious, we're sending 
     * the request out through a tunnel w/ reply back through another tunnel.
     *
     */
    protected void sendLeaseSearch(RouterInfo router) {
        TunnelInfo inTunnel = getInboundTunnelId(); 
        if (inTunnel == null) {
            _log.error("No tunnels to get search replies through!  wtf!");
            getContext().jobQueue().addJob(new FailedJob(getContext(), router));
            return;
        }
        TunnelId inTunnelId = inTunnel.getReceiveTunnelId(0);

        // this will fail if we've shitlisted our inbound gateway, but the gw may not necessarily
        // be shitlisted by whomever needs to contact them, so we don't need to check this
        
        //RouterInfo inGateway = getContext().netDb().lookupRouterInfoLocally(inTunnel.getPeer(0));
        //if (inGateway == null) {
        //    _log.error("We can't find the gateway to our inbound tunnel?! wtf");
        //    getContext().jobQueue().addJob(new FailedJob(getContext(), router));
        //    return;
        //}
	
        int timeout = _facade.getPeerTimeout(router.getIdentity().getHash());
        long expiration = getContext().clock().now() + timeout;

        DatabaseLookupMessage msg = buildMessage(inTunnelId, inTunnel.getPeer(0), expiration);	
	
        TunnelInfo outTunnel = getOutboundTunnelId();
        if (outTunnel == null) {
            _log.error("No tunnels to send search out through!  wtf!");
            getContext().jobQueue().addJob(new FailedJob(getContext(), router));
            return;
        }        
        TunnelId outTunnelId = outTunnel.getSendTunnelId(0);

	
        if (_log.shouldLog(Log.DEBUG))
            _log.debug(getJobId() + ": Sending leaseSet search to " + router.getIdentity().getHash().toBase64() 
                       + " for " + msg.getSearchKey().toBase64() + " w/ replies through [" 
                       + msg.getFrom().toBase64() + "] via tunnel [" 
                       + msg.getReplyTunnel() + "]");

        SearchMessageSelector sel = new SearchMessageSelector(getContext(), router, _expiration, _state);
        SearchUpdateReplyFoundJob reply = new SearchUpdateReplyFoundJob(getContext(), router, _state, _facade, 
                                                                        this, outTunnel, inTunnel);
        
        getContext().messageRegistry().registerPending(sel, reply, new FailedJob(getContext(), router), timeout);
        getContext().tunnelDispatcher().dispatchOutbound(msg, outTunnelId, router.getIdentity().getHash());
    }
    
    /** we're searching for a router, so we can just send direct */
    protected void sendRouterSearch(RouterInfo router) {
        int timeout = _facade.getPeerTimeout(router.getIdentity().getHash());
        long expiration = getContext().clock().now() + timeout;

        DatabaseLookupMessage msg = buildMessage(expiration);

        if (_log.shouldLog(Log.DEBUG))
            _log.debug(getJobId() + ": Sending router search to " + router.getIdentity().getHash().toBase64() 
                      + " for " + msg.getSearchKey().toBase64() + " w/ replies to us [" 
                      + msg.getFrom().toBase64() + "]");
        SearchMessageSelector sel = new SearchMessageSelector(getContext(), router, _expiration, _state);
        SearchUpdateReplyFoundJob reply = new SearchUpdateReplyFoundJob(getContext(), router, _state, _facade, this);
        SendMessageDirectJob j = new SendMessageDirectJob(getContext(), msg, router.getIdentity().getHash(), 
                                                          reply, new FailedJob(getContext(), router), sel, timeout, SEARCH_PRIORITY);
        getContext().jobQueue().addJob(j);
    }
    
    /** 
     * what tunnel will we send the search out through? 
     *
     * @return tunnel id (or null if none are found)
     */
    private TunnelInfo getOutboundTunnelId() {
        return getContext().tunnelManager().selectOutboundTunnel();
    }
    
    /**
     * what tunnel will we get replies through?
     *
     * @return tunnel id (or null if none are found)
     */
    private TunnelInfo getInboundTunnelId() {
        return getContext().tunnelManager().selectInboundTunnel();
    }

    /**
     * Build the database search message 
     *
     * @param replyTunnelId tunnel to receive replies through
     * @param replyGateway gateway for the reply tunnel
     * @param expiration when the search should stop 
     */
    protected DatabaseLookupMessage buildMessage(TunnelId replyTunnelId, Hash replyGateway, long expiration) {
        DatabaseLookupMessage msg = new DatabaseLookupMessage(getContext(), true);
        msg.setSearchKey(_state.getTarget());
        //msg.setFrom(replyGateway.getIdentity().getHash());
        msg.setFrom(replyGateway);
        msg.setDontIncludePeers(_state.getClosestAttempted(MAX_CLOSEST));
        msg.setMessageExpiration(expiration);
        msg.setReplyTunnel(replyTunnelId);
        return msg;
    }
    
    /**
     * We're looking for a router, so lets build the lookup message (no need to tunnel route either, so just have
     * replies sent back to us directly)
     *
     */
    protected DatabaseLookupMessage buildMessage(long expiration) {
        DatabaseLookupMessage msg = new DatabaseLookupMessage(getContext(), true);
        msg.setSearchKey(_state.getTarget());
        msg.setFrom(getContext().routerHash());
        msg.setDontIncludePeers(_state.getClosestAttempted(MAX_CLOSEST));
        msg.setMessageExpiration(expiration);
        msg.setReplyTunnel(null);
        return msg;
    }
    
    void replyFound(DatabaseSearchReplyMessage message, Hash peer) {
        long duration = _state.replyFound(peer);
        // this processing can take a while, so split 'er up
        getContext().jobQueue().addJob(new SearchReplyJob(getContext(), (DatabaseSearchReplyMessage)message, peer, duration));
    }
    
    /**
     * We've gotten a search reply that contained the specified
     * number of peers that we didn't know about before.
     *
     */
    protected void newPeersFound(int numNewPeers) {
        // noop
    }
    
    private final class SearchReplyJob extends JobImpl {
        private DatabaseSearchReplyMessage _msg;
        /** 
         * Peer who we think sent us the reply.  Note: could be spoofed!  If the
         * attacker knew we were searching for a particular key from a 
         * particular peer, they could send us some searchReply messages with
         * shitty values, trying to get us to consider that peer unreliable.  
         * Potential fixes include either authenticated 'from' address or use a
         * nonce in the search + searchReply (and check for it in the selector).
         *
         */
        private Hash _peer;
        private int _curIndex;
        private int _invalidPeers;
        private int _seenPeers;
        private int _newPeers;
        private int _duplicatePeers;
        private int _repliesPendingVerification;
        private long _duration;
        public SearchReplyJob(RouterContext enclosingContext, DatabaseSearchReplyMessage message, Hash peer, long duration) {
            super(enclosingContext);
            _msg = message;
            _peer = peer;
            _curIndex = 0;
            _invalidPeers = 0;
            _seenPeers = 0;
            _newPeers = 0;
            _duplicatePeers = 0;
            _repliesPendingVerification = 0;
        }
        public String getName() { return "Process Reply for Kademlia Search"; }
        public void runJob() {
            if (_curIndex >= _msg.getNumReplies()) {
                if (_repliesPendingVerification > 0) {
                    // we received new references from the peer, but still 
                    // haven't verified all of them, so lets give it more time
                    SearchReplyJob.this.requeue(_timeoutMs);
                } else {
                    // either they didn't tell us anything new or we have verified
                    // (or failed to verify) all of them.  we're done
                    getContext().profileManager().dbLookupReply(_peer, _newPeers, _seenPeers, 
                                                               _invalidPeers, _duplicatePeers, _duration);
                    if (_newPeers > 0)
                        newPeersFound(_newPeers);
                }
            } else {
                Hash peer = _msg.getReply(_curIndex);
                
                boolean shouldAdd = false;
                
                RouterInfo info = getContext().netDb().lookupRouterInfoLocally(peer);
                if (info == null) {
                    // if the peer is giving us lots of bad peer references, 
                    // dont try to fetch them.
                    
                    boolean sendsBadInfo = getContext().profileOrganizer().peerSendsBadReplies(_peer);
                    if (!sendsBadInfo) {
                        // we don't need to search for everthing we're given here - only ones that
                        // are next in our search path...
                        if (getContext().shitlist().isShitlisted(peer)) {
                            if (_log.shouldLog(Log.INFO))
                                _log.info("Not looking for a shitlisted peer...");
                            getContext().statManager().addRateData("netDb.searchReplyValidationSkipped", 1, 0);
                        } else {
                            //getContext().netDb().lookupRouterInfo(peer, new ReplyVerifiedJob(getContext(), peer), new ReplyNotVerifiedJob(getContext(), peer), _timeoutMs);
                            //_repliesPendingVerification++;
                            shouldAdd = true;
                        }
                    } else {
                        if (_log.shouldLog(Log.INFO))
                            _log.info("Peer " + _peer.toBase64() + " sends us bad replies, so not verifying " + peer.toBase64());
                        getContext().statManager().addRateData("netDb.searchReplyValidationSkipped", 1, 0);
                    }
                }
            
                if (_state.wasAttempted(peer)) {
                    _duplicatePeers++;
                } 
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug(getJobId() + ": dbSearchReply received on search referencing router " 
                              + peer);
                if (shouldAdd) {
                    if (_facade.getKBuckets().add(peer))
                        _newPeers++;
                    else
                        _seenPeers++;
                }
                
                _curIndex++;
                requeue(0);
            }
        }
        
        /** the peer gave us a reference to a new router, and we were able to fetch it */
        private final class ReplyVerifiedJob extends JobImpl {
            private Hash _key;
            public ReplyVerifiedJob(RouterContext enclosingContext, Hash key) {
                super(enclosingContext);
                _key = key;
            }
            public String getName() { return "Search reply value verified"; }
            public void runJob() {
                if (_log.shouldLog(Log.INFO))
                    _log.info("Peer reply from " + _peer.toBase64() + " verified: " + _key.toBase64());
                _repliesPendingVerification--;
                getContext().statManager().addRateData("netDb.searchReplyValidated", 1, 0);
            }
        }
        /** the peer gave us a reference to a new router, and we were NOT able to fetch it */
        private final class ReplyNotVerifiedJob extends JobImpl {
            private Hash _key;
            public ReplyNotVerifiedJob(RouterContext enclosingContext, Hash key) {
                super(enclosingContext);
                _key = key;
            }
            public String getName() { return "Search reply value NOT verified"; }
            public void runJob() {
                if (_log.shouldLog(Log.INFO))
                    _log.info("Peer reply from " + _peer.toBase64() + " failed verification: " + _key.toBase64());
                _repliesPendingVerification--;
                _invalidPeers++;
                getContext().statManager().addRateData("netDb.searchReplyNotValidated", 1, 0);
            }
        }
    }

    /**
     * Called when a particular peer failed to respond before the timeout was 
     * reached, or if the peer could not be contacted at all.
     *
     */
    protected class FailedJob extends JobImpl {
        private Hash _peer;
        private boolean _penalizePeer;
        private long _sentOn;
        public FailedJob(RouterContext enclosingContext, RouterInfo peer) {
            this(enclosingContext, peer, true);
        }
        /**
         * Allow the choice as to whether failed searches should count against
         * the peer (such as if we search for a random key)
         *
         */
        public FailedJob(RouterContext enclosingContext, RouterInfo peer, boolean penalizePeer) {
            super(enclosingContext);
            _penalizePeer = penalizePeer;
            _peer = peer.getIdentity().getHash();
            _sentOn = enclosingContext.clock().now();
        }
        public void runJob() {
            if (_state.completed()) return;
            _state.replyTimeout(_peer);
            if (_penalizePeer) { 
                if (_log.shouldLog(Log.INFO))
                    _log.info("Penalizing peer for timeout on search: " + _peer.toBase64() + " after " + (getContext().clock().now() - _sentOn));
                getContext().profileManager().dbLookupFailed(_peer);
            } else {
                if (_log.shouldLog(Log.ERROR))
                    _log.error("NOT (!!) Penalizing peer for timeout on search: " + _peer.toBase64());
            }
            getContext().statManager().addRateData("netDb.failedPeers", 1, 0);
            searchNext();
        }
        public String getName() { return "Kademlia Search Failed"; }
    }
    
    /**
     * Search was totally successful
     */
    private void succeed() {
        if (_log.shouldLog(Log.INFO))
            _log.info(getJobId() + ": Succeeded search for key " + _state.getTarget() 
                      + " after querying " + _state.getAttempted().size());
        if (_log.shouldLog(Log.DEBUG))
            _log.debug(getJobId() + ": State of successful search: " + _state);
	
        if (_keepStats) {
            long time = getContext().clock().now() - _state.getWhenStarted();
            getContext().statManager().addRateData("netDb.successTime", time, 0);
            getContext().statManager().addRateData("netDb.successPeers", _state.getAttempted().size(), time);
        }
        if (_onSuccess != null)
            getContext().jobQueue().addJob(_onSuccess);
        
        _facade.searchComplete(_state.getTarget());
        
        handleDeferred(true);
        
        resend();
    }
    
    /**
     * After a successful search for a leaseSet, we resend that leaseSet to all
     * of the peers we tried and failed to query.  This var bounds how many of
     * those peers will get the data, in case a search had to crawl about 
     * substantially.
     *
     */
    private static final int MAX_LEASE_RESEND = 10;
    
    /**
     * Should we republish a routerInfo received?  Probably not worthwhile, since
     * routerInfo entries should be very easy to find.
     *
     */
    private static final boolean SHOULD_RESEND_ROUTERINFO = false;
    
    /**
     * After we get the data we were searching for, rebroadcast it to the peers
     * we would query first if we were to search for it again (healing the network).
     *
     */
    private void resend() {
        DataStructure ds = _facade.lookupLeaseSetLocally(_state.getTarget());
        if (ds == null) {
            if (SHOULD_RESEND_ROUTERINFO) {
                ds = _facade.lookupRouterInfoLocally(_state.getTarget());
                if (ds != null)
                    _facade.sendStore(_state.getTarget(), ds, null, null, RESEND_TIMEOUT, _state.getSuccessful());
            }
        } else {
            Set sendTo = _state.getRepliedPeers(); // _state.getFailed();
            sendTo.addAll(_state.getPending());
            int numSent = 0;
            for (Iterator iter = sendTo.iterator(); iter.hasNext(); ) {
                Hash peer = (Hash)iter.next();
                RouterInfo peerInfo = _facade.lookupRouterInfoLocally(peer);
                if (peerInfo == null) continue;
                if (resend(peerInfo, (LeaseSet)ds))
                    numSent++;
                if (numSent >= MAX_LEASE_RESEND)
                    break;
            }
            getContext().statManager().addRateData("netDb.republishQuantity", numSent, numSent);
        }
    }

    /**
     * Resend the leaseSet to the peer who had previously failed to 
     * provide us with the data when we asked them.  
     */
    private boolean resend(RouterInfo toPeer, LeaseSet ls) {
        DatabaseStoreMessage msg = new DatabaseStoreMessage(getContext());
        msg.setKey(ls.getDestination().calculateHash());
        msg.setLeaseSet(ls);
        msg.setMessageExpiration(getContext().clock().now() + RESEND_TIMEOUT);

        TunnelInfo outTunnel = getContext().tunnelManager().selectOutboundTunnel();

        if (outTunnel != null) {
            TunnelId targetTunnelId = null; // not needed
            Job onSend = null; // not wanted
            
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("resending leaseSet out to " + toPeer.getIdentity().getHash() + " through " + outTunnel + ": " + msg);
            getContext().tunnelDispatcher().dispatchOutbound(msg, outTunnel.getSendTunnelId(0), null, toPeer.getIdentity().getHash());
            return true;
        } else {
            if (_log.shouldLog(Log.WARN))
                _log.warn("unable to resend a leaseSet - no outbound exploratory tunnels!");
            return false;
        }
    }

    /**
     * Search totally failed
     */
    protected void fail() {
        if (isLocal()) {
            if (_log.shouldLog(Log.ERROR))
                _log.error(getJobId() + ": why did we fail if the target is local?: " + _state.getTarget().toBase64(), new Exception("failure cause"));
            succeed();
            return;
        }
            
        if (_log.shouldLog(Log.INFO))
            _log.info(getJobId() + ": Failed search for key " + _state.getTarget());
        if (_log.shouldLog(Log.DEBUG))
            _log.debug(getJobId() + ": State of failed search: " + _state);
        
        if (_keepStats) {
            long time = getContext().clock().now() - _state.getWhenStarted();
            getContext().statManager().addRateData("netDb.failedTime", time, 0);
            //_facade.fail(_state.getTarget());
        }
        if (_onFailure != null)
            getContext().jobQueue().addJob(_onFailure);
        
        _facade.searchComplete(_state.getTarget());
        handleDeferred(false);
    }

    public int addDeferred(Job onFind, Job onFail, long expiration, boolean isLease) {
        Search search = new Search(onFind, onFail, expiration, isLease);
        boolean ok = true;
        int deferred = 0;
        synchronized (_deferredSearches) {
            if (_deferredCleared)
                ok = false;
            else
                _deferredSearches.add(search);
            deferred = _deferredSearches.size();
        }
        
        if (!ok) {
            // race between adding deferred and search completing
            if (_log.shouldLog(Log.WARN))
                _log.warn("Race deferred before searchCompleting?  our onFind=" + _onSuccess + " new one: " + onFind);
            
            // the following /shouldn't/ be necessary, but it doesnt hurt 
            _facade.searchComplete(_state.getTarget());
            _facade.search(_state.getTarget(), onFind, onFail, expiration - getContext().clock().now(), isLease);
            return 0;
        } else {
            return deferred;
        }
    }
    
    private void handleDeferred(boolean success) {
        List deferred = null;
        synchronized (_deferredSearches) {
            if (_deferredSearches.size() > 0) {
                deferred = new ArrayList(_deferredSearches);
                _deferredSearches.clear();
            }
            _deferredCleared = true;
        }
        if (deferred != null) {
            long now = getContext().clock().now();
            for (int i = 0; i < deferred.size(); i++) {
                Search cur = (Search)deferred.get(i);
                if (cur.getExpiration() < now)
                    getContext().jobQueue().addJob(cur.getOnFail());
                else if (success)
                    getContext().jobQueue().addJob(cur.getOnFind());
                else // failed search, not yet expired, but it took too long to reasonably continue
                    getContext().jobQueue().addJob(cur.getOnFail());
            }
        }
    }
    
    private class Search {
        private Job _onFind;
        private Job _onFail;
        private long _expiration;
        private boolean _isLease;
        
        public Search(Job onFind, Job onFail, long expiration, boolean isLease) {
            _onFind = onFind;
            _onFail = onFail;
            _expiration = expiration;
            _isLease = isLease;
        }
        public Job getOnFind() { return _onFind; }
        public Job getOnFail() { return _onFail; }
        public long getExpiration() { return _expiration; }
        public boolean getIsLease() { return _isLease; }
    }
    
    public String getName() { return "Kademlia NetDb Search"; }
    
    public String toString() { 
        return super.toString() + " started " 
               + DataHelper.formatDuration((getContext().clock().now() - _startedOn)) + " ago";
    }
}
