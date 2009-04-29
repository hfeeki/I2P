package net.i2p.router.transport;

/*
 * public domain
 */

import java.net.InetAddress;
import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;


/**
 * Get the local addresses
 *
 * @author zzz
 */
public class Addresses {
    
    /** return the first non-local address it finds, or null */
    public static String getAnyAddress() {
        String[] a = getAddresses();
        if (a.length > 0)
            return a[0];
        return null;
    }

    /**
     *  Return an array of all addresses, excluding
     *  IPv6, local, broadcast, multicast, etc.
     */
    public static String[] getAddresses() {
        Set<String> rv = new HashSet(4);
        try {
            InetAddress localhost = InetAddress.getLocalHost();
            InetAddress[] allMyIps = InetAddress.getAllByName(localhost.getCanonicalHostName());
            if (allMyIps != null) {
                for (int i = 0; i < allMyIps.length; i++)
                     add(rv, allMyIps[i]);
            }
        } catch (UnknownHostException e) {}

        try {
            for(Enumeration<NetworkInterface> ifcs = NetworkInterface.getNetworkInterfaces(); ifcs.hasMoreElements();) {
                NetworkInterface ifc = ifcs.nextElement();
                for(Enumeration<InetAddress> addrs =  ifc.getInetAddresses(); addrs.hasMoreElements();) {
                    InetAddress addr = addrs.nextElement();
                    add(rv, addr);
                }
            }
        } catch (SocketException e) {}

        return rv.toArray(new String[rv.size()]);
    }

    private static void add(Set<String> set, InetAddress ia) {
        if (ia.isAnyLocalAddress() ||
            ia.isLinkLocalAddress() ||
            ia.isLoopbackAddress() ||
            ia.isMulticastAddress() ||
            ia.isSiteLocalAddress() ||
            !(ia instanceof Inet4Address)) {
//            System.err.println("Skipping: " + ia.getHostAddress());
            return;
        }
        String ip = ia.getHostAddress();
        set.add(ip);
    }

    public static void main(String[] args) {
        String[] a = getAddresses();
        for (String s : a)
            System.err.println("Address: " + s);
    }
}
