package net.i2p.i2ptunnel;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

import net.i2p.I2PAppContext;
import net.i2p.util.Log;

/**
 * Coordinate a set of tunnels within the JVM, loading and storing their config
 * to disk, and building new ones as requested.
 *
 */
public class TunnelControllerGroup {
    private Log _log;
    private static TunnelControllerGroup _instance;
    static final String DEFAULT_CONFIG_FILE = "i2ptunnel.config";
    
    private List _controllers;
    private String _configFile = DEFAULT_CONFIG_FILE;
    
    public static TunnelControllerGroup getInstance() { 
        synchronized (TunnelControllerGroup.class) {
            if (_instance == null)
                _instance = new TunnelControllerGroup(DEFAULT_CONFIG_FILE);
            return _instance; 
        }
    }

    private TunnelControllerGroup(String configFile) { 
        _log = I2PAppContext.getGlobalContext().logManager().getLog(TunnelControllerGroup.class);
        _controllers = new ArrayList();
        _configFile = configFile;
        loadControllers(_configFile);
    }
    
    public static void main(String args[]) {
        if ( (args == null) || (args.length <= 0) ) {
            _instance = new TunnelControllerGroup(DEFAULT_CONFIG_FILE);
        } else if (args.length == 1) {
            _instance = new TunnelControllerGroup(args[0]);
        } else {
            System.err.println("Usage: TunnelControllerGroup [filename]");
            return;
        }
    }
    
