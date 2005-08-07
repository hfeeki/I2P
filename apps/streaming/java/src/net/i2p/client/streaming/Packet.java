package net.i2p.client.streaming;

import java.util.Arrays;
import net.i2p.I2PAppContext;
import net.i2p.data.Base64;
import net.i2p.data.ByteArray;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.data.Signature;
import net.i2p.data.SigningPrivateKey;
import net.i2p.util.ByteCache;

/**
 * Contain a single packet transferred as part of a streaming connection.  
 * The data format is as follows:<ul>
 * <li>{@link #getSendStreamId sendStreamId} [4 byte value]</li>
 * <li>{@link #getReceiveStreamId receiveStreamId} [4 byte value]</li>
 * <li>{@link #getSequenceNum sequenceNum} [4 byte unsigned integer]</li>
 * <li>{@link #getAckThrough ackThrough} [4 byte unsigned integer]</li>
 * <li>number of NACKs [1 byte unsigned integer]</li>
 * <li>that many {@link #getNacks NACKs}</li>
 * <li>{@link #getResendDelay resendDelay} [1 byte integer]</li>
 * <li>flags [2 byte value]</li>
 * <li>option data size [2 byte integer]</li>
 * <li>option data specified by those flags [0 or more bytes]</li>
 * <li>payload [remaining packet size]</li>
 * </ul>
 *
 * <p>The flags field above specifies some metadata about the packet, and in
 * turn may require certain additional data to be included.  The flags are
 * as follows (with any data structures specified added to the options area
 * in the given order):</p><ol>
 * <li>{@link #FLAG_SYNCHRONIZE}: no option data</li>
 * <li>{@link #FLAG_CLOSE}: no option data</li>
 * <li>{@link #FLAG_RESET}: no option data</li>
 * <li>{@link #FLAG_SIGNATURE_INCLUDED}: {@link net.i2p.data.Signature}</li>
 * <li>{@link #FLAG_SIGNATURE_REQUESTED}: no option data</li>
 * <li>{@link #FLAG_FROM_INCLUDED}: {@link net.i2p.data.Destination}</li>
 * <li>{@link #FLAG_DELAY_REQUESTED}: 1 byte integer</li>
 * <li>{@link #FLAG_MAX_PACKET_SIZE_INCLUDED}: 2 byte integer</li>
 * <li>{@link #FLAG_PROFILE_INTERACTIVE}: no option data</li>
 * </ol>
 *
 * <p>If the signature is included, it uses the Destination's DSA key 
 * to sign the entire header and payload with the space in the options 
 * for the signature being set to all zeroes.</p>
 *
 * <p>If the sequenceNum is 0 and the SYN is not set, this is a plain ACK 
 * packet that should not be ACKed</p>
 *
 */
public class Packet {
    private byte _sendStreamId[];
    private byte _receiveStreamId[];
    private long _sequenceNum;
    private long _ackThrough;
    private long _nacks[];
    private int _resendDelay;
    private int _flags;
    private ByteArray _payload;
    // the next four are set only if the flags say so
    private Signature _optionSignature;
    private Destination _optionFrom;
    private int _optionDelay;
    private int _optionMaxSize;
    private ByteCache _cache;
    
    /** 
     * The receiveStreamId will be set to this when the packet doesn't know 
     * what ID will be assigned by the remote peer (aka this is the initial
     * synchronize packet)
     *
     */
    public static final byte STREAM_ID_UNKNOWN[] = new byte[] { 0x00, 0x00, 0x00, 0x00 };
    
