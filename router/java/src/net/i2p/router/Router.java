package net.i2p.router;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.Writer;
import java.text.DecimalFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TimeZone;
import java.util.TreeSet;

import net.i2p.CoreVersion;
import net.i2p.crypto.DHSessionKeyBuilder;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.RouterInfo;
import net.i2p.data.SigningPrivateKey;
import net.i2p.data.i2np.GarlicMessage;
//import net.i2p.data.i2np.TunnelMessage;
import net.i2p.router.message.GarlicMessageHandler;
//import net.i2p.router.message.TunnelMessageHandler;
import net.i2p.router.startup.StartupJob;
import net.i2p.stat.Rate;
import net.i2p.stat.RateStat;
import net.i2p.util.FileUtil;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;

/**
 * Main driver for the router.
 *
 */
public class Router {
    private Log _log;
    private RouterContext _context;
    private Properties _config;
    private String _configFilename;
    private RouterInfo _routerInfo;
    private long _started;
    private boolean _higherVersionSeen;
    private SessionKeyPersistenceHelper _sessionKeyPersistenceHelper;
    private boolean _killVMOnEnd;
    private boolean _isAlive;
    private int _gracefulExitCode;
    private I2PThread.OOMEventListener _oomListener;
    private ShutdownHook _shutdownHook;
    private I2PThread _gracefulShutdownDetector;
    private Set _shutdownTasks;
    
    public final static String PROP_CONFIG_FILE = "router.configLocation";
    
    /** let clocks be off by 1 minute */
    public final static long CLOCK_FUDGE_FACTOR = 1*60*1000; 

    /** used to differentiate routerInfo files on different networks */
    public static final int NETWORK_ID = 1;
    
    public final static String PROP_INFO_FILENAME = "router.info.location";
    public final static String PROP_INFO_FILENAME_DEFAULT = "router.info";
    public final static String PROP_KEYS_FILENAME = "router.keys.location";
    public final static String PROP_KEYS_FILENAME_DEFAULT = "router.keys";
    public final static String PROP_SHUTDOWN_IN_PROGRESS = "__shutdownInProgress";
        
    static {
        // grumble about sun's java caching DNS entries *forever* by default
        // so lets just keep 'em for a minute
        System.setProperty("sun.net.inetaddr.ttl", "60");
        System.setProperty("networkaddress.cache.ttl", "60");
        // until we handle restricted routes and/or all peers support v6, try v4 first
        System.setProperty("java.net.preferIPv4Stack", "true");
        System.setProperty("http.agent", "I2P");
        // (no need for keepalive)
        System.setProperty("http.keepAlive", "false");
        System.setProperty("user.timezone", "GMT");
        // just in case, lets make it explicit...
        TimeZone.setDefault(TimeZone.getTimeZone("GMT"));
    }
    
    public Router() { this(null, null); }
    public Router(Properties envProps) { this(null, envProps); }
    public Router(String configFilename) { this(configFilename, null); }
    public Router(String configFilename, Properties envProps) {
        if (!beginMarkingLiveliness(envProps)) {
            System.err.println("ERROR: There appears to be another router already running!");
            System.err.println("       Please make sure to shut down old instances before starting up");
            System.err.println("       a new one.  If you are positive that no other instance is running,");
            System.err.println("       please delete the file " + getPingFile(envProps));
            System.exit(-1);
        }

        _config = new Properties();
        _context = new RouterContext(this, envProps);
        if (configFilename == null)
            _configFilename = _context.getProperty(PROP_CONFIG_FILE, "router.config");
        else
            _configFilename = configFilename;
        _routerInfo = null;
        _higherVersionSeen = false;
        _log = _context.logManager().getLog(Router.class);
        _log.info("New router created with config file " + _configFilename);
        _sessionKeyPersistenceHelper = new SessionKeyPersistenceHelper(_context);
        _killVMOnEnd = true;
        _oomListener = new I2PThread.OOMEventListener() { 
            public void outOfMemory(OutOfMemoryError oom) { 
                _log.log(Log.CRIT, "Thread ran out of memory", oom);
                for (int i = 0; i < 5; i++) { // try this 5 times, in case it OOMs
                    try { 
                        _log.log(Log.CRIT, "free mem: " + Runtime.getRuntime().freeMemory() + 
                                           " total mem: " + Runtime.getRuntime().totalMemory());
                        break; // w00t
                    } catch (OutOfMemoryError oome) {
                        // gobble
                    }
                }
                shutdown(EXIT_OOM); 
            }
        };
        _shutdownHook = new ShutdownHook();
        _gracefulShutdownDetector = new I2PThread(new GracefulShutdown());
        _gracefulShutdownDetector.setDaemon(true);
        _gracefulShutdownDetector.setName("Graceful shutdown hook");
        _gracefulShutdownDetector.start();
        
        I2PThread watchdog = new I2PThread(new RouterWatchdog(_context));
        watchdog.setName("RouterWatchdog");
        watchdog.setDaemon(true);
        watchdog.start();
        
        _shutdownTasks = new HashSet(0);
    }
    
    /**
     * Configure the router to kill the JVM when the router shuts down, as well
     * as whether to explicitly halt the JVM during the hard fail process.
     *
     */
    public void setKillVMOnEnd(boolean shouldDie) { _killVMOnEnd = shouldDie; }
    public boolean getKillVMOnEnd() { return _killVMOnEnd; }
    
