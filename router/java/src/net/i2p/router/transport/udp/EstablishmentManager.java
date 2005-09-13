package net.i2p.router.transport.udp;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Iterator;
import java.util.Map;

import net.i2p.crypto.DHSessionKeyBuilder;
import net.i2p.data.Base64;
import net.i2p.data.RouterAddress;
import net.i2p.data.RouterIdentity;
import net.i2p.data.SessionKey;
import net.i2p.data.Signature;
import net.i2p.data.i2np.DatabaseStoreMessage;
import net.i2p.router.CommSystemFacade;
import net.i2p.router.OutNetMessage;
import net.i2p.router.RouterContext;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer;

/**
 * Coordinate the establishment of new sessions - both inbound and outbound.
 * This has its own thread to add packets to the packet queue when necessary,
 * as well as to drop any failed establishment attempts.
 *
 */
public class EstablishmentManager {
    private RouterContext _context;
    private Log _log;
    private UDPTransport _transport;
    private PacketBuilder _builder;
    /** map of RemoteHostId to InboundEstablishState */
    private Map _inboundStates;
    /** map of RemoteHostId to OutboundEstablishState */
    private Map _outboundStates;
    /** map of RemoteHostId to List of OutNetMessage for messages exceeding capacity */
    private Map _queuedOutbound;
    /** map of nonce (Long) to OutboundEstablishState */
    private Map _liveIntroductions;
    private boolean _alive;
    private Object _activityLock;
    private int _activity;
    
    private static final int DEFAULT_MAX_CONCURRENT_ESTABLISH = 16;
    public static final String PROP_MAX_CONCURRENT_ESTABLISH = "i2np.udp.maxConcurrentEstablish";
    
    public EstablishmentManager(RouterContext ctx, UDPTransport transport) {
        _context = ctx;
        _log = ctx.logManager().getLog(EstablishmentManager.class);
        _transport = transport;
        _builder = new PacketBuilder(ctx);
        _inboundStates = new HashMap(32);
        _outboundStates = new HashMap(32);
        _queuedOutbound = new HashMap(32);
        _liveIntroductions = new HashMap(32);
        _activityLock = new Object();
        _context.statManager().createRateStat("udp.inboundEstablishTime", "How long it takes for a new inbound session to be established", "udp", new long[] { 60*60*1000, 24*60*60*1000 });
        _context.statManager().createRateStat("udp.outboundEstablishTime", "How long it takes for a new outbound session to be established", "udp", new long[] { 60*60*1000, 24*60*60*1000 });
        _context.statManager().createRateStat("udp.inboundEstablishFailedState", "What state a failed inbound establishment request fails in", "udp", new long[] { 60*60*1000, 24*60*60*1000 });
        _context.statManager().createRateStat("udp.outboundEstablishFailedState", "What state a failed outbound establishment request fails in", "udp", new long[] { 60*60*1000, 24*60*60*1000 });
        _context.statManager().createRateStat("udp.sendIntroRelayRequest", "How often we send a relay request to reach a peer", "udp", new long[] { 60*60*1000, 24*60*60*1000 });
        _context.statManager().createRateStat("udp.sendIntroRelayTimeout", "How often a relay request times out before getting a response (due to the target or intro peer being offline)", "udp", new long[] { 60*60*1000, 24*60*60*1000 });
        _context.statManager().createRateStat("udp.receiveIntroRelayResponse", "How long it took to receive a relay response", "udp", new long[] { 60*60*1000, 24*60*60*1000 });
    }
    
    public void startup() {
        _alive = true;
        I2PThread t = new I2PThread(new Establisher(), "UDP Establisher");
        t.setDaemon(true);
        t.start();
    }
    public void shutdown() { 
        _alive = false;
        notifyActivity();
    }
    
    /**
     * Grab the active establishing state
     */
    InboundEstablishState getInboundState(RemoteHostId from) {
        synchronized (_inboundStates) {
            InboundEstablishState state = (InboundEstablishState)_inboundStates.get(from);
            if ( (state == null) && (_log.shouldLog(Log.DEBUG)) )
                _log.debug("No inbound states for " + from + ", with remaining: " + _inboundStates);
            return state;
        }
    }
    
