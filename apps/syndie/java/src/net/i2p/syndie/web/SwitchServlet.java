package net.i2p.syndie.web;

import java.io.IOException;
import java.io.PrintWriter;

import javax.servlet.http.HttpServletRequest;

import net.i2p.syndie.Archive;
import net.i2p.syndie.User;
import net.i2p.syndie.data.BlogURI;
import net.i2p.syndie.data.ThreadIndex;
import net.i2p.syndie.sml.ThreadedHTMLRenderer;

/**
 * Login/register form
 *
 */
public class SwitchServlet extends BaseServlet { 
    protected String getTitle() { return "Syndie :: Login/Register"; }
    
    protected void renderServletDetails(User user, HttpServletRequest req, PrintWriter out, ThreadIndex index, 
                                        int threadOffset, BlogURI visibleEntry, Archive archive) throws IOException {
        out.write("<form action=\"" + getControlTarget() + "\" method=\"POST\">\n");
        writeAuthActionFields(out);
        out.write("<tr><td colspan=\"3\"><b>Log in to an existing account</b></td></tr>\n" +
                  "<tr><td colspan=\"3\">Login: <input type=\"text\" name=\"login\" /></td></tr>\n" +
                  "<tr><td colspan=\"3\">Password: <input type=\"password\" name=\"password\" /></td></tr>\n" +
                  "<tr><td colspan=\"3\"><input type=\"submit\" name=\"action\" value=\"Login\" />\n" +
                  "<input type=\"submit\" name=\"action\" value=\"Cancel\" />\n" +
                  "<input type=\"submit\" name=\"action\" value=\"Logout\" /></td></tr>\n" +
                  "</form>\n" +
                  "<tr><td colspan=\"3\"><hr /></td></tr>\n" +
                  "<form action=\"" + ThreadedHTMLRenderer.buildProfileURL(null) + "\" method=\"POST\">\n");
        writeAuthActionFields(out);
        out.write("<tr><td colspan=\"3\"><b>Register a new account</b></td></tr>\n" +
                  "<tr><td colspan=\"3\">Login: <input type=\"text\" name=\"login\" /> (only known locally)</td></tr>\n" +
                  "<tr><td colspan=\"3\">Password: <input type=\"password\" name=\"password\" /></td></tr>\n" +
                  "<tr><td colspan=\"3\">Public name: <input type=\"text\" name=\"accountName\" /></td></tr>\n" +
                  "<tr><td colspan=\"3\">Description: <input type=\"text\" name=\"description\" /></td></tr>\n" +
                  "<tr><td colspan=\"3\">Contact URL: <input type=\"text\" name=\"contactURL\" /></td></tr>\n" +
                  "<tr><td colspan=\"3\">Registration password: <input type=\"password\" name=\"registrationPass\" />" +
                  " (only necessary if the Syndie administrator requires it)</td></tr>\n" +
                  "<tr><td colspan=\"3\"><input type=\"submit\" name=\"action\" value=\"Register\" /></td></tr>\n" +
                  "</form>\n");
    }
}