    public String getConfigFilename() { return _configFilename; }
    public void setConfigFilename(String filename) { _configFilename = filename; }
    
    public String getConfigSetting(String name) { 
        synchronized (_config) {
            return _config.getProperty(name); 
        }
    }
    public void setConfigSetting(String name, String value) { 
        synchronized (_config) {
            _config.setProperty(name, value); 
        }
    }
    public void removeConfigSetting(String name) { 
        synchronized (_config) {
            _config.remove(name); 
        }
    }
    public Set getConfigSettings() { 
        synchronized (_config) {
            return new HashSet(_config.keySet()); 
        }
    }
    public Properties getConfigMap() { 
        Properties rv = new Properties();
        synchronized (_config) {
            rv.putAll(_config); 
        }
        return rv;
    }
    
    public RouterInfo getRouterInfo() { return _routerInfo; }
    public void setRouterInfo(RouterInfo info) { 
        _routerInfo = info; 
        if (info != null)
            _context.jobQueue().addJob(new PersistRouterInfoJob());
    }
    
    /**
     * True if the router has tried to communicate with another router who is running a higher
     * incompatible protocol version.  
     *
     */
    public boolean getHigherVersionSeen() { return _higherVersionSeen; }
    public void setHigherVersionSeen(boolean seen) { _higherVersionSeen = seen; }
    
    public long getWhenStarted() { return _started; }
    /** wall clock uptime */
    public long getUptime() { return _context.clock().now() - _context.clock().getOffset() - _started; }
    
    public RouterContext getContext() { return _context; }
    
    void runRouter() {
        _isAlive = true;
        _started = _context.clock().now();
        Runtime.getRuntime().addShutdownHook(_shutdownHook);
        I2PThread.addOOMEventListener(_oomListener);
        
        _context.keyManager().startup();
        
        readConfig();
        
        setupHandlers();
        _context.messageValidator().startup();
        _context.tunnelDispatcher().startup();
        _context.inNetMessagePool().startup();
        startupQueue();
        _context.jobQueue().addJob(new CoalesceStatsJob());
        _context.jobQueue().addJob(new UpdateRoutingKeyModifierJob());
        warmupCrypto();
        _sessionKeyPersistenceHelper.startup();
        //_context.adminManager().startup();
        
        // let the timestamper get us sync'ed
        long before = System.currentTimeMillis();
        _context.clock().getTimestamper().waitForInitialization();
        long waited = System.currentTimeMillis() - before;
        if (_log.shouldLog(Log.INFO))
            _log.info("Waited " + waited + "ms to initialize");
        
        _context.jobQueue().addJob(new StartupJob(_context));
    }
    
    public void readConfig() {
        String f = getConfigFilename();
        Properties config = getConfig(_context, f);
        for (Iterator iter = config.keySet().iterator(); iter.hasNext(); ) {
            String name = (String)iter.next();
            String val = config.getProperty(name);
            setConfigSetting(name, val);
        }
    }
    
    private static Properties getConfig(RouterContext ctx, String filename) {
        Log log = ctx.logManager().getLog(Router.class);
        if (log.shouldLog(Log.DEBUG))
            log.debug("Config file: " + filename);
        Properties props = new Properties();
        FileInputStream fis = null;
        try {
            File f = new File(filename);
            if (f.canRead()) {
                DataHelper.loadProps(props, f);
                // dont be a wanker
                props.remove(PROP_SHUTDOWN_IN_PROGRESS);
            } else {
                log.warn("Configuration file " + filename + " does not exist");
            }
        } catch (Exception ioe) {
            log.error("Error loading the router configuration from " + filename, ioe);
        } finally {
            if (fis != null) try { fis.close(); } catch (IOException ioe) {}
        }
        return props;
    }
    
    public boolean isAlive() { return _isAlive; }
    
    /**
     * Rebuild and republish our routerInfo since something significant 
     * has changed.
     */
    public void rebuildRouterInfo() {
        if (_log.shouldLog(Log.INFO))
            _log.info("Rebuilding new routerInfo");
        
        RouterInfo ri = null;
        if (_routerInfo != null)
            ri = new RouterInfo(_routerInfo);
        else
            ri = new RouterInfo();
        
        try {
            ri.setPublished(_context.clock().now());
            Properties stats = _context.statPublisher().publishStatistics();
            stats.setProperty(RouterInfo.PROP_NETWORK_ID, NETWORK_ID+"");
            ri.setOptions(stats);
            ri.setAddresses(_context.commSystem().createAddresses());
            SigningPrivateKey key = _context.keyManager().getSigningPrivateKey();
            if (key == null) {
                _log.log(Log.CRIT, "Internal error - signing private key not known?  wtf");
                return;
            }
            ri.sign(key);
            setRouterInfo(ri);
            try {
                _context.netDb().publish(ri);
            } catch (IllegalArgumentException iae) {
                _log.log(Log.CRIT, "Local router info is invalid?  rebuilding a new identity", iae);
                rebuildNewIdentity();
            }
        } catch (DataFormatException dfe) {
            _log.log(Log.CRIT, "Internal error - unable to sign our own address?!", dfe);
        }
    }

