package net.i2p.client.streaming;

import java.util.Set;

import net.i2p.I2PAppContext;
import net.i2p.data.ByteArray;
import net.i2p.data.Destination;
import net.i2p.data.SessionKey;
import net.i2p.util.ByteCache;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer;

/**
 * coordinate local attributes about a packet - send time, ack time, number of
 * retries, etc.
 */
public class PacketLocal extends Packet implements MessageOutputStream.WriteStatus {
    private I2PAppContext _context;
    private Log _log;
    private Connection _connection;
    private Destination _to;
    private SessionKey _keyUsed;
    private Set _tagsSent;
    private long _createdOn;
    private int _numSends;
    private long _lastSend;
    private long _acceptedOn;
    private long _ackOn;
    private long _cancelledOn;
    private SimpleTimer.TimedEvent _resendEvent;
    private ByteCache _cache = ByteCache.getInstance(128, MAX_PAYLOAD_SIZE);
    
    public PacketLocal(I2PAppContext ctx, Destination to) {
        this(ctx, to, null);
    }
    public PacketLocal(I2PAppContext ctx, Destination to, Connection con) {
        _context = ctx;
        _createdOn = ctx.clock().now();
        _log = ctx.logManager().getLog(PacketLocal.class);
        _to = to;
        _connection = con;
        _lastSend = -1;
        _cancelledOn = -1;
    }
    
    public Destination getTo() { return _to; }
    public void setTo(Destination to) { _to = to; }
    
    public SessionKey getKeyUsed() { return _keyUsed; }
    public void setKeyUsed(SessionKey key) { _keyUsed = key; }
    
    public Set getTagsSent() { return _tagsSent; }
    public void setTagsSent(Set tags) { 
        if ( (_tagsSent != null) && (_tagsSent.size() > 0) && (tags.size() > 0) ) {
            //int old = _tagsSent.size();
            //_tagsSent.addAll(tags);
            if (!_tagsSent.equals(tags))
                System.out.println("ERROR: dup tags: old=" + _tagsSent.size() + " new=" + tags.size() + " packet: " + toString());
        } else {
            _tagsSent = tags;
        }
    }
    
    public boolean shouldSign() { 
        return isFlagSet(FLAG_SIGNATURE_INCLUDED) ||
               isFlagSet(FLAG_SYNCHRONIZE) ||
               isFlagSet(FLAG_CLOSE) ||
               isFlagSet(FLAG_ECHO);
    }
    
    /** last minute update of ack fields, just before write/sign  */
    public void prepare() {
        if (_connection != null)
            _connection.getInputStream().updateAcks(this);
        if (_numSends > 0) // so we can debug to differentiate resends
            setOptionalDelay(_numSends * 1000);
    }
    
    public long getCreatedOn() { return _createdOn; }
    public long getLifetime() { return _context.clock().now() - _createdOn; }
    public void incrementSends() { 
        _numSends++;
        _lastSend = _context.clock().now();
    }
    public void ackReceived() { 
        ByteArray ba = null;
        synchronized (this) {
            if (_ackOn <= 0)
                _ackOn = _context.clock().now(); 
            ba = getPayload();
            setPayload(null);
            notifyAll();
        }
        SimpleTimer.getInstance().removeEvent(_resendEvent);
        if (ba != null)
            _cache.release(ba);
    }
    public void cancelled() { 
        ByteArray ba = null;
        synchronized (this) {
            _cancelledOn = _context.clock().now();
            ba = getPayload();
            setPayload(null);
            notifyAll();
        }
        SimpleTimer.getInstance().removeEvent(_resendEvent);
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Cancelled! " + toString(), new Exception("cancelled"));
        if (ba != null)
            _cache.release(ba);
    }
    
    /** how long after packet creation was it acked? */
    public int getAckTime() {
        if (_ackOn <= 0) 
            return -1;
        else
            return (int)(_ackOn - _createdOn);
    }
    public int getNumSends() { return _numSends; }
    public long getLastSend() { return _lastSend; }
    public Connection getConnection() { return _connection; }
    
    public void setResendPacketEvent(SimpleTimer.TimedEvent evt) { _resendEvent = evt; }
    
    public String toString() {
        String str = super.toString();
        
        if ( (_tagsSent != null) && (_tagsSent.size() > 0) ) 
            str = str + " with tags";

        if (_ackOn > 0)
            return str + " ack after " + getAckTime() + (_numSends <= 1 ? "" : " sent " + _numSends + " times");
        else
            return str + (_numSends <= 1 ? "" : " sent " + _numSends + " times");
    }
    
    public void waitForAccept(int maxWaitMs) {
        if (_connection == null) 
            throw new IllegalStateException("Cannot wait for accept with no connection");
        long before = _context.clock().now();
        long expiration = before+maxWaitMs;
        int queued = _connection.getUnackedPacketsSent();
        int window = _connection.getOptions().getWindowSize();
        boolean accepted = _connection.packetSendChoke(maxWaitMs);
        long after = _context.clock().now();
        if (accepted)
            _acceptedOn = after;
        else
            _acceptedOn = -1;
        int afterQueued = _connection.getUnackedPacketsSent();
        if ( (after - before > 1000) && (_log.shouldLog(Log.DEBUG)) )
            _log.debug("Took " + (after-before) + "ms to get " 
                       + (accepted ? " accepted" : " rejected")
                       + (_cancelledOn > 0 ? " and CANCELLED" : "")
                       + ", queued behind " + queued +" with a window size of " + window 
                       + ", finally accepted with " + afterQueued + " queued: " 
                       + toString());
    }
    
    public void waitForCompletion(int maxWaitMs) {
        long expiration = _context.clock().now()+maxWaitMs;
        while (true) {
            long timeRemaining = expiration - _context.clock().now();
            if ( (timeRemaining <= 0) && (maxWaitMs > 0) ) return;
            try {
                synchronized (this) {
                    if (_ackOn > 0) return;
                    if (_cancelledOn > 0) return;
                    if (timeRemaining > 60*1000)
                        timeRemaining = 60*1000;
                    else if (timeRemaining <= 0)
                        timeRemaining = 10*1000;
                    wait(timeRemaining);
                }
            } catch (InterruptedException ie) {}
        }
    }
    
    public boolean writeAccepted() { return _acceptedOn > 0 && _cancelledOn <= 0; }
    public boolean writeFailed() { return _cancelledOn > 0; }
    public boolean writeSuccessful() { return _ackOn > 0 && _cancelledOn <= 0; }
}
