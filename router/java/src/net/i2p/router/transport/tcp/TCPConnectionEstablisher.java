package net.i2p.router.transport.tcp;

import net.i2p.data.Hash;
import net.i2p.data.RouterInfo;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 * Build new outbound connections, one at a time.  All the heavy lifting is in
 * {@link ConnectionBuilder#establishConnection}
 *
 */
public class TCPConnectionEstablisher implements Runnable {
    private Log _log;
    private RouterContext _context;
    private TCPTransport _transport;
    
    public TCPConnectionEstablisher(RouterContext ctx, TCPTransport transport) {
        _context = ctx;
        _transport = transport;
        _log = ctx.logManager().getLog(TCPConnectionEstablisher.class);
    }
    
    public void run() {
        while (true) {
            try {
                loop();
            } catch (Exception e) {
                _log.log(Log.CRIT, "wtf, establisher b0rked.  send this stack trace to jrandom", e);
            }
        }
    }
    
    private void loop() {
        RouterInfo info = _transport.getNextPeer();
        if (info == null) {
            try { Thread.sleep(5*1000); } catch (InterruptedException ie) {}
            return;
        }

        ConnectionBuilder cb = new ConnectionBuilder(_context, _transport, info);
        TCPConnection con = null;
        try {
            con = cb.establishConnection();
        } catch (Exception e) {
            _log.log(Log.CRIT, "Unhandled exception establishing a connection to " 
                               + info.getIdentity().getHash().toBase64(), e);
        }
        if (con != null) {
            _transport.connectionEstablished(con);
        } else {
            if (!_context.router().isAlive()) return;
            _transport.addConnectionErrorMessage(cb.getError());
            Hash peer = info.getIdentity().getHash();
            _context.profileManager().commErrorOccurred(peer);
            _context.shitlist().shitlistRouter(peer, "Unable to contact");
            _context.netDb().fail(peer);
        }

        // this removes the _pending block on the address and 
        // identity we attempted to contact.  if the peer changed
        // identities, any additional _pending blocks will also have
        // been cleared above with .connectionEstablished 
        _transport.establishmentComplete(info);
    }
}
