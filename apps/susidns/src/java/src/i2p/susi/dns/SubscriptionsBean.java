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

package i2p.susi.dns;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Properties;

public class SubscriptionsBean
{
	private String action, fileName, content, serial, lastSerial;
	private boolean saved;
	
	Properties properties;
	
	public SubscriptionsBean()
	{
		properties = new Properties();
	}
	private long configLastLoaded = 0;
	private void loadConfig()
	{
		long currentTime = System.currentTimeMillis();
		
		if( properties.size() > 0 &&  currentTime - configLastLoaded < 10000 )
			return;
		
		try {
			properties.clear();
			properties.load( new FileInputStream( ConfigBean.configFileName ) );
			configLastLoaded = currentTime;
		}
		catch (Exception e) {
			Debug.debug( e.getClass().getName() + ": " + e.getMessage() );
		}	
	}
	public String getAction() {
		return action;
	}

	public void setAction(String action) {
		this.action = action;
	}

	public String getFileName()
	{
		loadConfig();
		
		fileName = ConfigBean.addressbookPrefix + properties.getProperty( "subscriptions", "subscriptions.txt" );
		
		return fileName;
	}
	private void reload()
	{
		File file = new File( getFileName() );
		if( file != null && file.isFile() ) {
			StringBuffer buf = new StringBuffer();
			try {
				BufferedReader br = new BufferedReader( new FileReader( file ) );
				String line;
				while( ( line = br.readLine() ) != null ) {
					buf.append( line );
					buf.append( "\n" );
				}
				content = buf.toString();
				saved = true;
			} catch (FileNotFoundException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
	}
	
	private void save()
	{
		File file = new File( getFileName() );
		try {
			PrintWriter out = new PrintWriter( new FileOutputStream( file ) );
			out.print( content );
			out.flush();
			out.close();
			saved = true;
		} catch (FileNotFoundException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
	public String getMessages() {
		String message = "";
		if( action != null ) {
			if( lastSerial != null && serial != null && serial.compareTo( lastSerial ) == 0 ) {
				if( action.compareToIgnoreCase( "save") == 0 ) {
					save();
					message = "Subscriptions saved.";
				}
				else if( action.compareToIgnoreCase( "reload") == 0 ) {
					reload();
					message = "Subscriptions reloaded.";
				}
			}			
			else {
				message = "Invalid nonce. Are you being spoofed?";
			}
		}
		if( message.length() > 0 )
			message = "<p class=\"messages\">" + message + "</p>";
		return message;
	}
	public String getSerial()
	{
		lastSerial = "" + Math.random();
		action = null;
		return lastSerial;
	}
	public void setSerial(String serial ) {
		this.serial = serial;
	}
	public void setContent(String content) {
		this.content = content;
		this.saved = false;
		
		/*
		 * as this is a property file we need a newline at the end of the last line!
		 */
		if( ! this.content.endsWith( "\n" ) ) {
			this.content += "\n";
		}
	}
	public String getContent()
	{
		if( content != null )
			return content;
		
		reload();
		
		return content;
	}
}
