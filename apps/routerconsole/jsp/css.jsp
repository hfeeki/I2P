<%
   /*
    * This should be included inside <head>...</head>,
    * as it sets the stylesheet.
    *
    * This is included almost 30 times, so keep whitespace etc. to a minimum.
    */

   // http://www.crazysquirrel.com/computing/general/form-encoding.jspx
   if (request.getCharacterEncoding() == null)
       request.setCharacterEncoding("UTF-8");

   response.setHeader("Pragma", "no-cache");
   response.setHeader("Cache-Control","no-cache");
   response.setDateHeader("Expires", 0);
   // the above will b0rk if the servlet engine has already flushed
   // the response prior to including this file, so it should be
   // near the top

   if (request.getParameter("i2p.contextId") != null) {
       session.setAttribute("i2p.contextId", request.getParameter("i2p.contextId"));
   }
%>
<meta http-equiv="Content-Type" content="text/html; charset=UTF-8">
<link rel="shortcut icon" href="/themes/console/images/favicon.ico">
<jsp:useBean class="net.i2p.router.web.CSSHelper" id="cssHelper" scope="request" />
<jsp:setProperty name="cssHelper" property="contextId" value="<%=(String)session.getAttribute("i2p.contextId")%>" />
<%
   cssHelper.setLang(request.getParameter("lang"));
%>
<link href="<%=cssHelper.getTheme(request.getHeader("User-Agent"))%>console.css" rel="stylesheet" type="text/css">
<!--[if IE]><link href="/themes/console/classic/ieshim.css" rel="stylesheet" type="text/css" /><![endif]-->
