package net.i2p.i2ptunnel.web;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2005 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.concurrent.ConcurrentHashMap;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.StringTokenizer;

import net.i2p.I2PAppContext;
import net.i2p.data.Base32;
import net.i2p.data.Certificate;
import net.i2p.data.Destination;
import net.i2p.data.PrivateKeyFile;
import net.i2p.data.SessionKey;
import net.i2p.i2ptunnel.TunnelController;
import net.i2p.i2ptunnel.TunnelControllerGroup;
import net.i2p.util.ConcurrentHashSet;
import net.i2p.util.Log;

/**
 * Simple accessor for exposing tunnel info, but also an ugly form handler
 *
 * Warning - This class is not part of the i2ptunnel API, and at some point
 * it will be moved from the jar to the war.
 * Usage by classes outside of i2ptunnel.war is deprecated.
 */
public class IndexBean {
    protected I2PAppContext _context;
    protected Log _log;
    protected TunnelControllerGroup _group;
    private String _action;
    private int _tunnel;
    private long _prevNonce;
    private long _prevNonce2;
    private long _curNonce;
    private long _nextNonce;

    private String _type;
    private String _name;
    private String _description;
    private String _i2cpHost;
    private String _i2cpPort;
    private String _tunnelDepth;
    private String _tunnelQuantity;
    private String _tunnelVariance;
    private String _tunnelBackupQuantity;
    private boolean _connectDelay;
    private String _customOptions;
    private String _proxyList;
    private String _port;
    private String _reachableBy;
    private String _reachableByOther;
    private String _targetDestination;
    private String _targetHost;
    private String _targetPort;
    private String _spoofedHost;
    private String _privKeyFile;
    private String _profile;
    private boolean _startOnLoad;
    private boolean _sharedClient;
    private boolean _privKeyGenerate;
    private boolean _removeConfirmed;
    private Set<String> _booleanOptions;
    private Map<String, String> _otherOptions;
    private int _hashCashValue;
    private int _certType;
    private String _certSigner;
    
    public static final int RUNNING = 1;
    public static final int STARTING = 2;
    public static final int NOT_RUNNING = 3;
    public static final int STANDBY = 4;
    
    /** deprecated unimplemented, now using routerconsole realm */
    //public static final String PROP_TUNNEL_PASSPHRASE = "i2ptunnel.passphrase";
    public static final String PROP_TUNNEL_PASSPHRASE = "consolePassword";
    static final String PROP_NONCE = IndexBean.class.getName() + ".nonce";
    static final String PROP_NONCE_OLD = PROP_NONCE + '2';
    static final String CLIENT_NICKNAME = "shared clients";
    
    public static final String PROP_THEME_NAME = "routerconsole.theme";
    public static final String DEFAULT_THEME = "light";
    public static final String PROP_CSS_DISABLED = "routerconsole.css.disabled";
    public static final String PROP_JS_DISABLED = "routerconsole.javascript.disabled";
    
    public IndexBean() {
        _context = I2PAppContext.getGlobalContext();
        _log = _context.logManager().getLog(IndexBean.class);
        _group = TunnelControllerGroup.getInstance();
        _action = null;
        _tunnel = -1;
        _curNonce = -1;
        _prevNonce = -1;
        _prevNonce2 = -1;
        try { 
            String nonce2 = System.getProperty(PROP_NONCE_OLD);
            if (nonce2 != null)
                _prevNonce2 = Long.parseLong(nonce2);
            String nonce = System.getProperty(PROP_NONCE);
            if (nonce != null) {
                _prevNonce = Long.parseLong(nonce);
                System.setProperty(PROP_NONCE_OLD, nonce);
            }
        } catch (NumberFormatException nfe) {}
        _nextNonce = _context.random().nextLong();
        System.setProperty(PROP_NONCE, Long.toString(_nextNonce));
        _booleanOptions = new ConcurrentHashSet(4);
        _otherOptions = new ConcurrentHashMap(4);
    }
    
    public long getNextNonce() { return _nextNonce; }
    public void setNonce(String nonce) {
        if ( (nonce == null) || (nonce.trim().length() <= 0) ) return;
        try {
            _curNonce = Long.parseLong(nonce);
        } catch (NumberFormatException nfe) {
            _curNonce = -1;
        }
    }

