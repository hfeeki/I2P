package net.i2p.sam;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by human in 2004 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't  make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.IOException;

/**
 * Interface for sending raw data to a SAM client
 */
public interface SAMRawReceiver {

    /**
     * Send a byte array to a SAM client, without informations
     * regarding the sender.
     *
     * @param data Byte array to be received
     */
    public void receiveRawBytes(byte data[]) throws IOException;

    /**
     * Stop receiving data.
     *
     */
    public void stopRawReceiving();
}
