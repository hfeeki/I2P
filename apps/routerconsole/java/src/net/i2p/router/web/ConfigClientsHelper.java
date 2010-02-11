package net.i2p.router.web;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

import net.i2p.router.startup.ClientAppConfig;

public class ConfigClientsHelper extends HelperBase {
    private String _edit;

    public ConfigClientsHelper() {}
    
    public void setEdit(String edit) {
         if (edit == null)
             return;
        String xStart = _("Edit");
        if (edit.startsWith(xStart + "<span class=hide> ") &&
            edit.endsWith("</span>")) {
            // IE sucks
            _edit = edit.substring(xStart.length() + 18, edit.length() - 7);
        } else if (edit.startsWith("Edit ")) {
            _edit = edit.substring(5);
        } else if (edit.startsWith(xStart + ' ')) {
            _edit = edit.substring(xStart.length() + 1);
        } else if ((_("Add Client")).equals(edit)) {
            _edit = "new";
        }
    }

    public String getForm1() {
        StringBuilder buf = new StringBuilder(1024);
        buf.append("<table>\n");
        buf.append("<tr><th align=\"right\">" + _("Client") + "</th><th>" + _("Run at Startup?") + "</th><th>" + _("Control") + "</th><th align=\"left\">" + _("Class and arguments") + "</th></tr>\n");
        
        List<ClientAppConfig> clients = ClientAppConfig.getClientApps(_context);
        for (int cur = 0; cur < clients.size(); cur++) {
            ClientAppConfig ca = clients.get(cur);
            renderForm(buf, ""+cur, ca.clientName, false, !ca.disabled,
                       "webConsole".equals(ca.clientName) || "Web console".equals(ca.clientName),
                       ca.className + ((ca.args != null) ? " " + ca.args : ""), (""+cur).equals(_edit), true, false, false);
        }
        
        if ("new".equals(_edit))
            renderForm(buf, "" + clients.size(), "", false, false, false, "", true, false, false, false);
        buf.append("</table>\n");
        return buf.toString();
    }

    public String getForm2() {
        StringBuilder buf = new StringBuilder(1024);
        buf.append("<table>\n");
        buf.append("<tr><th align=\"right\">" + _("WebApp") + "</th><th>" + _("Run at Startup?") + "</th><th>" + _("Control") + "</th><th align=\"left\">" + _("Description") + "</th></tr>\n");
        Properties props = RouterConsoleRunner.webAppProperties();
        Set<String> keys = new TreeSet(props.keySet());
        for (Iterator<String> iter = keys.iterator(); iter.hasNext(); ) {
            String name = iter.next();
            if (name.startsWith(RouterConsoleRunner.PREFIX) && name.endsWith(RouterConsoleRunner.ENABLED)) {
                String app = name.substring(RouterConsoleRunner.PREFIX.length(), name.lastIndexOf(RouterConsoleRunner.ENABLED));
                String val = props.getProperty(name);
                renderForm(buf, app, app, !"addressbook".equals(app),
                           "true".equals(val), RouterConsoleRunner.ROUTERCONSOLE.equals(app), app + ".war", false, false, false, false);
            }
        }
        buf.append("</table>\n");
        return buf.toString();
    }

    public boolean showPlugins() {
        return PluginStarter.pluginsEnabled(_context);
    }

    public String getForm3() {
        StringBuilder buf = new StringBuilder(1024);
        buf.append("<table>\n");
        buf.append("<tr><th align=\"right\">" + _("Plugin") + "</th><th>" + _("Run at Startup?") + "</th><th>" + _("Control") + "</th><th align=\"left\">" + _("Description") + "</th></tr>\n");
        Properties props = PluginStarter.pluginProperties();
        Set<String> keys = new TreeSet(props.keySet());
        for (Iterator<String> iter = keys.iterator(); iter.hasNext(); ) {
            String name = iter.next();
            if (name.startsWith(PluginStarter.PREFIX) && name.endsWith(PluginStarter.ENABLED)) {
                String app = name.substring(PluginStarter.PREFIX.length(), name.lastIndexOf(PluginStarter.ENABLED));
                String val = props.getProperty(name);
                Properties appProps = PluginStarter.pluginProperties(_context, app);
                StringBuilder desc = new StringBuilder(256);
                desc.append("<table border=\"0\">")
                    .append("<tr><td><b>").append(_("Version")).append("<td>").append(stripHTML(appProps, "version"))
                    .append("<tr><td><b>")
                    .append(_("Signed by")).append("<td>");
                String s = stripHTML(appProps, "keyName");
                if (s.indexOf("@") > 0)
                    desc.append("<a href=\"mailto:").append(s).append("\">").append(s).append("</a>");
                else
                    desc.append(s);
                s = stripHTML(appProps, "date");
                if (s != null) {
                    long ms = 0;
                    try {
                        ms = Long.parseLong(s);
                    } catch (NumberFormatException nfe) {}
                    if (ms > 0) {
                        String date = (new SimpleDateFormat("yyyy-MM-dd HH:mm")).format(new Date(ms));
                        desc.append("<tr><td><b>")
                            .append(_("Date")).append("<td>").append(date);
                    }
                }
                s = stripHTML(appProps, "author");
                if (s != null) {
                    desc.append("<tr><td><b>")
                        .append(_("Author")).append("<td>");
                    if (s.indexOf("@") > 0)
                        desc.append("<a href=\"mailto:").append(s).append("\">").append(s).append("</a>");
                    else
                        desc.append(s);
                }
                s = stripHTML(appProps, "description_" + Messages.getLanguage(_context));
                if (s == null)
                    s = stripHTML(appProps, "description");
                if (s != null) {
                    desc.append("<tr><td><b>")
                        .append(_("Description")).append("<td>").append(s);
                }
                s = stripHTML(appProps, "license");
                if (s != null) {
                    desc.append("<tr><td><b>")
                        .append(_("License")).append("<td>").append(s);
                }
                s = stripHTML(appProps, "websiteURL");
                if (s != null) {
                    desc.append("<tr><td>")
                        .append("<a href=\"").append(s).append("\">").append(_("Website")).append("</a><td>&nbsp;");
                }
                String updateURL = stripHTML(appProps, "updateURL");
                if (updateURL != null) {
                    desc.append("<tr><td>")
                        .append("<a href=\"").append(updateURL).append("\">").append(_("Update link")).append("</a><td>&nbsp;");
                }
                desc.append("</table>");
                renderForm(buf, app, app, false,
                           "true".equals(val), false, desc.toString(), false, false,
                           updateURL != null, true);
            }
        }
        buf.append("</table>\n");
        return buf.toString();
    }

