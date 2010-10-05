/*
 * By zzz 2008, released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 */
package net.i2p.client.naming;

import java.io.InputStream;

import net.i2p.I2PAppContext;
import net.i2p.data.Destination;
import net.i2p.util.Log;

/**
 * An interface to an external naming service program, with in-memory caching.
 * This can be used as a simple and flexible way to experiment with
 * alternative naming systems.
 *
 * The external command takes a hostname argument and must return (only) the
 * 516-byte Base64 destination, or hostname=dest, on stdout.
 * A trailing \n or \r\n is acceptable.
 * The command must exit 0 on success. Nonzero on failure is optional.
 *
 * The external command can do local and/or remote (via i2p or not) lookups.
 * No timeouts are implemented here - the author of the external program
 * must ensure that the program returns in a reasonable amount of time -
 * (15 sec max suggested)
 *
 * Can be used from MetaNamingService, (e.g. after HostsTxtNamingService),
 * or as the sole naming service.
 *
 * Sample chained config to put in configadvanced.jsp (restart required):
 *
 * i2p.naming.impl=net.i2p.client.naming.MetaNamingService
 * i2p.nameservicelist=net.i2p.client.naming.HostsTxtNamingService,net.i2p.client.naming.ExecNamingService
 * i2p.naming.exec.command=/usr/local/bin/i2presolve
 *
 * Sample unchained config to put in configadvanced.jsp (restart required):
 *
 * i2p.naming.impl=net.i2p.client.naming.ExecNamingService
 * i2p.naming.exec.command=/usr/local/bin/i2presolve
 *
 */
public class ExecNamingService extends NamingService {

    private final static String PROP_EXEC_CMD = "i2p.naming.exec.command";
    private final static String DEFAULT_EXEC_CMD = "/usr/local/bin/i2presolve";
    private final static String PROP_SHELL_CMD = "i2p.naming.exec.shell";
    private final static String DEFAULT_SHELL_CMD = "/bin/bash";
    private final static Log _log = new Log(ExecNamingService.class);

    /** 
     * The naming service should only be constructed and accessed through the 
     * application context.  This constructor should only be used by the 
     * appropriate application context itself.
     *
     */
    public ExecNamingService(I2PAppContext context) {
        super(context);
    }
    
    @Override
    public Destination lookup(String hostname) {
        // If it's long, assume it's a key.
        if (hostname.length() >= DEST_SIZE)
            return lookupBase64(hostname);

        hostname = hostname.toLowerCase();

        // If you want b32, chain with HostsTxtNamingService
        if (hostname.length() == 60 && hostname.endsWith(".b32.i2p"))
            return null;

        // check the cache
        Destination d = getCache(hostname);
        if (d != null)
            return d;

        // lookup
        String key = fetchAddr(hostname);	  	
        if (key != null) {
            _log.error("Success: " + hostname);
            d = lookupBase64(key);
            putCache(hostname, d);
            return d;
        }
        return null;
    }

    // FIXME allow larger Dests for non-null Certs
    private static final int DEST_SIZE = 516;                    // Std. Base64 length (no certificate)
    private static final int MAX_RESPONSE = DEST_SIZE + 68 + 10; // allow for hostname= and some trailing stuff
    private String fetchAddr(String hostname) {
        String[] commandArr = new String[3];
        commandArr[0] = _context.getProperty(PROP_SHELL_CMD, DEFAULT_SHELL_CMD);
        commandArr[1] = "-c";
        String command = _context.getProperty(PROP_EXEC_CMD, DEFAULT_EXEC_CMD) + " " + hostname;
        commandArr[2] = command;
        
        try {
            Process get = Runtime.getRuntime().exec(commandArr);
            get.waitFor();
            int exitValue = get.exitValue();		
            if (exitValue != 0) {
                _log.error("Exit " + exitValue + " from " + commandArr[0] + " " + commandArr[1] + " \"" + command + "\"");
                return null;
            }
            InputStream is = get.getInputStream();
            byte[] input = new byte[MAX_RESPONSE];
            int count = is.read(input);
            is.close();
            if (count < DEST_SIZE) {
                _log.error("Short response: " + command);
                return null;
            }
            String key = new String(input);
            if (key.startsWith(hostname + "="))   // strip hostname=
                key = key.substring(hostname.length() + 1); 
            key = key.substring(0, DEST_SIZE);    // catch IndexOutOfBounds exception below
            if (!key.endsWith("AA")) {
                _log.error("Invalid key: " + command);
                return null;
            }
            if (key.replaceAll("[a-zA-Z0-9~-]", "").length() != 0) {
                _log.error("Invalid chars: " + command);
                return null;
            }
            return key;
        } catch (Throwable t) {
            _log.error("Error fetching the addr", t);
        }
        return null;
    }
}