    OutboundEstablishState getOutboundState(RemoteHostId from) {
        synchronized (_outboundStates) {
            OutboundEstablishState state = (OutboundEstablishState)_outboundStates.get(from);
            if ( (state == null) && (_log.shouldLog(Log.DEBUG)) )
                _log.debug("No outbound states for " + from + ", with remaining: " + _outboundStates);
            return state;
        }
    }
    
    private int getMaxConcurrentEstablish() {
        String val = _context.getProperty(PROP_MAX_CONCURRENT_ESTABLISH);
        if (val != null) {
            try {
                return Integer.parseInt(val);
            } catch (NumberFormatException nfe) {
                return DEFAULT_MAX_CONCURRENT_ESTABLISH;
            }
        }
        return DEFAULT_MAX_CONCURRENT_ESTABLISH;
    }
  
    /**
     * Send the message to its specified recipient by establishing a connection
     * with them and sending it off.  This call does not block, and on failure,
     * the message is failed.
     *
     */
    public void establish(OutNetMessage msg) {
        RouterAddress ra = msg.getTarget().getTargetAddress(_transport.getStyle());
        if (ra == null) {
            _transport.failed(msg);
            return;
        }
        UDPAddress addr = new UDPAddress(ra);
        InetAddress remAddr = addr.getHostAddress();
        int port = addr.getPort();
        RemoteHostId to = new RemoteHostId(remAddr.getAddress(), port);
        
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Add outobund establish state to: " + to);
        
        synchronized (_outboundStates) {
            OutboundEstablishState state = (OutboundEstablishState)_outboundStates.get(to);
            if (state == null) {
                if (_outboundStates.size() >= getMaxConcurrentEstablish()) {
                    List queued = (List)_queuedOutbound.get(to);
                    if (queued == null) {
                        queued = new ArrayList(1);
                        _queuedOutbound.put(to, queued);
                    }
                    queued.add(msg);
                } else {
                    state = new OutboundEstablishState(_context, remAddr, port, 
                                                       msg.getTarget().getIdentity(), 
                                                       new SessionKey(addr.getIntroKey()), addr);
                    _outboundStates.put(to, state);
                }
            }
            if (state != null) {
                state.addMessage(msg);
                List queued = (List)_queuedOutbound.remove(to);
                if (queued != null)
                    for (int i = 0; i < queued.size(); i++)
                        state.addMessage((OutNetMessage)queued.get(i));
            }
        }
        
        notifyActivity();
    }
    
    /**
     * Got a SessionRequest (initiates an inbound establishment)
     *
     */
    void receiveSessionRequest(RemoteHostId from, UDPPacketReader reader) {
        boolean isNew = false;
        InboundEstablishState state = null;
        synchronized (_inboundStates) {
            state = (InboundEstablishState)_inboundStates.get(from);
            if (state == null) {
                state = new InboundEstablishState(_context, from.getIP(), from.getPort(), _transport.getLocalPort());
                isNew = true;
                _inboundStates.put(from, state);
            }
        }
        state.receiveSessionRequest(reader.getSessionRequestReader());
        if (isNew) {
            if (!_transport.introducersRequired()) {
                long tag = _context.random().nextLong(MAX_TAG_VALUE);
                state.setSentRelayTag(tag);
                if (_log.shouldLog(Log.INFO))
                    _log.info("Received session request from " + from + ", sending relay tag " + tag);
            } else {
                if (_log.shouldLog(Log.INFO))
                    _log.info("Received session request, but our status is " + _transport.getReachabilityStatus());
            }
        }
        
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Receive session request from: " + state.getRemoteHostId().toString());
        
        notifyActivity();
    }
    
    /** 
     * got a SessionConfirmed (should only happen as part of an inbound 
     * establishment) 
     */
    void receiveSessionConfirmed(RemoteHostId from, UDPPacketReader reader) {
        InboundEstablishState state = null;
        synchronized (_inboundStates) {
            state = (InboundEstablishState)_inboundStates.get(from);
        }
        if (state != null) {
            state.receiveSessionConfirmed(reader.getSessionConfirmedReader());
            notifyActivity();
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Receive session confirmed from: " + state.getRemoteHostId().toString());
        }
    }
    
