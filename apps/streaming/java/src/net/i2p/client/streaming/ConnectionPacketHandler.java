package net.i2p.client.streaming;

import java.util.List;

import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.util.Log;

/**
 * Receive a packet for a particular connection - placing the data onto the
 * queue, marking packets as acked, updating various fields, etc.
 *
 */
public class ConnectionPacketHandler {
    private I2PAppContext _context;
    private Log _log;
    
    /** rtt = rtt*RTT_DAMPENING + (1-RTT_DAMPENING)*currentPacketRTT */
    private static final double RTT_DAMPENING = 0.9;
    
    public ConnectionPacketHandler(I2PAppContext context) {
        _context = context;
        _log = context.logManager().getLog(ConnectionPacketHandler.class);
    }
    
    /** distribute a packet to the connection specified */
    void receivePacket(Packet packet, Connection con) {
        boolean ok = verifyPacket(packet, con);
        if (!ok) return;
        boolean isNew = con.getInputStream().messageReceived(packet.getSequenceNum(), packet.getPayload());

        // close *after* receiving the data, as well as after verifying the signatures / etc
        if (packet.isFlagSet(Packet.FLAG_CLOSE) && packet.isFlagSet(Packet.FLAG_SIGNATURE_INCLUDED))
            con.closeReceived();
        
        if (isNew) {
            con.incrementUnackedPacketsReceived();
            long nextTime = con.getNextSendTime();
            if (nextTime <= 0) {
                con.setNextSendTime(con.getOptions().getSendAckDelay() + _context.clock().now());
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Scheduling ack in " + con.getOptions().getSendAckDelay() + "ms for received packet " + packet);
            } else {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Ack is already scheduled in " + (nextTime-_context.clock().now()) 
                               + "ms, though we just received " + packet);
            }
        } else {
            if (packet.getSequenceNum() > 0) {
                // take note of congestion
                con.getOptions().setResendDelay(con.getOptions().getResendDelay()*2);
                //con.getOptions().setWindowSize(con.getOptions().getWindowSize()/2);
                if (_log.shouldLog(Log.WARN))
                    _log.warn("congestion.. dup " + packet);   
                con.incrementUnackedPacketsReceived();
            } else {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("ACK only packet received: " + packet);
            }
        }
        
