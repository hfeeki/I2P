<%@page contentType="text/html"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">

<html><head>
<title>I2P Router Console - network database summary</title>
<%@include file="css.jsp" %>
</head><body>

<%@include file="nav.jsp" %>
<%@include file="summary.jsp" %>
 <h1>I2P Network Database Summary</h1>
<div class="main" id="main">
 <jsp:useBean class="net.i2p.router.web.NetDbHelper" id="netdbHelper" scope="request" />
 <jsp:setProperty name="netdbHelper" property="contextId" value="<%=(String)session.getAttribute("i2p.contextId")%>" />
 <jsp:setProperty name="netdbHelper" property="writer" value="<%=out%>" />
 <jsp:setProperty name="netdbHelper" property="full" value="<%=request.getParameter("f")%>" />
 <jsp:setProperty name="netdbHelper" property="router" value="<%=request.getParameter("r")%>" />
 <jsp:setProperty name="netdbHelper" property="lease" value="<%=request.getParameter("l")%>" />
 <jsp:getProperty name="netdbHelper" property="netDbSummary" />
</div>

</body>
</html>