    /**
     * This packet is creating a new socket connection (if the receiveStreamId
     * is STREAM_ID_UNKNOWN) or it is acknowledging a request to 
     * create a connection and in turn is accepting the socket.
     *
     */
    public static final int FLAG_SYNCHRONIZE = (1 << 0);
    /**
     * The sender of this packet will not be sending any more payload data.
     */
    public static final int FLAG_CLOSE = (1 << 1);
    /**
     * This packet is being sent to signify that the socket does not exist 
     * (or, if in response to an initial synchronize packet, that the 
     * connection was refused).
     *
     */
    public static final int FLAG_RESET = (1 << 2);
    /**
     * This packet contains a DSA signature from the packet's sender.  This 
     * signature is within the packet options.  All synchronize packets must
     * have this flag set.
     *
     */
    public static final int FLAG_SIGNATURE_INCLUDED = (1 << 3);
    /**
     * This packet wants the recipient to include signatures on subsequent
     * packets sent to the creator of this packet.
     */
    public static final int FLAG_SIGNATURE_REQUESTED = (1 << 4);
    /**
     * This packet includes the full I2P destination of the packet's sender.
     * The initial synchronize packet must have this flag set.
     */
    public static final int FLAG_FROM_INCLUDED = (1 << 5);
    /**
     * This packet includes an explicit request for the recipient to delay
     * sending any packets with data for a given amount of time.
     *
     */
    public static final int FLAG_DELAY_REQUESTED = (1 << 6);
    /**
     * This packet includes a request that the recipient not send any 
     * subsequent packets with payloads greater than a specific size.
     * If not set and no prior value was delivered, the maximum value 
     * will be assumed (approximately 32KB).
     *
     */
    public static final int FLAG_MAX_PACKET_SIZE_INCLUDED = (1 << 7);
    /**
     * If set, this packet is travelling as part of an interactive flow,
     * meaning it is more lag sensitive than throughput sensitive.  aka
     * send data ASAP rather than waiting around to send full packets.
     *
     */
    public static final int FLAG_PROFILE_INTERACTIVE = (1 << 8);
    /**
     * If set, this packet is a ping (if sendStreamId is set) or a 
     * ping reply (if receiveStreamId is set).
     */
    public static final int FLAG_ECHO = (1 << 9);

    public static final int DEFAULT_MAX_SIZE = 32*1024;
    private static final int MAX_DELAY_REQUEST = 65535;

    public Packet() {
        _cache = ByteCache.getInstance(128, MAX_PAYLOAD_SIZE);
    }
    
    /** what stream is this packet a part of? */
    public byte[] getSendStreamId() { 
        if ( (_sendStreamId == null) || (DataHelper.eq(_sendStreamId, STREAM_ID_UNKNOWN)) )
            return null;
        else
            return _sendStreamId; 
    }
    public void setSendStreamId(byte[] id) { 
        _sendStreamId = id; 
        if ( (id != null) && (DataHelper.eq(id, STREAM_ID_UNKNOWN)) )
            _sendStreamId = null;
    }
    
    /** 
     * Stream that replies should be sent on.  if the 
     * connection is still being built, this should be 
     * null.
     *
     */
    public byte[] getReceiveStreamId() { 
        if ( (_receiveStreamId == null) || (DataHelper.eq(_receiveStreamId, STREAM_ID_UNKNOWN)) )
            return null;
        else
            return _receiveStreamId; 
    }
    public void setReceiveStreamId(byte[] id) { 
        _receiveStreamId = id; 
        if ( (id != null) && (DataHelper.eq(id, STREAM_ID_UNKNOWN)) )
            _receiveStreamId = null;
    }
    
    /** 0-indexed sequence number for this Packet in the sendStream */
    public long getSequenceNum() { return _sequenceNum; }
    public void setSequenceNum(long num) { _sequenceNum = num; }
    
    /** 
     * The highest packet sequence number that received
     * on the receiveStreamId.  This field is ignored on the initial
     * connection packet (where receiveStreamId is the unknown id).
     *
     */
    public long getAckThrough() { return _ackThrough; }
    public void setAckThrough(long id) { _ackThrough = id; }
    
    /**
     * List of packet sequence numbers below the getAckThrough() value
     * have not been received.  this may be null.
     *
     */
    public long[] getNacks() { return _nacks; }
    public void setNacks(long nacks[]) { _nacks = nacks; }
    
    /**
     * How long is the creator of this packet going to wait before
     * resending this packet (if it hasn't yet been ACKed).  The 
     * value is seconds since the packet was created.
     *
     */
    public int getResendDelay() { return _resendDelay; }
    public void setResendDelay(int numSeconds) { _resendDelay = numSeconds; }
    
    public static final int MAX_PAYLOAD_SIZE = 32*1024;
    