    /**
     * Load up all of the tunnels configured in the given file (but do not start
     * them)
     *
     */
    public void loadControllers(String configFile) {
        Properties cfg = loadConfig(configFile);
        if (cfg == null) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Unable to load the config from " + configFile);
            return;
        }
        int i = 0; 
        while (true) {
            String type = cfg.getProperty("tunnel." + i + ".type");
            if (type == null) 
                break;
            TunnelController controller = new TunnelController(cfg, "tunnel." + i + ".");
            _controllers.add(controller);
            i++;
        }
        if (_log.shouldLog(Log.INFO))
            _log.info(i + " controllers loaded from " + configFile);
    }
    
    public void reloadControllers() {
        unloadControllers();
        loadControllers(_configFile);
    }
    
    /**
     * Stop and remove reference to all known tunnels (but dont delete any config
     * file or do other silly things)
     *
     */
    public void unloadControllers() {
        stopAllControllers();
        _controllers.clear();
        if (_log.shouldLog(Log.INFO))
            _log.info("All controllers stopped and unloaded");
    }
    
    /**
     * Add the given tunnel to the set of known controllers (but dont add it to
     * a config file or start it or anything)
     *
     */
    public void addController(TunnelController controller) { _controllers.add(controller); }
    
    /**
     * Stop and remove the given tunnel
     *
     * @return list of messages from the controller as it is stopped
     */
    public List removeController(TunnelController controller) {
        if (controller == null) return new ArrayList();
        controller.stopTunnel();
        List msgs = controller.clearMessages();
        _controllers.remove(controller);
        msgs.add("Tunnel " + controller.getName() + " removed");
        return msgs;
    }
    
    /**
     * Stop all tunnels
     *
     * @return list of messages the tunnels generate when stopped
     */
    public List stopAllControllers() {
        List msgs = new ArrayList();
        for (int i = 0; i < _controllers.size(); i++) {
            TunnelController controller = (TunnelController)_controllers.get(i);
            controller.stopTunnel();
            msgs.addAll(controller.clearMessages());
        }
        
        if (_log.shouldLog(Log.INFO))
            _log.info(_controllers.size() + " controllers stopped");
        return msgs;
    }
    
    /**
     * Start all tunnels
     *
     * @return list of messages the tunnels generate when started
     */
    public List startAllControllers() {
        List msgs = new ArrayList();
        for (int i = 0; i < _controllers.size(); i++) {
            TunnelController controller = (TunnelController)_controllers.get(i);
            controller.startTunnel();
            msgs.addAll(controller.clearMessages());
        }
        
        if (_log.shouldLog(Log.INFO))
            _log.info(_controllers.size() + " controllers started");
        return msgs;
    }
    
    /**
     * Restart all tunnels
     *
     * @return list of messages the tunnels generate when restarted
     */
    public List restartAllControllers() {
        List msgs = new ArrayList();
        for (int i = 0; i < _controllers.size(); i++) {
            TunnelController controller = (TunnelController)_controllers.get(i);
            controller.restartTunnel();
            msgs.addAll(controller.clearMessages());
        }
        if (_log.shouldLog(Log.INFO))
            _log.info(_controllers.size() + " controllers restarted");
        return msgs;
    }
    
    /**
     * Fetch all outstanding messages from any of the known tunnels
     *
     * @return list of messages the tunnels have generated
     */
    public List clearAllMessages() {
        List msgs = new ArrayList();
        for (int i = 0; i < _controllers.size(); i++) {
            TunnelController controller = (TunnelController)_controllers.get(i);
            msgs.addAll(controller.clearMessages());
        }
        return msgs;
    }
    
    /**
     * Save the configuration of all known tunnels to the default config 
     * file
     *
     */
    public void saveConfig() {
        saveConfig(_configFile);
    }
    /**
     * Save the configuration of all known tunnels to the given file
     *
     */
    public void saveConfig(String configFile) {
        _configFile = configFile;
        File cfgFile = new File(configFile);
        File parent = cfgFile.getParentFile();
        if ( (parent != null) && (!parent.exists()) )
            parent.mkdirs();
        
        
        TreeMap map = new TreeMap();
        for (int i = 0; i < _controllers.size(); i++) {
            TunnelController controller = (TunnelController)_controllers.get(i);
            Properties cur = controller.getConfig("tunnel." + i + ".");
            map.putAll(cur);
        }
        
        StringBuffer buf = new StringBuffer(1024);
        for (Iterator iter = map.keySet().iterator(); iter.hasNext(); ) {
            String key = (String)iter.next();
            String val = (String)map.get(key);
            buf.append(key).append('=').append(val).append('\n');
        }
        
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(cfgFile);
            fos.write(buf.toString().getBytes());
            if (_log.shouldLog(Log.INFO))
                _log.info("Config written to " + cfgFile.getPath());
        } catch (IOException ioe) {
            _log.error("Error writing out the config");
        } finally {
            if (fos != null) try { fos.close(); } catch (IOException ioe) {}
        }
    }
    
    /**
     * Load up the config data from the file
     *
     * @return properties loaded or null if there was an error
     */
    private Properties loadConfig(String configFile) {
        File cfgFile = new File(configFile);
        if (!cfgFile.exists()) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Unable to load the controllers from " + configFile);
            return null;
        }
        
        Properties props = new Properties();
        FileInputStream fis = null;
        try {
            fis = new FileInputStream(cfgFile);
            BufferedReader in = new BufferedReader(new InputStreamReader(fis));
            String line = null;
            while ( (line = in.readLine()) != null) {
                line = line.trim();
                if (line.length() <= 0) continue;
                if (line.startsWith("#") || line.startsWith(";"))
                    continue;
                int eq = line.indexOf('=');
                if ( (eq <= 0) || (eq >= line.length() - 1) )
                    continue;
                String key = line.substring(0, eq);
                String val = line.substring(eq+1);
                props.setProperty(key, val);
            }
            
            if (_log.shouldLog(Log.INFO))
                _log.info("Props loaded with " + props.size() + " lines");
            return props;
        } catch (IOException ioe) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Error reading the controllers from " + configFile, ioe);
            return null;
        } finally {
            if (fis != null) try { fis.close(); } catch (IOException ioe) {}
        }
    }
    
    /**
     * Retrieve a list of tunnels known
     *
     * @return list of TunnelController objects
     */
    public List getControllers() { return _controllers; }
    
}
