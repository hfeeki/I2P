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
import java.io.OutputStream;

import net.i2p.util.Log;

/**
 * Defines a certificate that can be attached to various I2P structures, such
 * as RouterIdentity and Destination, allowing routers and clients to help
 * manage denial of service attacks and the network utilization.  Certificates
 * can even be defined to include identifiable information signed by some 
 * certificate authority, though that use probably isn't appropriate for an
 * anonymous network ;)
 *
 * @author jrandom
 */
public class Certificate extends DataStructureImpl {
    private final static Log _log = new Log(Certificate.class);
    private int _type;
    private byte[] _payload;

    /** Specifies a null certificate type with no payload */
    public final static int CERTIFICATE_TYPE_NULL = 0;
    /** specifies a Hashcash style certificate */
    public final static int CERTIFICATE_TYPE_HASHCASH = 1;

    public Certificate() {
        _type = 0;
        _payload = null;
    }

    public Certificate(int type, byte[] payload) {
        _type = type;
        _payload = payload;
    }

    /** */
    public int getCertificateType() {
        return _type;
    }

    public void setCertificateType(int type) {
        _type = type;
    }

    public byte[] getPayload() {
        return _payload;
    }

    public void setPayload(byte[] payload) {
        _payload = payload;
    }

    public void readBytes(InputStream in) throws DataFormatException, IOException {
        _type = (int) DataHelper.readLong(in, 1);
        int length = (int) DataHelper.readLong(in, 2);
        if (length > 0) {
            _payload = new byte[length];
            int read = read(in, _payload);
            if (read != length)
                throw new DataFormatException("Not enough bytes for the payload (read: " + read + " length: " + length
                                              + ")");
        }
    }

    public void writeBytes(OutputStream out) throws DataFormatException, IOException {
        if (_type < 0) throw new DataFormatException("Invalid certificate type: " + _type);
        if ((_type != 0) && (_payload == null)) throw new DataFormatException("Payload is required for non null type");

        DataHelper.writeLong(out, 1, _type);
        if (_payload != null) {
            DataHelper.writeLong(out, 2, _payload.length);
            out.write(_payload);
        } else {
            DataHelper.writeLong(out, 2, 0L);
        }
    }

    public boolean equals(Object object) {
        if ((object == null) || !(object instanceof Certificate)) return false;
        Certificate cert = (Certificate) object;
        return getCertificateType() == cert.getCertificateType() && DataHelper.eq(getPayload(), cert.getPayload());
    }

    public int hashCode() {
        return getCertificateType() + DataHelper.hashCode(getPayload());
    }

    public String toString() {
        StringBuffer buf = new StringBuffer(64);
        buf.append("[Certificate: type: ");
        if (getCertificateType() == CERTIFICATE_TYPE_NULL)
            buf.append("Null certificate");
        else if (getCertificateType() == CERTIFICATE_TYPE_HASHCASH)
            buf.append("Hashcash certificate");
        else
            buf.append("Unknown certificiate type (").append(getCertificateType()).append(")");

        if (_payload == null) {
            buf.append(" null payload");
        } else {
            buf.append(" payload size: ").append(_payload.length);
            int len = 32;
            if (len > _payload.length) len = _payload.length;
            buf.append(" first ").append(len).append(" bytes: ");
            buf.append(DataHelper.toString(_payload, len));
        }
        buf.append("]");
        return buf.toString();
    }
}