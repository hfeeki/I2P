package net.i2p.router.transport;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import net.i2p.data.RouterIdentity;
import java.io.FilterOutputStream;
import java.io.OutputStream;
import java.io.IOException;

public class BandwidthLimitedOutputStream extends FilterOutputStream {
    private RouterIdentity _peer;
    
    public BandwidthLimitedOutputStream(OutputStream source, RouterIdentity peer) {
	super(source);
	_peer = peer;
    }
    
    private final static int CHUNK_SIZE = 64;
    
    public void write(int val) throws IOException { 
	BandwidthLimiter.getInstance().delayOutbound(_peer, 1);
	out.write(val);
    }
    public void write(byte src[]) throws IOException { 
	if (src == null) return;
	if (src.length > CHUNK_SIZE) {
	    for (int i = 0; i < src.length; ) {
		write(src, i*CHUNK_SIZE, CHUNK_SIZE);
		i += CHUNK_SIZE;
	    }
	} else {
	    write(src, 0, src.length);
	}
    }
    public void write(byte src[], int off, int len) throws IOException { 
	if (src == null) return;
	if (len <= 0) return;
	if (len <= CHUNK_SIZE) {
	    BandwidthLimiter.getInstance().delayOutbound(_peer, len);
	    out.write(src, off, len);
	} else {
	    int i = 0;
	    while (i+CHUNK_SIZE < len) {
		BandwidthLimiter.getInstance().delayOutbound(_peer, CHUNK_SIZE);
		out.write(src, off+i*CHUNK_SIZE, CHUNK_SIZE);
		i++;
	    }
	    int remainder = len % CHUNK_SIZE;
	    if (remainder != 0) {
		BandwidthLimiter.getInstance().delayOutbound(_peer, remainder);
		out.write(src, off+len-(remainder), remainder);
	    }
	}
    }
}
