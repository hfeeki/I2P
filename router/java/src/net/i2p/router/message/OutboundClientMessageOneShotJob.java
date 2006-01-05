package net.i2p.router.message;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;

import net.i2p.data.Certificate;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.data.Lease;
import net.i2p.data.LeaseSet;
import net.i2p.data.PublicKey;
import net.i2p.data.SessionKey;
import net.i2p.data.i2cp.MessageId;

import net.i2p.data.i2np.DataMessage;
import net.i2p.data.i2np.DeliveryStatusMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.data.i2np.GarlicMessage;
import net.i2p.data.i2np.DeliveryInstructions;

import net.i2p.router.message.PayloadGarlicConfig;

import net.i2p.router.ClientMessage;
import net.i2p.router.JobImpl;
import net.i2p.router.ReplyJob;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelInfo;
import net.i2p.router.MessageSelector;

import net.i2p.util.Log;

/**
 * Send a client message out a random outbound tunnel and into a random inbound
 * tunnel on the target leaseSet.  This also bundles the sender's leaseSet and
 * a DeliveryStatusMessage (for ACKing any sessionTags used in the garlic).
 *
 */
public class OutboundClientMessageOneShotJob extends JobImpl {
    private Log _log;
    private long _overallExpiration;
    private boolean _shouldBundle;
    private ClientMessage _clientMessage;
    private MessageId _clientMessageId;
    private int _clientMessageSize;
    private Destination _from;
    private Destination _to;
    private String _toString;
    /** target destination's leaseSet, if known */
    private LeaseSet _leaseSet;
    /** Actual lease the message is being routed through */
    private Lease _lease;
    private PayloadGarlicConfig _clove;
    private long _cloveId;
    private long _start;
    private boolean _finished;
    private long _leaseSetLookupBegin;
    private TunnelInfo _outTunnel;
    private TunnelInfo _inTunnel;
    
    /**
     * final timeout (in milliseconds) that the outbound message will fail in.
     * This can be overridden in the router.config or the client's session config
     * (the client's session config takes precedence)
     */
    public final static String OVERALL_TIMEOUT_MS_PARAM = "clientMessageTimeout";
    private final static long OVERALL_TIMEOUT_MS_DEFAULT = 60*1000;
    
    /** priority of messages, that might get honored some day... */
    private final static int SEND_PRIORITY = 500;
    
    /**
     * If the client's config specifies shouldBundleReplyInfo=true, messages sent from
     * that client to any peers will probabalistically include the sending destination's
     * current LeaseSet (allowing the recipient to reply without having to do a full
     * netDb lookup).  This should improve performance during the initial negotiations,
     * but is not necessary for communication that isn't bidirectional.
     *
     */
    public static final String BUNDLE_REPLY_LEASESET = "shouldBundleReplyInfo";
    /**
     * Allow the override of the frequency of bundling the reply info in with a message.
     * The client app can specify bundleReplyInfoProbability=80 (for instance) and that
     * will cause the router to include the sender's leaseSet with 80% of the messages
     * sent to the peer.
     *
     */
    public static final String BUNDLE_PROBABILITY = "bundleReplyInfoProbability";
    /** 
     * How often do messages include the reply leaseSet (out of every 100 tries).  
     * Including it each time is probably overkill, but who knows.  
     */
    private static final int BUNDLE_PROBABILITY_DEFAULT = 100;
    
