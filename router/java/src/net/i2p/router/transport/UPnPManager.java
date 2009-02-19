package net.i2p.router.transport;

/*
 * public domain
 */

import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import net.i2p.data.RouterAddress;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

import org.cybergarage.util.Debug;
import org.freenetproject.DetectedIP;
import org.freenetproject.ForwardPort;
import org.freenetproject.ForwardPortCallback;
import org.freenetproject.ForwardPortStatus;

/**
 * Bridge from the I2P RouterAddress data structure to
 * the freenet data structures
 *
 * No disable option yet.
 * UPnP listens on ports 1900, 8008, and 8058 - no config option yet.
 * No routerconsole support yet.
 *
 * @author zzz
 */
public class UPnPManager {
    private Log _log;
    private RouterContext _context;
    private UPnP _upnp;
    private UPnPCallback _upnpCallback;
    private boolean _isRunning;

    public UPnPManager(RouterContext context) {
        _context = context;
        _log = _context.logManager().getLog(UPnPManager.class);
        _upnp = new UPnP(context);
        _upnpCallback = new UPnPCallback();
        _isRunning = false;
    }
    
    public synchronized void start() {
        _log.error("UPnP Start");
        Debug.on();  // UPnP stuff -> wrapper log
        if (!_isRunning)
            _upnp.runPlugin();
        _isRunning = true;
    }

    public synchronized void stop() {
        _log.error("UPnP Stop");
        if (_isRunning)
            _upnp.terminate();
        _isRunning = false;
    }
    
    /** call when the ports might have changed */
    public void update(Map<String, RouterAddress> addresses) {
        _log.error("UPnP Update:");
        if (!_isRunning)
            return;
        Set<ForwardPort> forwards = new HashSet(addresses.size());
        for (String style : addresses.keySet()) {
            RouterAddress ra = addresses.get(style);
            if (ra == null)
                continue;
            Properties opts = ra.getOptions();
            if (opts == null)
                continue;
            String s = opts.getProperty("port");
            if (s == null)
                continue;
            int port = -1;
            try {
                port = Integer.parseInt(s);
            } catch (NumberFormatException nfe) { continue; }
            int protocol = -1;
            if ("SSU".equals(style))
                protocol = ForwardPort.PROTOCOL_UDP_IPV4;
            else if ("NTCP".equals(style))
                protocol = ForwardPort.PROTOCOL_TCP_IPV4;
            else
                continue;
            _log.error("Adding: " + style + " " + port);
            ForwardPort fp = new ForwardPort(style, false, protocol, port);
            forwards.add(fp);
        }
        _upnp.onChangePublicPorts(forwards, _upnpCallback);
    }

    /** just logs for now */
    private class UPnPCallback implements ForwardPortCallback {
	
        /** Called to indicate status on one or more forwarded ports. */
        public void portForwardStatus(Map<ForwardPort,ForwardPortStatus> statuses) {
             _log.error("UPnP Callback:");

             DetectedIP[] ips = _upnp.getAddress();
             for (DetectedIP ip : ips) {
                 _log.error("External address: " + ip.publicAddress + " type: " + ip.natType);
             }

             for (ForwardPort fp : statuses.keySet()) {
                 ForwardPortStatus fps = statuses.get(fp);
                 _log.error(fp.name + " " + fp.protocol + " " + fp.portNumber +
                            " status: " + fps.status + " reason: " + fps.reasonString + " ext port: " + fps.externalPort);
             }
         }
    }
}