        int numResends = 0;
        List acked = con.ackPackets(packet.getAckThrough(), packet.getNacks());
        if ( (acked != null) && (acked.size() > 0) ) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug(acked.size() + " of our packets acked with " + packet);
            // use the lowest RTT, since these would likely be bunched together,
            // waiting for the most recent packet received before sending the ACK
            int lowestRtt = -1;
            for (int i = 0; i < acked.size(); i++) {
                PacketLocal p = (PacketLocal)acked.get(i);
                if ( (lowestRtt < 0) || (p.getAckTime() < lowestRtt) ) {
                    //if (p.getNumSends() <= 1)
                    lowestRtt = p.getAckTime();
                }
                
                if (p.getNumSends() > 1)
                    numResends++;
                
                // ACK the tags we delivered so we can use them
                if ( (p.getKeyUsed() != null) && (p.getTagsSent() != null) 
                      && (p.getTagsSent().size() > 0) ) {
                    _context.sessionKeyManager().tagsDelivered(p.getTo().getPublicKey(), 
                                                               p.getKeyUsed(), 
                                                               p.getTagsSent());
                }
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Packet acked after " + p.getAckTime() + "ms: " + p);
            }
            if (lowestRtt > 0) {
                int oldRTT = con.getOptions().getRTT();
                int newRTT = (int)(RTT_DAMPENING*oldRTT + (1-RTT_DAMPENING)*lowestRtt);
                con.getOptions().setRTT(newRTT);
            }
        }

        boolean fastAck = adjustWindow(con, isNew, packet.getSequenceNum(), numResends);
        con.eventOccurred();
        if (fastAck) {
            if (con.getLastSendTime() + con.getOptions().getRTT() < _context.clock().now()) {
                _log.error("Fast ack for dup " + packet);
                con.ackImmediately();
            }
        }
    }
    
    private boolean adjustWindow(Connection con, boolean isNew, long sequenceNum, int numResends) {
        if ( (!isNew) && (sequenceNum > 0) ) {
            // dup real packet
            int oldSize = con.getOptions().getWindowSize();
            oldSize >>>= 1;
            if (oldSize <= 0)
                oldSize = 1;
            con.getOptions().setWindowSize(oldSize);
            return true;
        } else if (numResends > 0) {
            int newWindowSize = con.getOptions().getWindowSize();
            newWindowSize /= 2; // >>>= numResends;
            if (newWindowSize <= 0)
                newWindowSize = 1;
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Shrink the window to " + newWindowSize + " (#resends: " + numResends 
                           + ") for " + con);
            con.getOptions().setWindowSize(newWindowSize);
        } else {
            // new packet that ack'ed uncongested data, or an empty ack
            int newWindowSize = con.getOptions().getWindowSize();
            newWindowSize += 1; //acked.size();
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("New window size " + newWindowSize + " (#resends: " + numResends 
                           + ") for " + con);
            con.getOptions().setWindowSize(newWindowSize);
        }
        return false;
    }
    
    /**
     * Make sure this packet is ok and that we can continue processing its data.
     * 
     * @return true if the packet is ok for this connection, false if we shouldn't
     *         continue processing.
     */
    private boolean verifyPacket(Packet packet, Connection con) {
        if (packet.isFlagSet(Packet.FLAG_RESET)) {
            verifyReset(packet, con);
            return false;
        } else {
            boolean sigOk = verifySignature(packet, con);
            
            if (con.getSendStreamId() == null) {
                if (packet.isFlagSet(Packet.FLAG_SYNCHRONIZE)) {
                    con.setSendStreamId(packet.getReceiveStreamId());
                    con.setRemotePeer(packet.getOptionalFrom());
                    return true;
                } else {
                    // neither RST nor SYN and we dont have the stream id yet?  nuh uh
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Packet without RST or SYN where we dont know stream ID: " 
                                  + packet);
                    return false;
                }
            } else {
                if (!DataHelper.eq(con.getSendStreamId(), packet.getReceiveStreamId())) {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Packet received with the wrong reply stream id: " 
                                  + con + " / " + packet);
                    return false;
                } else {
                    return true;
                }
            }
        }
    }

    /**
     * Make sure this RST packet is valid, and if it is, act on it.
     */
    private void verifyReset(Packet packet, Connection con) {
        if (DataHelper.eq(con.getReceiveStreamId(), packet.getSendStreamId())) {
            boolean ok = packet.verifySignature(_context, packet.getOptionalFrom(), null);
            if (!ok) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Received unsigned / forged RST on " + con);
                return;
            } else {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Reset received");
                // ok, valid RST
                con.resetReceived();
                con.eventOccurred();

                // no further processing
                return;
            }
        } else {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Received a packet for the wrong connection?  wtf: " 
                          + con + " / " + packet);
            return;
        }
    }
    
    /**
     * Verify the signature if necessary.  
     *
     * @return false only if the signature was required and it was invalid
     */
    private boolean verifySignature(Packet packet, Connection con) {
        // verify the signature if necessary
        if (con.getOptions().getRequireFullySigned() || 
            packet.isFlagSet(Packet.FLAG_SYNCHRONIZE) ||
            packet.isFlagSet(Packet.FLAG_CLOSE) ) {
            // we need a valid signature
            Destination from = con.getRemotePeer();
            if (from == null)
                from = packet.getOptionalFrom();
            boolean sigOk = packet.verifySignature(_context, from, null);
            if (!sigOk) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Received unsigned / forged packet: " + packet);
                return false;
            }
        }
        return true;
    }    
}