    /**
     * Ugly list of files that we need to kill if we are building a new identity
     *
     */
    private static final String _rebuildFiles[] = new String[] { "router.info", 
                                                                 "router.keys",
                                                                 "connectionTag.keys",
                                                                 "keyBackup/privateEncryption.key",
                                                                 "keyBackup/privateSigning.key",
                                                                 "keyBackup/publicEncryption.key",
                                                                 "keyBackup/publicSigning.key",
                                                                 "sessionKeys.dat" };
    /**
     * Rebuild a new identity the hard way - delete all of our old identity 
     * files, then reboot the router.
     *
     */
    public void rebuildNewIdentity() {
        for (int i = 0; i < _rebuildFiles.length; i++) {
            File f = new File(_rebuildFiles[i]);
            if (f.exists()) {
                boolean removed = f.delete();
                if (removed)
                    System.out.println("INFO:  Removing old identity file: " + _rebuildFiles[i]);
                else
                    System.out.println("ERROR: Could not remove old identity file: " + _rebuildFiles[i]);
            }
        }
        System.out.println("INFO:  Restarting the router after removing any old identity files");
        // hard and ugly
        System.exit(EXIT_HARD_RESTART);
    }
    
    /**
     * coalesce the stats framework every minute
     *
     */
    private final class CoalesceStatsJob extends JobImpl {
        public CoalesceStatsJob() { 
            super(Router.this._context); 
            Router.this._context.statManager().createRateStat("bw.receiveBps", "How fast we receive data", "Bandwidth", new long[] { 60*1000, 5*60*1000, 60*60*1000 });
            Router.this._context.statManager().createRateStat("bw.sendBps", "How fast we send data", "Bandwidth", new long[] { 60*1000, 5*60*1000, 60*60*1000 });
            Router.this._context.statManager().createRateStat("router.activePeers", "How many peers we are actively talking with", "Throttle", new long[] { 5*60*1000, 60*60*1000 });
            Router.this._context.statManager().createRateStat("router.highCapacityPeers", "How many high capacity peers we know", "Throttle", new long[] { 5*60*1000, 60*60*1000 });
            Router.this._context.statManager().createRateStat("router.fastPeers", "How many fast peers we know", "Throttle", new long[] { 5*60*1000, 60*60*1000 });
        }
        public String getName() { return "Coalesce stats"; }
        public void runJob() {
            Router.this._context.statManager().coalesceStats();
            
            RateStat receiveRate = _context.statManager().getRate("transport.receiveMessageSize");
            if (receiveRate != null) {
                Rate rate = receiveRate.getRate(60*1000);
                if (rate != null) { 
                    double bytes = rate.getLastTotalValue();
                    double bps = (bytes*1000.0d)/(rate.getPeriod()*1024.0d); 
                    Router.this._context.statManager().addRateData("bw.receiveBps", (long)bps, 60*1000);
                }
            }

            RateStat sendRate = _context.statManager().getRate("transport.sendMessageSize");
            if (sendRate != null) {
                Rate rate = sendRate.getRate(60*1000);
                if (rate != null) {
                    double bytes = rate.getLastTotalValue();
                    double bps = (bytes*1000.0d)/(rate.getPeriod()*1024.0d); 
                    Router.this._context.statManager().addRateData("bw.sendBps", (long)bps, 60*1000);
                }
            }
            
            int active = Router.this._context.commSystem().countActivePeers();
            Router.this._context.statManager().addRateData("router.activePeers", active, 60*1000);
            
            int fast = Router.this._context.profileOrganizer().countFastPeers();
            Router.this._context.statManager().addRateData("router.fastPeers", fast, 60*1000);
            
            int highCap = Router.this._context.profileOrganizer().countHighCapacityPeers();
            Router.this._context.statManager().addRateData("router.highCapacityPeers", highCap, 60*1000);

            requeue(60*1000);
        }
    }
    
    /**
     * Update the routing Key modifier every day at midnight (plus on startup).
     * This is done here because we want to make sure the key is updated before anyone
     * uses it.
     */
    private final class UpdateRoutingKeyModifierJob extends JobImpl {
        private Calendar _cal = new GregorianCalendar(TimeZone.getTimeZone("GMT"));
        public UpdateRoutingKeyModifierJob() { super(Router.this._context); }
        public String getName() { return "Update Routing Key Modifier"; }
        public void runJob() {
            Router.this._context.routingKeyGenerator().generateDateBasedModData();
            requeue(getTimeTillMidnight());
        }
        private long getTimeTillMidnight() {
            long now = Router.this._context.clock().now();
            _cal.setTime(new Date(now));
            _cal.add(Calendar.DATE, 1);
            _cal.set(Calendar.HOUR_OF_DAY, 0);
            _cal.set(Calendar.MINUTE, 0);
            _cal.set(Calendar.SECOND, 0);
            _cal.set(Calendar.MILLISECOND, 0);
            long then = _cal.getTime().getTime();
            _log.debug("Time till midnight: " + (then-now) + "ms");
            if (then - now <= 60*1000) {
                // everyone wave at kaffe.
                // "Hi Kaffe"
                return 60*1000;
            } else {
                return then - now;
            }
        }
    }
    
    private void warmupCrypto() {
        _context.random().nextBoolean();
        new DHSessionKeyBuilder(); // load the class so it starts the precalc process
    }
    
    private void startupQueue() {
        _context.jobQueue().runQueue(1);
    }
    
