package net.i2p.client.streaming;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.i2p.I2PAppContext;
import net.i2p.client.I2PSession;
import net.i2p.data.Base64;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer;

/**
 * Maintain the state controlling a streaming connection between two 
 * destinations.
 *
 */
public class Connection {
    private I2PAppContext _context;
    private Log _log;
    private ConnectionManager _connectionManager;
    private Destination _remotePeer;
    private byte _sendStreamId[];
    private byte _receiveStreamId[];
    private long _lastSendTime;
    private long _lastSendId;
    private boolean _resetReceived;
    private boolean _resetSent;
    private long _resetSentOn;
    private boolean _connected;
    private boolean _hardDisconnected;
    private MessageInputStream _inputStream;
    private MessageOutputStream _outputStream;
    private SchedulerChooser _chooser;
    private long _nextSendTime;
    private long _ackedPackets;
    private long _createdOn;
    private long _closeSentOn;
    private long _closeReceivedOn;
    private int _unackedPacketsReceived;
    private long _congestionWindowEnd;
    private long _highestAckedThrough;
    /** Packet ID (Long) to PacketLocal for sent but unacked packets */
    private Map _outboundPackets;
    private PacketQueue _outboundQueue;
    private ConnectionPacketHandler _handler;
    private ConnectionOptions _options;
    private ConnectionDataReceiver _receiver;
    private I2PSocketFull _socket;
    /** set to an error cause if the connection could not be established */
    private String _connectionError;
    private long _disconnectScheduledOn;
    private long _lastReceivedOn;
    private ActivityTimer _activityTimer;
    /** window size when we last saw congestion */
    private int _lastCongestionSeenAt;
    private long _lastCongestionTime;
    private long _lastCongestionHighestUnacked;
    private boolean _ackSinceCongestion;
    /** Notify this on connection (or connection failure) */
    private Object _connectLock;
    /** how many messages have been resent and not yet ACKed? */
    private int _activeResends;
    
    private long _lifetimeBytesSent;
    private long _lifetimeBytesReceived;
    private long _lifetimeDupMessageSent;
    private long _lifetimeDupMessageReceived;
    
    public static final long MAX_RESEND_DELAY = 60*1000;
    public static final long MIN_RESEND_DELAY = 10*1000;

    /** wait up to 5 minutes after disconnection so we can ack/close packets */
    public static int DISCONNECT_TIMEOUT = 5*60*1000;
    
    /** lets be sane- no more than 64 packets in the air in each dir */
    public static final int MAX_WINDOW_SIZE = 64;
    
    public Connection(I2PAppContext ctx, ConnectionManager manager, SchedulerChooser chooser, PacketQueue queue, ConnectionPacketHandler handler) {
        this(ctx, manager, chooser, queue, handler, null);
    }
    public Connection(I2PAppContext ctx, ConnectionManager manager, SchedulerChooser chooser, PacketQueue queue, ConnectionPacketHandler handler, ConnectionOptions opts) {
        _context = ctx;
        _log = ctx.logManager().getLog(Connection.class);
        _receiver = new ConnectionDataReceiver(ctx, this);
        _inputStream = new MessageInputStream(ctx);
        _outputStream = new MessageOutputStream(ctx, _receiver, (opts == null ? Packet.MAX_PAYLOAD_SIZE : opts.getMaxMessageSize()));
        _chooser = chooser;
        _outboundPackets = new TreeMap();
        _outboundQueue = queue;
        _handler = handler;
        _options = (opts != null ? opts : new ConnectionOptions());
        _lastSendId = -1;
        _nextSendTime = -1;
        _ackedPackets = 0;
        _createdOn = ctx.clock().now();
        _closeSentOn = -1;
        _closeReceivedOn = -1;
        _unackedPacketsReceived = 0;
        _congestionWindowEnd = 0;
        _highestAckedThrough = -1;
        _lastCongestionSeenAt = MAX_WINDOW_SIZE*2; // lets allow it to grow
        _lastCongestionTime = -1;
        _lastCongestionHighestUnacked = -1;
        _connectionManager = manager;
        _resetReceived = false;
        _connected = true;
        _disconnectScheduledOn = -1;
        _lastReceivedOn = -1;
        _activityTimer = new ActivityTimer();
        _ackSinceCongestion = true;
        _connectLock = new Object();
        _activeResends = 0;
        _resetSentOn = -1;
        _context.statManager().createRateStat("stream.con.windowSizeAtCongestion", "How large was our send window when we send a dup?", "Stream", new long[] { 60*1000, 10*60*1000, 60*60*1000 });
        _context.statManager().createRateStat("stream.chokeSizeBegin", "How many messages were outstanding when we started to choke?", "Stream", new long[] { 60*1000, 10*60*1000, 60*60*1000 });
        _context.statManager().createRateStat("stream.chokeSizeEnd", "How many messages were outstanding when we stopped being choked?", "Stream", new long[] { 60*1000, 10*60*1000, 60*60*1000 });
    }
    
