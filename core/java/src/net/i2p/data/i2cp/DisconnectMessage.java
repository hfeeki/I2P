package net.i2p.data.i2cp;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.util.Log;

/**
 * Defines the message a client sends to a router when destroying
 * existing session.
 *
 * @author jrandom
 */
public class DisconnectMessage extends I2CPMessageImpl {
    private final static Log _log = new Log(DisconnectMessage.class);
    public final static int MESSAGE_TYPE = 30;
    private String _reason;

    public DisconnectMessage() {
        setReason(null);
    }

    public String getReason() {
        return _reason;
    }

    public void setReason(String reason) {
        _reason = reason;
    }

    protected void doReadMessage(InputStream in, int size) throws I2CPMessageException, IOException {
        try {
            _reason = DataHelper.readString(in);
        } catch (DataFormatException dfe) {
            throw new I2CPMessageException("Unable to load the message data", dfe);
        }
    }

    protected byte[] doWriteMessage() throws I2CPMessageException, IOException {
        ByteArrayOutputStream os = new ByteArrayOutputStream(64);
        try {
            DataHelper.writeString(os, _reason);
        } catch (DataFormatException dfe) {
            throw new I2CPMessageException("Error writing out the message data", dfe);
        }
        return os.toByteArray();
    }

    public int getType() {
        return MESSAGE_TYPE;
    }

    @Override
    public boolean equals(Object object) {
        if ((object != null) && (object instanceof DisconnectMessage)) {
            DisconnectMessage msg = (DisconnectMessage) object;
            return DataHelper.eq(getReason(), msg.getReason());
        }
 
        return false;
    }

    @Override
    public String toString() {
        StringBuffer buf = new StringBuffer();
        buf.append("[DisconnectMessage: ");
        buf.append("\n\tReason: ").append(getReason());
        buf.append("]");
        return buf.toString();
    }
}