    /** deprecated unimplemented, now using routerconsole realm */
    public void setPassphrase(String phrase) {
    }
    
    public void setAction(String action) {
        if ( (action == null) || (action.trim().length() <= 0) ) return;
        _action = action;
    }
    public void setTunnel(String tunnel) {
        if ( (tunnel == null) || (tunnel.trim().length() <= 0) ) return;
        try {
            _tunnel = Integer.parseInt(tunnel);
        } catch (NumberFormatException nfe) {
            _tunnel = -1;
        }
    }
    
    /** just check if console password option is set, jetty will do auth */
    private boolean validPassphrase() {
        String pass = _context.getProperty(PROP_TUNNEL_PASSPHRASE);
        return pass != null && pass.trim().length() > 0;
    }
    
    private String processAction() {
        if ( (_action == null) || (_action.trim().length() <= 0) || ("Cancel".equals(_action)))
            return "";
        if ( (_prevNonce != _curNonce) && (_prevNonce2 != _curNonce) && (!validPassphrase()) )
            return "Invalid form submission, probably because you used the 'back' or 'reload' button on your browser. Please resubmit.";
        if ("Stop all".equals(_action)) 
            return stopAll();
        else if ("Start all".equals(_action))
            return startAll();
        else if ("Restart all".equals(_action))
            return restartAll();
        else if ("Reload configuration".equals(_action))
            return reloadConfig();
        else if ("stop".equals(_action))
            return stop();
        else if ("start".equals(_action))
            return start();
        else if ("Save changes".equals(_action) || // IE workaround:
                (_action.toLowerCase().indexOf("s</span>ave") >= 0))
            return saveChanges();
        else if ("Delete this proxy".equals(_action) || // IE workaround:
                (_action.toLowerCase().indexOf("d</span>elete") >= 0))
            return deleteTunnel();
        else if ("Estimate".equals(_action))
            return PrivateKeyFile.estimateHashCashTime(_hashCashValue);
        else if ("Modify".equals(_action))
            return modifyDestination();
        else if ("Generate".equals(_action))
            return generateNewEncryptionKey();
        else
            return "Action " + _action + " unknown";
    }
    private String stopAll() {
        if (_group == null) return "";
        List msgs = _group.stopAllControllers();
        return getMessages(msgs);
    }
    private String startAll() {
        if (_group == null) return "";
        List msgs = _group.startAllControllers();
        return getMessages(msgs);
    }
    private String restartAll() {
        if (_group == null) return "";
        List msgs = _group.restartAllControllers();
        return getMessages(msgs);
    }
    private String reloadConfig() {
        if (_group == null) return "";
        
        _group.reloadControllers();
        return "Config reloaded";
    }
    private String start() {
        if (_tunnel < 0) return "Invalid tunnel";
        
        List controllers = _group.getControllers();
        if (_tunnel >= controllers.size()) return "Invalid tunnel";
        TunnelController controller = (TunnelController)controllers.get(_tunnel);
        controller.startTunnelBackground();
        return "";
    }
    
    private String stop() {
        if (_tunnel < 0) return "Invalid tunnel";
        
        List controllers = _group.getControllers();
        if (_tunnel >= controllers.size()) return "Invalid tunnel";
        TunnelController controller = (TunnelController)controllers.get(_tunnel);
        controller.stopTunnel();
        return "";
    }
    
