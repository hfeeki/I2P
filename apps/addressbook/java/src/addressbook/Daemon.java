/*
 * Copyright (c) 2004 Ragnarok
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * COPYRIGHT HOLDER BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package addressbook;

import java.util.Iterator;
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.LinkedList;
import java.io.File;

/**
 * Main class of addressbook.  Performs updates, and runs the main loop.
 * 
 * @author Ragnarok
 *
 */
public class Daemon {
    public static final String VERSION = "2.0.3";
    
    /**
     * Update the router and published address books using remote data from the
     * subscribed address books listed in subscriptions.
     * 
     * @param master
     *            The master AddressBook. This address book is never
     *            overwritten, so it is safe for the user to write to.
     * @param router
     *            The router AddressBook. This is the address book read by
     *            client applications.
     * @param published
     *            The published AddressBook. This address book is published on
     *            the user's eepsite so that others may subscribe to it.
     * @param subscriptions
     *            A SubscriptionList listing the remote address books to update
     *            from.
     * @param log
     *            The log to write changes and conflicts to.
     */
    public static void update(AddressBook master, AddressBook router,
            File published, SubscriptionList subscriptions, Log log) {
        String routerLocation = router.getLocation();
        master.merge(router);
        Iterator iter = subscriptions.iterator();
        while (iter.hasNext()) {
            master.merge((AddressBook) iter.next(), log);
        }
        master.write(new File(routerLocation));
        if (published != null)
            master.write(published);
        subscriptions.write();
    }

    /**
     * Run an update, using the Map settings to provide the parameters.
     * 
     * @param settings
     *            A Map containg the parameters needed by update.
     * @param home
     *            The directory containing addressbook's configuration files.
     */
    public static void update(Map settings, String home) {
        File masterFile = new File(home, (String) settings
                .get("master_addressbook"));
        File routerFile = new File(home, (String) settings
                .get("router_addressbook"));
        File published = null;
        if ("true".equals(settings.get("should_publish"))) 
            published = new File(home, (String) settings
                .get("published_addressbook"));
        File subscriptionFile = new File(home, (String) settings
                .get("subscriptions"));
        File logFile = new File(home, (String) settings.get("log"));
        File etagsFile = new File(home, (String) settings.get("etags"));
        File lastModifiedFile = new File(home, (String) settings
                .get("last_modified"));

        AddressBook master = new AddressBook(masterFile);
        AddressBook router = new AddressBook(routerFile);
        
        List defaultSubs = new LinkedList();
        defaultSubs.add("http://i2p/NF2RLVUxVulR3IqK0sGJR0dHQcGXAzwa6rEO4WAWYXOHw-DoZhKnlbf1nzHXwMEJoex5nFTyiNMqxJMWlY54cvU~UenZdkyQQeUSBZXyuSweflUXFqKN-y8xIoK2w9Ylq1k8IcrAFDsITyOzjUKoOPfVq34rKNDo7fYyis4kT5bAHy~2N1EVMs34pi2RFabATIOBk38Qhab57Umpa6yEoE~rbyR~suDRvD7gjBvBiIKFqhFueXsR2uSrPB-yzwAGofTXuklofK3DdKspciclTVzqbDjsk5UXfu2nTrC1agkhLyqlOfjhyqC~t1IXm-Vs2o7911k7KKLGjB4lmH508YJ7G9fLAUyjuB-wwwhejoWqvg7oWvqo4oIok8LG6ECR71C3dzCvIjY2QcrhoaazA9G4zcGMm6NKND-H4XY6tUWhpB~5GefB3YczOqMbHq4wi0O9MzBFrOJEOs3X4hwboKWANf7DT5PZKJZ5KorQPsYRSq0E3wSOsFCSsdVCKUGsAAAA/i2p/hosts.txt");
        
        SubscriptionList subscriptions = new SubscriptionList(subscriptionFile,
                etagsFile, lastModifiedFile, defaultSubs);
        Log log = new Log(logFile);

        Daemon.update(master, router, published, subscriptions, log);
    }

    /**
     * Load the settings, set the proxy, then enter into the main loop. The main
     * loop performs an immediate update, and then an update every number of
     * hours, as configured in the settings file.
     * 
     * @param args
     *            Command line arguments. If there are any arguments provided,
     *            the first is taken as addressbook's home directory, and the
     *            others are ignored.
     */
    public static void main(String[] args) {
        String settingsLocation = "config.txt";
        Map settings = new HashMap();
        String home;
        if (args.length > 0) {
            home = args[0];
        } else {
            home = ".";
        }
        
        Map defaultSettings = new HashMap();
        defaultSettings.put("proxy_host", "localhost");
        defaultSettings.put("proxy_port", "4444");
        defaultSettings.put("master_addressbook", "../userhosts.txt");
        defaultSettings.put("router_addressbook", "../hosts.txt");
        defaultSettings.put("published_addressbook", "../eepsite/docroot/hosts.txt");
        defaultSettings.put("should_publish", "false");
        defaultSettings.put("log", "log.txt");
        defaultSettings.put("subscriptions", "subscriptions.txt");
        defaultSettings.put("etags", "etags");
        defaultSettings.put("last_modified", "last_modified");
        defaultSettings.put("update_delay", "1");
        
        File homeFile = new File(home);
        if (!homeFile.exists()) {
            boolean created = homeFile.mkdirs();
            if (created)
                System.out.println("INFO:  Addressbook directory " + homeFile.getName() + " created");
            else
                System.out.println("ERROR: Addressbook directory " + homeFile.getName() + " could not be created");
        }
        
        File settingsFile = new File(homeFile, settingsLocation);
        
        while (true) {
            settings = ConfigParser.parse(settingsFile, defaultSettings);

            System.setProperty("proxySet", "true");
            System.setProperty("http.proxyHost", (String) settings
                    .get("proxy_host"));
            System.setProperty("http.proxyPort", (String) settings
                    .get("proxy_port"));
            long delay = Long.parseLong((String) settings.get("update_delay"));
            if (delay < 1) {
                delay = 1;
            }
            
            Daemon.update(settings, home);
            try {
                Thread.sleep(delay * 60 * 60 * 1000);
            } catch (InterruptedException exp) {
            }
        }
    }
}