    private void setupHandlers() {
        _context.inNetMessagePool().registerHandlerJobBuilder(GarlicMessage.MESSAGE_TYPE, new GarlicMessageHandler(_context));
        //_context.inNetMessagePool().registerHandlerJobBuilder(TunnelMessage.MESSAGE_TYPE, new TunnelMessageHandler(_context));
    }
    
    public void renderStatusHTML(Writer out) throws IOException {
        out.write("<h1>Router console</h1>\n" +
                   "<i><a href=\"/oldconsole.jsp\">console</a> | <a href=\"/oldstats.jsp\">stats</a></i><br>\n" +
                   "<form action=\"/oldconsole.jsp\">" +
                   "<select name=\"go\" onChange='location.href=this.value'>" +
                   "<option value=\"/oldconsole.jsp#bandwidth\">Bandwidth</option>\n" +
                   "<option value=\"/oldconsole.jsp#clients\">Clients</option>\n" +
                   "<option value=\"/oldconsole.jsp#transports\">Transports</option>\n" +
                   "<option value=\"/oldconsole.jsp#profiles\">Peer Profiles</option>\n" +
                   "<option value=\"/oldconsole.jsp#tunnels\">Tunnels</option>\n" +
                   "<option value=\"/oldconsole.jsp#jobs\">Jobs</option>\n" +
                   "<option value=\"/oldconsole.jsp#shitlist\">Shitlist</option>\n" +
                   "<option value=\"/oldconsole.jsp#pending\">Pending messages</option>\n" +
                   "<option value=\"/oldconsole.jsp#netdb\">Network Database</option>\n" +
                   "<option value=\"/oldconsole.jsp#logs\">Log messages</option>\n" +
                   "</select> <input type=\"submit\" value=\"GO\" /> </form>" +
                   "<hr />\n");

        StringBuffer buf = new StringBuffer(32*1024);
        
        if ( (_routerInfo != null) && (_routerInfo.getIdentity() != null) )
            buf.append("<b>Router: </b> ").append(_routerInfo.getIdentity().getHash().toBase64()).append("<br />\n");
        buf.append("<b>As of: </b> ").append(new Date(_context.clock().now())).append(" (uptime: ").append(DataHelper.formatDuration(getUptime())).append(") <br />\n");
        buf.append("<b>Started on: </b> ").append(new Date(getWhenStarted())).append("<br />\n");
        buf.append("<b>Clock offset: </b> ").append(_context.clock().getOffset()).append("ms (OS time: ").append(new Date(_context.clock().now() - _context.clock().getOffset())).append(")<br />\n");
        long tot = Runtime.getRuntime().totalMemory()/1024;
        long free = Runtime.getRuntime().freeMemory()/1024;
        buf.append("<b>Memory:</b> In use: ").append((tot-free)).append("KB Free: ").append(free).append("KB <br />\n"); 
        buf.append("<b>Version:</b> Router: ").append(RouterVersion.VERSION).append(" / SDK: ").append(CoreVersion.VERSION).append("<br />\n"); 
        if (_higherVersionSeen) 
            buf.append("<b><font color=\"red\">HIGHER VERSION SEEN</font><b> - please <a href=\"http://www.i2p.net/\">check</a> to see if there is a new release out<br />\n");

        buf.append("<hr /><a name=\"bandwidth\"> </a><h2>Bandwidth</h2>\n");
        long sent = _context.bandwidthLimiter().getTotalAllocatedOutboundBytes();
        long received = _context.bandwidthLimiter().getTotalAllocatedInboundBytes();
        buf.append("<ul>");

        buf.append("<li> ").append(sent).append(" bytes sent, ");
        buf.append(received).append(" bytes received</li>");

        long notSent = _context.bandwidthLimiter().getTotalWastedOutboundBytes();
        long notReceived = _context.bandwidthLimiter().getTotalWastedInboundBytes();

        buf.append("<li> ").append(notSent).append(" bytes outbound bytes unused, ");
        buf.append(notReceived).append(" bytes inbound bytes unused</li>");

        DecimalFormat fmt = new DecimalFormat("##0.00");

        // we use the unadjusted time, since thats what getWhenStarted is based off
        long lifetime = _context.clock().now()-_context.clock().getOffset() - getWhenStarted();
        lifetime /= 1000;
        if ( (sent > 0) && (received > 0) ) {
            double sendKBps = sent / (lifetime*1024.0);
            double receivedKBps = received / (lifetime*1024.0);
            buf.append("<li>Lifetime rate: ");
            buf.append(fmt.format(sendKBps)).append("KBps sent ");
            buf.append(fmt.format(receivedKBps)).append("KBps received");
            buf.append("</li>");
        }
        if ( (notSent > 0) && (notReceived > 0) ) {
            double notSendKBps = notSent / (lifetime*1024.0);
            double notReceivedKBps = notReceived / (lifetime*1024.0);
            buf.append("<li>Lifetime unused rate: ");
            buf.append(fmt.format(notSendKBps)).append("KBps outbound unused  ");
            buf.append(fmt.format(notReceivedKBps)).append("KBps inbound unused");
            buf.append("</li>");
        } 
        
        RateStat sendRate = _context.statManager().getRate("transport.sendMessageSize");
        for (int i = 0; i < sendRate.getPeriods().length; i++) {
            Rate rate = sendRate.getRate(sendRate.getPeriods()[i]);
            double bytes = rate.getLastTotalValue();
            long ms = rate.getLastTotalEventTime() + rate.getLastTotalEventTime();
            if (ms <= 0) {
                bytes = 0;
                ms = 1;
            }
            buf.append("<li>");
            buf.append(DataHelper.formatDuration(rate.getPeriod())).append(" instantaneous send avg: ");
            double bps = bytes*1000.0d/ms;
            if (bps > 2048) {
                bps /= 1024.0d;
                buf.append(fmt.format(bps)).append(" KBps");
            } else {
                buf.append(fmt.format(bps)).append(" Bps");
            }
            buf.append(" over ").append((long)bytes).append(" bytes");
            buf.append("</li><li>");
            buf.append(DataHelper.formatDuration(rate.getPeriod())).append(" period send avg: ");
            bps = bytes*1000.0d/(rate.getPeriod()); 
            if (bps > 2048) {
                bps /= 1024.0d;
                buf.append(fmt.format(bps)).append(" KBps");
            } else {
                buf.append(fmt.format(bps)).append(" Bps");
            }
            buf.append(" over ").append((long)bytes).append(" bytes");
            buf.append("</li>");
        }

        RateStat receiveRate = _context.statManager().getRate("transport.receiveMessageSize");
        for (int i = 0; i < receiveRate.getPeriods().length; i++) {
            Rate rate = receiveRate.getRate(receiveRate.getPeriods()[i]);
            double bytes = rate.getLastTotalValue();
            long ms = rate.getLastTotalEventTime();
            if (ms <= 0) {
                bytes = 0;
                ms = 1;
            }
            buf.append("<li>");
            buf.append(DataHelper.formatDuration(rate.getPeriod())).append(" instantaneous receive avg: ");
            double bps = bytes*1000.0d/ms;
            if (bps > 2048) {
                bps /= 1024.0d;
                buf.append(fmt.format(bps)).append(" KBps ");
            } else {
                buf.append(fmt.format(bps)).append(" Bps ");
            }
            buf.append(" over ").append((long)bytes).append(" bytes");
            buf.append("</li><li>");
            buf.append(DataHelper.formatDuration(rate.getPeriod())).append(" period receive avg: ");
            bps = bytes*1000.0d/(rate.getPeriod());
            if (bps > 2048) {
                bps /= 1024.0d;
                buf.append(fmt.format(bps)).append(" KBps");
            } else {
                buf.append(fmt.format(bps)).append(" Bps");
            }
            buf.append(" over ").append((long)bytes).append(" bytes");
            buf.append("</li>");
        }

        buf.append("</ul>\n");
        buf.append("<i>Instantaneous averages count how fast the transfers go when we're trying to transfer data, ");
        buf.append("while period averages count how fast the transfers go across the entire period, even when we're not ");
        buf.append("trying to transfer data.  Lifetime averages count how many elephants there are on the moon [like anyone reads this text]</i>");
        buf.append("\n");
        
        out.write(buf.toString());
        
        _context.bandwidthLimiter().renderStatusHTML(out);

        out.write("<hr /><a name=\"clients\"> </a>\n");
        
        _context.clientManager().renderStatusHTML(out);
        
        out.write("\n<hr /><a name=\"transports\"> </a>\n");
        
        _context.commSystem().renderStatusHTML(out);
        
        out.write("\n<hr /><a name=\"profiles\"> </a>\n");
        
        _context.peerManager().renderStatusHTML(out);
        
        out.write("\n<hr /><a name=\"tunnels\"> </a>\n");
        
        _context.tunnelManager().renderStatusHTML(out);
        
        out.write("\n<hr /><a name=\"jobs\"> </a>\n");
        
        _context.jobQueue().renderStatusHTML(out);
        
        out.write("\n<hr /><a name=\"shitlist\"> </a>\n");
        
        _context.shitlist().renderStatusHTML(out);
        
        out.write("\n<hr /><a name=\"pending\"> </a>\n");
        
        _context.messageRegistry().renderStatusHTML(out);
        
        out.write("\n<hr /><a name=\"netdb\"> </a>\n");
        
        _context.netDb().renderStatusHTML(out);
        
        buf.setLength(0);
        buf.append("\n<hr /><a name=\"logs\"> </a>\n");	
        List msgs = _context.logManager().getBuffer().getMostRecentMessages();
        buf.append("\n<h2>Most recent console messages:</h2><table border=\"1\">\n");
        for (Iterator iter = msgs.iterator(); iter.hasNext(); ) {
            String msg = (String)iter.next();
            buf.append("<tr><td valign=\"top\" align=\"left\"><pre>");
            appendLogMessage(buf, msg);
            buf.append("</pre></td></tr>\n");
        }
        buf.append("</table>\n");
        out.write(buf.toString());
        out.flush();
    }
    