    /** ro trumps edit and showEditButton */
    private void renderForm(StringBuilder buf, String index, String name, boolean urlify,
                            boolean enabled, boolean ro, String desc, boolean edit,
                            boolean showEditButton, boolean showUpdateButton, boolean showStopButton) {
        buf.append("<tr><td class=\"mediumtags\" align=\"right\" width=\"25%\">");
        if (urlify && enabled) {
            String link = "/";
            if (! RouterConsoleRunner.ROUTERCONSOLE.equals(name))
                link += name + "/";
            buf.append("<a href=\"").append(link).append("\">").append(_(name)).append("</a>");
        } else if (edit && !ro) {
            buf.append("<input type=\"text\" name=\"name").append(index).append("\" value=\"");
            if (name.length() > 0)
                buf.append(_(name));
            buf.append("\" >");
        } else {
            if (name.length() > 0)
                buf.append(_(name));
        }
        buf.append("</td><td align=\"center\" width=\"10%\"><input type=\"checkbox\" class=\"optbox\" name=\"").append(index).append(".enabled\" value=\"true\" ");
        if (enabled) {
            buf.append("checked=\"true\" ");
            if (ro)
                buf.append("disabled=\"true\" ");
        }
        buf.append("></td><td align=\"center\" width=\"15%\">");
        if ((!enabled) && !edit) {
            buf.append("<button type=\"submit\" name=\"action\" value=\"Start ").append(index).append("\" >" + _("Start") + "<span class=hide> ").append(index).append("</span></button>");
        }
        if (showEditButton && (!edit) && !ro)
            buf.append("<button type=\"submit\" name=\"edit\" value=\"Edit ").append(index).append("\" >" + _("Edit") + "<span class=hide> ").append(index).append("</span></button>");
        if (showStopButton && (!edit))
            buf.append("<button type=\"submit\" name=\"action\" value=\"Stop ").append(index).append("\" >" + _("Stop") + "<span class=hide> ").append(index).append("</span></button>");
        if (showUpdateButton && (!edit) && !ro) {
            buf.append("<button type=\"submit\" name=\"action\" value=\"Check ").append(index).append("\" >" + _("Check for updates") + "<span class=hide> ").append(index).append("</span></button>");
            buf.append("<button type=\"submit\" name=\"action\" value=\"Update ").append(index).append("\" >" + _("Update") + "<span class=hide> ").append(index).append("</span></button>");
        }
        if ((!edit) && !ro)
            buf.append("<button type=\"submit\" name=\"action\" value=\"Delete ").append(index).append("\" >" + _("Delete") + "<span class=hide> ").append(index).append("</span></button>");
        buf.append("</td><td align=\"left\" width=\"50%\">");
        if (edit && !ro) {
            buf.append("<input type=\"text\" size=\"80\" name=\"desc").append(index).append("\" value=\"");
            buf.append(desc);
            buf.append("\" >");
        } else {
            buf.append(desc);
        }
        buf.append("</td></tr>\n");
    }

    /**
     *  Like in DataHelper but doesn't convert null to ""
     *  There's a lot worse things a plugin could do but...
     */
    static String stripHTML(Properties props, String key) {
        String orig = props.getProperty(key);
        if (orig == null) return null;
        String t1 = orig.replace('<', ' ');
        String rv = t1.replace('>', ' ');
        return rv;
    }
}
