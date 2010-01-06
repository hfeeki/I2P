<%@page contentType="text/html"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">
<%
/*
 * All links in the summary bar must have target="_top"
 * so they don't load in the iframe
 */
%>
<html><head>
<%@include file="css.jsi" %>
<title>Summary Bar</title>
<%
    // try hard to avoid an error page in the iframe after shutdown
    String action = request.getParameter("action");
    String d = request.getParameter("refresh");
    // Normal browsers send value, IE sends button label
    boolean shutdownSoon = "shutdownImmediate".equals(action) || "restartImmediate".equals(action) ||
                           "Shutdown immediately".equals(action) || "Restart immediately".equals(action);
    if (!shutdownSoon) {
        if (d == null || "".equals(d)) {
            d = intl.getRefresh();
        } else {
            d = net.i2p.data.DataHelper.stripHTML(d);  // XSS
            intl.setRefresh(d);
        }
        // we probably don't get here if d == "0" since caught in summary.jsi, but just
        // to be sure...
        if (!"0".equals(d)) {
            // doesn't work for restart or shutdown with no expl. tunnels,
            // since the call to ConfigRestartBean.renderStatus() hasn't happened yet...
            // So we delay slightly
            if ("restart".equalsIgnoreCase(action) || "shutdown".equalsIgnoreCase(action)) {
                synchronized(this) {
                    try {
                        wait(1000);
                    } catch(InterruptedException ie) {}
                }
            }
            long timeleft = net.i2p.router.web.ConfigRestartBean.getRestartTimeRemaining();
            long delay = 60;
            try { delay = Long.parseLong(d); } catch (NumberFormatException nfe) {}
            if (delay*1000 < timeleft + 5000)
                out.print("<meta http-equiv=\"refresh\" content=\"" + d + "\" >\n");
            else
                shutdownSoon = true;
        }
    }
%>
</head><body style="margin: 0;"><div class="routersummary">
<%@include file="summarynoframe.jsi" %>
<%
    // d and shutdownSoon defined above
    if (!shutdownSoon) {
        out.print("<div class=\"refresh\"><form action=\"summaryframe.jsp\" method=\"GET\">\n");
        if ("0".equals(d)) {
            out.print("<b>");
            out.print(intl._("Refresh (s)"));
            out.print(":</b> <input size=\"3\" type=\"text\" name=\"refresh\" value=\"60\" >\n");
            out.print("<button type=\"submit\" value=\"Enable\" >");
            out.print(intl._("Enable"));
            out.print("</button></div>\n");
        } else {
            // this will load in the iframe but subsequent pages will not have the iframe
            out.print("<input type=\"hidden\" name=\"refresh\" value=\"0\" >\n");
            out.print("<button type=\"submit\" value=\"Disable\" >");
            out.print(intl._("Disable {0}s Refresh", d));
            out.print("</button></div>\n");
        }
        out.print("</form>\n");
    }
%>
</div></body></html>
