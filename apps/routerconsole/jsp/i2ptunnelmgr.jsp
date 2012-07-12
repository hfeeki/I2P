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
        response.setHeader("Location", "/i2ptunnel/");
    } else {
%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">

<html><head>
<%@include file="css.jsi" %>
<%=intl.title("i2p tunnel manager")%>
<script src="/js/ajax.js" type="text/javascript"></script>
<script type="text/javascript">
  var failMessage = "<hr><b><%=intl._("Router is down")%><\/b>";
  function requestAjax1() { ajax("/xhr1.jsp?requestURI=<%=request.getRequestURI()%>", "xhr", <%=intl.getRefresh()%>000); }
  function initAjax() { setTimeout(requestAjax1, <%=intl.getRefresh()%>000);  }
  function injectClass(f) {
      f.className += ' iframed';
      var doc = 'contentDocument' in f? f.contentDocument : f.contentWindow.document;
      doc.body.className += ' iframed';
      doc.getElementsByClassName('panel')[0].className += ' iframed';
  }
  function resizeFrame(f) {
      var doc = 'contentDocument' in f? f.contentDocument : f.contentWindow.document;
      f.style.height = doc.body.offsetHeight + "px";
  }
  function setupFrame() {
      f = document.getElementById("i2ptunnelframe");
      injectClass(f);
      resizeFrame(f);
  }
</script>
</head><body onload="initAjax()">

<%@include file="summary.jsi" %>

<h1><%=intl._("I2P Tunnel Manager")%> <span class="newtab"><a href="/i2ptunnel/" target="_blank" title="<%=intl._("Open in new tab")%>"><img src="<%=intl.getTheme(request.getHeader("User-Agent"))%>images/newtab.png" /></a></span></h1>
<div class="main" id="main">
<iframe src="/i2ptunnel/" width="100%" height="100%" frameborder="0" border="0" name="i2ptunnelframe" id="i2ptunnelframe" onload="setupFrame()" allowtransparency="true">
</iframe>
</div></body></html>
<%
    }
%>
