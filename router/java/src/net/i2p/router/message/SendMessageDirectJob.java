package net.i2p.router.message;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.Date;

import net.i2p.data.Hash;
import net.i2p.data.RouterInfo;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.router.InNetMessage;
import net.i2p.router.InNetMessagePool;
import net.i2p.router.Job;
import net.i2p.router.JobImpl;
import net.i2p.router.JobQueue;
import net.i2p.router.MessageSelector;
import net.i2p.router.NetworkDatabaseFacade;
import net.i2p.router.OutNetMessage;
import net.i2p.router.OutNetMessagePool;
import net.i2p.router.ReplyJob;
import net.i2p.router.Router;
import net.i2p.router.transport.OutboundMessageRegistry;
import net.i2p.util.Clock;
import net.i2p.util.Log;
import net.i2p.router.RouterContext;

public class SendMessageDirectJob extends JobImpl {
    private Log _log;
    private I2NPMessage _message;
    private Hash _targetHash;
    private RouterInfo _router;
    private long _expiration;
    private int _priority;
    private Job _onSend;
    private ReplyJob _onSuccess;
    private Job _onFail;
    private MessageSelector _selector;
    private boolean _alreadySearched;
    private boolean _sent;
    private long _searchOn;
    
    private final static long DEFAULT_TIMEOUT = 60*1000;
    
    public SendMessageDirectJob(RouterContext ctx, I2NPMessage message, Hash toPeer, long expiration, int priority) {
        this(ctx, message, toPeer, null, null, null, null, expiration, priority);
    }
    public SendMessageDirectJob(RouterContext ctx, I2NPMessage message, Hash toPeer, int priority) {
        this(ctx, message, toPeer, DEFAULT_TIMEOUT+ctx.clock().now(), priority);
    }
    public SendMessageDirectJob(RouterContext ctx, I2NPMessage message, Hash toPeer, ReplyJob onSuccess, Job onFail, MessageSelector selector, long expiration, int priority) {
        this(ctx, message, toPeer, null, onSuccess, onFail, selector, expiration, priority);
    }
    public SendMessageDirectJob(RouterContext ctx, I2NPMessage message, Hash toPeer, Job onSend, ReplyJob onSuccess, Job onFail, MessageSelector selector, long expiration, int priority) {
        super(ctx);
        _log = _context.logManager().getLog(SendMessageDirectJob.class);
        _message = message;
        _targetHash = toPeer;
        _router = null;
        _expiration = expiration;
        _priority = priority;
        _searchOn = 0;
        _alreadySearched = false;
        _onSend = onSend;
        _onSuccess = onSuccess;
        _onFail = onFail;
        _selector = selector;
        if (message == null)
            throw new IllegalArgumentException("Attempt to send a null message");
        if (_targetHash == null)
            throw new IllegalArgumentException("Attempt to send a message to a null peer");
        _sent = false;
        long remaining = expiration - _context.clock().now();
        if (remaining < 50*1000) {
            _log.info("Sending message to expire in " + remaining + "ms containing " + message.getUniqueId() + " (a " + message.getClass().getName() + ")", new Exception("SendDirect from"));
        }
    }
    
    public String getName() { return "Send Message Direct"; }
    public void runJob() { 
        long now = _context.clock().now();
        if (_expiration == 0) 
            _expiration = now + DEFAULT_TIMEOUT;

        if (_expiration - 30*1000 < now) {
            _log.info("Soon to expire sendDirect of " + _message.getClass().getName() 
                      + " [expiring in " + (_expiration-now) + "]", getAddedBy());
        }

        if (_expiration < now) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Timed out sending message " + _message + " directly (expiration = " 
                          + new Date(_expiration) + ") to " + _targetHash.toBase64(), getAddedBy());
            return;
        }
        if (_router != null) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Router specified, sending");
            send();
        } else {
            _router = _context.netDb().lookupRouterInfoLocally(_targetHash);
            if (_router != null) {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Router not specified but lookup found it");
                send();
            } else {
                if (!_alreadySearched) {
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Router not specified, so we're looking for it...");
                    _context.netDb().lookupRouterInfo(_targetHash, this, this, 
                                                      _expiration - _context.clock().now());
                    _searchOn = _context.clock().now();
                    _alreadySearched = true;
                } else {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Unable to find the router to send to: " + _targetHash 
                                   + " after searching for " + (_context.clock().now()-_searchOn) 
                                   + "ms, message: " + _message, getAddedBy());
                }
            }
        }
    }
    
    private void send() {
        if (_sent) { 
            if (_log.shouldLog(Log.WARN))
                _log.warn("Not resending!", new Exception("blah")); 
            return; 
        }
        _sent = true;
        Hash to = _router.getIdentity().getHash();
        Hash us = _context.router().getRouterInfo().getIdentity().getHash();
        if (us.equals(to)) {
            if (_selector != null) {
                OutNetMessage outM = new OutNetMessage(_context);
                outM.setExpiration(_expiration);
                outM.setMessage(_message);
                outM.setOnFailedReplyJob(_onFail);
                outM.setOnFailedSendJob(_onFail);
                outM.setOnReplyJob(_onSuccess);
                outM.setOnSendJob(_onSend);
                outM.setPriority(_priority);
                outM.setReplySelector(_selector);
                outM.setTarget(_router);
                _context.messageRegistry().registerPending(outM);
            }

            if (_onSend != null)
                _context.jobQueue().addJob(_onSend);

            InNetMessage msg = new InNetMessage();
            msg.setFromRouter(_router.getIdentity());
            msg.setMessage(_message);
            _context.inNetMessagePool().add(msg);

            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Adding " + _message.getClass().getName() 
                           + " to inbound message pool as it was destined for ourselves");
            //_log.debug("debug", _createdBy);
        } else {
            OutNetMessage msg = new OutNetMessage(_context);
            msg.setExpiration(_expiration);
            msg.setMessage(_message);
            msg.setOnFailedReplyJob(_onFail);
            msg.setOnFailedSendJob(_onFail);
            msg.setOnReplyJob(_onSuccess);
            msg.setOnSendJob(_onSend);
            msg.setPriority(_priority);
            msg.setReplySelector(_selector);
            msg.setTarget(_router);
            _context.outNetMessagePool().add(msg);
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Adding " + _message.getClass().getName() 
                           + " to outbound message pool targeting " 
                           + _router.getIdentity().getHash().toBase64());
            //_log.debug("Message pooled: " + _message);
        }
    }
}
