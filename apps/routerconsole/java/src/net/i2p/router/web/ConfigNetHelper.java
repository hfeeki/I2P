package net.i2p.router.web;

import net.i2p.data.RouterAddress;
import net.i2p.router.CommSystemFacade;
import net.i2p.router.LoadTestManager;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.transport.TransportManager;
import net.i2p.router.transport.udp.UDPAddress;
import net.i2p.router.transport.udp.UDPTransport;
import net.i2p.time.Timestamper;

public class ConfigNetHelper {
    private RouterContext _context;
    /**
     * Configure this bean to query a particular router context
     *
     * @param contextId begging few characters of the routerHash, or null to pick
     *                  the first one we come across.
     */
    public void setContextId(String contextId) {
        try {
            _context = ContextHelper.getContext(contextId);
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    public ConfigNetHelper() {}
    
    /** copied from various private components */
    public final static String PROP_I2NP_UDP_PORT = "i2np.udp.port";
    public final static String PROP_I2NP_INTERNAL_UDP_PORT = "i2np.udp.internalPort";
    public final static String PROP_I2NP_NTCP_HOSTNAME = "i2np.ntcp.hostname";
    public final static String PROP_I2NP_NTCP_PORT = "i2np.ntcp.port";
    public final static String PROP_I2NP_NTCP_AUTO_PORT = "i2np.ntcp.autoip";
    public final static String PROP_I2NP_NTCP_AUTO_IP = "i2np.ntcp.autoport";
    public String getNtcphostname() {
        if (!TransportManager.enableNTCP(_context))
            return "\" disabled=\"true";
        String hostname = _context.getProperty(PROP_I2NP_NTCP_HOSTNAME); 
        if (hostname == null) return "";
        return hostname;
    }
    public String getNtcpport() { 
        if (!TransportManager.enableNTCP(_context))
            return "\" disabled=\"true";
        String port = _context.getProperty(PROP_I2NP_NTCP_PORT); 
        if (port == null) return "";
        return port;
    }
    
    public String getUdpAddress() {
        RouterAddress addr = _context.router().getRouterInfo().getTargetAddress("SSU");
        if (addr == null)
            return "unknown";
        UDPAddress ua = new UDPAddress(addr);
        return ua.toString();
    }
    
    public String getUdpIP() {
        RouterAddress addr = _context.router().getRouterInfo().getTargetAddress("SSU");
        if (addr == null)
            return "unknown";
        UDPAddress ua = new UDPAddress(addr);
        if (ua.getHost() == null)
            return "unknown";
        return ua.getHost();
    }

    public String getUdpPort() {
        RouterAddress addr = _context.router().getRouterInfo().getTargetAddress("SSU");
        if (addr == null)
            return "unknown";
        UDPAddress ua = new UDPAddress(addr);
        if (ua.getPort() <= 0)
            return "unknown";
        return "" + ua.getPort();
    }

    public String getEnableTimeSyncChecked() {
        String disabled = _context.getProperty(Timestamper.PROP_DISABLED, "false");
        if ( (disabled != null) && ("true".equalsIgnoreCase(disabled)) )
            return "";
        else
            return " checked ";
    }
    
    public String getHiddenModeChecked() {
        String enabled = _context.getProperty(Router.PROP_HIDDEN, "false");
        if ( (enabled != null) && ("true".equalsIgnoreCase(enabled)) )
            return " checked ";
        else
            return "";
    }

    public String getDynamicKeysChecked() {
        String enabled = _context.getProperty(Router.PROP_DYNAMIC_KEYS, "false");
        if ( (enabled != null) && ("true".equalsIgnoreCase(enabled)) )
            return " checked ";
        else
            return "";
    }

    public String getTcpAutoPortChecked() {
        if (!TransportManager.enableNTCP(_context))
            return " disabled=\"true\" ";
        String enabled = _context.getProperty(PROP_I2NP_NTCP_AUTO_PORT, "false");
        if ( (enabled != null) && ("true".equalsIgnoreCase(enabled)) )
            return " checked ";
        else
            return "";
    }

    public String getTcpAutoIPChecked() {
        if (!TransportManager.enableNTCP(_context))
            return " disabled=\"true\" ";
        String enabled = _context.getProperty(PROP_I2NP_NTCP_AUTO_IP, "false");
        if ( (enabled != null) && ("true".equalsIgnoreCase(enabled)) )
            return " checked ";
        else
            return "";
    }

    public String getRequireIntroductionsChecked() {
        short status = _context.commSystem().getReachabilityStatus();
        switch (status) {
            case CommSystemFacade.STATUS_OK:
                if ("true".equalsIgnoreCase(_context.getProperty(UDPTransport.PROP_FORCE_INTRODUCERS, "false")))
                    return "checked=\"true\"";
                return "";
            case CommSystemFacade.STATUS_DIFFERENT:
            case CommSystemFacade.STATUS_REJECT_UNSOLICITED:
                return "checked=\"true\"";
            case CommSystemFacade.STATUS_UNKNOWN:
                if ("true".equalsIgnoreCase(_context.getProperty(UDPTransport.PROP_FORCE_INTRODUCERS, "false")))
                    return "checked=\"true\"";
                return "";
            default:
                return "checked=\"true\"";
        }
    }
    
    public String getInboundRate() {
        return "" + _context.bandwidthLimiter().getInboundKBytesPerSecond();
    }
    public String getOutboundRate() {
        return "" + _context.bandwidthLimiter().getOutboundKBytesPerSecond();
    }
    public String getInboundBurstRate() {
        return "" + _context.bandwidthLimiter().getInboundBurstKBytesPerSecond();
    }
    public String getOutboundBurstRate() {
        return "" + _context.bandwidthLimiter().getOutboundBurstKBytesPerSecond();
    }
    public String getInboundBurstFactorBox() {
        int numSeconds = 1;
        int rateKBps = _context.bandwidthLimiter().getInboundBurstKBytesPerSecond();
        int burstKB = _context.bandwidthLimiter().getInboundBurstBytes() * 1024;
        if ( (rateKBps > 0) && (burstKB > 0) )
            numSeconds = burstKB / rateKBps;
        return getBurstFactor(numSeconds, "inboundburstfactor");
    }
    
    public String getOutboundBurstFactorBox() {
        int numSeconds = 1;
        int rateKBps = _context.bandwidthLimiter().getOutboundBurstKBytesPerSecond();
        int burstKB = _context.bandwidthLimiter().getOutboundBurstBytes() * 1024;
        if ( (rateKBps > 0) && (burstKB > 0) )
            numSeconds = burstKB / rateKBps;
        return getBurstFactor(numSeconds, "outboundburstfactor");
    }
    
    private static String getBurstFactor(int numSeconds, String name) {
        StringBuffer buf = new StringBuffer(256);
        buf.append("<select name=\"").append(name).append("\">\n");
        boolean found = false;
        for (int i = 10; i <= 60; i += 10) {
            buf.append("<option value=\"").append(i).append("\" ");
            if (i == numSeconds) {
                buf.append("selected ");
                found = true;
            } else if ( (i == 60) && (!found) ) {
                buf.append("selected ");
            }
            buf.append(">");
            buf.append(i).append(" seconds</option>\n");
        }
        buf.append("</select>\n");
        return buf.toString();
    }
    
    public String getEnableLoadTesting() {
        if (LoadTestManager.isEnabled(_context))
            return " checked ";
        else
            return "";
    }
    
    public String getSharePercentageBox() {
        int pct = (int) (100 * _context.router().getSharePercentage());
        StringBuffer buf = new StringBuffer(256);
        buf.append("<select name=\"sharePercentage\">\n");
        boolean found = false;
        for (int i = 30; i <= 110; i += 10) {
            int val = i;
            if (i == 110) {
                if (found)
                    break;
                else
                    val = pct;
            }
            buf.append("<option value=\"").append(val).append("\" ");
            if (pct == val) {
                buf.append("selected=\"true\" ");
                found = true;
            }
            buf.append(">Up to ").append(val).append("%</option>\n");
        }
        buf.append("</select>\n");
        return buf.toString();
    }

    public static final int DEFAULT_SHARE_KBPS = 12;
    public int getShareBandwidth() {
        int irateKBps = _context.bandwidthLimiter().getInboundKBytesPerSecond();
        int orateKBps = _context.bandwidthLimiter().getOutboundKBytesPerSecond();
        double pct = _context.router().getSharePercentage();
        if (irateKBps < 0 || orateKBps < 0)
            return DEFAULT_SHARE_KBPS;
        return (int) (pct * Math.min(irateKBps, orateKBps));
    }
}