    /** get the actual payload of the message.  may be null */
    public ByteArray getPayload() { return _payload; }
    public void setPayload(ByteArray payload) { 
        //if ( (_payload != null) && (_payload != payload) )
        //    _cache.release(_payload);
        _payload = payload; 
        if ( (payload != null) && (payload.getValid() > MAX_PAYLOAD_SIZE) )
            throw new IllegalArgumentException("Too large payload: " + payload.getValid());
    }
    public int getPayloadSize() {
        return (_payload == null ? 0 : _payload.getValid());
    }
    public void releasePayload() {
        //if (_payload != null)
        //    _cache.release(_payload);
        _payload = null;
    }
    public ByteArray acquirePayload() {
        ByteArray old = _payload;
        _payload = new ByteArray(new byte[Packet.MAX_PAYLOAD_SIZE]); //_cache.acquire();
        //if (old != null)
        //    _cache.release(old);
        return _payload;
    }

    /** is a particular flag set on this packet? */
    public boolean isFlagSet(int flag) { return 0 != (_flags & flag); }
    public void setFlag(int flag) { _flags |= flag; }
    public void setFlag(int flag, boolean set) { 
        if (set)
            _flags |= flag; 
        else
            _flags &= ~flag;
    }

    /** the signature on the packet (only included if the flag for it is set) */
    public Signature getOptionalSignature() { return _optionSignature; }
    public void setOptionalSignature(Signature sig) { 
        setFlag(FLAG_SIGNATURE_INCLUDED, sig != null);
        _optionSignature = sig; 
    }

    /** the sender of the packet (only included if the flag for it is set) */
    public Destination getOptionalFrom() { return _optionFrom; }
    public void setOptionalFrom(Destination from) { 
        setFlag(FLAG_FROM_INCLUDED, from != null);
        if (from == null) throw new RuntimeException("from is null!?");
        _optionFrom = from; 
    }
    
    /** 
     * How many milliseconds the sender of this packet wants the recipient
     * to wait before sending any more data (only valid if the flag for it is
     * set) 
     */
    public int getOptionalDelay() { return _optionDelay; }
    public void setOptionalDelay(int delayMs) {
        setFlag(FLAG_DELAY_REQUESTED, delayMs > 0); 
        if (delayMs > MAX_DELAY_REQUEST)
            _optionDelay = MAX_DELAY_REQUEST;
        else if (delayMs < 0)
            _optionDelay = 0;
        else
            _optionDelay = delayMs; 
    }
    
    /**
     * What is the largest payload the sender of this packet wants to receive?
     *
     */
    public int getOptionalMaxSize() { return _optionMaxSize; }
    public void setOptionalMaxSize(int numBytes) { 
        setFlag(FLAG_MAX_PACKET_SIZE_INCLUDED, numBytes > 0);
        _optionMaxSize = numBytes; 
    }
    