    /**
     * Send the sucker
     */
    public OutboundClientMessageOneShotJob(RouterContext ctx, ClientMessage msg) {
        super(ctx);
        _log = ctx.logManager().getLog(OutboundClientMessageOneShotJob.class);
        
        ctx.statManager().createFrequencyStat("client.sendMessageFailFrequency", "How often does a client fail to send a message?", "ClientMessages", new long[] { 60*1000l, 60*60*1000l, 24*60*60*1000l });
        ctx.statManager().createRateStat("client.sendMessageSize", "How large are messages sent by the client?", "ClientMessages", new long[] { 60*1000l, 60*60*1000l, 24*60*60*1000l });
        ctx.statManager().createRateStat("client.sendAckTime", "How long does it take to get an ACK back from a message?", "ClientMessages", new long[] { 5*60*1000l, 60*60*1000l, 24*60*60*1000l });
        ctx.statManager().createRateStat("client.timeoutCongestionTunnel", "How lagged our tunnels are when a send times out?", "ClientMessages", new long[] { 5*60*1000l, 60*60*1000l, 24*60*60*1000l });
        ctx.statManager().createRateStat("client.timeoutCongestionMessage", "How fast we process messages locally when a send times out?", "ClientMessages", new long[] { 5*60*1000l, 60*60*1000l, 24*60*60*1000l });
        ctx.statManager().createRateStat("client.timeoutCongestionInbound", "How much faster we are receiving data than our average bps when a send times out?", "ClientMessages", new long[] { 5*60*1000l, 60*60*1000l, 24*60*60*1000l });
        ctx.statManager().createRateStat("client.leaseSetFoundLocally", "How often we tried to look for a leaseSet and found it locally?", "ClientMessages", new long[] { 5*60*1000l, 60*60*1000l, 24*60*60*1000l });
        ctx.statManager().createRateStat("client.leaseSetFoundRemoteTime", "How long we tried to look fora remote leaseSet (when we succeeded)?", "ClientMessages", new long[] { 5*60*1000l, 60*60*1000l, 24*60*60*1000l });
        ctx.statManager().createRateStat("client.leaseSetFailedRemoteTime", "How long we tried to look for a remote leaseSet (when we failed)?", "ClientMessages", new long[] { 5*60*1000l, 60*60*1000l, 24*60*60*1000l });
        ctx.statManager().createRateStat("client.dispatchPrepareTime", "How long until we've queued up the dispatch job (since we started)?", "ClientMessages", new long[] { 5*60*1000l, 60*60*1000l, 24*60*60*1000l });
        ctx.statManager().createRateStat("client.dispatchTime", "How long until we've dispatched the message (since we started)?", "ClientMessages", new long[] { 5*60*1000l, 60*60*1000l, 24*60*60*1000l });
        ctx.statManager().createRateStat("client.dispatchSendTime", "How long the actual dispatching takes?", "ClientMessages", new long[] { 5*60*1000l, 60*60*1000l, 24*60*60*1000l });
        long timeoutMs = OVERALL_TIMEOUT_MS_DEFAULT;
        _clientMessage = msg;
        _clientMessageId = msg.getMessageId();
        _clientMessageSize = msg.getPayload().getSize();
        _from = msg.getFromDestination();
        _to = msg.getDestination();
        _toString = _to.calculateHash().toBase64().substring(0,4);
        _leaseSetLookupBegin = -1;
        
        String param = msg.getSenderConfig().getOptions().getProperty(OVERALL_TIMEOUT_MS_PARAM);
        if (param == null)
            param = ctx.router().getConfigSetting(OVERALL_TIMEOUT_MS_PARAM);
        if (param != null) {
            try {
                timeoutMs = Long.parseLong(param);
            } catch (NumberFormatException nfe) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Invalid client message timeout specified [" + param 
                              + "], defaulting to " + OVERALL_TIMEOUT_MS_DEFAULT, nfe);
                timeoutMs = OVERALL_TIMEOUT_MS_DEFAULT;
            }
        }
        
