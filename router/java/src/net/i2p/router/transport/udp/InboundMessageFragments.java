package net.i2p.router.transport.udp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.i2p.router.RouterContext;
import net.i2p.util.DecayingBloomFilter;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;

/**
 * Organize the received data message fragments, feeding completed messages
 * to the {@link MessageReceiver} and telling the {@link ACKSender} of new
 * peers to ACK.  In addition, it drops failed fragments and keeps a
 * minimal list of the most recently completed messages (even though higher
 * up in the router we have full blown replay detection, its nice to have a
 * basic line of defense here).
 *
 * TODO: add in some sensible code to drop expired fragments from peers we 
 * don't hear from again (either a periodic culling for expired peers, or
 * a scheduled event)
 *
 */
public class InboundMessageFragments {
    private RouterContext _context;
    private Log _log;
    /** Map of peer (Hash) to a Map of messageId (Long) to InboundMessageState objects */
    private Map _inboundMessages;
    /** list of message IDs recently received, so we can ignore in flight dups */
    private DecayingBloomFilter _recentlyCompletedMessages;
    private OutboundMessageFragments _outbound;
    private UDPTransport _transport;
    private ACKSender _ackSender;
    private MessageReceiver _messageReceiver;
    private boolean _alive;
    
    /** decay the recently completed every 2 minutes */
    private static final int DECAY_PERIOD = 120*1000;
        
    public InboundMessageFragments(RouterContext ctx, OutboundMessageFragments outbound, UDPTransport transport) {
        _context = ctx;
        _log = ctx.logManager().getLog(InboundMessageFragments.class);
        _inboundMessages = new HashMap(64);
        _outbound = outbound;
        _transport = transport;
        _ackSender = new ACKSender(_context, _transport);
        _messageReceiver = new MessageReceiver(_context, _transport);
        _context.statManager().createRateStat("udp.receivedCompleteTime", "How long it takes to receive a full message", "udp", new long[] { 60*1000, 10*60*1000, 60*60*1000, 24*60*60*1000 });
        _context.statManager().createRateStat("udp.receivedCompleteFragments", "How many fragments go in a fully received message", "udp", new long[] { 60*1000, 10*60*1000, 60*60*1000, 24*60*60*1000 });
        _context.statManager().createRateStat("udp.receivedACKs", "How many messages were ACKed at a time", "udp", new long[] { 60*1000, 10*60*1000, 60*60*1000, 24*60*60*1000 });
        _context.statManager().createRateStat("udp.ignoreRecentDuplicate", "Take note that we received a packet for a recently completed message", "udp", new long[] { 60*1000, 10*60*1000, 60*60*1000, 24*60*60*1000 });
        _context.statManager().createRateStat("udp.receiveMessagePeriod", "How long it takes to pull the message fragments out of a packet", "udp", new long[] { 60*1000, 10*60*1000, 60*60*1000, 24*60*60*1000 });
        _context.statManager().createRateStat("udp.receiveACKPeriod", "How long it takes to pull the ACKs out of a packet", "udp", new long[] { 60*1000, 10*60*1000, 60*60*1000, 24*60*60*1000 });
    }
    
    public void startup() { 
        _alive = true; 
        // may want to extend the DecayingBloomFilter so we can use a smaller 
        // array size (currently its tuned for 10 minute rates for the 
        // messageValidator)
        _recentlyCompletedMessages = new DecayingBloomFilter(_context, DECAY_PERIOD, 8);
        _ackSender.startup();
        _messageReceiver.startup();
    }
    public void shutdown() {
        _alive = false;
        if (_recentlyCompletedMessages != null)
            _recentlyCompletedMessages.stopDecaying();
        _recentlyCompletedMessages = null;
        _ackSender.shutdown();
        _messageReceiver.shutdown();
        synchronized (_inboundMessages) {
            _inboundMessages.clear();
        }
    }
    public boolean isAlive() { return _alive; }

