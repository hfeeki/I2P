package net.i2p.data.i2np;
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

import net.i2p.crypto.DSAEngine;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.DataStructureImpl;
import net.i2p.data.Hash;
import net.i2p.data.Signature;
import net.i2p.data.SigningPrivateKey;
import net.i2p.data.SigningPublicKey;
import net.i2p.util.Log;

/**
 *
 * @author jrandom
 */
public class TunnelVerificationStructure extends DataStructureImpl {
    private final static Log _log = new Log(TunnelVerificationStructure.class);
    private Hash _msgHash;
    private Signature _authSignature;
    
    public TunnelVerificationStructure() { 
	setMessageHash(null); 
	setAuthorizationSignature(null);
    }
    
    public Hash getMessageHash() { return _msgHash; }
    public void setMessageHash(Hash hash) { _msgHash = hash; }
    
    public Signature getAuthorizationSignature() { return _authSignature; }
    public void setAuthorizationSignature(Signature sig) { _authSignature = sig; }
    
    public void sign(SigningPrivateKey key) {
	if (_msgHash != null) {
	    Signature sig = DSAEngine.getInstance().sign(_msgHash.getData(), key);
	    setAuthorizationSignature(sig);
	}
    }
    public boolean verifySignature(SigningPublicKey key) {
	if (_msgHash == null) return false;
	return DSAEngine.getInstance().verifySignature(_authSignature, _msgHash.getData(), key);
    }
    
    public void readBytes(InputStream in) throws DataFormatException, IOException {
	_msgHash = new Hash();
	_msgHash.readBytes(in);
	_authSignature = new Signature();
	_authSignature.readBytes(in);
    }
    
    public void writeBytes(OutputStream out) throws DataFormatException, IOException {
	if (_authSignature == null) { 
	    _authSignature = new Signature();
	    _authSignature.setData(Signature.FAKE_SIGNATURE);
	}
        if ( (_msgHash == null) || (_authSignature == null) ) throw new DataFormatException("Invalid data");
	_msgHash.writeBytes(out);
	_authSignature.writeBytes(out);
    }
    
    public boolean equals(Object obj) {
        if ( (obj == null) || !(obj instanceof TunnelVerificationStructure))
            return false;
	TunnelVerificationStructure str = (TunnelVerificationStructure)obj;
	return DataHelper.eq(getMessageHash(), str.getMessageHash()) &&
	       DataHelper.eq(getAuthorizationSignature(), str.getAuthorizationSignature());
    }
    
    public int hashCode() {
	if ( (_msgHash == null) || (_authSignature == null) ) return 0;
	return getMessageHash().hashCode() + getAuthorizationSignature().hashCode();
    }
    
    public String toString() {
        return "[TunnelVerificationStructure: " + getMessageHash() + " " + getAuthorizationSignature() + "]";
    }
}
