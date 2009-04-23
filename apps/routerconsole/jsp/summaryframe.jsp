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
<title>Summary Bar</title>
<meta http-equiv="Content-Type" content="text/html; charset=UTF-8" />
<%
    // try hard to avoid an error page in the iframe after shutdown
    String action = request.getParameter("action");
    String d = request.getParameter("refresh");
    boolean shutdownSoon = "shutdownImmediate".equals(action) || "restartImmediate".equals(action);
    if (!shutdownSoon) {
        if (d == null || "".equals(d)) {
            d = System.getProperty("routerconsole.summaryRefresh");
            if (d == null || "".equals(d))
                d = "60";
        } else {
            System.setProperty("routerconsole.summaryRefresh", d);
        }
        // we probably don't get here if d == "0" since caught in summary.jsp, but just
        // to be sure...
        if (!"0".equals(d)) {
            // doesn't work for restart or shutdown with no expl. tunnels,
            // since the call to ConfigRestartBean.renderStatus() hasn't happened yet...
            long timeleft = net.i2p.router.web.ConfigRestartBean.getRestartTimeRemaining();
            long delay = 60;
            try { delay = Long.parseLong(d); } catch (NumberFormatException nfe) {}
            if (delay*1000 < timeleft + 5000)
                out.print("<meta http-equiv=\"refresh\" content=\"" + d + "\" />\n");
            else
                shutdownSoon = true;
        }
    }
%>
<link rel="stylesheet" href="default.css" type="text/css" />
</head>

<body style="margin: 0;">

<div class="routersummary">
<%@include file="summarynoframe.jsp" %>
<%
    // d and shutdownSoon defined above
    if (!shutdownSoon) {
        out.print("<hr /><p><form action=\"summaryframe.jsp\" method=\"GET\">\n");
        if ("0".equals(d)) {
            out.print("<b>Refresh (s):<b> <input size=\"3\" type=\"text\" name=\"refresh\" value=\"60\" />\n");
            out.print("<button type=\"submit\">Enable</button>\n");
        } else {
            // this will load in the iframe but subsequent pages will not have the iframe
            out.print("<input type=\"hidden\" name=\"refresh\" value=\"0\" />\n");
            out.print("<button type=\"submit\">Disable " + d + "s Refresh</button>\n");
        }
        out.print("</form></p>\n");
    }
%>
</div>

</body>
</html>
