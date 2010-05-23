<%
/*
 * Created on Sep 02, 2005
 * 
 *  This file is part of susidns project, see http://susi.i2p/
 *  
 *  Copyright (C) 2005 <susi23@mail.i2p>
 *
 *  This program is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation; either version 2 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program; if not, write to the Free Software
 *  Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 *  
 * $Revision: 1.2 $
 */

    // http://www.crazysquirrel.com/computing/general/form-encoding.jspx
    if (request.getCharacterEncoding() == null)
        request.setCharacterEncoding("UTF-8");

%>
<%@page pageEncoding="UTF-8"%>
<%@ page contentType="text/html"%>
<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core"%>
<jsp:useBean id="version" class="i2p.susi.dns.VersionBean" scope="application" />
<jsp:useBean id="subs" class="i2p.susi.dns.SubscriptionsBean" scope="session" />
<jsp:useBean id="intl" class="i2p.susi.dns.Messages" scope="application" />
<jsp:setProperty name="subs" property="*" />
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">
<html>
<head>
<meta http-equiv="Content-Type" content="text/html; charset=UTF-8" />
<title><%=intl._("subscriptions")%> - susidns</title>
<link rel="stylesheet" type="text/css" href="css.css">
</head>
<body>
<div class="page">
<div id="logo">
<img src="images/logo.png" alt="susidns logo" border="0"/>
</div><hr>
<div id="navi">
<p>
<%=intl._("addressbooks")%>
<a href="addressbook.jsp?book=private"><%=intl._("private")%></a> |
<a href="addressbook.jsp?book=master"><%=intl._("master")%></a> |
<a href="addressbook.jsp?book=router"><%=intl._("router")%></a> |
<a href="addressbook.jsp?book=published"><%=intl._("published")%></a> *
<%=intl._("subscriptions")%> *
<a href="config.jsp"><%=intl._("configuration")%></a> *
<a href="index.jsp"><%=intl._("overview")%></a>
</p>
</div><hr>
<div id="headline">
<h3>${subs.fileName}</h3>
</div>
<div id="messages">${subs.messages}</div>
<form method="POST" action="subscriptions.jsp">
<div id="content">
<input type="hidden" name="serial" value="${subs.serial}" >
<textarea name="content" rows="10" cols="80">${subs.content}</textarea>
</div>
<div id="buttons">
<input type="submit" name="action" value="<%=intl._("Reload")%>" >
<input type="submit" name="action" value="<%=intl._("Save")%>" >
</div>
</form>
<div id="help">
<p class="help">
<%=intl._("The subscription file contains a list of i2p URLs.")%>
<%=intl._("The addressbook application regularly checks this list for new eepsites.")%>
<%=intl._("Those URLs refer to published hosts.txt files.")%>
<%=intl._("The default subscription is the hosts.txt from www.i2p2.i2p, which is updated infrequently.")%>
<%=intl._("So it is a good idea to add additional subscriptions to sites that have the latest addresses.")%>
<a href="http://www.i2p2.i2p/faq.html#subscriptions"><%=intl._("See the FAQ for a list of subscription URLs.")%></a>
</p>
</div><hr>
<div id="footer">
<p class="footer">susidns v${version.version} &copy; <a href="${version.url}">susi</a> 2005</p>
</div>
</div>
</body>
</html>
