package net.i2p.router.tunnel;

import net.i2p.data.Hash;
import net.i2p.data.RouterInfo;
import net.i2p.data.i2np.TunnelDataMessage;
import net.i2p.router.JobImpl;
import net.i2p.router.OutNetMessage;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 * Receive the outbound message after it has been preprocessed and encrypted,
 * then forward it on to the first hop in the tunnel.
 *
 */
class OutboundReceiver implements TunnelGateway.Receiver {
    private RouterContext _context;
    private Log _log;
    private TunnelCreatorConfig _config;
    public OutboundReceiver(RouterContext ctx, TunnelCreatorConfig cfg) {
        _context = ctx;
        _log = ctx.logManager().getLog(OutboundReceiver.class);
        _config = cfg;
    }
    
    public void receiveEncrypted(byte encrypted[]) {
        TunnelDataMessage msg = new TunnelDataMessage(_context);
        msg.setData(encrypted);
        msg.setTunnelId(_config.getConfig(0).getSendTunnel());

        if (_log.shouldLog(Log.DEBUG))
            _log.debug("received encrypted, sending out " + _config + ": " + msg);
        RouterInfo ri = _context.netDb().lookupRouterInfoLocally(_config.getPeer(1));
        if (ri != null) {
            send(msg, ri);
        } else {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("lookup of " + _config.getPeer(1).toBase64().substring(0,4) 
                           + " required for " + msg);
            _context.netDb().lookupRouterInfo(_config.getPeer(1), new SendJob(msg), new FailedJob(), 10*1000);
        }
    }

    private void send(TunnelDataMessage msg, RouterInfo ri) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("forwarding encrypted data out " + _config + ": " + msg);
        OutNetMessage m = new OutNetMessage(_context);
        m.setMessage(msg);
        m.setExpiration(msg.getMessageExpiration());
        m.setTarget(ri);
        m.setPriority(400);
        _context.outNetMessagePool().add(m);
        _config.incrementProcessedMessages();
    }

    private class SendJob extends JobImpl {
        private TunnelDataMessage _msg;
        public SendJob(TunnelDataMessage msg) {
            super(_context);
            _msg = msg;
        }
        public String getName() { return "forward a tunnel message"; }
        public void runJob() {
            RouterInfo ri = _context.netDb().lookupRouterInfoLocally(_config.getPeer(1));
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("lookup of " + _config.getPeer(1).toBase64().substring(0,4) 
                           + " successful? " + (ri != null));
            if (ri != null)
                send(_msg, ri);
        }
    }
    
    private class FailedJob extends JobImpl {
        public FailedJob() {
            super(_context);
        }
        public String getName() { return "failed looking for our outbound gateway"; }
        public void runJob() {
            if (_log.shouldLog(Log.ERROR))
                _log.error("lookup of " + _config.getPeer(1).toBase64().substring(0,4) 
                           + " failed for " + _config);
        }
    }
}