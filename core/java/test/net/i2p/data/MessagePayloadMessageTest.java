package net.i2p.data;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;

import net.i2p.data.DataFormatException;
import net.i2p.data.DataStructure;
import net.i2p.data.Payload;
import net.i2p.data.i2cp.MessageId;
import net.i2p.data.i2cp.MessagePayloadMessage;
import net.i2p.data.i2cp.SessionId;
import net.i2p.util.Log;

/**
 * Test harness for loading / storing SendMessageMessage objects
 *
 * @author jrandom
 */
 
 public class MessagePayloadMessageTest extends StructureTest {
    public DataStructure createDataStructure() throws DataFormatException {
        MessagePayloadMessage msg = new MessagePayloadMessage();
        msg.setMessageId(123);
        msg.setPayload((Payload)(new PayloadTest()).createDataStructure());
        msg.setSessionId(321);
        return msg; 
    }
    public DataStructure createStructureToRead() { return new MessagePayloadMessage(); }
    
    public void testStructure() throws Exception{
        byte[] temp = null;
        
        DataStructure orig;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        orig = createDataStructure();
        orig.writeBytes(baos);
        
        temp = baos.toByteArray();
        
        DataStructure ds;
        ByteArrayInputStream bais = new ByteArrayInputStream(temp);
        ds = createStructureToRead();
        ds.readBytes(bais);
        ((MessagePayloadMessage)ds).getPayload().setUnencryptedData(((MessagePayloadMessage)ds).getPayload().getEncryptedData());
        
        assertEquals(orig, ds);
    }
    
}
