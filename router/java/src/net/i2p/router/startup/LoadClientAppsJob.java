package net.i2p.router.startup;

import java.lang.reflect.Method;
import java.io.IOException;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import net.i2p.data.DataHelper;
import net.i2p.router.JobImpl;
import net.i2p.router.RouterContext;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;

/**
 * Run any client applications specified in the router.config.  If any clientApp
 * contains the config property ".onBoot=true" it'll be launched immediately, otherwise
 * it'll get queued up for starting 2 minutes later.
 *
 */
class LoadClientAppsJob extends JobImpl {
    private Log _log;
    /** wait 2 minutes before starting up client apps */
    private final static long STARTUP_DELAY = 2*60*1000;
    
    private static final String PROP_CLIENT_CONFIG_FILENAME = "router.clientConfigFile";
    private static final String DEFAULT_CLIENT_CONFIG_FILENAME = "clients.config";
    private static boolean _loaded = false;
    
    public LoadClientAppsJob(RouterContext ctx) {
        super(ctx);
        _log = ctx.logManager().getLog(LoadClientAppsJob.class);
    }
    public void runJob() {
        synchronized (LoadClientAppsJob.class) {
            if (_loaded) return;
            _loaded = true;
        }
        Properties clientApps = getClientApps();
        int i = 0;
        while (true) {
            String className = clientApps.getProperty("clientApp."+i+".main");
            String clientName = clientApps.getProperty("clientApp."+i+".name");
            String args = clientApps.getProperty("clientApp."+i+".args");
            String onBoot = clientApps.getProperty("clientApp." + i + ".onBoot");
            boolean onStartup = false;
            if (onBoot != null)
                onStartup = "true".equals(onBoot) || "yes".equals(onBoot);
            
            if (className == null) 
                break;

            String argVal[] = parseArgs(args);
            if (onStartup) {
                // run this guy now
                runClient(className, clientName, argVal);
            } else {
                // wait 2 minutes
                getContext().jobQueue().addJob(new DelayedRunClient(className, clientName, argVal));
            }
            i++;
        }
    }

    private Properties getClientApps() {
        Properties rv = new Properties();
        String clientConfigFile = getContext().getProperty(PROP_CLIENT_CONFIG_FILENAME, DEFAULT_CLIENT_CONFIG_FILENAME);
        File cfgFile = new File(clientConfigFile);
        
        // fall back to use router.config's clientApp.* lines
        if (!cfgFile.exists()) 
            return new Properties(getContext().router().getConfigMap());
        
        try {
            DataHelper.loadProps(rv, cfgFile);
        } catch (IOException ioe) {
            _log.warn("Error loading the client app properties from " + cfgFile.getName(), ioe);
        }
        
        return rv;
    }
    
    private class DelayedRunClient extends JobImpl {
        private String _className;
        private String _clientName;
        private String _args[];
        public DelayedRunClient(String className, String clientName, String args[]) {
            super(LoadClientAppsJob.this.getContext());
            _className = className;
            _clientName = clientName;
            _args = args;
            getTiming().setStartAfter(LoadClientAppsJob.this.getContext().clock().now() + STARTUP_DELAY);
        }
        public String getName() { return "Delayed client job"; }
        public void runJob() {
            runClient(_className, _clientName, _args);
        }
    }
    
    static String[] parseArgs(String args) {
        List argList = new ArrayList(4);
        if (args != null) {
            char data[] = args.toCharArray();
            StringBuffer buf = new StringBuffer(32);
            boolean isQuoted = false;
            for (int i = 0; i < data.length; i++) {
                switch (data[i]) {
                    case '\'':
                    case '\"':
                        if (isQuoted) {
                            String str = buf.toString().trim();
                            if (str.length() > 0)
                                argList.add(str);
                            buf = new StringBuffer(32);
                        } else {
                            isQuoted = true;
                        }
                        break;
                    case ' ':
                    case '\t':
                        // whitespace - if we're in a quoted section, keep this as part of the quote,
                        // otherwise use it as a delim
                        if (isQuoted) {
                            buf.append(data[i]);
                        } else {
                            String str = buf.toString().trim();
                            if (str.length() > 0)
                                argList.add(str);
                            buf = new StringBuffer(32);
                        }
                        break;
                    default:
                        buf.append(data[i]);
                        break;
                }
            }
            if (buf.length() > 0) {
                String str = buf.toString().trim();
                if (str.length() > 0)
                    argList.add(str);
            }
        }
        String rv[] = new String[argList.size()];
        for (int i = 0; i < argList.size(); i++) 
            rv[i] = (String)argList.get(i);
        return rv;
    }

    private void runClient(String className, String clientName, String args[]) {
        _log.info("Loading up the client application " + clientName + ": " + className + " " + args);
        I2PThread t = new I2PThread(new RunApp(className, clientName, args));
        if (clientName == null) 
            clientName = className + " client";
        t.setName(clientName);
        t.setDaemon(true);
        t.start();
    }

    private final class RunApp implements Runnable {
        private String _className;
        private String _appName;
        private String _args[];
        public RunApp(String className, String appName, String args[]) { 
            _className = className; 
            _appName = appName;
            if (args == null)
                _args = new String[0];
            else
                _args = args;
        }
        public void run() {
            try {
                Class cls = Class.forName(_className);
                Method method = cls.getMethod("main", new Class[] { String[].class });
                method.invoke(cls, new Object[] { _args });
            } catch (Throwable t) {
                _log.log(Log.CRIT, "Error starting up the client class " + _className, t);
            }
            _log.info("Done running client application " + _appName);
        }
    }

    public String getName() { return "Load up any client applications"; }
    
    public static void main(String args[]) {
        test(null);
        test("hi how are you?");
        test("hi how are you? ");
        test(" hi how are you? ");
        test(" hi how are \"y\"ou? ");
        test("-nogui -e \"config localhost 17654\" -e \"httpclient 4544\"");
        test("-nogui -e 'config localhost 17654' -e 'httpclient 4544'");
    }
    private static void test(String args) {
        String parsed[] = parseArgs(args);
        System.out.print("Parsed [" + args + "] into " + parsed.length + " elements: ");
        for (int i = 0; i < parsed.length; i++)
            System.out.print("[" + parsed[i] + "] ");
        System.out.println();
    }
}
