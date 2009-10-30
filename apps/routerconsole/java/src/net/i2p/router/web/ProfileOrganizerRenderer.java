package net.i2p.router.web;

import java.io.IOException;
import java.io.Writer;
import java.text.DecimalFormat;
import java.util.Comparator;
import java.util.Iterator;
import java.util.Set;
import java.util.TreeSet;

import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.RouterInfo;
import net.i2p.router.RouterContext;
import net.i2p.router.peermanager.DBHistory;
import net.i2p.router.peermanager.PeerProfile;
import net.i2p.router.peermanager.ProfileOrganizer;
import net.i2p.stat.Rate;
import net.i2p.stat.RateStat;

/**
 * Helper class to refactor the HTML rendering from out of the ProfileOrganizer
 *
 */
class ProfileOrganizerRenderer {
    private RouterContext _context;
    private ProfileOrganizer _organizer;
    private ProfileComparator _comparator;
    
    public ProfileOrganizerRenderer(ProfileOrganizer organizer, RouterContext context) {
        _context = context;
        _organizer = organizer;
        _comparator = new ProfileComparator();
    }
    public void renderStatusHTML(Writer out) throws IOException {
        Set peers = _organizer.selectAllPeers();
        
        long now = _context.clock().now();
        long hideBefore = now - 90*60*1000;
        
        TreeSet order = new TreeSet(_comparator);
        TreeSet integratedPeers = new TreeSet(_comparator);
        for (Iterator iter = peers.iterator(); iter.hasNext();) {
            Hash peer = (Hash)iter.next();
            if (_organizer.getUs().equals(peer)) continue;
            PeerProfile prof = _organizer.getProfile(peer);
            if (_organizer.isWellIntegrated(peer)) {
                integratedPeers.add(prof);
            } else {
                RouterInfo info = _context.netDb().lookupRouterInfoLocally(peer);
                if (info != null && info.getCapabilities().indexOf("f") >= 0)
                    integratedPeers.add(prof);
            }
            if (prof.getLastSendSuccessful() <= hideBefore) continue;
            order.add(prof);
        }
        
        int fast = 0;
        int reliable = 0;
        int integrated = 0;
        int failing = 0;
        StringBuilder buf = new StringBuilder(16*1024);
        buf.append("<h2>").append(_("Peer Profiles")).append("</h2>\n<p>");
        buf.append(_("Showing {0} recent profiles.", order.size())).append('\n');
        buf.append(_("Hiding {0} older profiles.", peers.size()-order.size()));
        buf.append("</p>");
                   buf.append("<table>");
                   buf.append("<tr>");
                   buf.append("<th>").append(_("Peer")).append("</th>");
                   buf.append("<th>").append(_("Groups (Caps)")).append("</th>");
                   buf.append("<th>").append(_("Speed")).append("</th>");
                   buf.append("<th>").append(_("Capacity")).append("</th>");
                   buf.append("<th>").append(_("Integration")).append("</th>");
                   buf.append("<th>").append(_("Status")).append("</th>");
                   buf.append("<th>&nbsp;</th>");
                   buf.append("</tr>");
        int prevTier = 1;
        for (Iterator iter = order.iterator(); iter.hasNext();) {
            PeerProfile prof = (PeerProfile)iter.next();
            Hash peer = prof.getPeer();
            
            int tier = 0;
            boolean isIntegrated = false;
            if (_organizer.isFast(peer)) {
                tier = 1;
                fast++;
                reliable++;
            } else if (_organizer.isHighCapacity(peer)) {
                tier = 2;
                reliable++;
            } else if (_organizer.isFailing(peer)) {
                failing++;
            } else {
                tier = 3;
            }
            
            if (_organizer.isWellIntegrated(peer)) {
                isIntegrated = true;
                integrated++;
            }
            
            if (tier != prevTier)
                buf.append("<tr><td colspan=\"7\"><hr></td></tr>\n");
            prevTier = tier;
            
            buf.append("<tr><td align=\"center\" nowrap>");
            buf.append(_context.commSystem().renderPeerHTML(peer));
            buf.append("</td><td align=\"center\">");
            
            switch (tier) {
                case 1: buf.append(_("Fast, High Capacity")); break;
                case 2: buf.append(_("High Capacity")); break;
                case 3: buf.append(_("Standard")); break;
                default: buf.append(_("Failing")); break;
            }
            if (isIntegrated) buf.append(", ").append(_("Integrated"));
            RouterInfo info = _context.netDb().lookupRouterInfoLocally(peer);
            if (info != null) {
                // prevent HTML injection in the caps and version
                buf.append(" (").append(DataHelper.stripHTML(info.getCapabilities()));
                String v = info.getOption("router.version");
                if (v != null)
                    buf.append(' ').append(DataHelper.stripHTML(v));
                buf.append(')');
            }
            
            buf.append("<td align=\"right\">").append(num(prof.getSpeedValue()));
            long bonus = prof.getSpeedBonus();
            if (bonus != 0) {
                if (bonus > 0)
                    buf.append(" (+");
                else
                    buf.append(" (");
                buf.append(bonus).append(')');
            }
            buf.append("</td><td align=\"right\">").append(num(prof.getCapacityValue()));
            bonus = prof.getCapacityBonus();
            if (bonus != 0) {
                if (bonus > 0)
                    buf.append(" (+");
                else
                    buf.append(" (");
                buf.append(bonus).append(')');
            }
            buf.append("</td><td align=\"right\">").append(num(prof.getIntegrationValue()));
            buf.append("</td><td align=\"center\">");
            if (_context.shitlist().isShitlisted(peer)) buf.append(_("Banned"));
            if (prof.getIsFailing()) buf.append(" ").append(_("Failing"));
            if (_context.commSystem().wasUnreachable(peer)) buf.append(" ").append(_("Unreachable"));
            Rate failed = prof.getTunnelHistory().getFailedRate().getRate(30*60*1000);
            long fails = failed.getCurrentEventCount() + failed.getLastEventCount();
            if (fails > 0) {
                Rate accepted = prof.getTunnelCreateResponseTime().getRate(30*60*1000);
                long total = fails + accepted.getCurrentEventCount() + accepted.getLastEventCount();
                if (total / fails <= 10)   // hide if < 10%
                    buf.append(' ').append(fails).append('/').append(total).append(" ").append(_("Test Fails"));
            }
            buf.append("&nbsp;</td>");
            buf.append("<td nowrap align=\"center\"><a target=\"_blank\" href=\"dumpprofile.jsp?peer=").append(peer.toBase64().substring(0,6)).append("\">profile</a>");
            buf.append("&nbsp;<a href=\"configpeer.jsp?peer=").append(peer.toBase64()).append("\">+-</a></td>\n");
            buf.append("</tr>");
            // let's not build the whole page in memory (~500 bytes per peer)
            out.write(buf.toString());
            buf.setLength(0);
        }
        buf.append("</table>");

        buf.append("<h2>").append(_("Floodfill and Integrated Peers")).append("</h2>\n");
        buf.append("<table>");
                   buf.append("<tr>");
                   buf.append("<th class=\"smallhead\">Peer</th>");
                   buf.append("<th class=\"smallhead\">Caps</th>");
                   buf.append("<th class=\"smallhead\">Integ. Value</th>");
                   buf.append("<th class=\"smallhead\">Last Heard About</th>");
                   buf.append("<th class=\"smallhead\">Last Heard From</th>");
//                   "<th class=\"smallhead\">Last Successful Send</th>" +
                   buf.append("<th class=\"smallhead\">Last Good Send</th>");        
//                   "<th class=\"smallhead\">Last Failed Send</th>" +
                   buf.append("<th class=\"smallhead\">Last Bad Send</th>");
                   buf.append("<th class=\"smallhead\">10m Resp. Time</th>");
                   buf.append("<th class=\"smallhead\">1h Resp. Time</th>");
                   buf.append("<th class=\"smallhead\">1d Resp. Time</th>");
//                   "<th class=\"smallhead\">Successful Lookups</th>" + 
                   buf.append("<th class=\"smallhead\">Good Lookups</th>"); 
//                   "<th>Failed Lookups</th>" +
                   buf.append("<th class=\"smallhead\">Bad Lookups</th>");        
                   buf.append("<th class=\"smallhead\">New Stores</th>");
                   buf.append("<th class=\"smallhead\">Old Stores</th>");
                   buf.append("<th class=\"smallhead\">1h Fail Rate</th>");
                   buf.append("<th class=\"smallhead\">1d Fail Rate</th>");
                   buf.append("</tr>");
        for (Iterator iter = integratedPeers.iterator(); iter.hasNext();) {
            PeerProfile prof = (PeerProfile)iter.next();
            Hash peer = prof.getPeer();

            buf.append("<tr><td align=\"center\" nowrap>");
            buf.append(_context.commSystem().renderPeerHTML(peer));
            buf.append("</td>");
            RouterInfo info = _context.netDb().lookupRouterInfoLocally(peer);
            if (info != null)
                buf.append("<td align=\"center\">").append(DataHelper.stripHTML(info.getCapabilities())).append("</td>");
            else
                buf.append("<td>&nbsp;</td>");
            buf.append("</code></td>");
            buf.append("<td align=\"right\">").append(num(prof.getIntegrationValue())).append("</td>");
            long time;
            time = now - prof.getLastHeardAbout();
            buf.append("<td align=\"right\">").append(DataHelper.formatDuration(time)).append("</td>");
            time = now - prof.getLastHeardFrom();
            buf.append("<td align=\"right\">").append(DataHelper.formatDuration(time)).append("</td>");
            time = now - prof.getLastSendSuccessful();
            buf.append("<td align=\"right\">").append(DataHelper.formatDuration(time)).append("</td>");
            time = now - prof.getLastSendFailed();
            buf.append("<td align=\"right\">").append(DataHelper.formatDuration(time)).append("</td>");
            buf.append("<td align=\"right\">").append(avg(prof, 10*60*1000l)).append("</td>");
            buf.append("<td align=\"right\">").append(avg(prof, 60*60*1000l)).append("</td>");
            buf.append("<td align=\"right\">").append(avg(prof, 24*60*60*1000l)).append("</td>");
            DBHistory dbh = prof.getDBHistory();
            if (dbh != null) {
                buf.append("<td align=\"right\">").append(dbh.getSuccessfulLookups()).append("</td>");
                buf.append("<td align=\"right\">").append(dbh.getFailedLookups()).append("</td>");
                buf.append("<td align=\"right\">").append(dbh.getUnpromptedDbStoreNew()).append("</td>");
                buf.append("<td align=\"right\">").append(dbh.getUnpromptedDbStoreOld()).append("</td>");
                buf.append("<td align=\"right\">").append(davg(dbh, 60*60*1000l)).append("</td>");
                buf.append("<td align=\"right\">").append(davg(dbh, 24*60*60*1000l)).append("</td>");
            }
        }
        buf.append("</table>");

        buf.append("<h3>").append(_("Thresholds:")).append("</h3>");
        buf.append("<p><b>").append(_("Speed")).append(":</b> ").append(num(_organizer.getSpeedThreshold())).append(" (").append(fast).append(" fast peers)<br>");
        buf.append("<b>").append(_("Capacity")).append(":</b> ").append(num(_organizer.getCapacityThreshold())).append(" (").append(reliable).append(" high capacity peers)<br>");
        buf.append("<b>").append(_("Integration")).append(":</b> ").append(num(_organizer.getIntegrationThreshold())).append(" (").append(integrated).append(" well integrated peers)</p>");
        buf.append("<h3>").append(_("Definitions")).append(":</h3><ul>");
                   buf.append("<li><b>").append(_("groups")).append("</b>: ").append(_("as determined by the profile organizer")).append("</li>");
                   buf.append("<li><b>").append(_("caps")).append("</b>: ").append(_("capabilities in the netDb, not used to determine profiles")).append("</li>");
                   buf.append("<li><b>").append(_("speed")).append("</b>: ").append(_("peak throughput (bytes per second) over a 1 minute period that the peer has sustained in a single tunnel")).append("</li>");
                   buf.append("<li><b>").append(_("capacity")).append("</b>: ").append(_("how many tunnels can we ask them to join in an hour?")).append("</li>");
                   buf.append("<li><b>").append(_("integration")).append("</b>: ").append(_("how many new peers have they told us about lately?")).append("</li>");
                   buf.append("<li><b>").append(_("status")).append("</b>: ").append(_("is the peer banned, or unreachable, or failing tunnel tests?")).append("</li>");
                   buf.append("</ul></i>");
        out.write(buf.toString());
        out.flush();
    }
    
