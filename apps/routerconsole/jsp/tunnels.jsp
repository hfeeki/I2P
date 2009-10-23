<%@page contentType="text/html"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">

<html><head>
<%@include file="css.jsp" %>
<%=cssHelper.title("tunnel summary")%>
</head><body>
<%@include file="summary.jsp" %><h1><%=cssHelper._("I2P Tunnel Summary")%></h1>
<div class="main" id="main">
 <jsp:useBean class="net.i2p.router.web.TunnelHelper" id="tunnelHelper" scope="request" />
 <jsp:setProperty name="tunnelHelper" property="contextId" value="<%=(String)session.getAttribute("i2p.contextId")%>" />
 <jsp:setProperty name="tunnelHelper" property="writer" value="<%=out%>" />
 <jsp:getProperty name="tunnelHelper" property="tunnelSummary" />
</div></body></html>