        _start = getContext().clock().now();
        _overallExpiration = timeoutMs + _start;
        _shouldBundle = getShouldBundle();
        _finished = false;
    }
    
    public String getName() { return "Outbound client message"; }
    
    public void runJob() {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug(getJobId() + ": Send outbound client message job beginning");
        long timeoutMs = _overallExpiration - getContext().clock().now();
        if (_log.shouldLog(Log.DEBUG))
            _log.debug(getJobId() + ": preparing to search for the leaseSet for " + _toString);
        Hash key = _to.calculateHash();
        SendJob success = new SendJob(getContext());
        LookupLeaseSetFailedJob failed = new LookupLeaseSetFailedJob(getContext());
        LeaseSet ls = getContext().netDb().lookupLeaseSetLocally(key);
        if (ls != null) {
            getContext().statManager().addRateData("client.leaseSetFoundLocally", 1, 0);
            _leaseSetLookupBegin = -1;
            if (_log.shouldLog(Log.DEBUG))
                _log.debug(getJobId() + ": Send outbound client message - leaseSet found locally for " + _toString);
            success.runJob();
        } else {
            _leaseSetLookupBegin = getContext().clock().now();
            if (_log.shouldLog(Log.DEBUG))
                _log.debug(getJobId() + ": Send outbound client message - sending off leaseSet lookup job for " + _toString);
            getContext().netDb().lookupLeaseSet(key, success, failed, timeoutMs);
        }
    }
    
    private boolean getShouldBundle() {
        Properties opts = _clientMessage.getSenderConfig().getOptions();
        String wantBundle = opts.getProperty(BUNDLE_REPLY_LEASESET, "true");
        if ("true".equals(wantBundle)) {
            int probability = BUNDLE_PROBABILITY_DEFAULT;
            String str = opts.getProperty(BUNDLE_PROBABILITY);
            try { 
                if (str != null) 
                    probability = Integer.parseInt(str);
            } catch (NumberFormatException nfe) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn(getJobId() + ": Bundle leaseSet probability overridden incorrectly [" 
                              + str + "]", nfe);
            }
            if (probability >= getContext().random().nextInt(100))
                return true;
            else
                return false;
        } else {
            return false;
        }
    }
    
    /** send a message to a random lease */
    private class SendJob extends JobImpl {
        public SendJob(RouterContext enclosingContext) { 
            super(enclosingContext);
        }
        public String getName() { return "Send outbound client message through the lease"; }
        public void runJob() {
            if (_leaseSetLookupBegin > 0) {
                long lookupTime = getContext().clock().now() - _leaseSetLookupBegin;
                getContext().statManager().addRateData("client.leaseSetFoundRemoteTime", lookupTime, lookupTime);
            }
            boolean ok = getNextLease();
            if (ok) {
                send();
            } else {
                if (_log.shouldLog(Log.ERROR))
                    _log.error("Unable to send on a random lease, as getNext returned null (to=" + _toString + ")");
                dieFatal();
            }
        }
    }
    
    /**
     * fetch the next lease that we should try sending through, randomly chosen
     * from within the sorted leaseSet (sorted by # of failures through each 
     * lease).
     *
     */
    private boolean getNextLease() {
        _leaseSet = getContext().netDb().lookupLeaseSetLocally(_to.calculateHash());
        if (_leaseSet == null) {
            if (_log.shouldLog(Log.WARN))
                _log.warn(getJobId() + ": Lookup locally didn't find the leaseSet for " + _toString);
            return false;
        } 
        long now = getContext().clock().now();
        
        // get the possible leases
        List leases = new ArrayList(_leaseSet.getLeaseCount());
        for (int i = 0; i < _leaseSet.getLeaseCount(); i++) {
            Lease lease = _leaseSet.getLease(i);
            if (lease.isExpired(Router.CLOCK_FUDGE_FACTOR)) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn(getJobId() + ": getNextLease() - expired lease! - " + lease + " for " + _toString);
                continue;
            } else {
                leases.add(lease);
            }
        }
        
        if (leases.size() <= 0) {
            if (_log.shouldLog(Log.INFO))
                _log.info(getJobId() + ": No leases found from: " + _leaseSet);
            return false;
        }
        
        // randomize the ordering (so leases with equal # of failures per next 
        // sort are randomly ordered)
        Collections.shuffle(leases);
        
        if (false) {
            // ordered by lease number of failures
            TreeMap orderedLeases = new TreeMap();
            for (Iterator iter = leases.iterator(); iter.hasNext(); ) {
                Lease lease = (Lease)iter.next();
                long id = lease.getNumFailure();
                while (orderedLeases.containsKey(new Long(id)))
                    id++;
                orderedLeases.put(new Long(id), lease);
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug(getJobId() + ": ranking lease we havent sent it down as " + id);
            }
            
            _lease = (Lease)orderedLeases.get(orderedLeases.firstKey());
        } else {
            _lease = (Lease)leases.get(0);
        }
        return true;
    }

    
    /** 
     * we couldn't even find the leaseSet, but try again (or die 
     * if we've already tried too hard)
     *
     */
    private class LookupLeaseSetFailedJob extends JobImpl {
        public LookupLeaseSetFailedJob(RouterContext enclosingContext)  {
            super(enclosingContext);
        }
        public String getName() { return "Lookup for outbound client message failed"; }
        public void runJob() {
            if (_leaseSetLookupBegin > 0) {
                long lookupTime = getContext().clock().now() - _leaseSetLookupBegin;
                getContext().statManager().addRateData("client.leaseSetFailedRemoteTime", lookupTime, lookupTime);
            }
            
            if (!_finished) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Unable to send to " + _toString + " because we couldn't find their leaseSet");
            }

            dieFatal();
        }
    }
    
    /**
     * Send the message to the specified tunnel by creating a new garlic message containing
     * the (already created) payload clove as well as a new delivery status message.  This garlic
     * message is sent out one of our tunnels, destined for the lease (tunnel+router) specified, and the delivery
     * status message is targetting one of our free inbound tunnels as well.  We use a new
     * reply selector to keep an eye out for that delivery status message's token
     *
     */
    private void send() {
        if (_finished) return;
        long token = getContext().random().nextLong(I2NPMessage.MAX_ID_VALUE);
        PublicKey key = _leaseSet.getEncryptionKey();
        SessionKey sessKey = new SessionKey();
        Set tags = new HashSet();
        LeaseSet replyLeaseSet = null;
        if (_shouldBundle) {
            replyLeaseSet = getContext().netDb().lookupLeaseSetLocally(_from.calculateHash());
        }
        
        _inTunnel = selectInboundTunnel();

        buildClove();
        if (_log.shouldLog(Log.DEBUG))
            _log.debug(getJobId() + ": Clove built to " + _toString);
        GarlicMessage msg = OutboundClientMessageJobHelper.createGarlicMessage(getContext(), token, 
                                                                               _overallExpiration, key, 
                                                                               _clove, _from.calculateHash(), 
                                                                               _to, _inTunnel,
                                                                               sessKey, tags, 
                                                                               true, replyLeaseSet);
        if (msg == null) {
            // set to null if there are no tunnels to ack the reply back through
            // (should we always fail for this? or should we send it anyway, even if
            // we dont receive the reply? hmm...)
            if (_log.shouldLog(Log.ERROR))
                _log.error(getJobId() + ": Unable to create the garlic message (no tunnels left) to " + _toString);
            dieFatal();
            return;
        }
        
        if (_log.shouldLog(Log.DEBUG))
            _log.debug(getJobId() + ": send() - token expected " + token + " to " + _toString);
        
        SendSuccessJob onReply = new SendSuccessJob(getContext(), sessKey, tags);
        SendTimeoutJob onFail = new SendTimeoutJob(getContext());
        ReplySelector selector = new ReplySelector(token);
        
        if (_log.shouldLog(Log.DEBUG))
            _log.debug(getJobId() + ": Placing GarlicMessage into the new tunnel message bound for " 
                       + _toString + " at "
                       + _lease.getTunnelId() + " on " 
                       + _lease.getGateway().toBase64());
        
        _outTunnel = selectOutboundTunnel();
        if (_outTunnel != null) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug(getJobId() + ": Sending tunnel message out " + _outTunnel.getSendTunnelId(0) + " to " 
                           + _toString + " at "
                           + _lease.getTunnelId() + " on " 
                           + _lease.getGateway().toBase64());

            DispatchJob dispatchJob = new DispatchJob(getContext(), msg, selector, onReply, onFail, (int)(_overallExpiration-getContext().clock().now()));
            if (false) // dispatch may take 100+ms, so toss it in its own job
                getContext().jobQueue().addJob(dispatchJob);
            else
                dispatchJob.runJob();
        } else {
            if (_log.shouldLog(Log.ERROR))
                _log.error(getJobId() + ": Could not find any outbound tunnels to send the payload through... wtf?");
            dieFatal();
        }
        _clientMessage = null;
        _clove = null;
        getContext().statManager().addRateData("client.dispatchPrepareTime", getContext().clock().now() - _start, 0);
    }

    private class DispatchJob extends JobImpl {
        private GarlicMessage _msg;
        private ReplySelector _selector;
        private SendSuccessJob _replyFound;
        private SendTimeoutJob _replyTimeout;
        private int _timeoutMs;
        public DispatchJob(RouterContext ctx, GarlicMessage msg, ReplySelector sel, SendSuccessJob success, SendTimeoutJob timeout, int timeoutMs) {
            super(ctx);
            _msg = msg;
            _selector = sel;
            _replyFound = success;
            _replyTimeout = timeout;
            _timeoutMs = timeoutMs;
        }
        public String getName() { return "Dispatch outbound client message"; }
        public void runJob() {
            getContext().messageRegistry().registerPending(_selector, _replyFound, _replyTimeout, _timeoutMs);
            if (_log.shouldLog(Log.INFO))
                _log.info("Dispatching message to " + _toString + ": " + _msg);
            long before = getContext().clock().now();
            getContext().tunnelDispatcher().dispatchOutbound(_msg, _outTunnel.getSendTunnelId(0), _lease.getTunnelId(), _lease.getGateway());
            long dispatchSendTime = getContext().clock().now() - before; 
            if (_log.shouldLog(Log.INFO))
                _log.info("Dispatching message to " + _toString + " complete");
            getContext().statManager().addRateData("client.dispatchTime", getContext().clock().now() - _start, 0);
            getContext().statManager().addRateData("client.dispatchSendTime", dispatchSendTime, 0);
        }
    }
    
    /**
     * Pick an arbitrary outbound tunnel to send the message through, or null if
     * there aren't any around
     *
     */
    private TunnelInfo selectOutboundTunnel() {
        return getContext().tunnelManager().selectOutboundTunnel(_from.calculateHash());
    }
    /**
     * Pick an arbitrary outbound tunnel for any deliveryStatusMessage to come back in
     *
     */
    private TunnelInfo selectInboundTunnel() {
        return getContext().tunnelManager().selectInboundTunnel(_from.calculateHash());
    }
    
    /**
     * give up the ghost, this message just aint going through.  tell the client to fuck off.
     *
     * this is safe to call multiple times (only tells the client once)
     */
    private void dieFatal() {
        if (_finished) return;
        _finished = true;
        
        long sendTime = getContext().clock().now() - _start;
        if (_log.shouldLog(Log.WARN))
            _log.warn(getJobId() + ": Failed to send the message " + _clientMessageId + " after " 
                       + sendTime + "ms");
        
        long messageDelay = getContext().throttle().getMessageDelay();
        long tunnelLag = getContext().throttle().getTunnelLag();
        long inboundDelta = (long)getContext().throttle().getInboundRateDelta();
            
        getContext().statManager().addRateData("client.timeoutCongestionTunnel", tunnelLag, 1);
        getContext().statManager().addRateData("client.timeoutCongestionMessage", messageDelay, 1);
        getContext().statManager().addRateData("client.timeoutCongestionInbound", inboundDelta, 1);
    
        getContext().messageHistory().sendPayloadMessage(_clientMessageId.getMessageId(), false, sendTime);
        getContext().clientManager().messageDeliveryStatusUpdate(_from, _clientMessageId, false);
        getContext().statManager().updateFrequency("client.sendMessageFailFrequency");
        _clientMessage = null;
        _clove = null;
    }
    
    /** build the payload clove that will be used for all of the messages, placing the clove in the status structure */
    private void buildClove() {
        PayloadGarlicConfig clove = new PayloadGarlicConfig();
        
        DeliveryInstructions instructions = new DeliveryInstructions();
        instructions.setDeliveryMode(DeliveryInstructions.DELIVERY_MODE_DESTINATION);
        instructions.setDestination(_to.calculateHash());
        
        instructions.setDelayRequested(false);
        instructions.setDelaySeconds(0);
        instructions.setEncrypted(false);
        
        clove.setCertificate(new Certificate(Certificate.CERTIFICATE_TYPE_NULL, null));
        clove.setDeliveryInstructions(instructions);
        clove.setExpiration(OVERALL_TIMEOUT_MS_DEFAULT+getContext().clock().now());
        clove.setId(getContext().random().nextLong(I2NPMessage.MAX_ID_VALUE));
        
        DataMessage msg = new DataMessage(getContext());
        msg.setData(_clientMessage.getPayload().getEncryptedData());
        msg.setMessageExpiration(clove.getExpiration());
        
        clove.setPayload(msg);
        clove.setRecipientPublicKey(null);
        clove.setRequestAck(false);
        
        _clove = clove;
        _cloveId = _clove.getId();
        
        if (_log.shouldLog(Log.DEBUG))
            _log.debug(getJobId() + ": Built payload clove with id " + clove.getId());
    }
    
    /**
     * Keep an eye out for any of the delivery status message tokens that have been
     * sent down the various tunnels to deliver this message
     *
     */
    private class ReplySelector implements MessageSelector {
        private long _pendingToken;
        public ReplySelector(long token) {
            _pendingToken = token;
            if (_log.shouldLog(Log.INFO))
                _log.info(OutboundClientMessageOneShotJob.this.getJobId() 
                           + "Reply selector for client message: token=" + token);
        }
        
        public boolean continueMatching() { 
            if (_log.shouldLog(Log.DEBUG))
                _log.debug(OutboundClientMessageOneShotJob.this.getJobId() 
                           + "dont continue matching for token=" + _pendingToken);
            return false; 
        }
        public long getExpiration() { return _overallExpiration; }
        
        public boolean isMatch(I2NPMessage inMsg) {
            if (inMsg.getType() == DeliveryStatusMessage.MESSAGE_TYPE) {
                if (_log.shouldLog(Log.INFO))
                    _log.info(OutboundClientMessageOneShotJob.this.getJobId() 
                               + "delivery status message received: " + inMsg + " our token: " + _pendingToken);
                return _pendingToken == ((DeliveryStatusMessage)inMsg).getMessageId();
            } else {
                return false;
            }
        }
        
        public String toString() {
            return "sending " + _toString + " waiting for token " + _pendingToken
                   + " for cloveId " + _cloveId;
        }
    }
    
    /**
     * Called after we get a confirmation that the message was delivered safely
     * (hoo-ray!)
     *
     */
    private class SendSuccessJob extends JobImpl implements ReplyJob {
        private SessionKey _key;
        private Set _tags;
        
        /**
         * Create a new success job that will be fired when the message encrypted with
         * the given session key and bearing the specified tags are confirmed delivered.
         *
         */
        public SendSuccessJob(RouterContext enclosingContext, SessionKey key, Set tags) {
            super(enclosingContext);
            _key = key;
            _tags = tags;
        }
        
        public String getName() { return "Send client message successful"; }
        public void runJob() {
            if (_finished) return;
            _finished = true;
            long sendTime = getContext().clock().now() - _start;
            if (_log.shouldLog(Log.INFO))
                _log.info(OutboundClientMessageOneShotJob.this.getJobId() 
                           + ": SUCCESS!  msg " + _clientMessageId
                           + " sent after " + sendTime + "ms");
            
            if ( (_key != null) && (_tags != null) && (_tags.size() > 0) ) {
                if (_leaseSet != null)
                    getContext().sessionKeyManager().tagsDelivered(_leaseSet.getEncryptionKey(),
                                                                   _key, _tags);
            }
            
            long dataMsgId = _cloveId;
            getContext().messageHistory().sendPayloadMessage(dataMsgId, true, sendTime);
            getContext().clientManager().messageDeliveryStatusUpdate(_from, _clientMessageId, true);
            _lease.setNumSuccess(_lease.getNumSuccess()+1);
        
            int size = _clientMessageSize;
            
            getContext().statManager().addRateData("client.sendAckTime", sendTime, 0);
            getContext().statManager().addRateData("client.sendMessageSize", _clientMessageSize, sendTime);
            if (_outTunnel != null) {
                if (_outTunnel.getLength() > 0)
                    size = ((size + 1023) / 1024) * 1024; // messages are in ~1KB blocks
                
                for (int i = 0; i < _outTunnel.getLength(); i++) {
                    getContext().profileManager().tunnelTestSucceeded(_outTunnel.getPeer(i), sendTime);
                    getContext().profileManager().tunnelDataPushed(_outTunnel.getPeer(i), sendTime, size);
                }
                _outTunnel.incrementVerifiedBytesTransferred(size);
            }
            if (_inTunnel != null)
                for (int i = 0; i < _inTunnel.getLength(); i++)
                    getContext().profileManager().tunnelTestSucceeded(_inTunnel.getPeer(i), sendTime);
        }

        public void setMessage(I2NPMessage msg) {}
    }
    
    /**
     * Fired after the basic timeout for sending through the given tunnel has been reached.
     * We'll accept successes later, but won't expect them
     *
     */
    private class SendTimeoutJob extends JobImpl {
        public SendTimeoutJob(RouterContext enclosingContext) {
            super(enclosingContext);
        }
        
        public String getName() { return "Send client message timed out"; }
        public void runJob() {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug(OutboundClientMessageOneShotJob.this.getJobId()
                           + ": Soft timeout through the lease " + _lease);
            
            _lease.setNumFailure(_lease.getNumFailure()+1);
            dieFatal();
        }
    }
}