    private static int MAX_MSG_LENGTH = 120;
    private static final void appendLogMessage(StringBuffer buf, String msg) {
        // disable this code for the moment because i think it
        // looks ugly (on the router console)
        if (true) {
            buf.append(msg);
            return;
        }
        if (msg.length() < MAX_MSG_LENGTH) {
            buf.append(msg);
            return;
        }
        int newline = msg.indexOf('\n');
        int len = msg.length();
        while ( (msg != null) && (len > 0) ) {
            if (newline < 0) {
                // last line, trim if necessary
                if (len > MAX_MSG_LENGTH)
                    msg = msg.substring(len-MAX_MSG_LENGTH);
                buf.append(msg);
                return;
            } else if (newline >= MAX_MSG_LENGTH) {
                // not the last line, but too long.  
                // trim the first few chars 
                String cur = msg.substring(newline-MAX_MSG_LENGTH, newline).trim();
                msg = msg.substring(newline+1);
                if (cur.length() > 0)
                    buf.append(cur).append('\n');
            } else {
                // newline <= max_msg_length, so its not the last,
                // and not too long
                String cur = msg.substring(0, newline).trim();
                msg = msg.substring(newline+1);
                if (cur.length() > 0)
                    buf.append(cur).append('\n');
            }
            newline = msg.indexOf('\n');
            len = msg.length();
        }
    }
    
