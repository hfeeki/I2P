package net.i2p.router.peermanager;

import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import net.i2p.data.Hash;
import net.i2p.data.RouterInfo;
import net.i2p.data.TunnelId;
import net.i2p.data.i2np.DatabaseStoreMessage;
import net.i2p.data.i2np.DeliveryStatusMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.router.JobImpl;
import net.i2p.router.MessageSelector;
import net.i2p.router.PeerSelectionCriteria;
import net.i2p.router.ReplyJob;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelInfo;
import net.i2p.router.TunnelSelectionCriteria;
import net.i2p.router.message.SendTunnelMessageJob;
import net.i2p.util.Log;

/**
 * Grab some peers that we want to test and probe them briefly to get some 
 * more accurate and up to date performance data.  This delegates the peer
 * selection to the peer manager and tests the peer by sending it a useless
 * database store message
 *
 */
public class PeerTestJob extends JobImpl {
    private Log _log;
    private PeerManager _manager;
    private boolean _keepTesting;
    private static final long DEFAULT_PEER_TEST_DELAY = 60*1000;
    private static final int TEST_PRIORITY = 100;
    
    /** Creates a new instance of PeerTestJob */
    public PeerTestJob(RouterContext context) {
        super(context);
        _log = context.logManager().getLog(PeerTestJob.class);
        _keepTesting = false;
    }
    
    /** how long should we wait before firing off new tests?  */
    private long getPeerTestDelay() { return DEFAULT_PEER_TEST_DELAY; } 
    /** how long to give each peer before marking them as unresponsive? */
    private long getTestTimeout() { return 30*1000; }
    /** number of peers to test each round */
    private int getTestConcurrency() { return 2; }
    
    public void startTesting(PeerManager manager) { 
        _manager = manager;
        _keepTesting = true;
        _context.jobQueue().addJob(this); 
        if (_log.shouldLog(Log.INFO))
            _log.info("Start testing peers");
    }
    public void stopTesting() { 
        _keepTesting = false;
        if (_log.shouldLog(Log.INFO))
            _log.info("Stop testing peers");
    }
    
    public String getName() { return "Initiate some peer tests"; }
    public void runJob() {
        if (!_keepTesting) return;
        Set peers = selectPeersToTest();
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Testing " + peers.size() + " peers");
        
        for (Iterator iter = peers.iterator(); iter.hasNext(); ) {
            RouterInfo peer = (RouterInfo)iter.next();
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Testing peer " + peer.getIdentity().getHash().toBase64());
            testPeer(peer);
        }
        requeue(getPeerTestDelay());
    }    
    
    /**
     * Retrieve a group of 0 or more peers that we want to test. 
     *
     * @return set of RouterInfo structures
     */
    private Set selectPeersToTest() {
        PeerSelectionCriteria criteria = new PeerSelectionCriteria();
        criteria.setMinimumRequired(getTestConcurrency());
        criteria.setMaximumRequired(getTestConcurrency());
        criteria.setPurpose(PeerSelectionCriteria.PURPOSE_TEST);
        Set peerHashes = _manager.selectPeers(criteria);
        
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Peer selection found " + peerHashes.size() + " peers");
        
        Set peers = new HashSet(peerHashes.size());
        for (Iterator iter = peerHashes.iterator(); iter.hasNext(); ) {
            Hash peer = (Hash)iter.next();
            RouterInfo peerInfo = _context.netDb().lookupRouterInfoLocally(peer);
            if (peerInfo != null) {
                peers.add(peerInfo);
            } else {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Test peer " + peer.toBase64() + " had no local routerInfo?");
            }
        }
        return peers;
    }
    
