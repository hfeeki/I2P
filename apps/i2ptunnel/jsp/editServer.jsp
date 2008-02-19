<%@page contentType="text/html" import="net.i2p.i2ptunnel.web.EditBean"%><?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.0 Strict//EN" "http://www.w3.org/TR/xhtml1/DTD/xhtml1-strict.dtd">
<jsp:useBean class="net.i2p.i2ptunnel.web.EditBean" id="editBean" scope="request" />
<% String tun = request.getParameter("tunnel");
   int curTunnel = -1;
   if (tun != null) {
     try {
       curTunnel = Integer.parseInt(tun);
     } catch (NumberFormatException nfe) {
       curTunnel = -1;
     }
   }
%>
<html xmlns="http://www.w3.org/1999/xhtml" xml:lang="en" lang="en">
<head>
    <title>I2PTunnel Webmanager - Edit</title>
    <meta http-equiv="Content-Type" content="text/html; charset=UTF-8" />
    <meta http-equiv="Content-Type" content="application/xhtml+xml; charset=UTF-8" />

    <% if (editBean.allowCSS()) {
  %><link href="images/favicon.ico" type="image/x-icon" rel="shortcut icon" />
    <link href="<%=editBean.getTheme()%>default.css" rel="stylesheet" type="text/css" /> 
    <link href="<%=editBean.getTheme()%>i2ptunnel.css" rel="stylesheet" type="text/css" />
    <% }
  %>