    private String saveChanges() {
        // Get current tunnel controller
        TunnelController cur = getController(_tunnel);
        
        Properties config = getConfig();
        if (config == null)
            return "Invalid params";
        
        if (cur == null) {
            // creating new
            cur = new TunnelController(config, "", true);
            _group.addController(cur);
            if (cur.getStartOnLoad())
                cur.startTunnelBackground();
        } else {
            cur.setConfig(config, "");
        }
        // Only modify other shared tunnels
        // if the current tunnel is shared, and of supported type
        if ("true".equalsIgnoreCase(cur.getSharedClient()) && isClient(cur.getType())) {
            // all clients use the same I2CP session, and as such, use the same I2CP options
            List controllers = _group.getControllers();

            for (int i = 0; i < controllers.size(); i++) {
                TunnelController c = (TunnelController)controllers.get(i);

                // Current tunnel modified by user, skip
                if (c == cur) continue;

                // Only modify this non-current tunnel
                // if it belongs to a shared destination, and is of supported type
                if ("true".equalsIgnoreCase(c.getSharedClient()) && isClient(c.getType())) {
                    Properties cOpt = c.getConfig("");
                    if (_tunnelQuantity != null) {
                        cOpt.setProperty("option.inbound.quantity", _tunnelQuantity);
                        cOpt.setProperty("option.outbound.quantity", _tunnelQuantity);
                    }
                    if (_tunnelDepth != null) {
                        cOpt.setProperty("option.inbound.length", _tunnelDepth);
                        cOpt.setProperty("option.outbound.length", _tunnelDepth);
                    }
                    if (_tunnelVariance != null) {
                        cOpt.setProperty("option.inbound.lengthVariance", _tunnelVariance);
                        cOpt.setProperty("option.outbound.lengthVariance", _tunnelVariance);
                    }
                    if (_tunnelBackupQuantity != null) {
                        cOpt.setProperty("option.inbound.backupQuantity", _tunnelBackupQuantity);
                        cOpt.setProperty("option.outbound.backupQuantity", _tunnelBackupQuantity);
                    }
                    cOpt.setProperty("option.inbound.nickname", CLIENT_NICKNAME);
                    cOpt.setProperty("option.outbound.nickname", CLIENT_NICKNAME);
                    
                    c.setConfig(cOpt, "");
                }
            }
        }
        
        List msgs = doSave();
        msgs.add(0, "Changes saved");
        return getMessages(msgs);
    }
    private List doSave() { 
        _group.saveConfig();
        return _group.clearAllMessages();
    } 
    private String deleteTunnel() {
        if (!_removeConfirmed)
            return "Please confirm removal";
        
        TunnelController cur = getController(_tunnel);
        if (cur == null)
            return "Invalid tunnel number";
        
        List msgs = _group.removeController(cur);
        msgs.addAll(doSave());
        return getMessages(msgs);
    }
    
    /**
     * Executes any action requested (start/stop/etc) and dump out the 
     * messages.
     *
     */
    public String getMessages() {
        if (_group == null)
            return "";
        
        StringBuilder buf = new StringBuilder(512);
        if (_action != null) {
            try {
                buf.append(processAction()).append("\n");
            } catch (Exception e) {
                _log.log(Log.CRIT, "Error processing " + _action, e);
            }
        }
        getMessages(_group.clearAllMessages(), buf);
        return buf.toString();
    }
    
    ////
    // The remaining methods are simple bean props for the jsp to query
    ////
    
    public String getTheme() {
    	String theme = _context.getProperty(PROP_THEME_NAME, DEFAULT_THEME);
	return "/themes/console/" + theme + "/";
    }

    public boolean allowCSS() {
        String css = _context.getProperty(PROP_CSS_DISABLED);
        return (css == null);
    }
    
    public boolean allowJS() {
        String js = _context.getProperty(PROP_JS_DISABLED);
        return (js == null);
    }
    
    public int getTunnelCount() {
        if (_group == null) return 0;
        return _group.getControllers().size();
    }
    
    public boolean isClient(int tunnelNum) {
        TunnelController cur = getController(tunnelNum);
        if (cur == null) return false;
        return isClient(cur.getType());
    }

    public static boolean isClient(String type) {
        return ( ("client".equals(type)) || 
        		("httpclient".equals(type)) ||
        		("sockstunnel".equals(type)) ||
        		("socksirctunnel".equals(type)) ||
        		("connectclient".equals(type)) ||
        		("streamrclient".equals(type)) ||
        		("ircclient".equals(type)));
    }
    
    public String getTunnelName(int tunnel) {
        TunnelController tun = getController(tunnel);
        if (tun != null && tun.getName() != null)
            return tun.getName();
        else
            return _("New Tunnel");
    }
    
    public String getClientPort(int tunnel) {
        TunnelController tun = getController(tunnel);
        if (tun != null && tun.getListenPort() != null)
            return tun.getListenPort();
        else
            return "";
    }
    
    public String getTunnelType(int tunnel) {
        TunnelController tun = getController(tunnel);
        if (tun != null)
            return getTypeName(tun.getType());
        else
            return "";
    }
    
