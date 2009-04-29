package net.i2p.router.transport;

/*
 * public domain
 */

import java.net.InetAddress;
import java.util.HashSet;
import java.util.Map;
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
 * UPnP listens on ports 1900, 8008, and 8058 - no config option yet.
 *
 * @author zzz
 */
public class UPnPManager {
    private Log _log;
    private RouterContext _context;
    private UPnP _upnp;
    private UPnPCallback _upnpCallback;
    private boolean _isRunning;
    private InetAddress _detectedAddress;
    private TransportManager _manager;

    public UPnPManager(RouterContext context, TransportManager manager) {
        _context = context;
        _manager = manager;
        _log = _context.logManager().getLog(UPnPManager.class);
        _upnp = new UPnP(context);
        _upnpCallback = new UPnPCallback();
        _isRunning = false;
    }
    
    public synchronized void start() {
        if (_log.shouldLog(Log.DEBUG)) {
            _log.debug("UPnP Start");
            Debug.on();  // UPnP stuff -> wrapper log
        }
        if (!_isRunning)
            _upnp.runPlugin();
        _isRunning = true;
    }

    public synchronized void stop() {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("UPnP Stop");
        if (_isRunning)
            _upnp.terminate();
        _isRunning = false;
        _detectedAddress = null;
    }
    
    /** call when the ports might have changed */
    public void update(Map<String, Integer> ports) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("UPnP Update:");
        if (!_isRunning)
            return;
        Set<ForwardPort> forwards = new HashSet(ports.size());
        for (String style : ports.keySet()) {
            int port = ports.get(style).intValue();
            int protocol = -1;
            if ("SSU".equals(style))
                protocol = ForwardPort.PROTOCOL_UDP_IPV4;
            else if ("NTCP".equals(style))
                protocol = ForwardPort.PROTOCOL_TCP_IPV4;
            else
                continue;
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Adding: " + style + " " + port);
            ForwardPort fp = new ForwardPort(style, false, protocol, port);
            forwards.add(fp);
        }
        _upnp.onChangePublicPorts(forwards, _upnpCallback);
    }

    /**
     *  This is the callback from UPnP.
     *  It calls the TransportManager callbacks.
     */
    private class UPnPCallback implements ForwardPortCallback {
	
        /** Called to indicate status on one or more forwarded ports. */
        public void portForwardStatus(Map<ForwardPort,ForwardPortStatus> statuses) {
            if (_log.shouldLog(Log.DEBUG))
                 _log.debug("UPnP Callback:");

            DetectedIP[] ips = _upnp.getAddress();
            byte[] detected = null;
            if (ips != null) {
                for (DetectedIP ip : ips) {
                    // store the first public one and tell the transport manager if it changed
                    if (TransportImpl.isPubliclyRoutable(ip.publicAddress.getAddress())) {
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug("External address: " + ip.publicAddress + " type: " + ip.natType);
                        if (!ip.publicAddress.equals(_detectedAddress)) {
                            _detectedAddress = ip.publicAddress;
                            _manager.externalAddressReceived(Transport.SOURCE_UPNP, _detectedAddress.getAddress(), 0);
                        }
                        break;
                    }
                }
            } else {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("No external address returned");
            }

            for (ForwardPort fp : statuses.keySet()) {
                ForwardPortStatus fps = statuses.get(fp);
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug(fp.name + " " + fp.protocol + " " + fp.portNumber +
                               " status: " + fps.status + " reason: " + fps.reasonString + " ext port: " + fps.externalPort);
                String style;
                if (fp.protocol == ForwardPort.PROTOCOL_UDP_IPV4)
                    style = "SSU";
                else if (fp.protocol == ForwardPort.PROTOCOL_TCP_IPV4)
                    style = "NTCP";
                else
                    continue;
                boolean success = fps.status >= ForwardPortStatus.MAYBE_SUCCESS;
                _manager.forwardPortStatus(style, fp.portNumber, success, fps.reasonString);
            }
        }
    }

    public String renderStatusHTML() {
        if (!_isRunning)
            return "<b>UPnP is not enabled</b>\n";
        return _upnp.renderStatusHTML();
    }
}