    /**
     * Write the packet to the buffer (starting at the offset) and return
     * the number of bytes written.
     *
     * @throws IllegalStateException if there is data missing or otherwise b0rked
     */
    public int writePacket(byte buffer[], int offset) throws IllegalStateException {
        return writePacket(buffer, offset, true);
    }
    /**
     * @param includeSig if true, include the real signature, otherwise put zeroes
     *                   in its place.
     */
    private int writePacket(byte buffer[], int offset, boolean includeSig) throws IllegalStateException {
        int cur = offset;
        if ( (_sendStreamId != null) && (_sendStreamId.length == 4) )
            System.arraycopy(_sendStreamId, 0, buffer, cur, _sendStreamId.length);
        else
            System.arraycopy(STREAM_ID_UNKNOWN, 0, buffer, cur, STREAM_ID_UNKNOWN.length);
        cur += 4;
        if ( (_receiveStreamId != null) && (_receiveStreamId.length == 4) )
            System.arraycopy(_receiveStreamId, 0, buffer, cur, _receiveStreamId.length);
        else
            System.arraycopy(STREAM_ID_UNKNOWN, 0, buffer, cur, STREAM_ID_UNKNOWN.length);
        cur += 4;
        DataHelper.toLong(buffer, cur, 4, _sequenceNum > 0 ? _sequenceNum : 0);
        cur += 4;
        DataHelper.toLong(buffer, cur, 4, _ackThrough > 0 ? _ackThrough : 0);
        cur += 4;
        if (_nacks != null) {
            DataHelper.toLong(buffer, cur, 1, _nacks.length);
            cur++;
            for (int i = 0; i < _nacks.length; i++) {
                DataHelper.toLong(buffer, cur, 4, _nacks[i]);
                cur += 4;
            }
        } else {
            DataHelper.toLong(buffer, cur, 1, 0);
            cur++;
        }
        DataHelper.toLong(buffer, cur, 1, _resendDelay > 0 ? _resendDelay : 0);
        cur++;
        DataHelper.toLong(buffer, cur, 2, _flags);
        cur += 2;

        int optionSize = 0;
        if (isFlagSet(FLAG_DELAY_REQUESTED))
            optionSize += 2;
        if (isFlagSet(FLAG_FROM_INCLUDED))
            optionSize += _optionFrom.size();
        if (isFlagSet(FLAG_MAX_PACKET_SIZE_INCLUDED))
            optionSize += 2;
        if (isFlagSet(FLAG_SIGNATURE_INCLUDED))
            optionSize += Signature.SIGNATURE_BYTES;
        
        DataHelper.toLong(buffer, cur, 2, optionSize);
        cur += 2;
        
        if (isFlagSet(FLAG_DELAY_REQUESTED)) {
            DataHelper.toLong(buffer, cur, 2, _optionDelay > 0 ? _optionDelay : 0);
            cur += 2;
        }
        if (isFlagSet(FLAG_FROM_INCLUDED)) {
            cur += _optionFrom.writeBytes(buffer, cur);
        }
        if (isFlagSet(FLAG_MAX_PACKET_SIZE_INCLUDED)) {
            DataHelper.toLong(buffer, cur, 2, _optionMaxSize > 0 ? _optionMaxSize : DEFAULT_MAX_SIZE);
            cur += 2;
        }
        if (isFlagSet(FLAG_SIGNATURE_INCLUDED)) {
            if (includeSig)
                System.arraycopy(_optionSignature.getData(), 0, buffer, cur, Signature.SIGNATURE_BYTES);
            else // we're signing (or validating)
                Arrays.fill(buffer, cur, cur+Signature.SIGNATURE_BYTES, (byte)0x0);
            cur += Signature.SIGNATURE_BYTES;
        }
        
        if (_payload != null) {
            try {
                System.arraycopy(_payload.getData(), _payload.getOffset(), buffer, cur, _payload.getValid());
            } catch (ArrayIndexOutOfBoundsException aioobe) {
                System.err.println("payload.length: " + _payload.getValid() + " buffer.length: " + buffer.length + " cur: " + cur);
                throw aioobe;
            }
            cur += _payload.getValid();
        }
                
        return cur - offset;
    }
    
    
    /**
     * how large would this packet be if we wrote it
     */
    public int writtenSize() throws IllegalStateException {
        int size = 0;
        size += 4; // _sendStreamId.length;
        size += 4; // _receiveStreamId.length;
        size += 4; // sequenceNum
        size += 4; // ackThrough
        if (_nacks != null) {
			size++; // nacks length
            size += 4 * _nacks.length;
        } else {
            size++; // nacks length
        }
        size++; // resendDelay
        size += 2; // flags

        if (isFlagSet(FLAG_DELAY_REQUESTED))
            size += 2;
        if (isFlagSet(FLAG_FROM_INCLUDED))
            size += _optionFrom.size();
        if (isFlagSet(FLAG_MAX_PACKET_SIZE_INCLUDED))
            size += 2;
        if (isFlagSet(FLAG_SIGNATURE_INCLUDED))
            size += Signature.SIGNATURE_BYTES;
        
        size += 2; // option size
        
        if (_payload != null) {
            size += _payload.getValid();
        }
        
        return size;
    }
    /**
     * Read the packet from the buffer (starting at the offset) and return
     * the number of bytes read.
     *
     * @param buffer packet buffer containing the data
     * @param offset index into the buffer to start readign
     * @param length how many bytes within the buffer past the offset are 
     *               part of the packet?
     *
     * @throws IllegalArgumentException if the data is b0rked
     */
    public void readPacket(byte buffer[], int offset, int length) throws IllegalArgumentException {
        if (buffer.length - offset < length) 
            throw new IllegalArgumentException("len=" + buffer.length + " off=" + offset + " length=" + length);
        if (length < 22) // min header size
            throw new IllegalArgumentException("Too small: len=" + buffer.length);
        int cur = offset;
        _sendStreamId = new byte[4];
        System.arraycopy(buffer, cur, _sendStreamId, 0, 4);
        cur += 4;
        _receiveStreamId = new byte[4];
        System.arraycopy(buffer, cur, _receiveStreamId, 0, 4);
        cur += 4;
        _sequenceNum = DataHelper.fromLong(buffer, cur, 4);
        cur += 4;
        _ackThrough = DataHelper.fromLong(buffer, cur, 4);
        cur += 4;
        int numNacks = (int)DataHelper.fromLong(buffer, cur, 1);
        cur++;
        if (length < 22 + numNacks*4)
            throw new IllegalArgumentException("Too small with " + numNacks + " nacks: " + length);
        if (numNacks > 0) {
            _nacks = new long[numNacks];
            for (int i = 0; i < numNacks; i++) {
                _nacks[i] = DataHelper.fromLong(buffer, cur, 4);
                cur += 4;
            }
        } else {
            _nacks = null;
        }
        _resendDelay = (int)DataHelper.fromLong(buffer, cur, 1);
        cur++;
        _flags = (int)DataHelper.fromLong(buffer, cur, 2);
        cur += 2;
        
        int optionSize = (int)DataHelper.fromLong(buffer, cur, 2);
        cur += 2;
        
        if (length < 22 + numNacks*4 + optionSize)
            throw new IllegalArgumentException("Too small with " + numNacks + " nacks and "
                                               + optionSize + " options: " + length);
        
        int payloadBegin = cur + optionSize;
        int payloadSize = length - payloadBegin;
        if ( (payloadSize < 0) || (payloadSize > MAX_PAYLOAD_SIZE) ) 
            throw new IllegalArgumentException("length: " + length + " offset: " + offset + " begin: " + payloadBegin);
        
        // skip ahead to the payload
        _payload = new ByteArray(new byte[payloadSize]); //_cache.acquire(); 
        System.arraycopy(buffer, payloadBegin, _payload.getData(), 0, payloadSize);
        _payload.setValid(payloadSize);
        _payload.setOffset(0);
        
        // ok now lets go back and deal with the options
        if (isFlagSet(FLAG_DELAY_REQUESTED)) {
            _optionDelay = (int)DataHelper.fromLong(buffer, cur, 2);
            cur += 2;
        }
        if (isFlagSet(FLAG_FROM_INCLUDED)) {
            _optionFrom = new Destination();
            try {
                cur += _optionFrom.readBytes(buffer, cur);
            } catch (DataFormatException dfe) {
                throw new IllegalArgumentException("Bad from field: " + dfe.getMessage());
            }
        }
        if (isFlagSet(FLAG_MAX_PACKET_SIZE_INCLUDED)) {
            _optionMaxSize = (int)DataHelper.fromLong(buffer, cur, 2);
            cur += 2;
        }
        if (isFlagSet(FLAG_SIGNATURE_INCLUDED)) {
            _optionSignature = new Signature();
            byte buf[] = new byte[Signature.SIGNATURE_BYTES];
            System.arraycopy(buffer, cur, buf, 0, Signature.SIGNATURE_BYTES);
            _optionSignature.setData(buf);
            cur += Signature.SIGNATURE_BYTES;
        }
    }
    