    public long getNextOutboundPacketNum() { 
        synchronized (this) {
            return ++_lastSendId;
        }
    }
    
    void closeReceived() {
        setCloseReceivedOn(_context.clock().now());
        _inputStream.closeReceived();
        synchronized (_connectLock) { _connectLock.notifyAll(); }
    }
    
    /**
     * Block until there is an open outbound packet slot or the write timeout 
     * expires.  
     *
     * @return true if the packet should be sent
     */
    boolean packetSendChoke(long timeoutMs) {
        if (false) return true;
        long writeExpire = timeoutMs;
        long start = _context.clock().now();
        boolean started = false;
        while (true) {
            long timeLeft = writeExpire - _context.clock().now();
            synchronized (_outboundPackets) {
                if (!started)
                    _context.statManager().addRateData("stream.chokeSizeBegin", _outboundPackets.size(), timeoutMs);
                if (!_connected) 
                    return false;
                started = true;
                if ( (_outboundPackets.size() >= _options.getWindowSize()) || (_activeResends > 0) ) {
                    if (writeExpire > 0) {
                        if (timeLeft <= 0) {
                            _log.error("Outbound window is full of " + _outboundPackets.size() 
                                       + " with " + _activeResends + " active resends"
                                       + " and we've waited too long (" + writeExpire + "ms)");
                            return false;
                        }
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug("Outbound window is full (" + _outboundPackets.size() + "/" + _options.getWindowSize() + "/" 
                                       + _activeResends + "), waiting " + timeLeft);
                        try { _outboundPackets.wait(timeLeft); } catch (InterruptedException ie) {}
                    } else {
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug("Outbound window is full (" + _outboundPackets.size() + "/" + _activeResends 
                                       + "), waiting indefinitely");
                        try { _outboundPackets.wait(); } catch (InterruptedException ie) {}
                    }
                } else {
                    _context.statManager().addRateData("stream.chokeSizeEnd", _outboundPackets.size(), _context.clock().now() - start);
                    return true;
                }
            }
        }
    }
    void windowAdjusted() {
        synchronized (_outboundPackets) {
            _outboundPackets.notifyAll();
        }
    }
    
    void ackImmediately() {
        PacketLocal packet = _receiver.send(null, 0, 0);
        //packet.releasePayload();
    }

    /**
     * got a packet we shouldn't have, send 'em a reset
     *
     */
    void sendReset() {
        if (_disconnectScheduledOn < 0) {
            _disconnectScheduledOn = _context.clock().now();
            SimpleTimer.getInstance().addEvent(new DisconnectEvent(), DISCONNECT_TIMEOUT);
        }
        _resetSent = true;
        if (_resetSentOn <= 0)
            _resetSentOn = _context.clock().now();
        if ( (_remotePeer == null) || (_sendStreamId == null) ) return;
        PacketLocal reply = new PacketLocal(_context, _remotePeer);
        reply.setFlag(Packet.FLAG_RESET);
        reply.setFlag(Packet.FLAG_SIGNATURE_INCLUDED);
        reply.setSendStreamId(_sendStreamId);
        reply.setReceiveStreamId(_receiveStreamId);
        reply.setOptionalFrom(_connectionManager.getSession().getMyDestination());
        // this just sends the packet - no retries or whatnot
        _outboundQueue.enqueue(reply);
    }
    
