package net.i2p.data;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import net.i2p.data.DataFormatException;
import net.i2p.data.DataStructure;
import net.i2p.data.TunnelId;

/**
 * Test harness for loading / storing TunnelId objects
 *
 * @author jrandom
 */
class TunnelIdTest extends StructureTest {
    static {
        TestData.registerTest(new TunnelIdTest(), "TunnelId");
    }
    public DataStructure createDataStructure() throws DataFormatException {
        TunnelId id = new TunnelId();
        id.setTunnelId(42);
        return id; 
    }
    public DataStructure createStructureToRead() { return new TunnelId(); }
}