    public String getTypeName(String internalType) {
        if ("client".equals(internalType)) return _("Standard client");
        else if ("httpclient".equals(internalType)) return _("HTTP client");
        else if ("ircclient".equals(internalType)) return _("IRC client");
        else if ("server".equals(internalType)) return _("Standard server");
        else if ("httpserver".equals(internalType)) return _("HTTP server");
        else if ("sockstunnel".equals(internalType)) return _("SOCKS 4/4a/5 proxy");
        else if ("socksirctunnel".equals(internalType)) return _("SOCKS IRC proxy");
        else if ("connectclient".equals(internalType)) return _("CONNECT/SSL/HTTPS proxy");
        else if ("ircserver".equals(internalType)) return _("IRC server");
        else if ("streamrclient".equals(internalType)) return _("Streamr client");
        else if ("streamrserver".equals(internalType)) return _("Streamr server");
        else if ("httpbidirserver".equals(internalType)) return _("HTTP bidir");
        else return internalType;
    }
    
    public String getInternalType(int tunnel) {
        TunnelController tun = getController(tunnel);
        if (tun != null)
            return tun.getType();
        else
            return "";
    }
    
    public String getClientInterface(int tunnel) {
        TunnelController tun = getController(tunnel);
        if (tun != null)
            return tun.getListenOnInterface();
        else
            return "";
    }
    
    public int getTunnelStatus(int tunnel) {
        TunnelController tun = getController(tunnel);
        if (tun == null) return NOT_RUNNING;
        if (tun.getIsRunning()) {
            if (isClient(tunnel) && tun.getIsStandby())
                return STANDBY;
            else
                return RUNNING;
        } else if (tun.getIsStarting()) return STARTING;
        else return NOT_RUNNING;
    }
    
    public String getTunnelDescription(int tunnel) {
        TunnelController tun = getController(tunnel);
        if (tun != null && tun.getDescription() != null)
            return tun.getDescription();
        else
            return "";
    }
    
    public String getSharedClient(int tunnel) {
    	TunnelController tun = getController(tunnel);
    	if (tun != null)
    		return tun.getSharedClient();
    	else
    		return "";
    }
    
    public String getClientDestination(int tunnel) {
        TunnelController tun = getController(tunnel);
        if (tun == null) return "";
        String rv;
        if ("client".equals(tun.getType()) || "ircclient".equals(tun.getType()) ||
            "streamrclient".equals(tun.getType()))
            rv = tun.getTargetDestination();
        else
            rv = tun.getProxyList();
        return rv != null ? rv : "";
    }
    
    public String getServerTarget(int tunnel) {
        TunnelController tun = getController(tunnel);
        if (tun != null)
            return tun.getTargetHost() + ':' + tun.getTargetPort();
        else
            return "";
    }
    
    public String getDestinationBase64(int tunnel) {
        TunnelController tun = getController(tunnel);
        if (tun != null) {
            String rv = tun.getMyDestination();
            if (rv != null)
                return rv;
            // if not running, do this the hard way
            String keyFile = tun.getPrivKeyFile();
            if (keyFile != null && keyFile.trim().length() > 0) {
                PrivateKeyFile pkf = new PrivateKeyFile(keyFile);
                try {
                    Destination d = pkf.getDestination();
                    if (d != null)
                        return d.toBase64();
                } catch (Exception e) {}
            }
        }
        return "";
    }
    
    public String getDestHashBase32(int tunnel) {
        TunnelController tun = getController(tunnel);
        if (tun != null) {
            String rv = tun.getMyDestHashBase32();
            if (rv != null)
                return rv;
        }
        return "";
    }
    
    ///
    /// bean props for form submission
    ///
    
    /**
     * What type of tunnel (httpclient, ircclient, client, or server).  This is 
     * required when adding a new tunnel.
     *
     */
    public void setType(String type) { 
        _type = (type != null ? type.trim() : null);   
    }
    String getType() { return _type; }
    
