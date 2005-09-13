package net.i2p.router.networkdb.kademlia;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.*;

import net.i2p.data.DataStructure;
import net.i2p.data.Hash;
import net.i2p.data.LeaseSet;
import net.i2p.data.RouterInfo;
import net.i2p.data.TunnelId;
import net.i2p.data.i2np.DatabaseStoreMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.router.Job;
import net.i2p.router.JobImpl;
import net.i2p.router.OutNetMessage;
import net.i2p.router.ReplyJob;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelInfo;
import net.i2p.router.peermanager.PeerProfile;
import net.i2p.stat.Rate;
import net.i2p.stat.RateStat;
import net.i2p.util.Log;

class StoreJob extends JobImpl {
    private Log _log;
    private KademliaNetworkDatabaseFacade _facade;
    protected StoreState _state;
    private Job _onSuccess;
    private Job _onFailure;
    private long _timeoutMs;
    private long _expiration;
    private PeerSelector _peerSelector;

    private final static int PARALLELIZATION = 4; // how many sent at a time
    private final static int REDUNDANCY = 4; // we want the data sent to 6 peers
    /**
     * additionally send to 1 outlier(s), in case all of the routers chosen in our
     * REDUNDANCY set are attacking us by accepting DbStore messages but dropping
     * the data.  
     *
     * TODO: um, honor this.  make sure we send to this many peers that aren't 
     * closest to the key.
     *
     */
    private final static int EXPLORATORY_REDUNDANCY = 1; 
    private final static int STORE_PRIORITY = 100;
    
    /**
     * Create a new search for the routingKey specified
     * 
     */
    public StoreJob(RouterContext context, KademliaNetworkDatabaseFacade facade, Hash key, 
                    DataStructure data, Job onSuccess, Job onFailure, long timeoutMs) {
        this(context, facade, key, data, onSuccess, onFailure, timeoutMs, null);
    }
    
    /**
     * @param toSkip set of peer hashes of people we dont want to send the data to (e.g. we
     *               already know they have it).  This can be null.
     */
    public StoreJob(RouterContext context, KademliaNetworkDatabaseFacade facade, Hash key, 
                    DataStructure data, Job onSuccess, Job onFailure, long timeoutMs, Set toSkip) {
        super(context);
        _log = context.logManager().getLog(StoreJob.class);
        getContext().statManager().createRateStat("netDb.storeRouterInfoSent", "How many routerInfo store messages have we sent?", "NetworkDatabase", new long[] { 5*60*1000l, 60*60*1000l, 24*60*60*1000l });
        getContext().statManager().createRateStat("netDb.storeLeaseSetSent", "How many leaseSet store messages have we sent?", "NetworkDatabase", new long[] { 5*60*1000l, 60*60*1000l, 24*60*60*1000l });
        getContext().statManager().createRateStat("netDb.storePeers", "How many peers each netDb must be sent to before success?", "NetworkDatabase", new long[] { 5*60*1000l, 60*60*1000l, 24*60*60*1000l });
        getContext().statManager().createRateStat("netDb.storeFailedPeers", "How many peers each netDb must be sent to before failing completely?", "NetworkDatabase", new long[] { 5*60*1000l, 60*60*1000l, 24*60*60*1000l });
        getContext().statManager().createRateStat("netDb.ackTime", "How long does it take for a peer to ack a netDb store?", "NetworkDatabase", new long[] { 5*60*1000l, 60*60*1000l, 24*60*60*1000l });
        getContext().statManager().createRateStat("netDb.replyTimeout", "How long after a netDb send does the timeout expire (when the peer doesn't reply in time)?", "NetworkDatabase", new long[] { 60*1000, 5*60*1000l, 60*60*1000l, 24*60*60*1000l });
        _facade = facade;
        _state = new StoreState(getContext(), key, data, toSkip);
        _onSuccess = onSuccess;
        _onFailure = onFailure;
        _timeoutMs = timeoutMs;
        _expiration = context.clock().now() + timeoutMs;
        _peerSelector = facade.getPeerSelector();
    }

    public String getName() { return "Kademlia NetDb Store";}
    public void runJob() {
        sendNext();
    }

    private boolean isExpired() { 
        return getContext().clock().now() >= _expiration;
    }