    /**
     * Got a SessionCreated (in response to our outbound SessionRequest)
     *
     */
    void receiveSessionCreated(RemoteHostId from, UDPPacketReader reader) {
        OutboundEstablishState state = null;
        synchronized (_outboundStates) {
            state = (OutboundEstablishState)_outboundStates.get(from);
        }
        if (state != null) {
            state.receiveSessionCreated(reader.getSessionCreatedReader());
            notifyActivity();
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Receive session created from: " + state.getRemoteHostId().toString());
        }
    }

    /**
     * A data packet arrived on an outbound connection being established, which
     * means its complete (yay!).  This is a blocking call, more than I'd like...
     *
     */
    PeerState receiveData(OutboundEstablishState state) {
        state.dataReceived();
        int active = 0;
        int admitted = 0;
        int remaining = 0;
        synchronized (_outboundStates) {
            active = _outboundStates.size();
            _outboundStates.remove(state.getRemoteHostId());
            if (_queuedOutbound.size() > 0) {
                // there shouldn't have been queued messages for this active state, but just in case...
                List queued = (List)_queuedOutbound.remove(state.getRemoteHostId());
                if (queued != null) {
                    for (int i = 0; i < queued.size(); i++) 
                        state.addMessage((OutNetMessage)queued.get(i));
                }
                
                admitted = locked_admitQueued();
            }
            remaining = _queuedOutbound.size();
        }
        //if (admitted > 0)
        //    _log.log(Log.CRIT, "Admitted " + admitted + " with " + remaining + " remaining queued and " + active + " active");
        
        if (_log.shouldLog(Log.INFO))
            _log.info("Outbound established completely!  yay");
        PeerState peer = handleCompletelyEstablished(state);
        notifyActivity();
        return peer;
    }

    private int locked_admitQueued() {
        int admitted = 0;
        while ( (_queuedOutbound.size() > 0) && (_outboundStates.size() < getMaxConcurrentEstablish()) ) {
            // ok, active shrunk, lets let some queued in.  duplicate the synchronized 
            // section from the add(

            RemoteHostId to = (RemoteHostId)_queuedOutbound.keySet().iterator().next();
            List queued = (List)_queuedOutbound.remove(to);

            if (queued.size() <= 0)
                continue;
            
            OutNetMessage msg = (OutNetMessage)queued.get(0);
            RouterAddress ra = msg.getTarget().getTargetAddress(_transport.getStyle());
            if (ra == null) {
                for (int i = 0; i < queued.size(); i++) 
                    _transport.failed((OutNetMessage)queued.get(i));
                continue;
            }
            UDPAddress addr = new UDPAddress(ra);
            InetAddress remAddr = addr.getHostAddress();
            int port = addr.getPort();

            OutboundEstablishState qstate = new OutboundEstablishState(_context, remAddr, port, 
                                               msg.getTarget().getIdentity(), 
                                               new SessionKey(addr.getIntroKey()), addr);
            _outboundStates.put(to, qstate);

            for (int i = 0; i < queued.size(); i++)
                qstate.addMessage((OutNetMessage)queued.get(i));
            admitted++;
        }
        return admitted;
    }
    
    private void notifyActivity() {
        synchronized (_activityLock) { 
            _activity++;
            _activityLock.notifyAll(); 
        }
    }
    
    /** kill any inbound or outbound that takes more than 30s */
    private static final int MAX_ESTABLISH_TIME = 30*1000;
    
    /** 
     * ok, fully received, add it to the established cons and queue up a
     * netDb store to them
     *
     */
    private void handleCompletelyEstablished(InboundEstablishState state) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Handle completely established (inbound): " + state.getRemoteHostId().toString());
        long now = _context.clock().now();
        RouterIdentity remote = state.getConfirmedIdentity();
        PeerState peer = new PeerState(_context);
        peer.setCurrentCipherKey(state.getCipherKey());
        peer.setCurrentMACKey(state.getMACKey());
        peer.setCurrentReceiveSecond(now - (now % 1000));
        peer.setKeyEstablishedTime(now);
        peer.setLastReceiveTime(now);
        peer.setLastSendTime(now);
        peer.setRemoteAddress(state.getSentIP(), state.getSentPort());
        peer.setRemotePeer(remote.calculateHash());
        peer.setWeRelayToThemAs(state.getSentRelayTag());
        peer.setTheyRelayToUsAs(0);
        //if (true) // for now, only support direct
        //    peer.setRemoteRequiresIntroduction(false);
        
