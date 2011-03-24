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
 * $Revision: 1.3 $
 */

    // http://www.crazysquirrel.com/computing/general/form-encoding.jspx
    if (request.getCharacterEncoding() == null)
        request.setCharacterEncoding("UTF-8");

%>
<%@page pageEncoding="UTF-8"%>
<%@ page contentType="text/html"%>
<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core"%>
<jsp:useBean id="version" class="i2p.susi.dns.VersionBean" scope="application" />
<jsp:useBean id="book" class="i2p.susi.dns.NamingServiceBean" scope="session" />
<jsp:useBean id="intl" class="i2p.susi.dns.Messages" scope="application" />
<jsp:setProperty name="book" property="*" />
<jsp:setProperty name="book" property="resetDeletionMarks" value="1"/>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">
<html>
<head>
<meta http-equiv="Content-Type" content="text/html; charset=UTF-8" />
<title>${book.book} <%=intl._("addressbook")%> - susidns</title>
<link rel="stylesheet" type="text/css" href="css.css">
</head>
<body>
<div class="page">
<div id="logo">
<img src="images/logo.png" alt="susidns logo" border="0"/>
</div>
<hr>
<div id="navi">
<p>
<%=intl._("addressbooks")%>
<a href="addressbook.jsp?book=private&amp;filter=none&amp;begin=0&amp;end=99"><%=intl._("private")%></a> |
<a href="addressbook.jsp?book=master&amp;filter=none&amp;begin=0&amp;end=99"><%=intl._("master")%></a> |
<a href="addressbook.jsp?book=router&amp;filter=none&amp;begin=0&amp;end=99"><%=intl._("router")%></a> |
<a href="addressbook.jsp?book=published&amp;filter=none&amp;begin=0&amp;end=99"><%=intl._("published")%></a> *
<a href="subscriptions.jsp"><%=intl._("subscriptions")%></a> *
<a href="config.jsp"><%=intl._("configuration")%></a> *
<a href="index.jsp"><%=intl._("overview")%></a>
</p>
</div>
<hr>
<div id="headline">
<h3><%=intl._(book.getBook())%> <%=intl._("addressbook")%>: ${book.fileName}</h3>
</div>

<div id="book">
<%
    String detail = request.getParameter("h");
    if (detail == null) {
        %><p>No host specified</p><%
    } else {
        i2p.susi.dns.AddressBean addr = book.getLookup();
        if (addr == null) {
            %><p>Not found: <%=detail%></p><%
        } else {
            String b32 = addr.getB32();
%>
<jsp:setProperty name="book" property="trClass"	value="0" />
<table class="book" cellspacing="0" cellpadding="5">
<tr class="list${book.trClass}">
<td><%=intl._("Host Name")%></td>
<td><a href="http://<%=addr.getName()%>/"><%=addr.getName()%></a></td>
</tr><tr class="list${book.trClass}">
<td><%=intl._("Base 32 Address")%></td>
<td><a href="http://<%=b32%>/"><%=b32%></a></td>
</tr><tr class="list${book.trClass}">
<td><%=intl._("Address Helper")%></td>
<td><a href="http://<%=addr.getName()%>/?i2paddresshelper=<%=addr.getDestination()%>"><%=intl._("link")%></a></td>
</tr><tr class="list${book.trClass}">
<td><%=intl._("Public Key")%></td>
<td><%=intl._("ElGamal 2048 bit")%></td>
</tr><tr class="list${book.trClass}">
<td><%=intl._("Signing Key")%></td>
<td><%=intl._("DSA 1024 bit")%></td>
</tr><tr class="list${book.trClass}">
<td><%=intl._("Certificate")%></td>
<td><%=addr.getCert()%></td>
</tr><tr class="list${book.trClass}">
<td><%=intl._("Added Date")%></td>
<td><%=addr.getAdded()%></td>
</tr><tr class="list${book.trClass}">
<td><%=intl._("Source")%></td>
<td><%=addr.getSource()%></td>
</tr><tr class="list${book.trClass}">
<td><%=intl._("Last Modified")%></td>
<td><%=addr.getModded()%></td>
</tr><tr class="list${book.trClass}">
<td><%=intl._("Notes")%></td>
<td><%=addr.getNotes()%></td>
</tr><tr class="list${book.trClass}">
<td><%=intl._("Destination")%></td>
<td class="destinations"><textarea rows="1" style="height: 3em;" cols="70" wrap="off" readonly="readonly" ><%=addr.getDestination()%></textarea></td>
</tr></table>
</div>
<div id="buttons">
<form method="POST" action="details.jsp">
<input type="hidden" name="serial" value="${book.serial}">
<input type="hidden" name="h" value="<%=detail%>">
<input type="submit" name="action" value="<%=intl._("Delete")%>" >
</form>
</div>
<%
        }
    }
%>
<hr>
<div id="footer">
<p class="footer">susidns v${version.version} &copy; <a href="${version.url}">susi</a> 2005</p>
</div>
</div>
</body>
</html>
