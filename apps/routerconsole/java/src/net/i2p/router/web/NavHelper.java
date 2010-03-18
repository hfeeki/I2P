package net.i2p.router.web;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.i2p.I2PAppContext;

public class NavHelper {
    private static Map<String, String> _apps = new ConcurrentHashMap(4);
    
    /**
     * To register a new client application so that it shows up on the router
     * console's nav bar, it should be registered with this singleton. 
     *
     * @param name pretty name the app will be called in the link
     * @param path full path pointing to the application's root 
     *             (e.g. /i2ptunnel/index.jsp)
     */
    public static void registerApp(String name, String path) {
        _apps.put(name, path);
    }
    public static void unregisterApp(String name) {
        _apps.remove(name);
    }
    
    /**
     *  Translated string is loaded by PluginStarter
     */
    public static String getClientAppLinks(I2PAppContext ctx) {
        if (_apps.isEmpty())
            return "";
        StringBuilder buf = new StringBuilder(256); 
        for (Iterator<String> iter = _apps.keySet().iterator(); iter.hasNext(); ) {
            String name = iter.next();
            String path = _apps.get(name);
            buf.append(" <a target=\"_top\" href=\"").append(path).append("\">");
            buf.append(name).append("</a>");
        }
        return buf.toString();
    }
}