    /**
     * Determine whether the signature on the data is valid.  
     *
     * @return true if the signature exists and validates against the data, 
     *         false otherwise.
     */
    public boolean verifySignature(I2PAppContext ctx, Destination from, byte buffer[]) {
        if (!isFlagSet(FLAG_SIGNATURE_INCLUDED)) return false;
        if (_optionSignature == null) return false;
        
        int size = writtenSize();
        
        if (buffer == null)
            buffer = new byte[size];
        int written = writePacket(buffer, 0, false);
        if (written != size) {
            ctx.logManager().getLog(Packet.class).error("Written " + written + " size " + size + " for " + toString(), new Exception("moo"));
            return false;
        }
        boolean ok = ctx.dsa().verifySignature(_optionSignature, buffer, 0, size, from.getSigningPublicKey());
        if (!ok) {
            ctx.logManager().getLog(Packet.class).error("Signature failed on " + toString(), new Exception("moo"));
        }
        return ok;
    }

    /**
     * Sign and write the packet to the buffer (starting at the offset) and return
     * the number of bytes written.
     *
     * @throws IllegalStateException if there is data missing or otherwise b0rked
     */
    public int writeSignedPacket(byte buffer[], int offset, I2PAppContext ctx, SigningPrivateKey key) throws IllegalStateException {
        setFlag(FLAG_SIGNATURE_INCLUDED);
        int size = writePacket(buffer, offset, false);
        _optionSignature = ctx.dsa().sign(buffer, offset, size, key);
        // jump into the signed data and inject the signature where we 
        // previously placed a bunch of zeroes
        int signatureOffset = offset 
                              + 4 // sendStreamId
                              + 4 // receiveStreamId
                              + 4 // sequenceNum
                              + 4 // ackThrough
                              + (_nacks != null ? 4*_nacks.length + 1 : 1)
                              + 1 // resendDelay
                              + 2 // flags
                              + 2 // optionSize
                              + (isFlagSet(FLAG_DELAY_REQUESTED) ? 2 : 0)
                              + (isFlagSet(FLAG_FROM_INCLUDED) ? _optionFrom.size() : 0)
                              + (isFlagSet(FLAG_MAX_PACKET_SIZE_INCLUDED) ? 2 : 0);
        System.arraycopy(_optionSignature.getData(), 0, buffer, signatureOffset, Signature.SIGNATURE_BYTES);
        return size;
    }
    
