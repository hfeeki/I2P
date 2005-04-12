package net.i2p.router.transport.udp;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.DatagramPacket;

import java.util.ArrayList;
import java.util.List;
import net.i2p.router.RouterContext;
import net.i2p.router.transport.FIFOBandwidthLimiter;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;

/**
 * Lowest level component to pull raw UDP datagrams off the wire as fast
 * as possible, controlled by both the bandwidth limiter and the router's
 * throttle.  If the inbound queue gets too large or packets have been
 * waiting around too long, they are dropped.  Packets should be pulled off
 * from the queue ASAP by a {@link PacketHandler}
 *
 */
public class UDPReceiver {
    private RouterContext _context;
    private Log _log;
    private DatagramSocket _socket;
    private String _name;
    private List _inboundQueue;
    private boolean _keepRunning;
    private Runner _runner;
    
    public UDPReceiver(RouterContext ctx, DatagramSocket socket, String name) {
        _context = ctx;
        _log = ctx.logManager().getLog(UDPReceiver.class);
        _name = name;
        _inboundQueue = new ArrayList(128);
        _socket = socket;
        _runner = new Runner();
        _context.statManager().createRateStat("udp.receivePacketSize", "How large packets received are", "udp", new long[] { 60*1000, 10*60*1000, 60*60*1000 });
        _context.statManager().createRateStat("udp.droppedInbound", "How many packet are queued up but not yet received when we drop", "udp", new long[] { 60*1000, 10*60*1000, 60*60*1000 });
    }
    
    public void startup() {
        _keepRunning = true;
        I2PThread t = new I2PThread(_runner, _name);
        t.setDaemon(true);
        t.start();
    }
    
    public void shutdown() {
        _keepRunning = false;
        synchronized (_inboundQueue) {
            _inboundQueue.clear();
            _inboundQueue.notifyAll();
        }
    }
    
    /**
     * Replace the old listen port with the new one, returning the old. 
     * NOTE: this closes the old socket so that blocking calls unblock!
     *
     */
    public DatagramSocket updateListeningPort(DatagramSocket socket, int newPort) {
        return _runner.updateListeningPort(socket, newPort);
    }

    /** if a packet been sitting in the queue for 2 seconds, drop subsequent packets */
    private static final long MAX_QUEUE_PERIOD = 2*1000;
    
    private void receive(UDPPacket packet) {
        synchronized (_inboundQueue) {
            int queueSize = _inboundQueue.size();
            if (queueSize > 0) {
                long headPeriod = ((UDPPacket)_inboundQueue.get(0)).getLifetime();
                if (headPeriod > MAX_QUEUE_PERIOD) {
                    _context.statManager().addRateData("udp.droppedInbound", queueSize, headPeriod);
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Dropping inbound packet with " + queueSize + " queued for " + headPeriod);
                    _inboundQueue.notifyAll();
                    return;
                }
            }
            _inboundQueue.add(packet);
            _inboundQueue.notifyAll();
        }
    }
    
    /**
     * Blocking call to retrieve the next inbound packet, or null if we have
     * shut down.
     *
     */
    public UDPPacket receiveNext() {
        while (_keepRunning) {
            synchronized (_inboundQueue) {
                if (_inboundQueue.size() <= 0) {
                    try {
                        _inboundQueue.wait();
                    } catch (InterruptedException ie) {}
                }
                if (_inboundQueue.size() > 0)
                    return (UDPPacket)_inboundQueue.remove(0);
            }
        }
        return null;
    }
    
    private class Runner implements Runnable {
        private boolean _socketChanged;
        public void run() {
            _socketChanged = false;
            while (_keepRunning) {
                if (_socketChanged) {
                    Thread.currentThread().setName(_name);
                    _socketChanged = false;
                }
                UDPPacket packet = UDPPacket.acquire(_context);
                
                // block before we read...
                while (!_context.throttle().acceptNetworkMessage())
                    try { Thread.sleep(10); } catch (InterruptedException ie) {}
                
                try {
                    synchronized (Runner.this) {
                        _socket.receive(packet.getPacket());
                    }
                    int size = packet.getPacket().getLength();
                    packet.resetBegin();
                    _context.statManager().addRateData("udp.receivePacketSize", size, 0);

                    // and block after we know how much we read but before
                    // we release the packet to the inbound queue
                    if (size > 0) {
                        FIFOBandwidthLimiter.Request req = _context.bandwidthLimiter().requestInbound(size, "UDP receiver");
                        while (req.getPendingInboundRequested() > 0)
                            req.waitForNextAllocation();
                    }
                    
                    receive(packet);
                } catch (IOException ioe) {
                    if (_socketChanged) {
                        if (_log.shouldLog(Log.INFO))
                            _log.info("Changing ports...");
                    } else {
                        if (_log.shouldLog(Log.ERROR))
                            _log.error("Error receiving", ioe);
                    }
                    packet.release();
                }
            }
        }
        
        public DatagramSocket updateListeningPort(DatagramSocket socket, int newPort) {
            _name = "UDPReceive on " + newPort;
            DatagramSocket old = null;
            synchronized (Runner.this) {
                old = _socket;
                _socket = socket;
            }
            _socketChanged = true;
            // ok, its switched, now lets break any blocking calls
            old.close();
            return old;
        }
    }
    
}