    /**
     * Flush any data that we can
     */
    void sendAvailable() {
        // this grabs the data, builds a packet, and queues it up via sendPacket
        try {
            _outputStream.flushAvailable(_receiver, false);
        } catch (IOException ioe) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Error flushing available", ioe);
        }
    }
    
    void sendPacket(PacketLocal packet) {
        if (packet == null) return;
        
        setNextSendTime(-1);
        _unackedPacketsReceived = 0;
        if (_options.getRequireFullySigned()) {
            packet.setFlag(Packet.FLAG_SIGNATURE_INCLUDED);
            packet.setFlag(Packet.FLAG_SIGNATURE_REQUESTED);
        }
        
        boolean ackOnly = false;
                
        if ( (packet.getSequenceNum() == 0) && (!packet.isFlagSet(Packet.FLAG_SYNCHRONIZE)) ) {
            ackOnly = true;
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("No resend for " + packet);
        } else {
            int remaining = 0;
            synchronized (_outboundPackets) {
                _outboundPackets.put(new Long(packet.getSequenceNum()), packet);
                remaining = _options.getWindowSize() - _outboundPackets.size() ;
                _outboundPackets.notifyAll();
            }
            if (remaining < 0) 
                remaining = 0;
            if (packet.isFlagSet(Packet.FLAG_CLOSE) || (remaining < 2)) {
                packet.setOptionalDelay(0);
            } else {
                int delay = _options.getRTT() / 2;
                packet.setOptionalDelay(delay);
                _log.debug("Requesting ack delay of " + delay + "ms for packet " + packet);
            }
            packet.setFlag(Packet.FLAG_DELAY_REQUESTED);
            
            long timeout = _options.getRTT() + MIN_RESEND_DELAY;
            if (timeout > MAX_RESEND_DELAY)
                timeout = MAX_RESEND_DELAY;
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Resend in " + timeout + " for " + packet, new Exception("Sent by"));

            SimpleTimer.getInstance().addEvent(new ResendPacketEvent(packet), timeout);
        }

        _lastSendTime = _context.clock().now();
        _outboundQueue.enqueue(packet);        
        resetActivityTimer();
        
        if (ackOnly) {
            // ACK only, don't schedule this packet for retries
            // however, if we are running low on sessionTags we want to send
            // something that will get a reply so that we can deliver some new tags -
            // ACKs don't get ACKed, but pings do.
            if ( (packet.getTagsSent() != null) && (packet.getTagsSent().size() > 0) ) {
                _log.warn("Sending a ping since the ACK we just sent has " + packet.getTagsSent().size() + " tags");
                _connectionManager.ping(_remotePeer, _options.getRTT()*2, false, packet.getKeyUsed(), packet.getTagsSent(), new PingNotifier());
            }
        }
    }
    
    private class PingNotifier implements ConnectionManager.PingNotifier {
        private long _startedPingOn;
        public PingNotifier() {
            _startedPingOn = _context.clock().now();
        }
        public void pingComplete(boolean ok) {
            long time = _context.clock().now()-_startedPingOn;
            if (ok)
                _options.updateRTT((int)time);
            else
                _options.updateRTT((int)time*2);
        }
    }
    
    List ackPackets(long ackThrough, long nacks[]) {
        if (nacks == null) {
            _highestAckedThrough = ackThrough;
        } else {
            long lowest = -1;
            for (int i = 0; i < nacks.length; i++) {
                if ( (lowest < 0) || (nacks[i] < lowest) )
                    lowest = nacks[i];
            }
            if (lowest - 1 > _highestAckedThrough)
                _highestAckedThrough = lowest - 1;
        }
        
        List acked = null;
        synchronized (_outboundPackets) {
            for (Iterator iter = _outboundPackets.keySet().iterator(); iter.hasNext(); ) {
                Long id = (Long)iter.next();
                if (id.longValue() <= ackThrough) {
                    boolean nacked = false;
                    if (nacks != null) {
                        // linear search since its probably really tiny
                        for (int i = 0; i < nacks.length; i++) {
                            if (nacks[i] == id.longValue()) {
                                nacked = true;
                                break; // NACKed
                            }
                        }
                    }
                    if (!nacked) { // aka ACKed
                        if (acked == null) 
                            acked = new ArrayList(1);
                        PacketLocal ackedPacket = (PacketLocal)_outboundPackets.get(id);
                        ackedPacket.ackReceived();
                        acked.add(ackedPacket);
                    }
                } else {
                    break; // _outboundPackets is ordered
                }
            }
            if (acked != null) {
                for (int i = 0; i < acked.size(); i++) {
                    PacketLocal p = (PacketLocal)acked.get(i);
                    _outboundPackets.remove(new Long(p.getSequenceNum()));
                    _ackedPackets++;
                    if (p.getNumSends() > 1) {
                        _activeResends--;
                        if (_log.shouldLog(Log.INFO))
                            _log.info("Active resend of " + p + " successful, # active left: " + _activeResends);
                    }
                }
            }
            if ( (_outboundPackets.size() <= 0) && (_activeResends != 0) ) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("All outbound packets acked, clearing " + _activeResends);
                _activeResends = 0;
            }
            _outboundPackets.notifyAll();
        }
        if ((acked != null) && (acked.size() > 0) )
            _ackSinceCongestion = true;
        return acked;
    }

    private long _occurredTime;
    private long _occurredEventCount;
    void eventOccurred() {
        long now = System.currentTimeMillis();
        
        TaskScheduler sched = _chooser.getScheduler(this);
        
        now = now - now % 1000;
        if (_occurredTime == now) {
            _occurredEventCount++;
        } else {
            _occurredTime = now;
            if ( (_occurredEventCount > 1000) && (_log.shouldLog(Log.WARN)) ) {
                _log.warn("More than 1000 events (" + _occurredEventCount + ") in a second on " 
                          + toString() + ": scheduler = " + sched);
            }
            _occurredEventCount = 0;
        }
        
        long before = System.currentTimeMillis();
            
        sched.eventOccurred(this);
        long elapsed = System.currentTimeMillis() - before;
        if ( (elapsed > 1000) && (_log.shouldLog(Log.WARN)) )
            _log.warn("Took " + elapsed + "ms to pump through " + sched);
    }
    
    void resetReceived() {
        if (_disconnectScheduledOn < 0) {
            _disconnectScheduledOn = _context.clock().now();
            SimpleTimer.getInstance().addEvent(new DisconnectEvent(), DISCONNECT_TIMEOUT);
        }
        _resetReceived = true;
        MessageOutputStream mos = _outputStream;
        MessageInputStream mis = _inputStream;
        if (mos != null)
            mos.streamErrorOccurred(new IOException("Reset received"));
        if (mis != null)
            mis.streamErrorOccurred(new IOException("Reset received"));
        _connectionError = "Connection reset";
        synchronized (_connectLock) { _connectLock.notifyAll(); }
    }
    public boolean getResetReceived() { return _resetReceived; }
    
    public boolean getIsConnected() { return _connected; }
    public boolean getHardDisconnected() { return _hardDisconnected; }
    public boolean getResetSent() { return _resetSent; }
    public long getResetSentOn() { return _resetSentOn; }
    public long getDisconnectScheduledOn() { return _disconnectScheduledOn; }

    void disconnect(boolean cleanDisconnect) {
        disconnect(cleanDisconnect, true);
    }
    void disconnect(boolean cleanDisconnect, boolean removeFromConMgr) {
        synchronized (_connectLock) { _connectLock.notifyAll(); }
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Disconnecting " + toString(), new Exception("discon"));
        
        if (!cleanDisconnect) {
            _hardDisconnected = true;
            if (_log.shouldLog(Log.WARN))
                _log.warn("Hard disconnecting and sending a reset on " + toString(), new Exception("cause"));
            sendReset();
        }
        
        if (cleanDisconnect && _connected) {
            // send close packets and schedule stuff...
            _outputStream.closeInternal();
            _inputStream.close();
        } else {
            if (_connected)
                doClose();
            killOutstandingPackets();
        }
        if (removeFromConMgr) {
            if (_disconnectScheduledOn < 0) {
                _disconnectScheduledOn = _context.clock().now();
                SimpleTimer.getInstance().addEvent(new DisconnectEvent(), DISCONNECT_TIMEOUT);
            }
        }
        _connected = false;
    }
    
    void disconnectComplete() {
        _connected = false;
        if (_socket != null)
            _socket.destroy();
        _socket = null;
        if (_outputStream != null)
            _outputStream.destroy();
        if (_receiver != null)
            _receiver.destroy();
        if (_activityTimer != null)
            SimpleTimer.getInstance().removeEvent(_activityTimer);
        _activityTimer = null;
        
        if (_disconnectScheduledOn < 0) {
            _disconnectScheduledOn = _context.clock().now();
            
            if (_log.shouldLog(Log.INFO))
                _log.info("Connection disconnect complete from dead, drop the con "
                          + toString());
            _connectionManager.removeConnection(this);
        }

        killOutstandingPackets();
    }
    
    private void killOutstandingPackets() {
        boolean tagsCancelled = false;
        synchronized (_outboundPackets) {
            for (Iterator iter = _outboundPackets.values().iterator(); iter.hasNext(); ) {
                PacketLocal pl = (PacketLocal)iter.next();
                if ( (pl.getTagsSent() != null) && (pl.getTagsSent().size() > 0) )
                    tagsCancelled = true;
                pl.cancelled();
            }
            _outboundPackets.clear();
            _outboundPackets.notifyAll();
        }            
        if (tagsCancelled)
            _context.sessionKeyManager().failTags(_remotePeer.getPublicKey());
    }
    
    private class DisconnectEvent implements SimpleTimer.TimedEvent {
        public DisconnectEvent() {
            if (_log.shouldLog(Log.INFO))
                _log.info("Connection disconnect timer initiated: 5 minutes to drop " 
                          + Connection.this.toString());
        }
        public void timeReached() {
            killOutstandingPackets();
            if (_log.shouldLog(Log.INFO))
                _log.info("Connection disconnect timer complete, drop the con "
                          + Connection.this.toString());
            _connectionManager.removeConnection(Connection.this);
        }
    }
    
    private void doClose() {
        _outputStream.streamErrorOccurred(new IOException("Hard disconnect"));
        _inputStream.closeReceived();
        synchronized (_connectLock) { _connectLock.notifyAll(); }
    }
    
    /** who are we talking with */
    public Destination getRemotePeer() { return _remotePeer; }
    public void setRemotePeer(Destination peer) { _remotePeer = peer; }
    
    /** what stream do we send data to the peer on? */
    public byte[] getSendStreamId() { return _sendStreamId; }
    public void setSendStreamId(byte[] id) { _sendStreamId = id; }
    
    /** stream the peer sends data to us on. (may be null) */
    public byte[] getReceiveStreamId() { return _receiveStreamId; }
    public void setReceiveStreamId(byte[] id) { 
        _receiveStreamId = id; 
        synchronized (_connectLock) { _connectLock.notifyAll(); }
    }
    
    /** when did we last send anything to the peer? */
    public long getLastSendTime() { return _lastSendTime; }
    public void setLastSendTime(long when) { _lastSendTime = when; }
    
    /** what was the last packet Id sent to the peer? */
    public long getLastSendId() { return _lastSendId; }
    public void setLastSendId(long id) { _lastSendId = id; }
    
    public ConnectionOptions getOptions() { return _options; }
    public void setOptions(ConnectionOptions opts) { _options = opts; }
        
    public I2PSession getSession() { return _connectionManager.getSession(); }
    public I2PSocketFull getSocket() { return _socket; }
    public void setSocket(I2PSocketFull socket) { _socket = socket; }
    
    public String getConnectionError() { return _connectionError; }
    public void setConnectionError(String err) { _connectionError = err; }
    
    public long getLifetime() { 
        if (_closeSentOn <= 0)
            return _context.clock().now() - _createdOn; 
        else
            return _closeSentOn - _createdOn;
    }
    
    public ConnectionPacketHandler getPacketHandler() { return _handler; }
    
    public long getLifetimeBytesSent() { return _lifetimeBytesSent; }
    public long getLifetimeBytesReceived() { return _lifetimeBytesReceived; }
    public long getLifetimeDupMessagesSent() { return _lifetimeDupMessageSent; }
    public long getLifetimeDupMessagesReceived() { return _lifetimeDupMessageReceived; }
    public void incrementBytesSent(int bytes) { _lifetimeBytesSent += bytes; }
    public void incrementDupMessagesSent(int msgs) { _lifetimeDupMessageSent += msgs; }
    public void incrementBytesReceived(int bytes) { _lifetimeBytesReceived += bytes; }
    public void incrementDupMessagesReceived(int msgs) { _lifetimeDupMessageReceived += msgs; }
    
    /** 
     * Time when the scheduler next want to send a packet, or -1 if 
     * never.  This should be set when we want to send on timeout, for 
     * instance, or want to delay an ACK.
     */
    public long getNextSendTime() { return _nextSendTime; }
    public void setNextSendTime(long when) { 
        if (_nextSendTime >= 0) {
            if (when < _nextSendTime)
                _nextSendTime = when;
        } else {
            _nextSendTime = when; 
        }

        if (_nextSendTime >= 0) {
            long max = _context.clock().now() + _options.getSendAckDelay();
            if (max < _nextSendTime)
                _nextSendTime = max;
        }
        
        if (_log.shouldLog(Log.DEBUG) && false) {
            if (_nextSendTime <= 0) 
                _log.debug("set next send time to an unknown time", new Exception(toString()));
            else
                _log.debug("set next send time to " + (_nextSendTime-_context.clock().now()) + "ms from now", new Exception(toString()));
        }
    }
    
    /** how many packets have we sent and the other side has ACKed? */
    public long getAckedPackets() { return _ackedPackets; }
    public long getCreatedOn() { return _createdOn; }
    public long getCloseSentOn() { return _closeSentOn; }
    public void setCloseSentOn(long when) { 
        _closeSentOn = when;
        if (_disconnectScheduledOn < 0) {
            _disconnectScheduledOn = _context.clock().now();
            SimpleTimer.getInstance().addEvent(new DisconnectEvent(), DISCONNECT_TIMEOUT);
        }
    }
    public long getCloseReceivedOn() { return _closeReceivedOn; }
    public void setCloseReceivedOn(long when) { _closeReceivedOn = when; }
    
    public void incrementUnackedPacketsReceived() { _unackedPacketsReceived++; }
    public int getUnackedPacketsReceived() { return _unackedPacketsReceived; }
    /** how many packets have we sent but not yet received an ACK for? */
    public int getUnackedPacketsSent() { 
        synchronized (_outboundPackets) { 
            return _outboundPackets.size(); 
        } 
    }
    
    public long getCongestionWindowEnd() { return _congestionWindowEnd; }
    public void setCongestionWindowEnd(long endMsg) { _congestionWindowEnd = endMsg; }
    public long getHighestAckedThrough() { return _highestAckedThrough; }
    public void setHighestAckedThrough(long msgNum) { _highestAckedThrough = msgNum; }
    
    public long getLastActivityOn() {
        return (_lastSendTime > _lastReceivedOn ? _lastSendTime : _lastReceivedOn);
    }
    
    public int getLastCongestionSeenAt() { return _lastCongestionSeenAt; }
    
    void congestionOccurred() {
        // if we hit congestion and e.g. 5 packets are resent,
        // dont set the size to (winSize >> 4).  only set the
        if (_ackSinceCongestion) {
            _lastCongestionSeenAt = _options.getWindowSize();
            _lastCongestionTime = _context.clock().now();
            _lastCongestionHighestUnacked = _lastSendId;
            _ackSinceCongestion = false;
        }
    }
    
    void packetReceived() {
        _lastReceivedOn = _context.clock().now();
        resetActivityTimer();
        synchronized (_connectLock) { _connectLock.notifyAll(); }
    }
    
    /** 
     * wait until a connection is made or the connection fails within the 
     * timeout period, setting the error accordingly.
     */
    void waitForConnect() {
        long expiration = _context.clock().now() + _options.getConnectTimeout();
        while (true) {
            if (_connected && (_receiveStreamId != null) && (_sendStreamId != null) ) {
                // w00t
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("waitForConnect(): Connected and we have stream IDs");
                return;
            }
            if (_connectionError != null) {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("waitForConnect(): connection error found: " + _connectionError);
                return;
            }
            if (!_connected) {
                _connectionError = "Connection failed";
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("waitForConnect(): not connected");
                return;
            }
            
            long timeLeft = expiration - _context.clock().now();
            if ( (timeLeft <= 0) && (_options.getConnectTimeout() > 0) ) {
                if (_connectionError == null) {
                    _connectionError = "Connection timed out";
                    disconnect(false);
                }
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("waitForConnect(): timed out: " + _connectionError);
                return;
            }
            if (timeLeft > 60*1000)
                timeLeft = 60*1000;
            if (_options.getConnectTimeout() <= 0)
                timeLeft = 60*1000;
            
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("waitForConnect(): wait " + timeLeft);
            try { 
                synchronized (_connectLock) {
                    _connectLock.wait(timeLeft); 
                }
            } catch (InterruptedException ie) {}
        }
    }
    
    private void resetActivityTimer() {
        if (_options.getInactivityTimeout() <= 0) return;
        if (_activityTimer == null) return;
        long howLong = _activityTimer.getTimeLeft();
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Resetting the inactivity timer to " + howLong);
        // this will get rescheduled, and rescheduled, and rescheduled...
        SimpleTimer.getInstance().addEvent(_activityTimer, howLong);
    }
    
    private class ActivityTimer implements SimpleTimer.TimedEvent {
        public void timeReached() {
            // uh, nothing more to do...
            if (!_connected) return;
            // we got rescheduled already
            if (getTimeLeft() > 0) return;
            // these are either going to time out or cause further rescheduling
            if (getUnackedPacketsSent() > 0) return;
            // wtf, this shouldn't have been scheduled
            if (_options.getInactivityTimeout() <= 0) return;
            // if one of us can't talk...
            if ( (_closeSentOn > 0) || (_closeReceivedOn > 0) ) return;
            
            // bugger it, might as well do the hard work now
            switch (_options.getInactivityAction()) {
                case ConnectionOptions.INACTIVITY_ACTION_SEND:
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Sending some data due to inactivity");
                    _receiver.send(null, 0, 0, true);
                    break;
                case ConnectionOptions.INACTIVITY_ACTION_NOOP:
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Inactivity timer expired, but we aint doin' shit");
                    break;
                case ConnectionOptions.INACTIVITY_ACTION_DISCONNECT:
                    // fall through
                default:
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Closing connection due to inactivity");
                    if (_log.shouldLog(Log.DEBUG)) {
                        StringBuffer buf = new StringBuffer(128);
                        buf.append("last sent was: ").append(_context.clock().now() - _lastSendTime);
                        buf.append("ms ago, last received was: ").append(_context.clock().now()-_lastReceivedOn);
                        buf.append("ms ago, inactivity timeout is: ").append(_options.getInactivityTimeout());
                        _log.debug(buf.toString());
                    }
                    
                    disconnect(true);
                    break;
            }
        }
        
        public final long getTimeLeft() {
            if (getLastActivityOn() > 0)
                return getLastActivityOn() + _options.getInactivityTimeout() - _context.clock().now();
            else
                return _createdOn + _options.getInactivityTimeout() - _context.clock().now();
        }
    }
    
    /** stream that the local peer receives data on */
    public MessageInputStream getInputStream() { return _inputStream; }
    /** stream that the local peer sends data to the remote peer on */
    public MessageOutputStream getOutputStream() { return _outputStream; }
    
    public String toString() { 
        StringBuffer buf = new StringBuffer(128);
        buf.append("[Connection ");
        if (_receiveStreamId != null)
            buf.append(Base64.encode(_receiveStreamId));
        else
            buf.append("unknown");
        buf.append("<-->");
        if (_sendStreamId != null)
            buf.append(Base64.encode(_sendStreamId));
        else
            buf.append("unknown");
        buf.append(" wsize: ").append(_options.getWindowSize());
        buf.append(" cwin: ").append(_congestionWindowEnd - _highestAckedThrough);
        buf.append(" rtt: ").append(_options.getRTT());
        // not synchronized to avoid some kooky races
        buf.append(" unacked outbound: ").append(_outboundPackets.size()).append(" ");
        /*
        buf.append(" unacked outbound: ");
        synchronized (_outboundPackets) {
            buf.append(_outboundPackets.size()).append(" [");
            for (Iterator iter = _outboundPackets.keySet().iterator(); iter.hasNext(); ) {
                buf.append(((Long)iter.next()).longValue()).append(" ");
            }
            buf.append("] ");
        }
         */
        buf.append("unacked inbound? ").append(getUnackedPacketsReceived());
        if (_inputStream != null) {
            buf.append(" [high ");
            buf.append(_inputStream.getHighestBlockId());
            long nacks[] = _inputStream.getNacks();
            if (nacks != null)
                for (int i = 0; i < nacks.length; i++)
                    buf.append(" ").append(nacks[i]);
            buf.append("]");
        }
        
        if (getResetSent())
            buf.append(" reset sent");
        if (getResetReceived())
            buf.append(" reset received");
        if (getCloseSentOn() > 0) {
            buf.append(" close sent ");
            long timeSinceClose = _context.clock().now() - getCloseSentOn();
            buf.append(DataHelper.formatDuration(timeSinceClose));
            buf.append(" ago");
        }
        if (getCloseReceivedOn() > 0)
            buf.append(" close received");
        buf.append(" acked packets ").append(getAckedPackets());
        
        buf.append("]");
        return buf.toString();
    }
    
    /**
     * Coordinate the resends of a given packet
     */
    private class ResendPacketEvent implements SimpleTimer.TimedEvent {
        private PacketLocal _packet;
        public ResendPacketEvent(PacketLocal packet) {
            _packet = packet;
            packet.setResendPacketEvent(ResendPacketEvent.this);
        }
        
        public void timeReached() {
            if (_packet.getAckTime() > 0) 
                return;
            
            if (_resetSent || _resetReceived) {
                _packet.cancelled();
                return;
            }
            
            //if (_log.shouldLog(Log.DEBUG))
            //    _log.debug("Resend period reached for " + _packet);
            boolean resend = false;
            boolean isLowest = false;
            synchronized (_outboundPackets) {
                if (_packet.getSequenceNum() == _highestAckedThrough + 1)
                    isLowest = true;
                if (_outboundPackets.containsKey(new Long(_packet.getSequenceNum())))
                    resend = true;
            }
            if ( (resend) && (_packet.getAckTime() < 0) ) {
                if (!isLowest) {
                    // we want to resend this packet, but there are already active
                    // resends in the air and we dont want to make a bad situation 
                    // worse.  wait another second
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Delaying resend of " + _packet + " as there are " 
                                  + _activeResends + " active resends already in play");
                    SimpleTimer.getInstance().addEvent(ResendPacketEvent.this, 1000);
                    return;
                }
                // revamp various fields, in case we need to ack more, etc
                _inputStream.updateAcks(_packet);
                _packet.setOptionalDelay(getOptions().getChoke());
                _packet.setOptionalMaxSize(getOptions().getMaxMessageSize());
                _packet.setResendDelay(getOptions().getResendDelay());
                _packet.setReceiveStreamId(_receiveStreamId);
                _packet.setSendStreamId(_sendStreamId);
                
                int newWindowSize = getOptions().getWindowSize();

                if (_ackSinceCongestion) {
                    // only shrink the window once per window
                    if (_packet.getSequenceNum() > _lastCongestionHighestUnacked) {
                        congestionOccurred();
                        _context.statManager().addRateData("stream.con.windowSizeAtCongestion", newWindowSize, _packet.getLifetime());
                        newWindowSize /= 2;
                        if (newWindowSize <= 0)
                            newWindowSize = 1;

                        if (_log.shouldLog(Log.WARN))
                            _log.warn("Congestion resending packet " + _packet.getSequenceNum() + ": new windowSize " + newWindowSize 
                                      + ") for " + Connection.this.toString());

                        // setRTT has its own ceiling
                        getOptions().setRTT(getOptions().getRTT() + 10*1000);
                        getOptions().setWindowSize(newWindowSize);
                        windowAdjusted();
                    }
                }
                
                int numSends = _packet.getNumSends() + 1;
                
                if (numSends == 2) {
                    // first resend for this packet
                    _activeResends++;
                }
                
                // in case things really suck, the other side may have lost thier
                // session tags (e.g. they restarted), so jump back to ElGamal.
                int failTagsAt = _options.getMaxResends() - 2;
                if ( (newWindowSize == 1) && (numSends == failTagsAt) ) {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Optimistically failing tags at resend " + numSends);
                    _context.sessionKeyManager().failTags(_remotePeer.getPublicKey());
                }
                
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Resend packet " + _packet + " time " + numSends + 
                              " activeResends: " + _activeResends + 
                              " (wsize "
                              + newWindowSize + " lifetime " 
                              + (_context.clock().now() - _packet.getCreatedOn()) + "ms)");
                _outboundQueue.enqueue(_packet);
                
                _lastSendTime = _context.clock().now();
                
                // acked during resending (... or somethin')
                if ( (_packet.getAckTime() > 0) && (_packet.getNumSends() > 1) ) {
                    _activeResends--;
                    synchronized (_outboundPackets) {
                        _outboundPackets.notifyAll();
                    }
                    return;
                }
                
                if (numSends > _options.getMaxResends()) {
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Too many resends");
                    _packet.cancelled();
                    disconnect(false);
                } else {
                    //long timeout = _options.getResendDelay() << numSends;
                    long rtt = _options.getRTT();
                    if (rtt < MIN_RESEND_DELAY)
                        rtt = MIN_RESEND_DELAY;
                    long timeout = rtt << (numSends-1);
                    if ( (timeout > MAX_RESEND_DELAY) || (timeout <= 0) )
                        timeout = MAX_RESEND_DELAY;
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Scheduling resend in " + timeout + "ms for " + _packet);
                    SimpleTimer.getInstance().addEvent(ResendPacketEvent.this, timeout);
                }
            } else {
                //if (_log.shouldLog(Log.DEBUG))
                //    _log.debug("Packet acked before resend (resend="+ resend + "): " 
                //               + _packet + " on " + Connection.this);
            }
        }
    }
}
