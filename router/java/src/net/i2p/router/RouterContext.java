package net.i2p.router;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import net.i2p.I2PAppContext;
import net.i2p.data.Hash;
import net.i2p.router.admin.AdminManager;
import net.i2p.router.client.ClientManagerFacadeImpl;
import net.i2p.router.networkdb.kademlia.KademliaNetworkDatabaseFacade;
import net.i2p.router.peermanager.Calculator;
import net.i2p.router.peermanager.CapacityCalculator;
import net.i2p.router.peermanager.IntegrationCalculator;
import net.i2p.router.peermanager.IsFailingCalculator;
import net.i2p.router.peermanager.PeerManagerFacadeImpl;
import net.i2p.router.peermanager.ProfileManagerImpl;
import net.i2p.router.peermanager.ProfileOrganizer;
import net.i2p.router.peermanager.ReliabilityCalculator;
import net.i2p.router.peermanager.SpeedCalculator;
import net.i2p.router.peermanager.StrictSpeedCalculator;
import net.i2p.router.transport.CommSystemFacadeImpl;
import net.i2p.router.transport.FIFOBandwidthLimiter;
import net.i2p.router.transport.OutboundMessageRegistry;
import net.i2p.router.transport.VMCommSystem;
import net.i2p.router.tunnel.pool.TunnelPoolManager;
import net.i2p.router.tunnel.TunnelDispatcher;

/**
 * Build off the core I2P context to provide a root for a router instance to
 * coordinate its resources.  Router instances themselves should be sure to have
 * their own RouterContext, and rooting off of it will allow multiple routers to
 * operate in the same JVM without conflict (e.g. sessionTags wont get 
 * intermingled, nor will their netDbs, jobQueues, or bandwidth limiters).
 *
 */
public class RouterContext extends I2PAppContext {
    private Router _router;
    private AdminManager _adminManager;
    private ClientManagerFacade _clientManagerFacade;
    private ClientMessagePool _clientMessagePool;
    private JobQueue _jobQueue;
    private InNetMessagePool _inNetMessagePool;
    private OutNetMessagePool _outNetMessagePool;
    private MessageHistory _messageHistory;
    private OutboundMessageRegistry _messageRegistry;
    private NetworkDatabaseFacade _netDb;
    private KeyManager _keyManager;
    private CommSystemFacade _commSystem;
    private ProfileOrganizer _profileOrganizer;
    private PeerManagerFacade _peerManagerFacade;
    private ProfileManager _profileManager;
    private FIFOBandwidthLimiter _bandwidthLimiter;
    private TunnelManagerFacade _tunnelManager;
    private TunnelDispatcher _tunnelDispatcher;
    private StatisticsManager _statPublisher;
    private Shitlist _shitlist;
    private MessageValidator _messageValidator;
    private MessageStateMonitor _messageStateMonitor;
    private RouterThrottle _throttle;
    private Calculator _isFailingCalc;
    private Calculator _integrationCalc;
    private Calculator _speedCalc;
    private Calculator _reliabilityCalc;
    private Calculator _capacityCalc;
    private Calculator _oldSpeedCalc;
    
    private static List _contexts = new ArrayList(1);
    