    private class ProfileComparator implements Comparator {
        public int compare(Object lhs, Object rhs) {
            if ( (lhs == null) || (rhs == null) ) 
                throw new NullPointerException("lhs=" + lhs + " rhs=" + rhs);
            if ( !(lhs instanceof PeerProfile) || !(rhs instanceof PeerProfile) ) 
                throw new ClassCastException("lhs=" + lhs.getClass().getName() + " rhs=" + rhs.getClass().getName());
            
            PeerProfile left = (PeerProfile)lhs;
            PeerProfile right = (PeerProfile)rhs;
            
            if (_context.profileOrganizer().isFast(left.getPeer())) {
                if (_context.profileOrganizer().isFast(right.getPeer())) {
                    return compareHashes(left, right);
                } else {
                    return -1; // fast comes first
                }
            } else if (_context.profileOrganizer().isHighCapacity(left.getPeer())) {
                if (_context.profileOrganizer().isFast(right.getPeer())) {
                    return 1; 
                } else if (_context.profileOrganizer().isHighCapacity(right.getPeer())) {
                    return compareHashes(left, right);
                } else {
                    return -1;
                }
            } else if (_context.profileOrganizer().isFailing(left.getPeer())) {
                if (_context.profileOrganizer().isFailing(right.getPeer())) {
                    return compareHashes(left, right);
                } else {
                    return 1;
                }
            } else {
                // left is not failing
                if (_context.profileOrganizer().isFast(right.getPeer())) {
                    return 1;
                } else if (_context.profileOrganizer().isHighCapacity(right.getPeer())) {
                    return 1;
                } else if (_context.profileOrganizer().isFailing(right.getPeer())) {
                    return -1;
                } else {
                    return compareHashes(left, right);
                }
            }
        }
        