    /**
     * Fire off the necessary jobs and messages to test the given peer
     *
     */
    private void testPeer(RouterInfo peer) {
        TunnelId inTunnelId = getInboundTunnelId(); 
        if (inTunnelId == null) {
            _log.error("No tunnels to get peer test replies through!  wtf!");
            return;
        }
	
        TunnelInfo inTunnel = _context.tunnelManager().getTunnelInfo(inTunnelId);
        RouterInfo inGateway = _context.netDb().lookupRouterInfoLocally(inTunnel.getThisHop());
        if (inGateway == null) {
            _log.error("We can't find the gateway to our inbound tunnel?! wtf");
            return;
        }
	
        long timeoutMs = getTestTimeout();
        long expiration = _context.clock().now() + timeoutMs;

        long nonce = _context.random().nextLong(I2NPMessage.MAX_ID_VALUE);
        DatabaseStoreMessage msg = buildMessage(peer, inTunnelId, inGateway.getIdentity().getHash(), nonce, expiration);
	
        TunnelId outTunnelId = getOutboundTunnelId();
        if (outTunnelId == null) {
            _log.error("No tunnels to send search out through!  wtf!");
            return;
        }
        TunnelInfo outTunnel = _context.tunnelManager().getTunnelInfo(outTunnelId);
	
        if (_log.shouldLog(Log.DEBUG))
            _log.debug(getJobId() + ": Sending peer test to " + peer.getIdentity().getHash().toBase64() 
                       + "w/ replies through [" + inGateway.getIdentity().getHash().toBase64() 
                       + "] via tunnel [" + msg.getReplyTunnel() + "]");

        ReplySelector sel = new ReplySelector(peer.getIdentity().getHash(), nonce, expiration);
        PeerReplyFoundJob reply = new PeerReplyFoundJob(_context, peer, inTunnel, outTunnel);
        PeerReplyTimeoutJob timeoutJob = new PeerReplyTimeoutJob(_context, peer, inTunnel, outTunnel);
        SendTunnelMessageJob j = new SendTunnelMessageJob(_context, msg, outTunnelId, peer.getIdentity().getHash(), 
                                                          null, null, reply, timeoutJob, sel, 
                                                          timeoutMs, TEST_PRIORITY);
        _context.jobQueue().addJob(j);

    }
    
    /** 
     * what tunnel will we send the test out through? 
     *
     * @return tunnel id (or null if none are found)
     */
    private TunnelId getOutboundTunnelId() {
        TunnelSelectionCriteria crit = new TunnelSelectionCriteria();
        crit.setMaximumTunnelsRequired(1);
        crit.setMinimumTunnelsRequired(1);
        List tunnelIds = _context.tunnelManager().selectOutboundTunnelIds(crit);
        if (tunnelIds.size() <= 0) {
            return null;
        }
	
        return (TunnelId)tunnelIds.get(0);
    }
    
    /**
     * what tunnel will we get replies through?
     *
     * @return tunnel id (or null if none are found)
     */
    private TunnelId getInboundTunnelId() {
        TunnelSelectionCriteria crit = new TunnelSelectionCriteria();
        crit.setMaximumTunnelsRequired(1);
        crit.setMinimumTunnelsRequired(1);
        List tunnelIds = _context.tunnelManager().selectInboundTunnelIds(crit);
        if (tunnelIds.size() <= 0) {
            return null;
        }
        return (TunnelId)tunnelIds.get(0);
    }

    /**
     * Build a message to test the peer with 
     */
    private DatabaseStoreMessage buildMessage(RouterInfo peer, TunnelId replyTunnel, Hash replyGateway, long nonce, long expiration) {
        DatabaseStoreMessage msg = new DatabaseStoreMessage(_context);
        msg.setKey(peer.getIdentity().getHash());
        msg.setRouterInfo(peer);
        msg.setReplyGateway(replyGateway);
        msg.setReplyTunnel(replyTunnel);
        msg.setReplyToken(nonce);
        msg.setMessageExpiration(new Date(expiration));
        return msg;
    }
    