        _transport.addRemotePeerState(peer);
        
        _context.statManager().addRateData("udp.inboundEstablishTime", state.getLifetime(), 0);
        sendOurInfo(peer);
    }
    
    /** 
     * ok, fully received, add it to the established cons and send any
     * queued messages
     *
     */
    private PeerState handleCompletelyEstablished(OutboundEstablishState state) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Handle completely established (outbound): " + state.getRemoteHostId().toString());
        long now = _context.clock().now();
        RouterIdentity remote = state.getRemoteIdentity();
        PeerState peer = new PeerState(_context);
        peer.setCurrentCipherKey(state.getCipherKey());
        peer.setCurrentMACKey(state.getMACKey());
        peer.setCurrentReceiveSecond(now - (now % 1000));
        peer.setKeyEstablishedTime(now);
        peer.setLastReceiveTime(now);
        peer.setLastSendTime(now);
        peer.setRemoteAddress(state.getSentIP(), state.getSentPort());
        peer.setRemotePeer(remote.calculateHash());
        peer.setTheyRelayToUsAs(state.getReceivedRelayTag());
        peer.setWeRelayToThemAs(0);
        
        _transport.addRemotePeerState(peer);
        
        _context.statManager().addRateData("udp.outboundEstablishTime", state.getLifetime(), 0);
        sendOurInfo(peer);
        
        while (true) {
            OutNetMessage msg = state.getNextQueuedMessage();
            if (msg == null)
                break;
            _transport.send(msg);
        }
        return peer;
    }
    
    private void sendOurInfo(PeerState peer) {
        if (_log.shouldLog(Log.INFO))
            _log.info("Publishing to the peer after confirm: " + peer);
        
        DatabaseStoreMessage m = new DatabaseStoreMessage(_context);
        m.setKey(_context.routerHash());
        m.setRouterInfo(_context.router().getRouterInfo());
        m.setMessageExpiration(_context.clock().now() + 10*1000);
        _transport.send(m, peer);
    }
    
    public static final long MAX_TAG_VALUE = 0xFFFFFFFFl;
    
    private void sendCreated(InboundEstablishState state) {
        long now = _context.clock().now();
        if (!_transport.introducersRequired()) {
            // offer to relay
            // (perhaps we should check our bw usage and/or how many peers we are 
            //  already offering introducing?)
            state.setSentRelayTag(_context.random().nextLong(MAX_TAG_VALUE));
        } else {
            // don't offer to relay
            state.setSentRelayTag(0);
        }
        
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Send created to: " + state.getRemoteHostId().toString());
        
        try {
            state.generateSessionKey();
        } catch (DHSessionKeyBuilder.InvalidPublicParameterException ippe) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Peer " + state.getRemoteHostId() + " sent us an invalid DH parameter (or were spoofed)", ippe);
            synchronized (_inboundStates) {
                _inboundStates.remove(state.getRemoteHostId());
            }
            return;
        }
        _transport.send(_builder.buildSessionCreatedPacket(state, _transport.getExternalPort(), _transport.getIntroKey()));
        // if they haven't advanced to sending us confirmed packets in 5s,
        // repeat
        state.setNextSendTime(now + 5*1000);
    }

    private void sendRequest(OutboundEstablishState state) {
        long now = _context.clock().now();
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Send request to: " + state.getRemoteHostId().toString());
        _transport.send(_builder.buildSessionRequestPacket(state));
        state.requestSent();
    }
    
    private static final long MAX_NONCE = 0xFFFFFFFFl;
    /** if we don't get a relayResponse in 3 seconds, try again with another intro peer */
    private static final int INTRO_ATTEMPT_TIMEOUT = 3*1000;
    
    private void handlePendingIntro(OutboundEstablishState state) {
        long nonce = _context.random().nextLong(MAX_NONCE);
        while (true) {
            synchronized (_liveIntroductions) {
                OutboundEstablishState old = (OutboundEstablishState)_liveIntroductions.put(new Long(nonce), state);
                if (old != null) {
                    nonce = _context.random().nextLong(MAX_NONCE);
                } else {
                    break;
                }
            }
        }
        SimpleTimer.getInstance().addEvent(new FailIntroduction(state, nonce), INTRO_ATTEMPT_TIMEOUT);
        state.setIntroNonce(nonce);
        _context.statManager().addRateData("udp.sendIntroRelayRequest", 1, 0);
        _transport.send(_builder.buildRelayRequest(state, _transport.getIntroKey()));
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Send intro for " + state.getRemoteHostId().toString() + " with our intro key as " + _transport.getIntroKey().toBase64());
        state.introSent();
    }
    private class FailIntroduction implements SimpleTimer.TimedEvent {
        private long _nonce;
        private OutboundEstablishState _state;
        public FailIntroduction(OutboundEstablishState state, long nonce) {
            _nonce = nonce;
            _state = state;
        }
        public void timeReached() {
            OutboundEstablishState removed = null;
            synchronized (_liveIntroductions) {
                removed = (OutboundEstablishState)_liveIntroductions.remove(new Long(_nonce));
                if (removed != _state) {
                    // another one with the same nonce in a very brief time...
                    _liveIntroductions.put(new Long(_nonce), removed);
                    removed = null;
                }
            }
            if (removed != null) {
                _context.statManager().addRateData("udp.sendIntroRelayTimeout", 1, 0);
                notifyActivity();
            }
        }
    }
    
    public void receiveRelayResponse(RemoteHostId bob, UDPPacketReader reader) {
        long nonce = reader.getRelayResponseReader().readNonce();
        OutboundEstablishState state = null;
        synchronized (_liveIntroductions) {
            state = (OutboundEstablishState)_liveIntroductions.remove(new Long(nonce));
        }
        if (state == null) 
            return; // already established
        
        int sz = reader.getRelayResponseReader().readCharlieIPSize();
        byte ip[] = new byte[sz];
        reader.getRelayResponseReader().readCharlieIP(ip, 0);
        InetAddress addr = null;
        try {
            addr = InetAddress.getByAddress(ip);
        } catch (UnknownHostException uhe) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Introducer for " + state + " (" + bob + ") sent us an invalid IP for our targer: " + Base64.encode(ip), uhe);
            // these two cause this peer to requeue for a new intro peer
            state.introductionFailed();
            notifyActivity();
            return;
        }
        _context.statManager().addRateData("udp.receiveIntroRelayResponse", state.getLifetime(), 0);
        int port = reader.getRelayResponseReader().readCharliePort();
        state.introduced(addr, ip, port);
        notifyActivity();
    }
    
    private void sendConfirmation(OutboundEstablishState state) {
        long now = _context.clock().now();
        boolean valid = state.validateSessionCreated();
        if (!valid) // validate clears fields on failure
            return;
        
        // gives us the opportunity to "detect" our external addr
        _transport.externalAddressReceived(state.getReceivedIP(), state.getReceivedPort());
        
        // signs if we havent signed yet
        state.prepareSessionConfirmed();
        
        UDPPacket packets[] = _builder.buildSessionConfirmedPackets(state, _context.router().getRouterInfo().getIdentity());
        
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Send confirm to: " + state.getRemoteHostId().toString());
        
        for (int i = 0; i < packets.length; i++)
            _transport.send(packets[i]);
        
        state.confirmedPacketsSent();
    }
    
    
    /**
     * Drive through the inbound establishment states, adjusting one of them
     * as necessary
     */
    private long handleInbound() {
        long now = _context.clock().now();
        long nextSendTime = -1;
        InboundEstablishState inboundState = null;
        synchronized (_inboundStates) {
            //if (_log.shouldLog(Log.DEBUG))
            //    _log.debug("# inbound states: " + _inboundStates.size());
            for (Iterator iter = _inboundStates.values().iterator(); iter.hasNext(); ) {
                InboundEstablishState cur = (InboundEstablishState)iter.next();
                if (cur.getState() == InboundEstablishState.STATE_CONFIRMED_COMPLETELY) {
                    // completely received (though the signature may be invalid)
                    iter.remove();
                    inboundState = cur;
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Removing completely confirmed inbound state");
                    break;
                } else if (cur.getLifetime() > MAX_ESTABLISH_TIME) {
                    // took too long, fuck 'em
                    iter.remove();
                    _context.statManager().addRateData("udp.inboundEstablishFailedState", cur.getState(), cur.getLifetime());
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Removing expired inbound state");
                } else {
                    if (cur.getNextSendTime() <= now) {
                        // our turn...
                        inboundState = cur;
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug("Processing inbound that wanted activity");
                        break;
                    } else {
                        // nothin to do but wait for them to send us
                        // stuff, so lets move on to the next one being
                        // established
                        long when = -1;
                        if (cur.getNextSendTime() <= 0) {
                            when = cur.getEstablishBeginTime() + MAX_ESTABLISH_TIME;
                        } else {
                            when = cur.getNextSendTime();
                        }
                        if (when < nextSendTime)
                            nextSendTime = when;
                    }
                }
            }
        }

        if (inboundState != null) {
            //if (_log.shouldLog(Log.DEBUG))
            //    _log.debug("Processing for inbound: " + inboundState);
            switch (inboundState.getState()) {
                case InboundEstablishState.STATE_REQUEST_RECEIVED:
                    sendCreated(inboundState);
                    break;
                case InboundEstablishState.STATE_CREATED_SENT: // fallthrough
                case InboundEstablishState.STATE_CONFIRMED_PARTIALLY:
                    // if its been 5s since we sent the SessionCreated, resend
                    if (inboundState.getNextSendTime() <= now)
                        sendCreated(inboundState);
                    break;
                case InboundEstablishState.STATE_CONFIRMED_COMPLETELY:
                    if (inboundState.getConfirmedIdentity() != null) {
                        handleCompletelyEstablished(inboundState);
                        break;
                    } else {
                        if (_log.shouldLog(Log.WARN))
                            _log.warn("why are we confirmed with no identity? " + inboundState);
                        break;
                    }
                case InboundEstablishState.STATE_UNKNOWN: // fallthrough
                default:
                    // wtf
                    if (_log.shouldLog(Log.ERROR))
                        _log.error("hrm, state is unknown for " + inboundState);
            }

            // ok, since there was something to do, we want to loop again
            nextSendTime = now;
        }
        
        return nextSendTime;
    }
    
    
    /**
     * Drive through the outbound establishment states, adjusting one of them
     * as necessary
     */
    private long handleOutbound() {
        long now = _context.clock().now();
        long nextSendTime = -1;
        OutboundEstablishState outboundState = null;
        int admitted = 0;
        int remaining = 0;
        int active = 0;
        synchronized (_outboundStates) {
            active = _outboundStates.size();
            //if (_log.shouldLog(Log.DEBUG))
            //    _log.debug("# outbound states: " + _outboundStates.size());
            for (Iterator iter = _outboundStates.values().iterator(); iter.hasNext(); ) {
                OutboundEstablishState cur = (OutboundEstablishState)iter.next();
                if (cur.getState() == OutboundEstablishState.STATE_CONFIRMED_COMPLETELY) {
                    // completely received
                    iter.remove();
                    outboundState = cur;
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Removing confirmed outbound: " + cur);
                    break;
                } else if (cur.getLifetime() > MAX_ESTABLISH_TIME) {
                    // took too long, fuck 'em
                    iter.remove();
                    outboundState = cur;
                    _context.statManager().addRateData("udp.outboundEstablishFailedState", cur.getState(), cur.getLifetime());
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Removing expired outbound: " + cur);
                    break;
                } else {
                    if (cur.getNextSendTime() <= now) {
                        // our turn...
                        outboundState = cur;
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug("Outbound wants activity: " + cur);
                        break;
                    } else {
                        // nothin to do but wait for them to send us
                        // stuff, so lets move on to the next one being
                        // established
                        long when = -1;
                        if (cur.getNextSendTime() <= 0) {
                            when = cur.getEstablishBeginTime() + MAX_ESTABLISH_TIME;
                        } else {
                            when = cur.getNextSendTime();
                        }
                        if ( (nextSendTime <= 0) || (when < nextSendTime) )
                            nextSendTime = when;
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug("Outbound doesn't want activity: " + cur + " (next=" + (when-now) + ")");
                    }
                }
            }
            
            admitted = locked_admitQueued();    
            remaining = _queuedOutbound.size();
        }

        //if (admitted > 0)
        //    _log.log(Log.CRIT, "Admitted " + admitted + " in push with " + remaining + " remaining queued and " + active + " active");
        
        if (outboundState != null) {
            if (outboundState.getLifetime() > MAX_ESTABLISH_TIME) {
                if (outboundState.getState() != OutboundEstablishState.STATE_CONFIRMED_COMPLETELY) {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Lifetime of expired outbound establish: " + outboundState.getLifetime());
                    while (true) {
                        OutNetMessage msg = outboundState.getNextQueuedMessage();
                        if (msg == null)
                            break;
                        _transport.failed(msg);
                    }
                    String err = null;
                    switch (outboundState.getState()) {
                        case OutboundEstablishState.STATE_CONFIRMED_PARTIALLY:
                            err = "Took too long to establish remote connection (confirmed partially)";
                            break;
                        case OutboundEstablishState.STATE_CREATED_RECEIVED:
                            err = "Took too long to establish remote connection (created received)";
                            break;
                        case OutboundEstablishState.STATE_REQUEST_SENT:
                            err = "Took too long to establish remote connection (request sent)";
                            break;
                        case OutboundEstablishState.STATE_PENDING_INTRO:
                            err = "Took too long to establish remote connection (intro failed)";
                            break;
                        case OutboundEstablishState.STATE_UNKNOWN: // fallthrough
                        default:
                            err = "Took too long to establish remote connection (unknown state)";
                    }
                    
                    _context.shitlist().shitlistRouter(outboundState.getRemoteIdentity().calculateHash(), err);
                } else {
                    while (true) {
                        OutNetMessage msg = outboundState.getNextQueuedMessage();
                        if (msg == null)
                            break;
                        _transport.send(msg);
                    }
                }
            } else {
                switch (outboundState.getState()) {
                    case OutboundEstablishState.STATE_UNKNOWN:
                        sendRequest(outboundState);
                        break;
                    case OutboundEstablishState.STATE_REQUEST_SENT:
                        // no response yet (or it was invalid), lets retry
                        if (outboundState.getNextSendTime() <= now)
                            sendRequest(outboundState);
                        break;
                    case OutboundEstablishState.STATE_CREATED_RECEIVED: // fallthrough
                    case OutboundEstablishState.STATE_CONFIRMED_PARTIALLY:
                        if (outboundState.getNextSendTime() <= now)
                            sendConfirmation(outboundState);
                        break;
                    case OutboundEstablishState.STATE_CONFIRMED_COMPLETELY:
                        handleCompletelyEstablished(outboundState);
                        break;
                    case OutboundEstablishState.STATE_PENDING_INTRO:
                        handlePendingIntro(outboundState);
                        break;
                    default:
                        // wtf
                }
            }
            
            //if (_log.shouldLog(Log.DEBUG))
            //    _log.debug("Since something happened outbound, next=now");
            // ok, since there was something to do, we want to loop again
            nextSendTime = now;
        } else {
            //if (_log.shouldLog(Log.DEBUG))
            //    _log.debug("Nothing happened outbound, next is in " + (nextSendTime-now));
        }
        
        return nextSendTime;
    }

    /**    
     * Driving thread, processing up to one step for an inbound peer and up to
     * one step for an outbound peer.  This is prodded whenever any peer's state
     * changes as well.
     *
     */    
    private class Establisher implements Runnable {
        public void run() {
            while (_alive) {
                try {
                    doPass();
                } catch (OutOfMemoryError oom) {
                    throw oom;
                } catch (RuntimeException re) {
                    _log.log(Log.CRIT, "Error in the establisher", re);
                }
            }
        }
    }
    
    private void doPass() {
        _activity = 0;
        long now = _context.clock().now();
        long nextSendTime = -1;
        long nextSendInbound = handleInbound();
        long nextSendOutbound = handleOutbound();
        if (nextSendInbound > 0)
            nextSendTime = nextSendInbound;
        if ( (nextSendTime < 0) || (nextSendOutbound < nextSendTime) )
            nextSendTime = nextSendOutbound;

        long delay = nextSendTime - now;
        if ( (nextSendTime == -1) || (delay > 0) ) {
            boolean interrupted = false;
            try {
                synchronized (_activityLock) {
                    if (_activity > 0)
                        return;
                    if (nextSendTime == -1)
                        _activityLock.wait();
                    else
                        _activityLock.wait(delay);
                }
            } catch (InterruptedException ie) {
                interrupted = true;
            }
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("After waiting w/ nextSend=" + nextSendTime 
                           + " and delay=" + delay + " and interrupted=" + interrupted);
        }
    }
}
