<%@page contentType="text/html"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">

<html><head>
<%@include file="css.jsp" %>
<%=intl.title("config service")%>
</head><body>

<%@include file="summary.jsp" %>
<h1><%=intl._("I2P Service Configuration")%></h1>
<div class="main" id="main">
 <%@include file="confignav.jsp" %>

 <jsp:useBean class="net.i2p.router.web.ConfigServiceHandler" id="formhandler" scope="request" />
 <jsp:setProperty name="formhandler" property="*" />
 <jsp:setProperty name="formhandler" property="contextId" value="<%=(String)session.getAttribute("i2p.contextId")%>" />
 <jsp:getProperty name="formhandler" property="allMessages" />
 <div class="configure">
 <form action="configservice.jsp" method="POST">
 <% String prev = System.getProperty("net.i2p.router.web.ConfigServiceHandler.nonce");
    if (prev != null) System.setProperty("net.i2p.router.web.ConfigServiceHandler.noncePrev", prev);
    System.setProperty("net.i2p.router.web.ConfigServiceHandler.nonce", new java.util.Random().nextLong()+""); %>
 <input type="hidden" name="nonce" value="<%=System.getProperty("net.i2p.router.web.ConfigServiceHandler.nonce")%>" />
 <h3><%=intl._("Shutdown the router")%></h3>
 <p><%=intl._("Graceful shutdown lets the router satisfy the agreements it has already made before shutting down, but may take a few minutes. If you need to kill the router immediately, that option is available as well.")%>
   </p>
  <hr><div class="formaction">
 <input type="submit" name="action" value="Shutdown gracefully" />
 <input type="submit" name="action" value="Shutdown immediately" />
 <input type="submit" name="action" value="Cancel graceful shutdown" />
 </div>
 <% if (System.getProperty("wrapper.version") != null) { %>
 <p><%=intl._("If you want the router to restart itself after shutting down, you can choose one of the following.  This is useful in some situations - for example, if you changed some settings that client applications only read at startup, such as the routerconsole password or the interface it listens on.  A graceful restart will take a few minutes (but your peers will appreciate your patience), while a hard restart does so immediately.  After tearing down the router, it will wait 1 minute before starting back up again.")%>
   </p>
 <hr><div class="formaction">
 <input type="submit" name="action" value="Graceful restart" />
 <input type="submit" name="action" value="Hard restart" />
 <% } %></div>

 <% if ( (System.getProperty("os.name") != null) && (System.getProperty("os.name").startsWith("Win")) ) { %>
 <h3><%=intl._("Systray integration")%></h3>
 <p><%=intl._("On the windows platform, there is a small application to sit in the system tray, allowing you to view the router's status (later on, I2P client applications will be able to integrate their own functionality into the system tray as well). If you are on windows, you can either enable or disable that icon here.")%>
  </p>
 <hr><div class="formaction">
 <input type="submit" name="action" value="Show systray icon" />
 <input type="submit" name="action" value="Hide systray icon" />
</div><h3><%=intl._("Run on startup")%></h3>
 <p><%=intl._("You can control whether I2P is run on startup or not by selecting one of the following options - I2P will install (or remove) a service accordingly.")%> 
    <%=intl._("If you prefer the command line, you can also run the ")%> <code>install_i2p_service_winnt.bat</code> (<%=intl._("or")%>
 <code>uninstall_i2p_service_winnt.bat</code>).</p>
 <hr><div class="formaction">
 <input type="submit" name="action" value="Run I2P on startup" />
<input type="submit" name="action" value="Don't run I2P on startup" /></div>
 <p><b><%=intl._("Note")%>:</b> <%=intl._("If you are running I2P as service right now, removing it will shut down your router immediately.  You may want to consider shutting down gracefully, as above, then running uninstall_i2p_service_winnt.bat.")%>
  </p>
 <% } %>

 <% if (System.getProperty("wrapper.version") != null) { %>
 <h3><%=intl._("Debugging")%></h3>
 <p><%=intl._("At times, it may be helpful to debug I2P by getting a thread dump.  To do so, please select the following option and review the thread dumped to")%>
 <a href="logs.jsp#servicelogs">wrapper.log</a>.</p>
  <hr><div class="formaction">
 <input type="submit" name="action" value="Dump threads" />
<% } %></div>

 <h3><%=intl._("Launch browser on router startup?")%></h3>
 <p><%=intl._("I2P's main configuration interface is this web console, so for your convenience I2P can launch a web browser on startup pointing at")%>
 <a href="http://127.0.0.1:7657/index.jsp">http://127.0.0.1:7657/index.jsp</a> .</p>
 <hr><div class="formaction">
 <input type="submit" name="action" value="View console on startup" />
 <input type="submit" name="action" value="Do not view console on startup" />
</div></form></div></div></body></html>