    public RouterContext(Router router) { this(router, null); }
    public RouterContext(Router router, Properties envProps) { 
        super(filterProps(envProps));
        _router = router;
        initAll();
        _contexts.add(this);
    }
    /**
     * Unless we are explicitly disabling the timestamper, we want to use it.
     * We need this now as the new timestamper default is disabled (so we don't
     * have each I2PAppContext creating their own SNTP queries all the time)
     *
     */
    static final Properties filterProps(Properties envProps) {
        if (envProps == null)
            envProps = new Properties();
        if (envProps.getProperty("time.disabled") == null)
            envProps.setProperty("time.disabled", "false");
        return envProps;
    }
    private void initAll() {
        _adminManager = new AdminManager(this);
        _clientManagerFacade = new ClientManagerFacadeImpl(this);
        _clientMessagePool = new ClientMessagePool(this);
        _jobQueue = new JobQueue(this);
        _inNetMessagePool = new InNetMessagePool(this);
        _outNetMessagePool = new OutNetMessagePool(this);
        _messageHistory = new MessageHistory(this);
        _messageRegistry = new OutboundMessageRegistry(this);
        _messageStateMonitor = new MessageStateMonitor(this);
        _netDb = new KademliaNetworkDatabaseFacade(this);
        _keyManager = new KeyManager(this);
        if ("false".equals(getProperty("i2p.vmCommSystem", "false")))
            _commSystem = new CommSystemFacadeImpl(this);
        else
            _commSystem = new VMCommSystem(this);
        _profileOrganizer = new ProfileOrganizer(this);
        _peerManagerFacade = new PeerManagerFacadeImpl(this);
        _profileManager = new ProfileManagerImpl(this);
        _bandwidthLimiter = new FIFOBandwidthLimiter(this);
        _tunnelManager = new TunnelPoolManager(this);
        _tunnelDispatcher = new TunnelDispatcher(this);
        _statPublisher = new StatisticsManager(this);
        _shitlist = new Shitlist(this);
        _messageValidator = new MessageValidator(this);
        //_throttle = new RouterThrottleImpl(this);
        _throttle = new RouterDoSThrottle(this);
        _isFailingCalc = new IsFailingCalculator(this);
        _integrationCalc = new IntegrationCalculator(this);
        _speedCalc = new SpeedCalculator(this);
        _oldSpeedCalc = new StrictSpeedCalculator(this);
        _reliabilityCalc = new ReliabilityCalculator(this);
        _capacityCalc = new CapacityCalculator(this);
    }
    
    /**
     * Retrieve the list of router contexts currently instantiated in this JVM.  
     * This will always contain only one item (except when a simulation per the
     * MultiRouter is going on), and the list should only be modified when a new
     * context is created or a router is shut down.
     *
     */
    public static List listContexts() { return _contexts; }
    
    /** what router is this context working for? */
    public Router router() { return _router; }
    /** convenience method for querying the router's ident */
    public Hash routerHash() { return _router.getRouterInfo().getIdentity().getHash(); }

    /**
     * Controls a basic admin interface
     *
     */
    public AdminManager adminManager() { return _adminManager; }
    /**
     * How are we coordinating clients for the router?
     */
    public ClientManagerFacade clientManager() { return _clientManagerFacade; }
    /**
     * Where do we toss messages for the clients (and where do we get client messages
     * to forward on from)?
     */
    public ClientMessagePool clientMessagePool() { return _clientMessagePool; }
    /**
     * Where do we get network messages from (aka where does the comm system dump what
     * it reads)?
     */
    public InNetMessagePool inNetMessagePool() { return _inNetMessagePool; }
    /**
     * Where do we put messages that the router wants to forwards onto the network?
     */
    public OutNetMessagePool outNetMessagePool() { return _outNetMessagePool; }
    /**
     * Tracker component for monitoring what messages are wrapped in what containers
     * and how they proceed through the network.  This is fully for debugging, as when
     * a large portion of the network tracks their messages through this messageHistory
     * and submits their logs, we can correlate them and watch as messages flow from 
     * hop to hop.
     */
    public MessageHistory messageHistory() { return _messageHistory; }
    /**
     * The registry is used by outbound messages to wait for replies.
     */
    public OutboundMessageRegistry messageRegistry() { return _messageRegistry; }
    /**
     * The monitor keeps track of inbound and outbound messages currently held in
     * memory / queued for processing.  We'll use this to throttle the router so
     * we don't overflow.
     *
     */
    public MessageStateMonitor messageStateMonitor() { return _messageStateMonitor; }
    /**
     * Our db cache
     */
    public NetworkDatabaseFacade netDb() { return _netDb; }
    /**
     * The actual driver of the router, where all jobs are enqueued and processed.
     */
    public JobQueue jobQueue() { return _jobQueue; }
    /**
     * Coordinates the router's ElGamal and DSA keys, as well as any keys given
     * to it by clients as part of a LeaseSet.
     */
    public KeyManager keyManager() { return _keyManager; }
    /**
     * How do we pass messages from our outNetMessagePool to another router
     */
    public CommSystemFacade commSystem() { return _commSystem; }
    /**
     * Organize the peers we know about into various tiers, profiling their
     * performance and sorting them accordingly.
     */
    public ProfileOrganizer profileOrganizer() { return _profileOrganizer; }
    /**
     * Minimal interface for selecting peers for various tasks based on given
     * criteria.  This is kept seperate from the profile organizer since this 
     * logic is independent of how the peers are organized (or profiled even).
     */
    public PeerManagerFacade peerManager() { return _peerManagerFacade; }
    /**
     * Expose a simple API for various router components to take note of 
     * particular events that a peer enacts (sends us a message, agrees to 
     * participate in a tunnel, etc).
     */
    public ProfileManager profileManager() { return _profileManager; }
    /**
     * Coordinate this router's bandwidth limits
     */
    public FIFOBandwidthLimiter bandwidthLimiter() { return _bandwidthLimiter; }
    /**
     * Coordinate this router's tunnels (its pools, participation, backup, etc).
     * Any configuration for the tunnels is rooted from the context's properties
     */
    public TunnelManagerFacade tunnelManager() { return _tunnelManager; }
    /**
     * Handle tunnel messages, as well as coordinate the gateways
     */
    public TunnelDispatcher tunnelDispatcher() { return _tunnelDispatcher; }
    /**
     * If the router is configured to, gather up some particularly tasty morsels
     * regarding the stats managed and offer to publish them into the routerInfo.
     */
    public StatisticsManager statPublisher() { return _statPublisher; }
    /** 
     * who does this peer hate?
     */
    public Shitlist shitlist() { return _shitlist; }
    /**
     * The router keeps track of messages it receives to prevent duplicates, as
     * well as other criteria for "validity".
     */
    public MessageValidator messageValidator() { return _messageValidator; }
    /**
     * Component to coordinate our accepting/rejecting of requests under load
     *
     */
    public RouterThrottle throttle() { return _throttle; }
    