    /** Short name of the tunnel */
    public void setName(String name) { 
        _name = (name != null ? name.trim() : null);
    }
    /** one line description */
    public void setDescription(String description) { 
        _description = (description != null ? description.trim() : null);
    }
    /** I2CP host the router is on */
    public void setClientHost(String host) {
        _i2cpHost = (host != null ? host.trim() : null);
    }
    /** I2CP port the router is on */
    public void setClientport(String port) {
        _i2cpPort = (port != null ? port.trim() : null);
    }
    /** how many hops to use for inbound tunnels */
    public void setTunnelDepth(String tunnelDepth) { 
        _tunnelDepth = (tunnelDepth != null ? tunnelDepth.trim() : null);
    }
    /** how many parallel inbound tunnels to use */
    public void setTunnelQuantity(String tunnelQuantity) { 
        _tunnelQuantity = (tunnelQuantity != null ? tunnelQuantity.trim() : null);
    }
    /** how much randomisation to apply to the depth of tunnels */
    public void setTunnelVariance(String tunnelVariance) { 
        _tunnelVariance = (tunnelVariance != null ? tunnelVariance.trim() : null);
    }
    /** how many tunnels to hold in reserve to guard against failures */
    public void setTunnelBackupQuantity(String tunnelBackupQuantity) { 
        _tunnelBackupQuantity = (tunnelBackupQuantity != null ? tunnelBackupQuantity.trim() : null);
    }
    /** what I2P session overrides should be used */
    public void setCustomOptions(String customOptions) { 
        _customOptions = (customOptions != null ? customOptions.trim() : null);
    }
    /** what HTTP outproxies should be used (httpclient specific) */
    public void setProxyList(String proxyList) { 
        _proxyList = (proxyList != null ? proxyList.trim() : null);
    }
    /** what port should this client/httpclient/ircclient listen on */
    public void setPort(String port) { 
        _port = (port != null ? port.trim() : null);
    }
    /** 
     * what interface should this client/httpclient/ircclient listen on (unless 
     * overridden by the setReachableByOther() field)
     */
    public void setReachableBy(String reachableBy) { 
        _reachableBy = (reachableBy != null ? reachableBy.trim() : null);
    }
    /**
     * If specified, defines the exact IP interface to listen for requests
     * on (in the case of client/httpclient/ircclient tunnels)
     */
    public void setReachableByOther(String reachableByOther) { 
        _reachableByOther = (reachableByOther != null ? reachableByOther.trim() : null);
    }
    /** What peer does this client tunnel point at */
    public void setTargetDestination(String dest) { 
        _targetDestination = (dest != null ? dest.trim() : null);
    }
    /** What host does this server tunnel point at */
    public void setTargetHost(String host) { 
        _targetHost = (host != null ? host.trim() : null);
    }
    /** What port does this server tunnel point at */
    public void setTargetPort(String port) { 
        _targetPort = (port != null ? port.trim() : null);
    }
    /** What host does this http server tunnel spoof */
    public void setSpoofedHost(String host) { 
        _spoofedHost = (host != null ? host.trim() : null);
    }
    /** What filename is this server tunnel's private keys stored in */
    public void setPrivKeyFile(String file) { 
        _privKeyFile = (file != null ? file.trim() : null);
    }
    /**
     * If called with any value (and the form submitted with action=Remove),
     * we really do want to stop and remove the tunnel.
     */
    public void setRemoveConfirm(String moo) {
        _removeConfirmed = true;
    }
    /**
     * If called with any value, we want this tunnel to start whenever it is
     * loaded (aka right now and whenever the router is started up)
     */
    public void setStartOnLoad(String moo) {
        _startOnLoad = true;
    }
    public void setShared(String moo) {
    	_sharedClient=true;
    }
    public void setShared(boolean val) {
    	_sharedClient=val;
    }
    public void setConnectDelay(String moo) {
        _connectDelay = true;
    }
    public void setProfile(String profile) { 
        _profile = profile; 
    }

    public void setReduce(String moo) {
        _booleanOptions.add("i2cp.reduceOnIdle");
    }
    public void setClose(String moo) {
        _booleanOptions.add("i2cp.closeOnIdle");
    }
    public void setEncrypt(String moo) {
        _booleanOptions.add("i2cp.encryptLeaseSet");
    }
    public void setAccess(String moo) {
        _booleanOptions.add("i2cp.enableAccessList");
    }
    public void setDelayOpen(String moo) {
        _booleanOptions.add("i2cp.delayOpen");
    }
    public void setNewDest(String val) {
        if ("1".equals(val))
            _booleanOptions.add("i2cp.newDestOnResume");
        else if ("2".equals(val))
            _booleanOptions.add("persistentClientKey");
    }

