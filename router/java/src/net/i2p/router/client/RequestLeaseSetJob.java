package net.i2p.router.client;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.Date;

import net.i2p.data.LeaseSet;
import net.i2p.data.i2cp.I2CPMessageException;
import net.i2p.data.i2cp.RequestLeaseSetMessage;
import net.i2p.router.Job;
import net.i2p.router.JobImpl;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 * Async job to walk the client through generating a lease set.  First sends it
 * to the client and then queues up a CheckLeaseRequestStatus job for
 * processing after the expiration.  When that CheckLeaseRequestStatus is run,
 * if the client still hasn't provided the signed leaseSet, fire off the onFailed
 * job from the intermediary LeaseRequestState and drop the client.
 *
 */
class RequestLeaseSetJob extends JobImpl {
    private Log _log;
    private ClientConnectionRunner _runner;
    private LeaseSet _ls;
    private long _expiration;
    private Job _onCreate;
    private Job _onFail;
    public RequestLeaseSetJob(RouterContext ctx, ClientConnectionRunner runner, LeaseSet set, long expiration, Job onCreate, Job onFail) {
        super(ctx);
        _log = ctx.logManager().getLog(RequestLeaseSetJob.class);
        _runner = runner;
        _ls = set;
        _expiration = expiration;
        _onCreate = onCreate;
        _onFail = onFail;
        ctx.statManager().createRateStat("client.requestLeaseSetSuccess", "How frequently the router requests successfully a new leaseSet?", "ClientMessages", new long[] { 10*60*1000, 60*60*1000, 24*60*60*1000 });
        ctx.statManager().createRateStat("client.requestLeaseSetTimeout", "How frequently the router requests a new leaseSet but gets no reply?", "ClientMessages", new long[] { 10*60*1000, 60*60*1000, 24*60*60*1000 });
        ctx.statManager().createRateStat("client.requestLeaseSetDropped", "How frequently the router requests a new leaseSet but the client drops?", "ClientMessages", new long[] { 10*60*1000, 60*60*1000, 24*60*60*1000 });
    }
    
    public String getName() { return "Request Lease Set"; }
    public void runJob() {
        if (_runner.isDead()) return;
        LeaseRequestState oldReq = _runner.getLeaseRequest();
        if (oldReq != null) {
            if (oldReq.getExpiration() > getContext().clock().now()) {
                _log.info("request of a leaseSet is still active, wait a little bit before asking again");
                requeue(5*1000);
                return;
            } else {
                _log.error("Old *expired* leaseRequest exists!  Why did the old request not get killed? (expiration = " + new Date(oldReq.getExpiration()) + ")", getAddedBy());
            }
        }
        
        LeaseRequestState state = new LeaseRequestState(_onCreate, _onFail, _expiration, _ls);
        
        RequestLeaseSetMessage msg = new RequestLeaseSetMessage();
        Date end = null;
        // get the earliest end date
        for (int i = 0; i < state.getRequested().getLeaseCount(); i++) {
            if ( (end == null) || (end.getTime() > state.getRequested().getLease(i).getEndDate().getTime()) )
                end = state.getRequested().getLease(i).getEndDate();
        }
        
        msg.setEndDate(end);
        msg.setSessionId(_runner.getSessionId());
        
        for (int i = 0; i < state.getRequested().getLeaseCount(); i++) {
            msg.addEndpoint(state.getRequested().getLease(i).getGateway(), state.getRequested().getLease(i).getTunnelId());
        }
        
        try {
            _runner.setLeaseRequest(state);
            _runner.doSend(msg);
            getContext().jobQueue().addJob(new CheckLeaseRequestStatus(getContext(), state));
            return;
        } catch (I2CPMessageException ime) {
            getContext().statManager().addRateData("client.requestLeaseSetDropped", 1, 0);
            _log.error("Error sending I2CP message requesting the lease set", ime);
            state.setIsSuccessful(false);
            _runner.setLeaseRequest(null);
            _runner.disconnectClient("I2CP error requesting leaseSet");
            return;
        }
    }
    
    /**
     * Schedule this job to be run after the request's expiration, so that if
     * it wasn't yet successful, we fire off the failure job and disconnect the
     * client (but if it was, noop)
     *
     */
    private class CheckLeaseRequestStatus extends JobImpl {
        private LeaseRequestState _req;
        
        public CheckLeaseRequestStatus(RouterContext enclosingContext, LeaseRequestState state) {
            super(enclosingContext);
            _req = state;
            getTiming().setStartAfter(state.getExpiration());
        }
        
        public void runJob() {
            if (_runner.isDead()) return;
            if (_req.getIsSuccessful()) {
                // we didn't fail
                RequestLeaseSetJob.CheckLeaseRequestStatus.this.getContext().statManager().addRateData("client.requestLeaseSetSuccess", 1, 0);
                return;
            } else {
                RequestLeaseSetJob.CheckLeaseRequestStatus.this.getContext().statManager().addRateData("client.requestLeaseSetTimeout", 1, 0);
                if (_log.shouldLog(Log.CRIT))
                    _log.log(Log.CRIT, "Failed to receive a leaseSet in the time allotted (" + new Date(_req.getExpiration()) + ") for " 
                             + _runner.getConfig().getDestination().calculateHash().toBase64());
                _runner.disconnectClient("Took too long to request leaseSet");
                if (_req.getOnFailed() != null)
                    RequestLeaseSetJob.this.getContext().jobQueue().addJob(_req.getOnFailed());
                
                // only zero out the request if its the one we know about
                if (_req == _runner.getLeaseRequest())
                    _runner.setLeaseRequest(null);
            }
        }
        public String getName() { return "Check LeaseRequest Status"; }
    }
}
