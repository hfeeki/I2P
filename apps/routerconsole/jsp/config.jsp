<%@page contentType="text/html" %>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">

<html><head>
<title>I2P Router Console - logs</title>
<link rel="stylesheet" href="default.css" type="text/css" />
</head><body>

<%@include file="nav.jsp" %>
<%@include file="summary.jsp" %>

<jsp:useBean class="net.i2p.router.web.ConfigNetHelper" id="nethelper" scope="request" />
<jsp:setProperty name="nethelper" property="contextId" value="<%=(String)session.getAttribute("i2p.contextId")%>" />

<div class="main" id="main">
 <%@include file="confignav.jsp" %>
 
 <jsp:useBean class="net.i2p.router.web.ConfigNetHandler" id="formhandler" scope="request" />
 <jsp:setProperty name="formhandler" property="*" />
 <jsp:setProperty name="formhandler" property="contextId" value="<%=(String)session.getAttribute("i2p.contextId")%>" />
 <font color="red"><jsp:getProperty name="formhandler" property="errors" /></font>
 <i><jsp:getProperty name="formhandler" property="notices" /></i>

 <form action="config.jsp" method="POST">
 <% String prev = System.getProperty("net.i2p.router.web.ConfigNetHandler.nonce");
    if (prev != null) System.setProperty("net.i2p.router.web.ConfigNetHandler.noncePrev", prev);
    System.setProperty("net.i2p.router.web.ConfigNetHandler.nonce", new java.util.Random().nextLong()+""); %>
 <input type="hidden" name="nonce" value="<%=System.getProperty("net.i2p.router.web.ConfigNetHandler.nonce")%>" />
 <input type="hidden" name="action" value="blah" />

 <b>External hostname/IP address:</b> 
    <input name="hostname" type="text" size="32" value="<jsp:getProperty name="nethelper" property="hostname" />" />
    <input type="submit" name="guesshost" value="Guess" /><br />
 <b>Externally reachable TCP port:</b>
     <input name="port" type="text" size="4" value="<jsp:getProperty name="nethelper" property="port" />" /> <br />
 <i>The hostname/IP address and TCP port must be reachable from the outside world.  If
 you are behind a firewall or NAT, this means you must poke a hole for this port.  If
 you are using DHCP and do not have a static IP address, you should either use a service like
 <a href="http://dyndns.org/">dyndns</a> or leave the hostname blank.  If you leave it blank,
 your router will autodetect the 'correct' IP address by asking a peer (and unconditionally 
 believing them if the address is routable and you don't have any established connections yet).
 The "guess" functionality makes an HTTP request 
 to <a href="http://www.whatismyip.com/">www.whatismyip.com</a>.</i>
 <hr />
 <b>Enable internal time synchronization?</b> <input type="checkbox" <jsp:getProperty name="nethelper" property="enableTimeSyncChecked" /> name="enabletimesync" /><br />
 <i>If disabled, your machine <b>must</b> be NTP synchronized - your clock must always
    be within a few seconds of "correct".</i>
 <hr />
 <b>Bandwidth limiter</b><br />
 <b>Inbound rate</b>: 
    <input name="inboundrate" type="text" size="2" value="<jsp:getProperty name="nethelper" property="inboundRate" />" /> KBytes per second<br />
 <b>Inbound burst duration:</b>
    <jsp:getProperty name="nethelper" property="inboundBurstFactorBox" /><br />
 <b>Outbound rate:</b>
    <input name="outboundrate" type="text" size="2" value="<jsp:getProperty name="nethelper" property="outboundRate" />" /> KBytes per second<br />
 <b>Outbound burst duration:</b> 
  <jsp:getProperty name="nethelper" property="outboundBurstFactorBox" /><br />
 <i>A negative rate means there is no limit</i><br />
 <hr />
 <b>Reseed</b> (from <input name="reseedfrom" type="text" size="40" value="http://dev.i2p.net/i2pdb/" />): 
               <input type="submit" name="reseed" value="now" /><br />
 <i>May take some time to download the peer references</i>
 <hr />
 <input type="submit" name="save" value="Save changes" /> <input type="reset" value="Cancel" /><br />
 <i>Changing the hostname or TCP port will force a 'soft restart' - dropping your connections 
    and clients as if the router was stopped and restarted.  <b>Please be patient</b> - it may take
    a few seconds to complete.</i>
 </form>
</div>

</body>
</html>