</head>
<body id="tunnelEditPage">
    <div id="pageHeader">
    </div>

    <form method="post" action="index.jsp">

        <div id="tunnelEditPanel" class="panel">
            <div class="header">
                <%
                String tunnelTypeName = "";
                String tunnelType = "";
                if (curTunnel >= 0) {
                    tunnelTypeName = editBean.getTunnelType(curTunnel);
                    tunnelType = editBean.getInternalType(curTunnel);
                  %><h4>Edit server settings</h4><% 
                } else {
                    tunnelTypeName = editBean.getTypeName(request.getParameter("type"));
                    tunnelType = request.getParameter("type");
                  %><h4>New server settings</h4><% 
                } %>
                <input type="hidden" name="tunnel" value="<%=request.getParameter("tunnel")%>" />
                <input type="hidden" name="nonce" value="<%=editBean.getNextNonce()%>" />
                <input type="hidden" name="type" value="<%=tunnelType%>" />
            </div>
      
            <div class="separator">
                <hr />
            </div>

            <div id="nameField" class="rowItem">
                <label for="name" accesskey="N">
                    <span class="accessKey">N</span>ame:
                </label>
                <input type="text" size="30" maxlength="50" name="name" id="name" title="Tunnel Name" value="<%=editBean.getTunnelName(curTunnel)%>" class="freetext" />               
            </div>
            <div id="typeField" class="rowItem">
                <label>Type:</label>
                <span class="text"><%=tunnelTypeName%></span>
            </div>
            <div id="descriptionField" class="rowItem">
                <label for="description" accesskey="e">
                    D<span class="accessKey">e</span>scription:
                </label>
                <input type="text" size="60" maxlength="80" name="description"  id="description" title="Tunnel Description" value="<%=editBean.getTunnelDescription(curTunnel)%>" class="freetext" />                
            </div>
            <div id="startupField" class="rowItem">
                <label for="startOnLoad" accesskey="a">
                    <span class="accessKey">A</span>uto Start:
                </label>
                <input value="1" type="checkbox" id="startOnLoad" name="startOnLoad" title="Start Tunnel Automatically"<%=(editBean.startAutomatically(curTunnel) ? " checked=\"checked\"" : "")%> class="tickbox" />                
                <span class="comment">(Check the Box for 'YES')</span>
            </div>
                 
            <div class="subdivider">
                <hr />
            </div>
                 
            <div id="targetField" class="rowItem">
                <label>Target:</label>
            </div>
            <div id="hostField" class="rowItem">
                <label for="targetHost" accesskey="H">
                    <span class="accessKey">H</span>ost:
                </label>
                <input type="text" size="20" id="targetHost" name="targetHost" title="Target Hostname or IP" value="<%=editBean.getTargetHost(curTunnel)%>" class="freetext" />                
            </div>
            <div id="portField" class="rowItem">
                <label for="targetPort" accesskey="P">
                    <span class="accessKey">P</span>ort:
                </label>
                <input type="text" size="6" maxlength="5" id="targetPort" name="targetPort" title="Target Port Number" value="<%=editBean.getTargetPort(curTunnel)%>" class="freetext" />               
            </div>
            
            <div class="subdivider">
                <hr />
            </div>
            
            <% if ("httpserver".equals(tunnelType)) {
          %><div id="websiteField" class="rowItem">
                <label for="spoofedHost" accesskey="W">
                    <span class="accessKey">W</span>ebsite name:
                </label>
                <input type="text" size="20" id="spoofedHost" name="spoofedHost" title="Website Host Name" value="<%=editBean.getSpoofedHost(curTunnel)%>" class="freetext" />                
            </div>
            <% }
          %><div id="privKeyField" class="rowItem">
                <label for="privKeyFile" accesskey="k">
                    Private <span class="accessKey">k</span>ey file:
                </label>
                <input type="text" size="30" id="privKeyFile" name="privKeyFile" title="Path to Private Key File" value="<%=editBean.getPrivateKeyFile(curTunnel)%>" class="freetext" />               
            </div>
            <div id="profileField" class="rowItem">
                <label for="profile" accesskey="f">
                    Pro<span class="accessKey">f</span>ile:
                </label>
                <select id="profile" name="profile" title="Connection Profile" class="selectbox">
                    <% boolean interactiveProfile = editBean.isInteractive(curTunnel);
                  %><option <%=(interactiveProfile == true  ? "selected=\"selected\" " : "")%>value="interactive">interactive connection </option>
                    <option <%=(interactiveProfile == false ? "selected=\"selected\" " : "")%>value="bulk">bulk connection (downloads/websites/BT) </option>
                </select>                
            </div> 
            <div id="destinationField" class="rowItem">
                <label for="localDestination" accesskey="L">
                    <span class="accessKey">L</span>ocal destination:
                </label>
                <input type="text" size="60" readonly="readonly" id="localDestination" title="Read Only: Local Destination (if known)" value="<%=editBean.getDestinationBase64(curTunnel)%>" class="freetext" />               
                <span class="comment">(if known)</span>
            </div>
            
            <div class="footer">
            </div>
        </div>

        <div id="tunnelAdvancedNetworking" class="panel">
            <div class="header">
                <h4>Advanced networking options</h4>
            </div>

            <div class="separator">
                <hr />
            </div>
            
            <div id="tunnelOptionsField" class="rowItem">
                <label>Tunnel Options:</label>
            </div>
            <div id="depthField" class="rowItem">
                <label for="tunnelDepth" accesskey="t">
                    Dep<span class="accessKey">t</span>h:
                </label>
                <select id="tunnelDepth" name="tunnelDepth" title="Depth of each Tunnel" class="selectbox">
                    <% int tunnelDepth = editBean.getTunnelDepth(curTunnel, 2);
                  %><option value="0"<%=(tunnelDepth == 0 ? " selected=\"selected\"" : "") %>>0 hop tunnel (low anonymity, low latency)</option>
                    <option value="1"<%=(tunnelDepth == 1 ? " selected=\"selected\"" : "") %>>1 hop tunnel (medium anonymity, medium latency)</option>
                    <option value="2"<%=(tunnelDepth == 2 ? " selected=\"selected\"" : "") %>>2 hop tunnel (high anonymity, high latency)</option>
                <% if (tunnelDepth > 2) { 
                %>    <option value="<%=tunnelDepth%>" selected="selected"><%=tunnelDepth%> hop tunnel</option>
                <% }
              %></select>
            </div>
            <div id="varianceField" class="rowItem">
                <label for="tunnelVariance" accesskey="v">
                    <span class="accessKey">V</span>ariance:
                </label>
                <select id="tunnelVariance" name="tunnelVariance" title="Level of Randomization for Tunnel Depth" class="selectbox">
                    <% int tunnelVariance = editBean.getTunnelVariance(curTunnel, -1);
                  %><option value="0"<%=(tunnelVariance  ==  0 ? " selected=\"selected\"" : "") %>>0 hop variance          (no randomisation, consistant performance)</option>
                    <option value="-1"<%=(tunnelVariance == -1 ? " selected=\"selected\"" : "") %>>+/- 0-1 hop variance    (standard randomisation, standard performance)</option>
                    <option value="-2"<%=(tunnelVariance == -2 ? " selected=\"selected\"" : "") %>>+/- 0-2 hop variance    (high randomisation, variable performance)</option>
                    <option value="1"<%=(tunnelVariance  ==  1 ? " selected=\"selected\"" : "") %>>+ 0-1 hop variance      (medium additive randomisation, subtractive performance)</option>
                    <option value="2"<%=(tunnelVariance  ==  2 ? " selected=\"selected\"" : "") %>>+ 0-2 hop variance      (high additive randomisation, subtractive performance)</option>
                <% if (tunnelVariance > 2 || tunnelVariance < -2) {
                %>    <option value="<%=tunnelVariance%>" selected="selected"><%= (tunnelVariance > 2 ? "+ " : "+/- ") %>0-<%=tunnelVariance%> hop variance</option>
                <% }
              %></select>                
            </div>
            <div id="countField" class="rowItem">
                <label for="tunnelQuantity" accesskey="C">
                    <span class="accessKey">C</span>ount:
                </label>
                <select id="tunnelQuantity" name="tunnelQuantity" title="Number of Tunnels in Group" class="selectbox">
                    <% int tunnelQuantity = editBean.getTunnelQuantity(curTunnel, 2);
                  %><option value="1"<%=(tunnelQuantity == 1 ? " selected=\"selected\"" : "") %>>1 inbound, 1 outbound tunnel  (low bandwidth usage, less reliability)</option>
                    <option value="2"<%=(tunnelQuantity == 2 ? " selected=\"selected\"" : "") %>>2 inbound, 2 outbound tunnels (standard bandwidth usage, standard reliability)</option>
                    <option value="3"<%=(tunnelQuantity == 3 ? " selected=\"selected\"" : "") %>>3 inbound, 3 outbound tunnels (higher bandwidth usage, higher reliability)</option>
                <% if (tunnelQuantity > 3) {
                %>    <option value="<%=tunnelQuantity%>" selected="selected"><%=tunnelQuantity%> tunnels</option>
                <% }
              %></select>                
            </div>
            <div id="backupField" class="rowItem">
                <label for="tunnelBackupQuantity" accesskey="b">
                    <span class="accessKey">B</span>ackup Count:
                </label>
                <select id="tunnelBackupQuantity" name="tunnelBackupQuantity" title="Number of Reserve Tunnels" class="selectbox">
                    <% int tunnelBackupQuantity = editBean.getTunnelBackupQuantity(curTunnel, 0);
                  %><option value="0"<%=(tunnelBackupQuantity == 0 ? " selected=\"selected\"" : "") %>>0 backup tunnels      (0 redundancy, no added resource usage)</option>
                    <option value="1"<%=(tunnelBackupQuantity == 1 ? " selected=\"selected\"" : "") %>>1 backup tunnel each direction (low redundancy, low resource usage)</option>
                    <option value="2"<%=(tunnelBackupQuantity == 2 ? " selected=\"selected\"" : "") %>>2 backup tunnels each direction (medium redundancy, medium resource usage)</option>
                    <option value="3"<%=(tunnelBackupQuantity == 3 ? " selected=\"selected\"" : "") %>>3 backup tunnels each direction (high redundancy, high resource usage)</option>
                <% if (tunnelBackupQuantity > 3) {
                %>    <option value="<%=tunnelBackupQuantity%>" selected="selected"><%=tunnelBackupQuantity%> backup tunnels</option>
                <% }
              %></select>                
            </div>
                            
            <div class="subdivider">
                <hr />
            </div>
            
            <div id="optionsField" class="rowItem">
                <label>I2CP Options:</label>
            </div>
            <div id="optionsHostField" class="rowItem">
                <label for="clientHost" accesskey="o">
                    H<span class="accessKey">o</span>st:
                </label>
                <input type="text" id="clientHost" name="clientHost" size="20" title="I2CP Hostname or IP" value="<%=editBean.getI2CPHost(curTunnel)%>" class="freetext" />                
            </div>
            <div id="optionsPortField" class="rowItem">
                <label for="clientPort" accesskey="r">
                    Po<span class="accessKey">r</span>t:
                </label>
                <input type="text" id="clientPort" name="clientport" size="20" title="I2CP Port Number" value="<%=editBean.getI2CPPort(curTunnel)%>" class="freetext" />                
            </div>
            
            <div class="subdivider">
                <hr />
            </div>
           
            <div id="customOptionsField" class="rowItem">
                <label for="customOptions" accesskey="u">
                    C<span class="accessKey">u</span>stom options:
                </label>
                <input type="text" id="customOptions" name="customOptions" size="60" title="Custom Options" value="<%=editBean.getCustomOptions(curTunnel)%>" class="freetext" />                
            </div>
            
            <div class="footer">
            </div>
        </div>
        <div id="globalOperationsPanel" class="panel">
            <div class="header"></div>
            <div class="footer">
                <div class="toolbox">
                    <input type="hidden" value="true" name="removeConfirm" />
                    <button id="controlSave" accesskey="S" class="control" type="submit" name="action" value="Save changes" title="Save Changes"><span class="accessKey">S</span>ave</button><button id="controlDelete" <%=(editBean.allowJS() ? "onclick=\"if (!confirm('Are you sure you want to delete?')) { return false; }\" " : "")%>accesskey="D" class="control" type="submit" name="action" value="Delete this proxy" title="Delete this Proxy"><span class="accessKey">D</span>elete</button>
                </div>
            </div> 
        </div>
    </form>
    <div id="pageFooter">
    </div>
</body>
</html>