    /** main-ish method for testing appendLogMessage */
    private static final void testAppendLog() {
        StringBuffer buf = new StringBuffer(1024);
        Router.appendLogMessage(buf, "hi\nhow are you\nh0h0h0");
        System.out.println("line: [" + buf.toString() + "]");
        buf.setLength(0);
        Router.appendLogMessage(buf, "\nfine thanks\nh0h0h0");
        System.out.println("line: [" + buf.toString() + "]");
        buf.setLength(0);
        Router.appendLogMessage(buf, "liar\nblah blah\n");
        System.out.println("line: [" + buf.toString() + "]");
        buf.setLength(0);
        Router.appendLogMessage(buf, "\n");
        System.out.println("line: [" + buf.toString() + "]");
        buf.setLength(0);
        Router.appendLogMessage(buf, "");
        System.out.println("line: [" + buf.toString() + "]");
        buf.setLength(0);
        Router.appendLogMessage(buf, ".........10........20........30........40........50........6");
        System.out.println("line: [" + buf.toString() + "]");
        buf.setLength(0);
        Router.appendLogMessage(buf, ".........10........\n20........30........40........50........6");
        System.out.println("line: [" + buf.toString() + "]");
        buf.setLength(0);
        Router.appendLogMessage(buf, ".........10........20\n........30........40........50........6");
        System.out.println("line: [" + buf.toString() + "]");
        buf.setLength(0);
        Router.appendLogMessage(buf, ".........10.......\n.20........30........40........50........6");
        System.out.println("line: [" + buf.toString() + "]");
        buf.setLength(0);
        Router.appendLogMessage(buf, "\n.........10........20........30........40........50........6");
        System.out.println("line: [" + buf.toString() + "]");
        buf.setLength(0);
    }
    
    public void addShutdownTask(Runnable task) {
        synchronized (_shutdownTasks) {
            _shutdownTasks.add(task);
        }
    }
    
    public static final int EXIT_GRACEFUL = 2;
    public static final int EXIT_HARD = 3;
    public static final int EXIT_OOM = 10;
    public static final int EXIT_HARD_RESTART = 4;
    public static final int EXIT_GRACEFUL_RESTART = 5;
    
    public void shutdown(int exitCode) {
        _isAlive = false;
        I2PThread.removeOOMEventListener(_oomListener);
        try { _context.jobQueue().shutdown(); } catch (Throwable t) { _log.log(Log.CRIT, "Error shutting down the job queue", t); }
        //try { _context.adminManager().shutdown(); } catch (Throwable t) { _log.log(Log.CRIT, "Error shutting down the admin manager", t); }        
        try { _context.statPublisher().shutdown(); } catch (Throwable t) { _log.log(Log.CRIT, "Error shutting down the stats manager", t); }
        try { _context.clientManager().shutdown(); } catch (Throwable t) { _log.log(Log.CRIT, "Error shutting down the client manager", t); }
        try { _context.tunnelManager().shutdown(); } catch (Throwable t) { _log.log(Log.CRIT, "Error shutting down the tunnel manager", t); }
        try { _context.tunnelDispatcher().shutdown(); } catch (Throwable t) { _log.log(Log.CRIT, "Error shutting down the tunnel dispatcher", t); }
        try { _context.netDb().shutdown(); } catch (Throwable t) { _log.log(Log.CRIT, "Error shutting down the networkDb", t); }
        try { _context.commSystem().shutdown(); } catch (Throwable t) { _log.log(Log.CRIT, "Error shutting down the comm system", t); }
        try { _context.peerManager().shutdown(); } catch (Throwable t) { _log.log(Log.CRIT, "Error shutting down the peer manager", t); }
        try { _context.messageRegistry().shutdown(); } catch (Throwable t) { _log.log(Log.CRIT, "Error shutting down the message registry", t); }
        try { _context.messageValidator().shutdown(); } catch (Throwable t) { _log.log(Log.CRIT, "Error shutting down the message validator", t); }
        try { _context.inNetMessagePool().shutdown(); } catch (Throwable t) { _log.log(Log.CRIT, "Error shutting down the inbound net pool", t); }
        try { _sessionKeyPersistenceHelper.shutdown(); } catch (Throwable t) { _log.log(Log.CRIT, "Error shutting down the session key manager", t); }
        RouterContext.listContexts().remove(_context);
        dumpStats();
        try {
            for (Iterator iter = _shutdownTasks.iterator(); iter.hasNext(); ) {
                Runnable task = (Runnable)iter.next();
                task.run();
            }
        } catch (Throwable t) {
            _log.log(Log.CRIT, "Error running shutdown task", t);
        }
        _log.log(Log.CRIT, "Shutdown(" + exitCode + ") complete", new Exception("Shutdown"));
        try { _context.logManager().shutdown(); } catch (Throwable t) { }
        File f = new File(getPingFile());
        f.delete();
        if (_killVMOnEnd) {
            try { Thread.sleep(1000); } catch (InterruptedException ie) {}
            Runtime.getRuntime().halt(exitCode);
        }
    }
    