    public void setReduceTime(String val) {
        if (val != null) {
            try {
                _otherOptions.put("i2cp.reduceIdleTime", "" + (Integer.parseInt(val.trim()) * 60*1000));
            } catch (NumberFormatException nfe) {}
        }
    }
    public void setReduceCount(String val) {
        if (val != null)
            _otherOptions.put("i2cp.reduceQuantity", val.trim());
    }
    public void setEncryptKey(String val) {
        if (val != null)
            _otherOptions.put("i2cp.leaseSetKey", val.trim());
    }
    public void setAccessList(String val) {
        if (val != null)
            _otherOptions.put("i2cp.accessList", val.trim().replaceAll("\r\n", ",").replaceAll("\n", ",").replaceAll(" ", ","));
    }
    public void setCloseTime(String val) {
        if (val != null) {
            try {
                _otherOptions.put("i2cp.closeIdleTime", "" + (Integer.parseInt(val.trim()) * 60*1000));
            } catch (NumberFormatException nfe) {}
        }
    }

    /** params needed for hashcash and dest modification */
    public void setEffort(String val) {
        if (val != null) {
            try {
                _hashCashValue = Integer.parseInt(val.trim());
            } catch (NumberFormatException nfe) {}
        }
    }
    public void setCert(String val) {
        if (val != null) {
            try {
                _certType = Integer.parseInt(val.trim());
            } catch (NumberFormatException nfe) {}
        }
    }
    public void setSigner(String val) {
        _certSigner = val;
    }

    /** Modify or create a destination */
    private String modifyDestination() {
        if (_privKeyFile == null || _privKeyFile.trim().length() <= 0)
            return "Private Key File not specified";

        TunnelController tun = getController(_tunnel);
        Properties config = getConfig();
        if (config == null)
            return "Invalid params";
        if (tun == null) {
            // creating new
            tun = new TunnelController(config, "", true);
            _group.addController(tun);
            saveChanges();
        } else if (tun.getIsRunning() || tun.getIsStarting()) {
            return "Tunnel must be stopped before modifying destination";
        }
        PrivateKeyFile pkf = new PrivateKeyFile(_privKeyFile);
        try {
            pkf.createIfAbsent();
        } catch (Exception e) {
            return "Create private key file failed: " + e;
        }
        switch (_certType) {
            case Certificate.CERTIFICATE_TYPE_NULL:
            case Certificate.CERTIFICATE_TYPE_HIDDEN:
                pkf.setCertType(_certType);
                break;
            case Certificate.CERTIFICATE_TYPE_HASHCASH:
                pkf.setHashCashCert(_hashCashValue);
                break;
            case Certificate.CERTIFICATE_TYPE_SIGNED:
                if (_certSigner == null || _certSigner.trim().length() <= 0)
                    return "No signing destination specified";
                // find the signer's key file...
                String signerPKF = null;
                for (int i = 0; i < getTunnelCount(); i++) {
                    TunnelController c = getController(i);
                    if (_certSigner.equals(c.getConfig("").getProperty("name")) ||
                        _certSigner.equals(c.getConfig("").getProperty("spoofedHost"))) {
                        signerPKF = c.getConfig("").getProperty("privKeyFile");
                        break;
                    }
                }
                if (signerPKF == null || signerPKF.length() <= 0)
                    return "Signing destination " + _certSigner + " not found";
                if (_privKeyFile.equals(signerPKF))
                    return "Self-signed destinations not allowed";
                Certificate c = pkf.setSignedCert(new PrivateKeyFile(signerPKF));
                if (c == null)
                    return "Signing failed - does signer destination exist?";
                break;
            default:
                return "Unknown certificate type";
        }
        Destination newdest;
        try {
            pkf.write();
            newdest = pkf.getDestination();
        } catch (Exception e) {
            return "Modification failed: " + e;
        }
        return "Destination modified - " +
               "New Base32 is " + Base32.encode(newdest.calculateHash().getData()) + ".b32.i2p " +
               "New Destination is " + newdest.toBase64();
     }

