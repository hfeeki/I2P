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
import net.i2p.data.LeaseSet;
import net.i2p.data.PrivateKey;
import net.i2p.data.SigningPrivateKey;
import net.i2p.data.i2cp.CreateLeaseSetMessage;
import net.i2p.data.i2cp.SessionId;

/**
 * Test harness for loading / storing CreateLeaseSetMessage objects
 *
 * @author jrandom
 */
class CreateLeaseSetMessageTest extends StructureTest {
    static {
        TestData.registerTest(new CreateLeaseSetMessageTest(), "CreateLeaseSetMessage");
    }
    public DataStructure createDataStructure() throws DataFormatException {
        CreateLeaseSetMessage msg = new CreateLeaseSetMessage();
	msg.setPrivateKey((PrivateKey)(new PrivateKeyTest()).createDataStructure());
	msg.setSigningPrivateKey((SigningPrivateKey)(new SigningPrivateKeyTest()).createDataStructure());
	msg.setLeaseSet((LeaseSet)(new LeaseSetTest()).createDataStructure());
        msg.setSessionId((SessionId)(new SessionIdTest()).createDataStructure());
        return msg; 
    }
    public DataStructure createStructureToRead() { return new CreateLeaseSetMessage(); }
}
