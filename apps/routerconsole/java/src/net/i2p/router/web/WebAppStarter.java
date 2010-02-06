package net.i2p.router.web;

import java.io.File;
import java.io.IOException;
import java.util.Properties;
import java.util.StringTokenizer;

import net.i2p.I2PAppContext;

import org.mortbay.jetty.Server;
import org.mortbay.jetty.servlet.WebApplicationContext;


/**
 *  Start a webappapp classpath as specified in webapps.config.
 *
 *  Sadly, setting Class-Path in MANIFEST.MF doesn't work for jetty wars.
 *  We could look there ourselves, or look for another properties file in the war,
 *  but let's just do it in webapps.config.
 *
 *  No, wac.addClassPath() does not work.
 *
 *  http://servlets.com/archive/servlet/ReadMsg?msgId=511113&listName=jetty-support
 *
 *  @since 0.7.12
 *  @author zzz
 */
public class WebAppStarter {

    /**
     *  adds and starts
     */
    static void startWebApp(I2PAppContext ctx, Server server, String appName, String warPath) throws Exception {
         File tmpdir = new File(ctx.getTempDir(), "jetty-work-" + appName + ctx.random().nextInt());
         WebApplicationContext wac = addWebApp(ctx, server, appName, warPath, tmpdir);
         wac.start();
    }

    /**
     *  add but don't start
     */
    static WebApplicationContext addWebApp(I2PAppContext ctx, Server server, String appName, String warPath, File tmpdir) throws IOException {

        WebApplicationContext wac = server.addWebApplication("/"+ appName, warPath);
        tmpdir.mkdir();
        wac.setTempDirectory(tmpdir);

        // this does the passwords...
        RouterConsoleRunner.initialize(wac);

        // see WebAppConfiguration for info
        String[] classNames = server.getWebApplicationConfigurationClassNames();
        String[] newClassNames = new String[classNames.length + 1];
        for (int j = 0; j < classNames.length; j++)
             newClassNames[j] = classNames[j];
        newClassNames[classNames.length] = WebAppConfiguration.class.getName();
        wac.setConfigurationClassNames(newClassNames);
        return wac;
    }
}