    /** New key */
    private String generateNewEncryptionKey() {
        TunnelController tun = getController(_tunnel);
        Properties config = getConfig();
        if (config == null)
            return "Invalid params";
        if (tun == null) {
            // creating new
            tun = new TunnelController(config, "", true);
            _group.addController(tun);
            saveChanges();
        } else if (tun.getIsRunning() || tun.getIsStarting()) {
            return "Tunnel must be stopped before modifying leaseset encryption key";
        }
        byte[] data = new byte[SessionKey.KEYSIZE_BYTES];
        _context.random().nextBytes(data);
        SessionKey sk = new SessionKey(data);
        setEncryptKey(sk.toBase64());
        setEncrypt("");
        saveChanges();
        return "New Leaseset Encryption Key: " + sk.toBase64();
     }

    /**
     * Based on all provided data, create a set of configuration parameters 
     * suitable for use in a TunnelController.  This will replace (not add to)
     * any existing parameters, so this should return a comprehensive mapping.
     *
     */
    private Properties getConfig() {
        Properties config = new Properties();
        updateConfigGeneric(config);
        
        if (isClient(_type)) {
            // generic client stuff
            if (_port != null)
                config.setProperty("listenPort", _port);
            if (_reachableByOther != null)
                config.setProperty("interface", _reachableByOther);
            else if (_reachableBy != null)
                config.setProperty("interface", _reachableBy);
            else
                config.setProperty("interface", "");

            config.setProperty("sharedClient", _sharedClient + "");
            for (String p : _booleanClientOpts)
                config.setProperty("option." + p, "" + _booleanOptions.contains(p));
            for (String p : _otherClientOpts)
                if (_otherOptions.containsKey(p))
                    config.setProperty("option." + p, _otherOptions.get(p));
        } else {
            // generic server stuff
            if (_targetHost != null)
                config.setProperty("targetHost", _targetHost);
            if (_targetPort != null)
                config.setProperty("targetPort", _targetPort);
            for (String p : _booleanServerOpts)
                config.setProperty("option." + p, "" + _booleanOptions.contains(p));
            for (String p : _otherServerOpts)
                if (_otherOptions.containsKey(p))
                    config.setProperty("option." + p, _otherOptions.get(p));
        }

        if ("httpclient".equals(_type) || "connectclient".equals(_type)) {
            if (_proxyList != null)
                config.setProperty("proxyList", _proxyList);
        } else if ("ircclient".equals(_type) || "client".equals(_type) || "streamrclient".equals(_type)) {
            if (_targetDestination != null)
                config.setProperty("targetDestination", _targetDestination);
        } else if ("httpserver".equals(_type) || "httpbidirserver".equals(_type)) {
            if (_spoofedHost != null)
                config.setProperty("spoofedHost", _spoofedHost);
        }
        if ("httpbidirserver".equals(_type)) {
            if (_port != null)
                config.setProperty("listenPort", _port);
            if (_reachableByOther != null)
                config.setProperty("interface", _reachableByOther);
            else if (_reachableBy != null)
                config.setProperty("interface", _reachableBy);
            else if (_targetHost != null)
                config.setProperty("interface", _targetHost);
            else
                config.setProperty("interface", "");
        }
        return config;
    }
    
    private static final String _noShowOpts[] = {
        "inbound.length", "outbound.length", "inbound.lengthVariance", "outbound.lengthVariance",
        "inbound.backupQuantity", "outbound.backupQuantity", "inbound.quantity", "outbound.quantity",
        "inbound.nickname", "outbound.nickname", "i2p.streaming.connectDelay", "i2p.streaming.maxWindowSize"
        };
    private static final String _booleanClientOpts[] = {
        "i2cp.reduceOnIdle", "i2cp.closeOnIdle", "i2cp.newDestOnResume", "persistentClientKey", "i2cp.delayOpen"
        };
    private static final String _booleanServerOpts[] = {
        "i2cp.reduceOnIdle", "i2cp.encryptLeaseSet", "i2cp.enableAccessList"
        };
    private static final String _otherClientOpts[] = {
        "i2cp.reduceIdleTime", "i2cp.reduceQuantity", "i2cp.closeIdleTime"
        };
    private static final String _otherServerOpts[] = {
        "i2cp.reduceIdleTime", "i2cp.reduceQuantity", "i2cp.leaseSetKey", "i2cp.accessList"
        };
    protected static final Set _noShowSet = new HashSet();
    static {
        _noShowSet.addAll(Arrays.asList(_noShowOpts));
        _noShowSet.addAll(Arrays.asList(_booleanClientOpts));
        _noShowSet.addAll(Arrays.asList(_booleanServerOpts));
        _noShowSet.addAll(Arrays.asList(_otherClientOpts));
        _noShowSet.addAll(Arrays.asList(_otherServerOpts));
    }