    /**
     * Pull the fragments and ACKs out of the authenticated data packet
     */
    public void receiveData(PeerState from, UDPPacketReader.DataReader data) {
        long beforeMsgs = _context.clock().now();
        receiveMessages(from, data);
        long afterMsgs = _context.clock().now();
        receiveACKs(from, data);
        long afterACKs = _context.clock().now();
        
        _context.statManager().addRateData("udp.receiveMessagePeriod", afterMsgs-beforeMsgs, afterACKs-beforeMsgs);
        _context.statManager().addRateData("udp.receiveACKPeriod", afterACKs-afterMsgs, afterACKs-beforeMsgs);
    }
    
    /**
     * Pull out all the data fragments and shove them into InboundMessageStates.
     * Along the way, if any state expires, or a full message arrives, move it
     * appropriately.
     *
     */
    private void receiveMessages(PeerState from, UDPPacketReader.DataReader data) {
        int fragments = data.readFragmentCount();
        if (fragments <= 0) return;
        synchronized (_inboundMessages) {
            Map messages = (Map)_inboundMessages.get(from.getRemotePeer());
            if (messages == null) {
                messages = new HashMap(fragments);
                _inboundMessages.put(from.getRemotePeer(), messages);
            }
        
            for (int i = 0; i < fragments; i++) {
                Long messageId = new Long(data.readMessageId(i));
            
                if (_recentlyCompletedMessages.isKnown(messageId.longValue())) {
                    _context.statManager().addRateData("udp.ignoreRecentDuplicate", 1, 0);
                    from.messageFullyReceived(messageId, -1);
                    _ackSender.ackPeer(from);
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Message received is a dup: " + messageId + " dups: " 
                                  + _recentlyCompletedMessages.getCurrentDuplicateCount() + " out of " 
                                  + _recentlyCompletedMessages.getInsertedCount());
                    continue;
                }
            
                int size = data.readMessageFragmentSize(i);
                InboundMessageState state = null;
                boolean messageComplete = false;
                boolean messageExpired = false;
                boolean fragmentOK = false;
                state = (InboundMessageState)messages.get(messageId);
                if (state == null) {
                    state = new InboundMessageState(_context, messageId.longValue(), from.getRemotePeer());
                    messages.put(messageId, state);
                }
                fragmentOK = state.receiveFragment(data, i);
                if (state.isComplete()) {
                    messageComplete = true;
                    messages.remove(messageId);
                    if (messages.size() <= 0)
                        _inboundMessages.remove(from.getRemotePeer());
                    
                    _recentlyCompletedMessages.add(messageId.longValue());

                    _messageReceiver.receiveMessage(state);
                    
                    from.messageFullyReceived(messageId, state.getCompleteSize());
                    _ackSender.ackPeer(from);
                    
                    if (_log.shouldLog(Log.INFO))
                        _log.info("Message received completely!  " + state);

                    _context.statManager().addRateData("udp.receivedCompleteTime", state.getLifetime(), state.getLifetime());
                    _context.statManager().addRateData("udp.receivedCompleteFragments", state.getFragmentCount(), state.getLifetime());
                } else if (state.isExpired()) {
                    messageExpired = true;
                    messages.remove(messageId);
                    if (messages.size() <= 0)
                        _inboundMessages.remove(from.getRemotePeer());
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Message expired while only being partially read: " + state);
                    state.releaseResources();
                }
                
                if (!fragmentOK)
                    break;
            }
        }
    }
    
    private void receiveACKs(PeerState from, UDPPacketReader.DataReader data) {
        if (data.readACKsIncluded()) {
            int fragments = 0;
            long acks[] = data.readACKs();
            if (acks != null) {
                _context.statManager().addRateData("udp.receivedACKs", acks.length, 0);
                _context.statManager().getStatLog().addData(from.getRemoteHostString(), "udp.peer.receiveACKCount", acks.length, 0);

                for (int i = 0; i < acks.length; i++) {
                    if (_log.shouldLog(Log.INFO))
                        _log.info("Full ACK of message " + acks[i] + " received!");
                    fragments += _outbound.acked(acks[i], from.getRemotePeer());
                }
            } else {
                _log.error("Received ACKs with no acks?! " + data);
            }
        }
        if (data.readECN())
            from.ECNReceived();
        else
            from.dataReceived();
    }
}