    /**
     * Simple selector looking for a dbStore of the peer specified
     *
     */
    private class ReplySelector implements MessageSelector {
        private long _expiration;
        private long _nonce;
        private Hash _peer;
        public ReplySelector(Hash peer, long nonce, long expiration) {
            _nonce = nonce;
            _expiration = expiration;
            _peer = peer;
        }
        public boolean continueMatching() { return false; }
        public long getExpiration() { return _expiration; }
        public boolean isMatch(I2NPMessage message) {
            if (message instanceof DeliveryStatusMessage) {
                DeliveryStatusMessage msg = (DeliveryStatusMessage)message;
                if (_nonce == msg.getMessageId()) {
                    long timeLeft = _expiration - _context.clock().now();
                    if (timeLeft < 0)
                        _log.warn("Took too long to get a reply from peer " + _peer.toBase64() 
                                  + ": " + (0-timeLeft) + "ms too slow");
                    return true;
                }
            }
            return false;
        }
    }
    /**
     * Called when the peer's response is found
     */
    private class PeerReplyFoundJob extends JobImpl implements ReplyJob {
        private RouterInfo _peer;
        private long _testBegin;
        private TunnelInfo _replyTunnel;
        private TunnelInfo _sendTunnel;
        public PeerReplyFoundJob(RouterContext context, RouterInfo peer, TunnelInfo replyTunnel, TunnelInfo sendTunnel) {
            super(context);
            _peer = peer;
            _replyTunnel = replyTunnel;
            _sendTunnel = sendTunnel;
            _testBegin = context.clock().now();
        }
        public String getName() { return "Peer test successful"; }
        public void runJob() {
            long responseTime = _context.clock().now() - _testBegin;
            
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("successful peer test after " + responseTime + " for " 
                           + _peer.getIdentity().getHash().toBase64() + " using outbound tunnel " 
                           + _sendTunnel.getTunnelId().getTunnelId() + " and inbound tunnel " 
                           + _replyTunnel.getTunnelId().getTunnelId());
            _context.profileManager().dbLookupSuccessful(_peer.getIdentity().getHash(), responseTime);
            
            _sendTunnel.setLastTested(_context.clock().now());
            _replyTunnel.setLastTested(_context.clock().now());
            
            TunnelInfo cur = _replyTunnel;
            while (cur != null) {
                Hash peer = cur.getThisHop();
                if ( (peer != null) && (!_context.routerHash().equals(peer)) )
                    _context.profileManager().tunnelTestSucceeded(peer, responseTime);
                cur = cur.getNextHopInfo();
            }
            cur = _sendTunnel;
            while (cur != null) {
                Hash peer = cur.getThisHop();
                if ( (peer != null) && (!_context.routerHash().equals(peer)) )
                    _context.profileManager().tunnelTestSucceeded(peer, responseTime);
                cur = cur.getNextHopInfo();
            }
        }
        
        public void setMessage(I2NPMessage message) {
            // noop
        }
        
    }
    /** 
     * Called when the peer's response times out
     */
    private class PeerReplyTimeoutJob extends JobImpl {
        private RouterInfo _peer;
        private TunnelInfo _replyTunnel;
        private TunnelInfo _sendTunnel;
        public PeerReplyTimeoutJob(RouterContext context, RouterInfo peer, TunnelInfo replyTunnel, TunnelInfo sendTunnel) {
            super(context);
            _peer = peer;
            _replyTunnel = replyTunnel;
            _sendTunnel = sendTunnel;
        }
        public String getName() { return "Peer test failed"; }
        private boolean getShouldFailTunnels() { return true; }
        private boolean getShouldFailPeer() { return true; }
        public void runJob() {
            if (getShouldFailPeer())
                _context.profileManager().dbLookupFailed(_peer.getIdentity().getHash());
                        
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("failed peer test for " 
                           + _peer.getIdentity().getHash().toBase64() + " using outbound tunnel " 
                           + _sendTunnel.getTunnelId().getTunnelId() + " and inbound tunnel " 
                           + _replyTunnel.getTunnelId().getTunnelId());

            if (getShouldFailTunnels()) {
                
                _sendTunnel.setLastTested(_context.clock().now());
                _replyTunnel.setLastTested(_context.clock().now());

                TunnelInfo cur = _replyTunnel;
                while (cur != null) {
                    Hash peer = cur.getThisHop();
                    if ( (peer != null) && (!_context.routerHash().equals(peer)) )
                        _context.profileManager().tunnelFailed(peer);
                    cur = cur.getNextHopInfo();
                }
                cur = _sendTunnel;
                while (cur != null) {
                    Hash peer = cur.getThisHop();
                    if ( (peer != null) && (!_context.routerHash().equals(peer)) )
                        _context.profileManager().tunnelFailed(peer);
                    cur = cur.getNextHopInfo();
                }
            }
        }
    }
}
