package net.i2p;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

/**
 * Expose a version string
 *
 */
public class CoreVersion {
    public final static String ID = "$Revision: 1.15 $ $Date: 2004/07/29 16:37:19 $";
    public final static String VERSION = "0.3.4.1";

    public static void main(String args[]) {
        System.out.println("I2P Core version: " + VERSION);
        System.out.println("ID: " + ID);
    }
}