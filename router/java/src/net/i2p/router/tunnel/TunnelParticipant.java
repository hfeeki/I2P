package net.i2p.router.tunnel;

import net.i2p.data.Hash;
import net.i2p.data.RouterInfo;
import net.i2p.data.TunnelId;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.data.i2np.TunnelDataMessage;
import net.i2p.router.JobImpl;
import net.i2p.router.OutNetMessage;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 * Participate in a tunnel at a location other than the gateway or outbound
 * endpoint.  This participant should be provided with the necessary processor
 * if it is an inbound tunnel endpoint, and that will enable the 
 * InboundMessageDistributor to receive defragmented and decrypted messages,
 * which it will then selectively forward.
 */
public class TunnelParticipant {
    private RouterContext _context;
    private Log _log;
    private HopConfig _config;
    private HopProcessor _processor;
    private InboundEndpointProcessor _inboundEndpointProcessor;
    private InboundMessageDistributor _inboundDistributor;
    private FragmentHandler _handler;
    private RouterInfo _nextHopCache;

    public TunnelParticipant(RouterContext ctx, HopConfig config, HopProcessor processor) {
        this(ctx, config, processor, null);
    }
    public TunnelParticipant(RouterContext ctx, InboundEndpointProcessor inEndProc) {
        this(ctx, null, null, inEndProc);
    }
    private TunnelParticipant(RouterContext ctx, HopConfig config, HopProcessor processor, InboundEndpointProcessor inEndProc) {
        _context = ctx;
        _log = ctx.logManager().getLog(TunnelParticipant.class);
        _config = config;
        _processor = processor;
        if ( (config == null) || (config.getSendTo() == null) )
            _handler = new RouterFragmentHandler(ctx, new DefragmentedHandler());
        _inboundEndpointProcessor = inEndProc;
        if (inEndProc != null)
            _inboundDistributor = new InboundMessageDistributor(ctx, inEndProc.getDestination());

        if ( (_config != null) && (_config.getSendTo() != null) ) {
            _nextHopCache = _context.netDb().lookupRouterInfoLocally(_config.getSendTo());
            if (_nextHopCache == null)
                _context.netDb().lookupRouterInfo(_config.getSendTo(), new Found(_context), null, 60*1000);
        }
    }
    
    private class Found extends JobImpl {
        public Found(RouterContext ctx) { super(ctx); }
        public String getName() { return "Next hop info found"; }
        public void runJob() {
            if (_nextHopCache == null)
                _nextHopCache = _context.netDb().lookupRouterInfoLocally(_config.getSendTo());
        }
    }
    
    public void dispatch(TunnelDataMessage msg, Hash recvFrom) {
        boolean ok = false;
        if (_processor != null)
            ok = _processor.process(msg.getData(), 0, msg.getData().length, recvFrom);
        else if (_inboundEndpointProcessor != null) 
            ok = _inboundEndpointProcessor.retrievePreprocessedData(msg.getData(), 0, msg.getData().length, recvFrom);
        
        if (!ok) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Failed to dispatch " + msg + ": processor=" + _processor 
                           + " inboundEndpoint=" + _inboundEndpointProcessor);
            return;
        }
        
        if ( (_config != null) && (_config.getSendTo() != null) ) {
            _config.incrementProcessedMessages();
            RouterInfo ri = _nextHopCache;
            if (ri == null)
                ri = _context.netDb().lookupRouterInfoLocally(_config.getSendTo());
            if (ri != null) {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Send off to nextHop directly (" + _config.getSendTo().toBase64().substring(0,4) 
                              + " for " + msg);
                send(_config, msg, ri);
            } else {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Lookup the nextHop (" + _config.getSendTo().toBase64().substring(0,4) 
                              + " for " + msg);
                _context.netDb().lookupRouterInfo(_config.getSendTo(), new SendJob(_context, msg), new TimeoutJob(_context, msg), 10*1000);
            }
        } else {
            _inboundEndpointProcessor.getConfig().incrementProcessedMessages();
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Receive fragment: on " + _config + ": " + msg);
            _handler.receiveTunnelMessage(msg.getData(), 0, msg.getData().length);
        }
    }
    
    private class DefragmentedHandler implements FragmentHandler.DefragmentedReceiver {
        public void receiveComplete(I2NPMessage msg, Hash toRouter, TunnelId toTunnel) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Receive complete: on " + _config + ": " + msg);
            _inboundDistributor.distribute(msg, toRouter, toTunnel);
        }
        
    }

    private void send(HopConfig config, TunnelDataMessage msg, RouterInfo ri) {
        msg.setUniqueId(_context.random().nextLong(I2NPMessage.MAX_ID_VALUE));
        msg.setMessageExpiration(_context.clock().now() + 10*1000);
        OutNetMessage m = new OutNetMessage(_context);
        msg.setTunnelId(config.getSendTunnel());
        m.setMessage(msg);
        m.setExpiration(msg.getMessageExpiration());
        m.setTarget(ri);
        m.setPriority(400);
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Forward on from " + _config + ": " + msg);
        _context.outNetMessagePool().add(m);
    }

    private class SendJob extends JobImpl {
        private TunnelDataMessage _msg;
        public SendJob(RouterContext ctx, TunnelDataMessage msg) {
            super(ctx);
            _msg = msg;
        }
        public String getName() { return "forward a tunnel message"; }
        public void runJob() {
            RouterInfo ri = _context.netDb().lookupRouterInfoLocally(_config.getSendTo());
            if (ri != null) {
                _nextHopCache = ri;
                send(_config, _msg, ri);
            } else {
                if (_log.shouldLog(Log.ERROR))
                    _log.error("Lookup the nextHop (" + _config.getSendTo().toBase64().substring(0,4) 
                              + " failed!  where do we go for " + _config + "?  msg dropped: " + _msg);
            }
        }
    }

    private class TimeoutJob extends JobImpl {
        private TunnelDataMessage _msg;
        public TimeoutJob(RouterContext ctx, TunnelDataMessage msg) {
            super(ctx);
            _msg = msg;
        }
        public String getName() { return "timeout looking for next hop info"; }
        public void runJob() {
            RouterInfo ri = _context.netDb().lookupRouterInfoLocally(_config.getSendTo());
            if (ri != null) {
                _nextHopCache = ri;
                if (_log.shouldLog(Log.ERROR))
                    _log.error("Lookup the nextHop (" + _config.getSendTo().toBase64().substring(0,4) 
                              + " failed, but we found it!!  where do we go for " + _config + "?  msg dropped: " + _msg);
            } else {
                if (_log.shouldLog(Log.ERROR))
                    _log.error("Lookup the nextHop (" + _config.getSendTo().toBase64().substring(0,4) 
                              + " failed!  where do we go for " + _config + "?  msg dropped: " + _msg);
            }
        }
    }
    
    public String toString() { 
        if (_config != null) {
            StringBuffer buf = new StringBuffer(64);
            buf.append("participant at ").append(_config.toString());
            return buf.toString();
        } else {
            return "inbound endpoint";
        }
    }
}