        private int compareHashes(PeerProfile left, PeerProfile right) {
            return left.getPeer().toBase64().compareTo(right.getPeer().toBase64());
        }
        
    }
    
    private final static DecimalFormat _fmt = new DecimalFormat("###,##0.00");
    private final static String num(double num) { synchronized (_fmt) { return _fmt.format(num); } }
    private final static String na = "n/a";

    private static String avg (PeerProfile prof, long rate) {
            RateStat rs = prof.getDbResponseTime();
            if (rs == null)
                return na;
            Rate r = rs.getRate(rate);
            if (r == null)
                return na;
            long c = r.getCurrentEventCount() + r.getLastEventCount();
            if (c == 0)
                return na;
            double d = r.getCurrentTotalValue() + r.getLastTotalValue();
            return Math.round(d/c) + "ms";
    }

    private static String davg (DBHistory dbh, long rate) {
            RateStat rs = dbh.getFailedLookupRate();
            if (rs == null)
                return na;
            Rate r = rs.getRate(rate);
            if (r == null)
                return na;
            long c = r.getCurrentEventCount() + r.getLastEventCount();
            return "" + c;
    }

    /** translate a string */
    private String _(String s) {
        return Messages.getString(s, _context);
    }

    /**
     *  translate a string with a parameter
     *  This is a lot more expensive than _(s), so use sparingly.
     *
     *  @param s string to be translated containing {0}
     *    The {0} will be replaced by the parameter.
     *    Single quotes must be doubled, i.e. ' -> '' in the string.
     *  @param o parameter, not translated.
     *    To tranlslate parameter also, use _("foo {0} bar", _("baz"))
     *    Do not double the single quotes in the parameter.
     *    Use autoboxing to call with ints, longs, floats, etc.
     */
    private String _(String s, Object o) {
        return Messages.getString(s, o, _context);
    }
}