    /**
     * Call this if we want the router to kill itself as soon as we aren't 
     * participating in any more tunnels (etc).  This will not block and doesn't
     * guarantee any particular time frame for shutting down.  To shut the 
     * router down immediately, use {@link #shutdown}.  If you want to cancel
     * the graceful shutdown (prior to actual shutdown ;), call 
     * {@link #cancelGracefulShutdown}.
     *
     */
    public void shutdownGracefully() {
        shutdownGracefully(EXIT_GRACEFUL);
    }
    public void shutdownGracefully(int exitCode) {
        _gracefulExitCode = exitCode;
        _config.setProperty(PROP_SHUTDOWN_IN_PROGRESS, "true");
        synchronized (_gracefulShutdownDetector) {
            _gracefulShutdownDetector.notifyAll();
        }
    }
    
    /**
     * Cancel any prior request to shut the router down gracefully.
     *
     */
    public void cancelGracefulShutdown() {
        _config.remove(PROP_SHUTDOWN_IN_PROGRESS);
        synchronized (_gracefulShutdownDetector) {
            _gracefulShutdownDetector.notifyAll();
        }        
    }
    
    public boolean gracefulShutdownInProgress() {
        return (null != _config.getProperty(PROP_SHUTDOWN_IN_PROGRESS));
    }
    /** How long until the graceful shutdown will kill us?  */
    public long getShutdownTimeRemaining() {
        long exp = _context.tunnelManager().getLastParticipatingExpiration();
        if (exp < 0)
            return -1;
        else
            return exp + 2*CLOCK_FUDGE_FACTOR - _context.clock().now();
    }
    
    /**
     * Simple thread that sits and waits forever, managing the
     * graceful shutdown "process" (describing it would take more text
     * than just reading the code...)
     *
     */
    private class GracefulShutdown implements Runnable {
        public void run() {
            while (true) {
                boolean shutdown = (null != _config.getProperty(PROP_SHUTDOWN_IN_PROGRESS));
                if (shutdown) {
                    if (_context.tunnelManager().getParticipatingCount() <= 0) {
                        if (_log.shouldLog(Log.CRIT))
                            _log.log(Log.CRIT, "Graceful shutdown progress - no more tunnels, safe to die");
                        shutdown(_gracefulExitCode);
                        return;
                    } else {
                        try {
                            synchronized (Thread.currentThread()) {
                                Thread.currentThread().wait(10*1000);
                            }
                        } catch (InterruptedException ie) {}
                    }
                } else {
                    try {
                        synchronized (Thread.currentThread()) {
                            Thread.currentThread().wait();
                        }
                    } catch (InterruptedException ie) {}
                }
            }
        }
    }
    
