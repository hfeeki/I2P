package net.i2p.i2ptunnel.web;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2005 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.StringTokenizer;

import net.i2p.i2ptunnel.TunnelController;
import net.i2p.i2ptunnel.TunnelControllerGroup;

/**
 * Ugly little accessor for the edit page
 */
public class EditBean extends IndexBean {
    public EditBean() { super(); }
    
    public static boolean staticIsClient(int tunnel) {
        TunnelControllerGroup group = TunnelControllerGroup.getInstance();
        List controllers = group.getControllers();
        if (controllers.size() > tunnel) {
            TunnelController cur = (TunnelController)controllers.get(tunnel);
            if (cur == null) return false;
            return isClient(cur.getType());
        } else {
            return false;
        }
    }
    
    public String getTargetHost(int tunnel) {
        TunnelController tun = getController(tunnel);
        if (tun != null && tun.getTargetHost() != null)
            return tun.getTargetHost();
        else
            return "127.0.0.1";
    }
    public String getTargetPort(int tunnel) {
        TunnelController tun = getController(tunnel);
        if (tun != null && tun.getTargetPort() != null)
            return tun.getTargetPort();
        else
            return "";
    }
    public String getSpoofedHost(int tunnel) {
        TunnelController tun = getController(tunnel);
        if (tun != null && tun.getSpoofedHost() != null)
            return tun.getSpoofedHost();
        else
            return "";
    }
    public String getPrivateKeyFile(int tunnel) {
        TunnelController tun = getController(tunnel);
        if (tun != null && tun.getPrivKeyFile() != null)
            return tun.getPrivKeyFile();
        else
            return "";
    }
    
    public boolean startAutomatically(int tunnel) {
        TunnelController tun = getController(tunnel);
        if (tun != null)
            return tun.getStartOnLoad();
        else
            return false;
    }
    
    public boolean isSharedClient(int tunnel) {
        TunnelController tun = getController(tunnel);
        if (tun != null)
            return "true".equalsIgnoreCase(tun.getSharedClient());
        else
            return true;
    }
    
    public boolean shouldDelay(int tunnel) {
        return getProperty(tunnel, "i2p.streaming.connectDelay", 0) > 0;
    }
    
    public boolean isInteractive(int tunnel) {
        return getProperty(tunnel, "i2p.streaming.maxWindowSize", 128) == 12;
    }
    
    public int getTunnelDepth(int tunnel, int defaultLength) {
        return getProperty(tunnel, "inbound.length", defaultLength);
    }
    
    public int getTunnelQuantity(int tunnel, int defaultQuantity) {
        return getProperty(tunnel, "inbound.quantity", defaultQuantity);
    }
   
    public int getTunnelBackupQuantity(int tunnel, int defaultBackupQuantity) {
        return getProperty(tunnel, "inbound.backupQuantity", defaultBackupQuantity);
    }
  
    public int getTunnelVariance(int tunnel, int defaultVariance) {
        return getProperty(tunnel, "inbound.lengthVariance", defaultVariance);
    }
    
    public boolean getReduce(int tunnel) {
        return false;
    }
    
    public int getReduceCount(int tunnel) {
        return getProperty(tunnel, "inbound.reduceQuantity", 1);
    }
    
    public int getReduceTime(int tunnel) {
        return getProperty(tunnel, "reduceIdleTime", 20);
    }
    
    public int getCert(int tunnel) {
        return 0;
    }
    
    public int getEffort(int tunnel) {
        return 23;
    }
    
    public String getSigner(int tunnel) {
        return "";
    }
    
    public boolean getEncrypt(int tunnel) {
        return false;
    }
    
    public String getEncryptKey(int tunnel) {
        return getProperty(tunnel, "encryptKey", "");
    }
    
    public boolean getAccess(int tunnel) {
        return false;
    }
    
    public String getAccessList(int tunnel) {
        return getProperty(tunnel, "accessList", "");
    }
    
    public boolean getClose(int tunnel) {
        return false;
    }
    
    public boolean getNewDest(int tunnel) {
        return false;
    }
    
    private int getProperty(int tunnel, String prop, int def) {
        TunnelController tun = getController(tunnel);
        if (tun != null) {
            Properties opts = getOptions(tun);
            if (opts != null) {
                String s = opts.getProperty(prop);
                if (s == null) return def;
                try {
                    return Integer.parseInt(s);
                } catch (NumberFormatException nfe) {}
            }
        }
        return def;
    }
    
    private String getProperty(int tunnel, String prop, String def) {
        TunnelController tun = getController(tunnel);
        if (tun != null) {
            Properties opts = getOptions(tun);
            if (opts != null)
                return opts.getProperty(prop, def);
        }
        return def;
    }
    
    public String getI2CPHost(int tunnel) {
        TunnelController tun = getController(tunnel);
        if (tun != null)
            return tun.getI2CPHost();
        else
            return "localhost";
    }
    
    public String getI2CPPort(int tunnel) {
        TunnelController tun = getController(tunnel);
        if (tun != null)
            return tun.getI2CPPort();
        else
            return "7654";
    }

    private static final String noShowProps[] = {
        "inbound.length", "outbound.length", "inbound.lengthVariance", "outbound.lengthVariance",
        "inbound.backupQuantity", "outbound.backupQuantity", "inbound.quantity", "outbound.quantity",
        "inbound.nickname", "outbound.nickname", "i2p.streaming.connectDelay", "i2p.streaming.maxWindowSize"
        };
    private static final Set noShowSet = new HashSet(noShowProps.length);
    static { noShowSet.addAll(Arrays.asList(noShowProps)); }

    public String getCustomOptions(int tunnel) {
        TunnelController tun = getController(tunnel);
        if (tun != null) {
            Properties opts = getOptions(tun);
            if (opts == null) return "";
            StringBuffer buf = new StringBuffer(64);
            int i = 0;
            for (Iterator iter = opts.keySet().iterator(); iter.hasNext(); ) {
                String key = (String)iter.next();
                if (noShowSet.contains(key))
                    continue;
                String val = opts.getProperty(key);
                if (i != 0) buf.append(' ');
                buf.append(key).append('=').append(val);
                i++;
            }
            return buf.toString();
        } else {
            return "";
        }
    }

    /**
     * Retrieve the client options from the tunnel
     *
     * @return map of name=val to be used as I2P session options
     */
    private static Properties getOptions(TunnelController controller) {
        if (controller == null) return null;
        String opts = controller.getClientOptions();
        StringTokenizer tok = new StringTokenizer(opts);
        Properties props = new Properties();
        while (tok.hasMoreTokens()) {
            String pair = tok.nextToken();
            int eq = pair.indexOf('=');
            if ( (eq <= 0) || (eq >= pair.length()) )
                continue;
            String key = pair.substring(0, eq);
            String val = pair.substring(eq+1);
            props.setProperty(key, val);
        }
        return props;
    }
}
