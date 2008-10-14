<%@page contentType="text/html"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">

<html><head>
<title>I2P Router Console - home</title>
<link rel="stylesheet" href="default.css" type="text/css" />
<link rel="shortcut icon" href="favicon.ico" />
</head><body>
<%
if (System.getProperty("router.consoleNonce") == null) {
    System.setProperty("router.consoleNonce", new java.util.Random().nextLong() + "");
}
%>

<%@include file="nav.jsp" %>
<%@include file="summary.jsp" %>

<div class="news" id="news">
 <jsp:useBean class="net.i2p.router.web.ContentHelper" id="newshelper" scope="request" />
 <jsp:setProperty name="newshelper" property="page" value="docs/news.xml" />
 <jsp:setProperty name="newshelper" property="maxLines" value="300" />
 <jsp:getProperty name="newshelper" property="content" />

 <jsp:useBean class="net.i2p.router.web.ConfigUpdateHelper" id="updatehelper" scope="request" />
 <jsp:setProperty name="updatehelper" property="contextId" value="<%=(String)session.getAttribute("i2p.contextId")%>" />
 <br /><i><font size="-1"><jsp:getProperty name="updatehelper" property="newsStatus" /></font></i><br />
</div>

<div class="main" id="main">
 <jsp:useBean class="net.i2p.router.web.ContentHelper" id="contenthelper" scope="request" />
 <jsp:setProperty name="contenthelper" property="page" value="docs/readme.html" />
 <jsp:setProperty name="contenthelper" property="maxLines" value="300" />
 <jsp:setProperty name="contenthelper" property="lang" value="<%=request.getParameter("lang")%>" />
 <jsp:getProperty name="contenthelper" property="content" />
</div>

</body>
</html>
