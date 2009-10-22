<%@page contentType="text/html"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">

<html><head><title>I2P Router Console - config keyring</title>
<%@include file="css.jsp" %>
</head><body>

<%@include file="summary.jsp" %>
<h1>I2P Keyring Configuration</h1>
<div class="main" id="main">
 <%@include file="confignav.jsp" %>

 <jsp:useBean class="net.i2p.router.web.ConfigKeyringHandler" id="formhandler" scope="request" />
 <jsp:setProperty name="formhandler" property="*" />
 <jsp:setProperty name="formhandler" property="contextId" value="<%=(String)session.getAttribute("i2p.contextId")%>" />
 <jsp:getProperty name="formhandler" property="allMessages" />
 <jsp:useBean class="net.i2p.router.web.ConfigKeyringHelper" id="keyringhelper" scope="request" />
 <jsp:setProperty name="keyringhelper" property="contextId" value="<%=(String)session.getAttribute("i2p.contextId")%>" />
<div class="configure"><h2>Keyring</h2><p>
 The router keyring is used to decrypt encrypted leaseSets.
The keyring may contain keys for local or remote encrypted destinations.</p>
 <div class="wideload"><p>
 <jsp:getProperty name="keyringhelper" property="summary" />
</p></div>

 <form action="configkeyring.jsp" method="POST">
 <% String prev = System.getProperty("net.i2p.router.web.ConfigKeyringHandler.nonce");
    if (prev != null) System.setProperty("net.i2p.router.web.ConfigKeyringHandler.noncePrev", prev);
    System.setProperty("net.i2p.router.web.ConfigKeyringHandler.nonce", new java.util.Random().nextLong()+""); %>
 <input type="hidden" name="nonce" value="<%=System.getProperty("net.i2p.router.web.ConfigKeyringHandler.nonce")%>" />
 <h3>Manual Keyring Addition</h3><p>
 Enter keys for encrypted remote destinations here.
 Keys for local destinations must be entered on the <a href="i2ptunnel/index.jsp">I2PTunnel page</a>.
</p>
  <div class="wideload">
      <p><table><tr>
          <td class="mediumtags" align="right">Dest. name, hash, or full key:</td>
          <td><textarea name="peer" cols="44" rows="1" style="height: 3em;" wrap="off"></textarea></td>
        </tr><tr>
          <td class="mediumtags" align="right">Encryption Key:</td>
          <td><input type="text" size="55" name="key" /></td>
        </tr><tr>
          <td align="right" colspan="2"><input type="submit" name="action" value="Add key" />
             <input type="submit" name="action" value="Delete key" /> <input type="reset" value="Cancel" /></td>
</tr></table></p></div></form></div></div></body></html>
