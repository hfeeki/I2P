<%@page contentType="text/html"%>
<%@page trimDirectiveWhitespaces="true"%>
<%@page pageEncoding="UTF-8"%>
<jsp:useBean class="net.i2p.router.web.CSSHelper" id="tester" scope="request" />
<jsp:setProperty name="tester" property="contextId" value="<%=(String)session.getAttribute(\"i2p.contextId\")%>" />
<%
    // CSSHelper is also pulled in by css.jsi below...
    boolean testIFrame = tester.allowIFrame(request.getHeader("User-Agent"));
    if (!testIFrame) {
        response.setStatus(302, "Moved");
        response.setHeader("Location", "/i2psnark/");
    } else {
%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">

<html><head>
<%@include file="css.jsi" %>
<%=intl.title("home")%>
<script src="/js/ajax.js" type="text/javascript"></script>
<script type="text/javascript">
  var failMessage = "<hr><b><%=intl._("Router is down")%><\/b>";
  function requestAjax1() { ajax("/xhr1.jsp?requestURI=<%=request.getRequestURI()%>", "xhr", <%=intl.getRefresh()%>000); }
  function initAjax() { setTimeout(requestAjax1, <%=intl.getRefresh()%>000);  }
  function resizeFrame(f) { f.style.height = f.contentWindow.document.body.scrollHeight + "px"; }
  function injectClass(f) { f.contentWindow.document.getElementsByTagName('body')[0].setAttribute('class', 'iframed'); }
  function init() {
      f = document.getElementById("i2psnarkframe");
      resizeFrame(f);
      injectClass(f);
      initAjax();
  }
</script>
</head><body onload="init()">

<%@include file="summary.jsi" %>

<h1><%=intl._("I2P Torrent Downloader")%></h1>
<div class="main" id="main">
<iframe src="/i2psnark/" width="100%" height="100%" frameborder="0" border="0" name="i2psnarkframe" id="i2psnarkframe" onload="resizeFrame(document.getElementById('i2psnarkframe'))" allowtransparency="true">
</iframe>
</div></body></html>
<%
    }
%>