    /**
     * Save the current config options (returning true if save was 
     * successful, false otherwise)
     *
     */
    public boolean saveConfig() {
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(_configFilename);
            TreeSet ordered = new TreeSet(_config.keySet());
            StringBuffer buf = new StringBuffer(8*1024);
            for (Iterator iter = ordered.iterator() ; iter.hasNext(); ) {
                String key = (String)iter.next();
                String val = _config.getProperty(key);
                buf.append(key).append('=').append(val).append('\n');
            }
            fos.write(buf.toString().getBytes());
        } catch (IOException ioe) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Error saving the config to " + _configFilename, ioe);
            return false;
        } finally {
            if (fos != null) try { fos.close(); } catch (IOException ioe) {}
        }
        
        return true;
    }
    
    public void restart() {
        _isAlive = false;
        
        try { _context.commSystem().restart(); } catch (Throwable t) { _log.log(Log.CRIT, "Error restarting the comm system", t); }
        //try { _context.adminManager().restart(); } catch (Throwable t) { _log.log(Log.CRIT, "Error restarting the client manager", t); }
        try { _context.clientManager().restart(); } catch (Throwable t) { _log.log(Log.CRIT, "Error restarting the client manager", t); }
        try { _context.tunnelManager().restart(); } catch (Throwable t) { _log.log(Log.CRIT, "Error restarting the tunnel manager", t); }
        try { _context.peerManager().restart(); } catch (Throwable t) { _log.log(Log.CRIT, "Error restarting the peer manager", t); }
        try { _context.netDb().restart(); } catch (Throwable t) { _log.log(Log.CRIT, "Error restarting the networkDb", t); }
        
        //try { _context.jobQueue().restart(); } catch (Throwable t) { _log.log(Log.CRIT, "Error restarting the job queue", t); }
        
        _log.log(Log.CRIT, "Restart teardown complete... ");
        try { Thread.sleep(10*1000); } catch (InterruptedException ie) {}
        
        _log.log(Log.CRIT, "Restarting...");
        
        _isAlive = true;
        _started = _context.clock().now();
        
        _log.log(Log.CRIT, "Restart complete");
    }
    
    private void dumpStats() {
        //_log.log(Log.CRIT, "Lifetime stats:\n\n" + StatsGenerator.generateStatsPage());
    }
    
    public static void main(String args[]) {
        System.out.println("Starting I2P " + RouterVersion.VERSION + "-" + RouterVersion.BUILD);
        System.out.println(RouterVersion.ID);
        installUpdates();
        verifyWrapperConfig();
        Router r = new Router();
        if ( (args != null) && (args.length == 1) && ("rebuild".equals(args[0])) ) {
            r.rebuildNewIdentity();
        } else {
            r.runRouter();
        }
    }
    
    private static final String UPDATE_FILE = "i2pupdate.zip";
    
    private static void installUpdates() {
        File updateFile = new File(UPDATE_FILE);
        if (updateFile.exists()) {
            System.out.println("INFO: Update file exists [" + UPDATE_FILE + "] - installing");
            boolean ok = FileUtil.extractZip(updateFile, new File("."));
            if (ok)
                System.out.println("INFO: Update installed");
            else
                System.out.println("ERROR: Update failed!");
            boolean deleted = updateFile.delete();
            if (!deleted) {
                System.out.println("ERROR: Unable to delete the update file!");
                updateFile.deleteOnExit();
            }
            System.out.println("INFO: Restarting after update");
            System.exit(EXIT_HARD_RESTART);
        }
    }
    
    private static void verifyWrapperConfig() {
        File cfgUpdated = new File("wrapper.config.updated");
        if (cfgUpdated.exists()) {
            cfgUpdated.delete();
            System.out.println("INFO: Wrapper config updated, but the service wrapper requires you to manually restart");
            System.out.println("INFO: Shutting down the router - please rerun it!");
            System.exit(EXIT_HARD);
        }
    }
    
    private static String getPingFile(Properties envProps) {
        if (envProps != null) 
            return envProps.getProperty("router.pingFile", "router.ping");
        else
            return "router.ping";
    }
    private String getPingFile() {
        return _context.getProperty("router.pingFile", "router.ping");
    }
    
    private static final long LIVELINESS_DELAY = 60*1000;
    
    /** 
     * Start a thread that will periodically update the file "router.ping", but if 
     * that file already exists and was recently written to, return false as there is
     * another instance running
     * 
     * @return true if the router is the only one running 
     */
    private boolean beginMarkingLiveliness(Properties envProps) {
        String filename = getPingFile(envProps);
        File f = new File(filename);
        if (f.exists()) {
            long lastWritten = f.lastModified();
            if (System.currentTimeMillis()-lastWritten > LIVELINESS_DELAY) {
                System.err.println("WARN: Old router was not shut down gracefully, deleting router.ping");
                f.delete();
            } else {
                return false;
            }
        }
        // not an I2PThread for context creation issues
        Thread t = new Thread(new MarkLiveliness(f));
        t.setName("Mark router liveliness");
        t.setDaemon(true);
        t.start();
        return true;
    }
    
    private class MarkLiveliness implements Runnable {
        private File _pingFile;
        public MarkLiveliness(File f) {
            _pingFile = f;
        }
        public void run() {
            _pingFile.deleteOnExit();
            do {
                ping();
                try { Thread.sleep(LIVELINESS_DELAY); } catch (InterruptedException ie) {}
            } while (_isAlive);
            _pingFile.delete();
        }
        
        private void ping() {
            FileOutputStream fos = null;
            try { 
                fos = new FileOutputStream(_pingFile);
                fos.write(("" + System.currentTimeMillis()).getBytes());
            } catch (IOException ioe) {
                if (_log != null) {
                    _log.log(Log.CRIT, "Error writing to ping file", ioe);
                } else {
                    System.err.println("Error writing to ping file");
                    ioe.printStackTrace();
                }
            } finally {
                if (fos != null) try { fos.close(); } catch (IOException ioe) {}
            }
        }
    }
    
    
    private static int __id = 0;
    private class ShutdownHook extends Thread {
        private int _id;
        public ShutdownHook() {
            _id = ++__id;
        }
        public void run() {
            setName("Router " + _id + " shutdown");
            _log.log(Log.CRIT, "Shutting down the router...");
            shutdown(EXIT_HARD);
        }
    }
    
    /** update the router.info file whenever its, er, updated */
    private class PersistRouterInfoJob extends JobImpl {
        public PersistRouterInfoJob() { super(Router.this._context); }
        public String getName() { return "Persist Updated Router Information"; }
        public void runJob() {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Persisting updated router info");

            String infoFilename = getConfigSetting(PROP_INFO_FILENAME);
            if (infoFilename == null)
                infoFilename = PROP_INFO_FILENAME_DEFAULT;

            RouterInfo info = getRouterInfo();

            FileOutputStream fos = null;
            try {
                fos = new FileOutputStream(infoFilename);
                info.writeBytes(fos);
            } catch (DataFormatException dfe) {
                _log.error("Error rebuilding the router information", dfe);
            } catch (IOException ioe) {
                _log.error("Error writing out the rebuilt router information", ioe);
            } finally {
                if (fos != null) try { fos.close(); } catch (IOException ioe) {}
            }
        }
    }
}
