<%@page contentType="text/html"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">

<html><head>
<title>I2P Router Console - config clients</title>
<link rel="stylesheet" href="default.css" type="text/css" />
</head><body>

<%@include file="nav.jsp" %>
<%@include file="summary.jsp" %>

<div class="main" id="main">
 <%@include file="confignav.jsp" %>
  
 <jsp:useBean class="net.i2p.router.web.ConfigServiceHandler" id="formhandler" scope="request" />
 <jsp:setProperty name="formhandler" property="*" />
 <jsp:setProperty name="formhandler" property="contextId" value="<%=(String)session.getAttribute("i2p.contextId")%>" />
 <font color="red"><jsp:getProperty name="formhandler" property="errors" /></font>
 <i><jsp:getProperty name="formhandler" property="notices" /></i>
 
 <form action="configservice.jsp" method="POST">
 <% String prev = System.getProperty("net.i2p.router.web.ConfigServiceHandler.nonce");
    if (prev != null) System.setProperty("net.i2p.router.web.ConfigServiceHandler.noncePrev", prev);
    System.setProperty("net.i2p.router.web.ConfigServiceHandler.nonce", new java.util.Random().nextLong()+""); %>
 <input type="hidden" name="nonce" value="<%=System.getProperty("net.i2p.router.web.ConfigServiceHandler.nonce")%>" />
 <h4>Shutdown the router</h4>
 Graceful shutdown lets the router satisfy the agreements it has already made 
 before shutting down, but may take a few minutes.  If you need to kill the
 router immediately, that option is available as well.<br />
 <input type="submit" name="action" value="Shutdown gracefully" />
 <input type="submit" name="action" value="Shutdown immediately" />
 <input type="submit" name="action" value="Cancel graceful shutdown" />
 <input type="submit" name="action" value="Hard restart" />
 <% if ( (System.getProperty("os.name") != null) && (System.getProperty("os.name").startsWith("Win")) ) { %>
 <h4>Systray integration</h4>
 On the windows platform, there is a small application to sit in the system 
 tray, allowing you to view the router's status (later on, I2P client applications
 will be able to integrate their own functionality into the system tray as well).
 If you are on windows, you can either enable or disable that icon here. <br />
 <input type="submit" name="action" value="Show systray icon" />
 <input type="submit" name="action" value="Hide systray icon" />
 <h4>Run on startup</h4>
 You can control whether I2P is run on startup or not by selecting one of the 
 following options - I2P will install (or remove) a service accordingly.  You can
 also run the <code>install_i2p_service_winnt.bat</code> (or 
 <code>uninstall_i2p_service_winnt.bat</code>) from the command line, if you prefer.<br />
 <input type="submit" name="action" value="Run I2P on startup" />
 <input type="submit" name="action" value="Don't run I2P on startup" /><br />
 <b>Note:</b> If you are running I2P as service right now, removing it will shut 
 down your router immediately.  You may want to consider shutting down gracefully, as
 above, then running uninstall_i2p_service_winnt.bat.
 <% } %>
 <h4>Debugging</h4>
 At times, it may be helpful to debug I2P by getting a thread dump.  To do so, 
 please select the following option and review the thread dumped to 
<a href="logs.jsp#servicelogs">wrapper.log</a>.<br />
 <input type="submit" name="action" value="Dump threads" />
 </form>
</div>

</body>
</html>
