package net.i2p.client;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't  make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.HashSet;
import java.util.Set;

import net.i2p.crypto.ElGamalAESEngine;
import net.i2p.data.DataFormatException;
import net.i2p.data.Destination;
import net.i2p.data.LeaseSet;
import net.i2p.data.Payload;
import net.i2p.data.PrivateKey;
import net.i2p.data.SessionKey;
import net.i2p.data.SessionTag;
import net.i2p.data.SigningPrivateKey;
import net.i2p.data.i2cp.AbuseReason;
import net.i2p.data.i2cp.AbuseSeverity;
import net.i2p.data.i2cp.CreateLeaseSetMessage;
import net.i2p.data.i2cp.CreateSessionMessage;
import net.i2p.data.i2cp.DestroySessionMessage;
import net.i2p.data.i2cp.MessageId;
import net.i2p.data.i2cp.ReportAbuseMessage;
import net.i2p.data.i2cp.SendMessageMessage;
import net.i2p.data.i2cp.SessionConfig;
import net.i2p.util.Log;
import net.i2p.util.RandomSource;

/**
 * Produce the various messages the session needs to send to the router.
 *
 * @author jrandom
 */
class I2CPMessageProducer {
    private final static Log _log = new Log(I2CPMessageProducer.class);
    private final static RandomSource _rand = RandomSource.getInstance();
    
    /** 
     * Send all the messages that a client needs to send to a router to establish
     * a new session.  
     */
    public void connect(I2PSessionImpl session) throws I2PSessionException {
        CreateSessionMessage msg = new CreateSessionMessage();
        SessionConfig cfg = new SessionConfig();
        cfg.setDestination(session.getMyDestination());
        cfg.setOptions(session.getOptions());
        try {
            cfg.signSessionConfig(session.getPrivateKey());
        } catch (DataFormatException dfe) {
            throw new I2PSessionException("Unable to sign the session config", dfe);
        }
        msg.setSessionConfig(cfg);
        session.sendMessage(msg);
    }
    
    /**
     * Send messages to the router destroying the session and disconnecting
     *
     */
    public void disconnect(I2PSessionImpl session) throws I2PSessionException {
	DestroySessionMessage dmsg = new DestroySessionMessage();
	dmsg.setSessionId(session.getSessionId());
	session.sendMessage(dmsg);
	// use DisconnectMessage only if we fail and drop connection... 
	// todo: update the code to fire off DisconnectMessage on socket error
        //DisconnectMessage msg = new DisconnectMessage();
        //msg.setReason("Destroy called");
        //session.sendMessage(msg);
    }
    
    /**
     * Package up and send the payload to the router for delivery
     *
     */
    public void sendMessage(I2PSessionImpl session, Destination dest, long nonce, byte[] payload, SessionTag tag, SessionKey key, Set tags, SessionKey newKey) throws I2PSessionException {
        SendMessageMessage msg = new SendMessageMessage();
        msg.setDestination(dest);
        msg.setSessionId(session.getSessionId());
	msg.setNonce(nonce);
        Payload data = createPayload(dest, payload, tag, key, tags, newKey);
        msg.setPayload(data);
        session.sendMessage(msg);
    }
    
    /**
     * Create a new signed payload and send it off to the destination
     *
     */
    private Payload createPayload(Destination dest, byte[] payload, SessionTag tag, SessionKey key, Set tags, SessionKey newKey) throws I2PSessionException {
	if (dest == null)
	    throw new I2PSessionException("No destination specified");
	if (payload == null)
	    throw new I2PSessionException("No payload specified");

	Payload data = new Payload();
	// randomize padding
	int size = payload.length + RandomSource.getInstance().nextInt(1024);
	byte encr[] = ElGamalAESEngine.encrypt(payload, dest.getPublicKey(), key, tags, tag, newKey, size); 
	// yes, in an intelligent component, newTags would be queued for confirmation along with key, and
	// generateNewTags would only generate tags if necessary
	
	data.setEncryptedData(encr);
	_log.debug("Encrypting the payload to public key " + dest.getPublicKey().toBase64() + "\nPayload: " + data.calculateHash());
	return data;
    }
    
    private static Set generateNewTags() {
	Set tags = new HashSet();
	for (int i = 0; i < 10; i++) {
	    byte tag[] = new byte[SessionTag.BYTE_LENGTH];
	    RandomSource.getInstance().nextBytes(tag);
	    tags.add(new SessionTag(tag));
	}
	return tags;
    }
    
    /**
     * Send an abuse message to the router
     */
    public void reportAbuse(I2PSessionImpl session, int msgId, int severity) throws I2PSessionException {
        ReportAbuseMessage msg = new ReportAbuseMessage();
        MessageId id = new MessageId();
        id.setMessageId(msgId);
        msg.setMessageId(id);
        AbuseReason reason = new AbuseReason();
        reason.setReason("Not specified");
        msg.setReason(reason);
        AbuseSeverity sv = new AbuseSeverity();
        sv.setSeverity(severity);
        msg.setSeverity(sv);
        session.sendMessage(msg);
    }
    
    /**
     * Create a new signed leaseSet in response to a request to do so and send it
     * to the router
     * 
     */
    public void createLeaseSet(I2PSessionImpl session, LeaseSet leaseSet, SigningPrivateKey signingPriv, PrivateKey priv) throws I2PSessionException {
        CreateLeaseSetMessage msg = new CreateLeaseSetMessage();
	msg.setLeaseSet(leaseSet);
	msg.setPrivateKey(priv);
	msg.setSigningPrivateKey(signingPriv);
	msg.setSessionId(session.getSessionId());
        session.sendMessage(msg);
    }
}