    /** how do we rank the failure of profiles? */
    public Calculator isFailingCalculator() { return _isFailingCalc; }
    /** how do we rank the integration of profiles? */
    public Calculator integrationCalculator() { return _integrationCalc; }
    /** how do we rank the speed of profiles? */
    public Calculator speedCalculator() { return _speedCalc; } 
    public Calculator oldSpeedCalculator() { return _oldSpeedCalc; }
    /** how do we rank the reliability of profiles? */
    public Calculator reliabilityCalculator() { return _reliabilityCalc; }
    /** how do we rank the capacity of profiles? */
    public Calculator capacityCalculator() { return _capacityCalc; }
    
    public String toString() {
        StringBuffer buf = new StringBuffer(512);
        buf.append("RouterContext: ").append(super.toString()).append('\n');
        buf.append(_router).append('\n');
        buf.append(_clientManagerFacade).append('\n');
        buf.append(_clientMessagePool).append('\n');
        buf.append(_jobQueue).append('\n');
        buf.append(_inNetMessagePool).append('\n');
        buf.append(_outNetMessagePool).append('\n');
        buf.append(_messageHistory).append('\n');
        buf.append(_messageRegistry).append('\n');
        buf.append(_netDb).append('\n');
        buf.append(_keyManager).append('\n');
        buf.append(_commSystem).append('\n');
        buf.append(_profileOrganizer).append('\n');
        buf.append(_peerManagerFacade).append('\n');
        buf.append(_profileManager).append('\n');
        buf.append(_bandwidthLimiter).append('\n');
        buf.append(_tunnelManager).append('\n');
        buf.append(_statPublisher).append('\n');
        buf.append(_shitlist).append('\n');
        buf.append(_messageValidator).append('\n');
        buf.append(_isFailingCalc).append('\n');
        buf.append(_integrationCalc).append('\n');
        buf.append(_speedCalc).append('\n');
        buf.append(_reliabilityCalc).append('\n');
        return buf.toString();
    }
    
    /**
     * Tie in the router's config as properties, as well as whatever the 
     * I2PAppContext says.
     *
     */
    public String getProperty(String propName) {
        if (_router != null) {
            String val = _router.getConfigSetting(propName);
            if (val != null) return val;
        }
        return super.getProperty(propName);
    }
    /**
     * Tie in the router's config as properties, as well as whatever the 
     * I2PAppContext says.
     *
     */
    public String getProperty(String propName, String defaultVal) {
        if (_router != null) {
            String val = _router.getConfigSetting(propName);
            if (val != null) return val;
        }
        return super.getProperty(propName, defaultVal);
    }
}