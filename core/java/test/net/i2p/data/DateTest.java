package net.i2p.data;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Date;

import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.util.Log;

import junit.framework.TestCase;

/**
 * Test harness for the date structure
 *
 * @author jrandom
 */
public class DateTest extends TestCase{
    
    public void testDate() throws Exception{
        byte[] temp = null;
        
        Date orig = new Date();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        
        DataHelper.writeDate(baos, orig);
        temp = baos.toByteArray();
        
        
        Date d = null;
        ByteArrayInputStream bais = new ByteArrayInputStream(temp);
        
        d = DataHelper.readDate(bais);
        
        assertEquals(orig, d);
    }
    
}