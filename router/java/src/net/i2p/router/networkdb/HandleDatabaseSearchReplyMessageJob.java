package net.i2p.router.networkdb;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import net.i2p.data.Hash;
import net.i2p.data.RouterIdentity;
import net.i2p.data.RouterInfo;
import net.i2p.data.i2np.DatabaseSearchReplyMessage;
import net.i2p.router.JobImpl;
import net.i2p.router.JobQueue;
import net.i2p.router.NetworkDatabaseFacade;
import net.i2p.util.Log;

/**
 * Receive DatabaseSearchReplyMessage data and store it in the local net db
 *
 */
public class HandleDatabaseSearchReplyMessageJob extends JobImpl {
    private final static Log _log = new Log(HandleDatabaseSearchReplyMessageJob.class);
    private DatabaseSearchReplyMessage _message;
    private RouterIdentity _from;
    private Hash _fromHash;
    
    public HandleDatabaseSearchReplyMessageJob(DatabaseSearchReplyMessage receivedMessage, RouterIdentity from, Hash fromHash) {
	_message = receivedMessage;
	_from = from;
	_fromHash = fromHash;
    }
    
    public void runJob() {
	if (_log.shouldLog(Log.DEBUG))
	    _log.debug("Handling database search reply message for key " + _message.getSearchKey().toBase64() + " with " + _message.getNumReplies() + " replies");
	if (_message.getNumReplies() > 0)
	    JobQueue.getInstance().addJob(new HandlePeerJob(0));
    }
    
    /**
     * Partial job - take each reply entry, store it, then requeue again until all 
     * of the entries are stored.  This prevents a single reply from swamping the jobqueue
     *
     */
    private final class HandlePeerJob extends JobImpl {
	private int _curReply;
	public HandlePeerJob(int reply) {
	    _curReply = reply;
	}
	public void runJob() {
	    boolean remaining = handle();
	    if (remaining)
		requeue(0);
	}
	
	private boolean handle() {
	    RouterInfo info = _message.getReply(_curReply);
	    if (_log.shouldLog(Log.INFO))
		_log.info("On search for " + _message.getSearchKey().toBase64() + ", received " + info.getIdentity().getHash().toBase64());
	    NetworkDatabaseFacade.getInstance().store(info.getIdentity().getHash(), info);
	    _curReply++;
	    return _message.getNumReplies() > _curReply;
	}
	public String getName() { return "Handle search reply value"; }
    }
    
    public String getName() { return "Handle Database Search Reply Message"; }
}
