<%@page contentType="text/html"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">

<html><head>
<title>I2P Router Console - logs</title>
<link rel="stylesheet" href="default.css" type="text/css" />
</head><body>

<%@include file="nav.jsp" %>
<%@include file="summary.jsp" %>

<div class="main" id="main">
 <jsp:useBean class="net.i2p.router.web.LogsHelper" id="logsHelper" scope="request" />
 <jsp:setProperty name="logsHelper" property="contextId" value="<%=(String)session.getAttribute("i2p.contextId")%>" />
 <h4>Router logs:</h4>
 <jsp:getProperty name="logsHelper" property="logs" />
 <hr />
 <h4>Connection logs:</h4><a name="connectionlogs"> </a>
 <jsp:getProperty name="logsHelper" property="connectionLogs" />
 <hr />
 <h4>Critical logs:</h4><a name="criticallogs"> </a>
 <jsp:getProperty name="logsHelper" property="criticalLogs" />
 <hr />
 <h4>Service logs:</h4><a name="servicelogs"> </a>
 <jsp:getProperty name="logsHelper" property="serviceLogs" />
</div>

</body>
</html>