    public String toString() {
        StringBuffer str = formatAsString();
        return str.toString();
    }
    
    protected StringBuffer formatAsString() {
        StringBuffer buf = new StringBuffer(64);
        buf.append(toId(_sendStreamId));
        //buf.append("<-->");
        buf.append(toId(_receiveStreamId)).append(": #").append(_sequenceNum);
        if (_sequenceNum < 10) 
            buf.append(" \t"); // so the tab lines up right
        else
            buf.append('\t');
        buf.append(toFlagString());
        buf.append(" ACK ").append(_ackThrough);
        if (_nacks != null) {
            buf.append(" NACK");
            for (int i = 0; i < _nacks.length; i++) {
                buf.append(" ").append(_nacks[i]);
            }
        }
        if ( (_payload != null) && (_payload.getValid() > 0) )
            buf.append(" data: ").append(_payload.getValid());
        return buf;
    }
    
    static final String toId(byte id[]) {
        if (id == null)
            return Base64.encode(STREAM_ID_UNKNOWN);
        else
            return Base64.encode(id);
    }
    
    private final String toFlagString() {
        StringBuffer buf = new StringBuffer(32);
        if (isFlagSet(FLAG_CLOSE)) buf.append(" CLOSE");
        if (isFlagSet(FLAG_DELAY_REQUESTED)) buf.append(" DELAY ").append(_optionDelay);
        if (isFlagSet(FLAG_ECHO)) buf.append(" ECHO");
        if (isFlagSet(FLAG_FROM_INCLUDED)) buf.append(" FROM");
        if (isFlagSet(FLAG_MAX_PACKET_SIZE_INCLUDED)) buf.append(" MS ").append(_optionMaxSize);
        if (isFlagSet(FLAG_PROFILE_INTERACTIVE)) buf.append(" INTERACTIVE");
        if (isFlagSet(FLAG_RESET)) buf.append(" RESET");
        if (isFlagSet(FLAG_SIGNATURE_INCLUDED)) buf.append(" SIG");
        if (isFlagSet(FLAG_SIGNATURE_REQUESTED)) buf.append(" SIGREQ");
        if (isFlagSet(FLAG_SYNCHRONIZE)) buf.append(" SYN");
        return buf.toString();
    }
}
