package net.i2p.router.tunnel;

import net.i2p.data.RouterInfo;
import net.i2p.data.i2np.TunnelDataMessage;
import net.i2p.router.JobImpl;
import net.i2p.router.OutNetMessage;
import net.i2p.router.RouterContext;

/**
 *
 */
public class InboundGatewayReceiver implements TunnelGateway.Receiver {
    private RouterContext _context;
    private HopConfig _config;
    private RouterInfo _target;
    
    public InboundGatewayReceiver(RouterContext ctx, HopConfig cfg) {
        _context = ctx;
        _config = cfg;
    }
    public void receiveEncrypted(byte[] encrypted) {
        receiveEncrypted(encrypted, false);
    }
    public void receiveEncrypted(byte[] encrypted, boolean alreadySearched) {
        if (_target == null) {
            _target = _context.netDb().lookupRouterInfoLocally(_config.getSendTo());
            if (_target == null) {
                ReceiveJob j = null;
                if (!alreadySearched)
                    j = new ReceiveJob(encrypted);
                _context.netDb().lookupRouterInfo(_config.getSendTo(), j, j, 5*1000);
                return;
            }
        }
        
        TunnelDataMessage msg = new TunnelDataMessage(_context);
        msg.setData(encrypted);
        msg.setTunnelId(_config.getSendTunnel());
        
        OutNetMessage out = new OutNetMessage(_context);
        out.setMessage(msg);
        out.setTarget(_target);
        out.setExpiration(msg.getMessageExpiration());
        out.setPriority(400);
        _context.outNetMessagePool().add(out);
    }
    
    private class ReceiveJob extends JobImpl {
        private byte[] _encrypted;
        public ReceiveJob(byte data[]) {
            super(_context);
            _encrypted = data;
        }
        public String getName() { return "lookup first hop"; }
        public void runJob() {
            receiveEncrypted(_encrypted, true);
        }
    }
}