    private void updateConfigGeneric(Properties config) {
        config.setProperty("type", _type);
        if (_name != null)
            config.setProperty("name", _name);
        if (_description != null)
            config.setProperty("description", _description);
        if (_i2cpHost != null)
            config.setProperty("i2cpHost", _i2cpHost);
        if ( (_i2cpPort != null) && (_i2cpPort.trim().length() > 0) ) {
            config.setProperty("i2cpPort", _i2cpPort);
        } else {
            config.setProperty("i2cpPort", "7654");
        }
        if (_privKeyFile != null)
            config.setProperty("privKeyFile", _privKeyFile);
        
        if (_customOptions != null) {
            StringTokenizer tok = new StringTokenizer(_customOptions);
            while (tok.hasMoreTokens()) {
                String pair = tok.nextToken();
                int eq = pair.indexOf('=');
                if ( (eq <= 0) || (eq >= pair.length()) )
                    continue;
                String key = pair.substring(0, eq);
                if (_noShowSet.contains(key))
                    continue;
                String val = pair.substring(eq+1);
                config.setProperty("option." + key, val);
            }
        }

        config.setProperty("startOnLoad", _startOnLoad + "");

        if (_tunnelQuantity != null) {
            config.setProperty("option.inbound.quantity", _tunnelQuantity);
            config.setProperty("option.outbound.quantity", _tunnelQuantity);
        }
        if (_tunnelDepth != null) {
            config.setProperty("option.inbound.length", _tunnelDepth);
            config.setProperty("option.outbound.length", _tunnelDepth);
        }
        if (_tunnelVariance != null) {
            config.setProperty("option.inbound.lengthVariance", _tunnelVariance);
            config.setProperty("option.outbound.lengthVariance", _tunnelVariance);
        }
        if (_tunnelBackupQuantity != null) {
            config.setProperty("option.inbound.backupQuantity", _tunnelBackupQuantity);
            config.setProperty("option.outbound.backupQuantity", _tunnelBackupQuantity);
        }
        if (_connectDelay)
            config.setProperty("option.i2p.streaming.connectDelay", "1000");
        else
            config.setProperty("option.i2p.streaming.connectDelay", "0");
        if (isClient(_type) && _sharedClient) {
            config.setProperty("option.inbound.nickname", CLIENT_NICKNAME);
            config.setProperty("option.outbound.nickname", CLIENT_NICKNAME);
        } else if (_name != null) {
            config.setProperty("option.inbound.nickname", _name);
            config.setProperty("option.outbound.nickname", _name);
        }
        if ("interactive".equals(_profile))
            // This was 1 which doesn't make much sense
            // The real way to make it interactive is to make the streaming lib
            // MessageInputStream flush faster but there's no option for that yet,
            // Setting it to 16 instead of the default but not sure what good that is either.
            config.setProperty("option.i2p.streaming.maxWindowSize", "16");
        else
            config.remove("option.i2p.streaming.maxWindowSize");
    }

    ///
    ///
    ///
    
    protected TunnelController getController(int tunnel) {
        if (tunnel < 0) return null;
        if (_group == null) return null;
        List controllers = _group.getControllers();
        if (controllers.size() > tunnel)
            return (TunnelController)controllers.get(tunnel); 
        else
            return null;
    }
    
    private String getMessages(List msgs) {
        StringBuilder buf = new StringBuilder(128);
        getMessages(msgs, buf);
        return buf.toString();
    }
    private void getMessages(List msgs, StringBuilder buf) {
        if (msgs == null) return;
        for (int i = 0; i < msgs.size(); i++) {
            buf.append((String)msgs.get(i)).append("\n");
        }
    }

    private String _(String key) {
        return Messages._(key, _context);
    }
}