    /**
     * send the key to the next batch of peers
     */
    private void sendNext() {
        if (_state.completed()) {
            if (_log.shouldLog(Log.INFO))
                _log.info("Already completed");
            return;
        }
        if (isExpired()) {
            _state.complete(true);
            fail();
        } else {
            //if (_log.shouldLog(Log.INFO))
            //    _log.info(getJobId() + ": Sending: " + _state);
            continueSending();
        }
    }
    
    protected int getParallelization() { return PARALLELIZATION; }
    protected int getRedundancy() { return REDUNDANCY; }

    /**
     * Send a series of searches to the next available peers as selected by
     * the routing table, but making sure no more than PARALLELIZATION are outstanding
     * at any time
     *
     */
    private void continueSending() { 
        if (_state.completed()) return;
        int toCheck = getParallelization() - _state.getPending().size();
        if (toCheck <= 0) {
            // too many already pending
            if (_log.shouldLog(Log.DEBUG))
                _log.debug(getJobId() + ": Too many store messages pending");
            return;
        } 
        if (toCheck > getParallelization())
            toCheck = getParallelization();

        List closestHashes = getClosestRouters(_state.getTarget(), toCheck, _state.getAttempted());
        if ( (closestHashes == null) || (closestHashes.size() <= 0) ) {
            if (_state.getPending().size() <= 0) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn(getJobId() + ": No more peers left and none pending");
                fail();
            } else {
                if (_log.shouldLog(Log.WARN))
                    _log.warn(getJobId() + ": No more peers left but some are pending, so keep waiting");
                return;
            }
        } else {
            //_state.addPending(closestHashes);
            if (_log.shouldLog(Log.INFO))
                _log.info(getJobId() + ": Continue sending key " + _state.getTarget() + " after " + _state.getAttempted().size() + " tries to " + closestHashes);
            for (Iterator iter = closestHashes.iterator(); iter.hasNext(); ) {
                Hash peer = (Hash)iter.next();
                DataStructure ds = _facade.getDataStore().get(peer);
                if ( (ds == null) || !(ds instanceof RouterInfo) ) {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn(getJobId() + ": Error selecting closest hash that wasnt a router! " + peer + " : " + ds);
                    _state.addSkipped(peer);
                } else {
                    int peerTimeout = _facade.getPeerTimeout(peer);
                    PeerProfile prof = getContext().profileOrganizer().getProfile(peer);
                    RateStat failing = prof.getDBHistory().getFailedLookupRate();
                    Rate failed = failing.getRate(60*60*1000);
                    //long failedCount = failed.getCurrentEventCount()+failed.getLastEventCount();
                    //if (failedCount > 10) {
                    //    _state.addSkipped(peer);
                    //    continue;
                    //}
                    //
                    //if (failed.getCurrentEventCount() + failed.getLastEventCount() > avg) {
                    //    _state.addSkipped(peer);
                    //}
                    
                    // we don't want to filter out peers based on our local shitlist, as that opens an avenue for
                    // manipulation (since a peer can get us to shitlist them by, well, being shitty, and that
                    // in turn would let them assume that a netDb store received didn't come from us)
                    //if (getContext().shitlist().isShitlisted(((RouterInfo)ds).getIdentity().calculateHash())) {
                    //    _state.addSkipped(peer);
                    //} else {
                        _state.addPending(peer);
                        sendStore((RouterInfo)ds, peerTimeout);
                    //}
                }
            }
        }
    }

    /**
     * Set of Hash structures for routers we want to send the data to next.  This is the 
     * 'interesting' part of the algorithm.  DBStore isn't usually as time sensitive as 
     * it is reliability sensitive, so lets delegate it off to the PeerSelector via 
     * selectNearestExplicit, which is currently O(n*log(n))
     *
     * @return ordered list of Hash objects
     */
    private List getClosestRouters(Hash key, int numClosest, Set alreadyChecked) {
        Hash rkey = getContext().routingKeyGenerator().getRoutingKey(key);
        //if (_log.shouldLog(Log.DEBUG))
        //    _log.debug(getJobId() + ": Current routing key for " + key + ": " + rkey);

        KBucketSet ks = _facade.getKBuckets();
        if (ks == null) return new ArrayList();
        return _peerSelector.selectNearestExplicit(rkey, numClosest, alreadyChecked, ks);
    }

    /**
     * Send a store to the given peer through a garlic route, including a reply 
     * DeliveryStatusMessage so we know it got there
     *
     */
    private void sendStore(RouterInfo router, int responseTime) {
        DatabaseStoreMessage msg = new DatabaseStoreMessage(getContext());
        msg.setKey(_state.getTarget());
        if (_state.getData() instanceof RouterInfo) 
            msg.setRouterInfo((RouterInfo)_state.getData());
        else if (_state.getData() instanceof LeaseSet) 
            msg.setLeaseSet((LeaseSet)_state.getData());
        else
            throw new IllegalArgumentException("Storing an unknown data type! " + _state.getData());
        msg.setMessageExpiration(getContext().clock().now() + _timeoutMs);

        if (router.getIdentity().equals(getContext().router().getRouterInfo().getIdentity())) {
            // don't send it to ourselves
            if (_log.shouldLog(Log.ERROR))
                _log.error(getJobId() + ": Dont send store to ourselves - why did we try?");
            return;
        } else {
            //if (_log.shouldLog(Log.DEBUG))
            //    _log.debug(getJobId() + ": Send store to " + router.getIdentity().getHash().toBase64());
        }

        sendStore(msg, router, getContext().clock().now() + responseTime);
    }
    
    private void sendStore(DatabaseStoreMessage msg, RouterInfo peer, long expiration) {
        if (msg.getValueType() == DatabaseStoreMessage.KEY_TYPE_LEASESET) {
            getContext().statManager().addRateData("netDb.storeLeaseSetSent", 1, 0);
            sendStoreThroughGarlic(msg, peer, expiration);
        } else {
            getContext().statManager().addRateData("netDb.storeRouterInfoSent", 1, 0);
            sendDirect(msg, peer, expiration);
        }
    }

    private void sendDirect(DatabaseStoreMessage msg, RouterInfo peer, long expiration) {
        long token = getContext().random().nextLong(I2NPMessage.MAX_ID_VALUE);
        msg.setReplyToken(token);
        msg.setReplyGateway(getContext().routerHash());

        if (_log.shouldLog(Log.DEBUG))
            _log.debug(getJobId() + ": send(dbStore) w/ token expected " + token);
        
        _state.addPending(peer.getIdentity().getHash());
        
        SendSuccessJob onReply = new SendSuccessJob(getContext(), peer);
        FailedJob onFail = new FailedJob(getContext(), peer, getContext().clock().now());
        StoreMessageSelector selector = new StoreMessageSelector(getContext(), getJobId(), peer, token, expiration);
        
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("sending store directly to " + peer.getIdentity().getHash());
        OutNetMessage m = new OutNetMessage(getContext());
        m.setExpiration(expiration);
        m.setMessage(msg);
        m.setOnFailedReplyJob(onFail);
        m.setOnFailedSendJob(onFail);
        m.setOnReplyJob(onReply);
        m.setPriority(STORE_PRIORITY);
        m.setReplySelector(selector);
        m.setTarget(peer);
        getContext().commSystem().processMessage(m);
    }
    
    private void sendStoreThroughGarlic(DatabaseStoreMessage msg, RouterInfo peer, long expiration) {
        long token = getContext().random().nextLong(I2NPMessage.MAX_ID_VALUE);
        
        TunnelInfo replyTunnel = selectInboundTunnel();
        if (replyTunnel == null) {
            _log.error("No reply inbound tunnels available!");
            return;
        }
        TunnelId replyTunnelId = replyTunnel.getReceiveTunnelId(0);
        if (replyTunnel == null) {
            _log.error("No reply inbound tunnels available!");
            return;
        }
        msg.setReplyToken(token);
        msg.setReplyTunnel(replyTunnelId);
        msg.setReplyGateway(replyTunnel.getPeer(0));

        if (_log.shouldLog(Log.DEBUG))
            _log.debug(getJobId() + ": send(dbStore) w/ token expected " + token);
        
        _state.addPending(peer.getIdentity().getHash());
        
        SendSuccessJob onReply = new SendSuccessJob(getContext(), peer);
        FailedJob onFail = new FailedJob(getContext(), peer, getContext().clock().now());
        StoreMessageSelector selector = new StoreMessageSelector(getContext(), getJobId(), peer, token, expiration);
        
        TunnelInfo outTunnel = selectOutboundTunnel();
        if (outTunnel != null) {
            //if (_log.shouldLog(Log.DEBUG))
            //    _log.debug(getJobId() + ": Sending tunnel message out " + outTunnelId + " to " 
            //               + peer.getIdentity().getHash().toBase64());
            TunnelId targetTunnelId = null; // not needed
            Job onSend = null; // not wanted
            
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("sending store to " + peer.getIdentity().getHash() + " through " + outTunnel + ": " + msg);
            getContext().messageRegistry().registerPending(selector, onReply, onFail, (int)(expiration - getContext().clock().now()));
            getContext().tunnelDispatcher().dispatchOutbound(msg, outTunnel.getSendTunnelId(0), null, peer.getIdentity().getHash());
        } else {
            if (_log.shouldLog(Log.ERROR))
                _log.error("No outbound tunnels to send a dbStore out!");
            fail();
        }
    }
    
    private TunnelInfo selectOutboundTunnel() {
        return getContext().tunnelManager().selectOutboundTunnel();
    }
 
    private TunnelInfo selectInboundTunnel() {
        return getContext().tunnelManager().selectInboundTunnel();
    }
 
    /**
     * Called after sending a dbStore to a peer successfully, 
     * marking the store as successful
     *
     */
    private class SendSuccessJob extends JobImpl implements ReplyJob {
        private RouterInfo _peer;
        
        public SendSuccessJob(RouterContext enclosingContext, RouterInfo peer) {
            super(enclosingContext);
            _peer = peer;
        }

        public String getName() { return "Kademlia Store Send Success"; }
        public void runJob() {
            long howLong = _state.confirmed(_peer.getIdentity().getHash());
            if (_log.shouldLog(Log.INFO))
                _log.info(StoreJob.this.getJobId() + ": Marking store of " + _state.getTarget() 
                          + " to " + _peer.getIdentity().getHash().toBase64() + " successful after " + howLong);
            getContext().profileManager().dbStoreSent(_peer.getIdentity().getHash(), howLong);
            getContext().statManager().addRateData("netDb.ackTime", howLong, howLong);

            if (_state.getCompleteCount() >= getRedundancy()) {
                succeed();
            } else {
                sendNext();
            }
        }
        
        public void setMessage(I2NPMessage message) {
            // ignored, since if the selector matched it, its fine by us
        }
    }

    /**
     * Called when a particular peer failed to respond before the timeout was 
     * reached, or if the peer could not be contacted at all.
     *
     */
    private class FailedJob extends JobImpl {
        private RouterInfo _peer;
        private long _sendOn;

        public FailedJob(RouterContext enclosingContext, RouterInfo peer, long sendOn) {
            super(enclosingContext);
            _peer = peer;
            _sendOn = sendOn;
        }
        public void runJob() {
            if (_log.shouldLog(Log.WARN))
                _log.warn(StoreJob.this.getJobId() + ": Peer " + _peer.getIdentity().getHash().toBase64() 
                          + " timed out sending " + _state.getTarget());
            _state.replyTimeout(_peer.getIdentity().getHash());
            getContext().profileManager().dbStoreFailed(_peer.getIdentity().getHash());
            getContext().statManager().addRateData("netDb.replyTimeout", getContext().clock().now() - _sendOn, 0);
            
            sendNext();
        }
        public String getName() { return "Kademlia Store Peer Failed"; }
    }

    /**
     * Send was totally successful
     */
    protected void succeed() {
        if (_log.shouldLog(Log.INFO))
            _log.info(getJobId() + ": Succeeded sending key " + _state.getTarget());
        if (_log.shouldLog(Log.DEBUG))
            _log.debug(getJobId() + ": State of successful send: " + _state);
        if (_onSuccess != null)
            getContext().jobQueue().addJob(_onSuccess);
        _facade.noteKeySent(_state.getTarget());
        _state.complete(true);
        getContext().statManager().addRateData("netDb.storePeers", _state.getAttempted().size(), _state.getWhenCompleted()-_state.getWhenStarted());
    }

    /**
     * Send totally failed
     */
    protected void fail() {
        if (_log.shouldLog(Log.WARN))
            _log.warn(getJobId() + ": Failed sending key " + _state.getTarget());
        if (_log.shouldLog(Log.DEBUG))
            _log.debug(getJobId() + ": State of failed send: " + _state, new Exception("Who failed me?"));
        if (_onFailure != null)
            getContext().jobQueue().addJob(_onFailure);
        _state.complete(true);
        getContext().statManager().addRateData("netDb.storeFailedPeers", _state.getAttempted().size(), _state.getWhenCompleted()-_state.getWhenStarted());
    }
}