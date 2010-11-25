package net.i2p.desktopgui.util;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Manage the configuration of desktopgui.
 * @author mathias
 *
 */
public class ConfigurationManager {
	
	private static ConfigurationManager instance;
	///Configurations with a String as value
	private Map<String, String> stringConfigurations = new HashMap<String, String>();
	///Configurations with a Boolean as value
	private Map<String, Boolean> booleanConfigurations = new HashMap<String, Boolean>();

	private ConfigurationManager() {}
	
	public static ConfigurationManager getInstance() {
		if(instance == null) {
			instance = new ConfigurationManager();
		}
		return instance;
	}
	
	/**
	 * Collects arguments of the form --word, --word=otherword and -blah
	 * to determine user parameters.
	 * @param args Command line arguments to the application
	 */
	public void loadArguments(String[] args) {
		for(int i=0; i<args.length; i++) {
			String arg = args[i];
			if(arg.startsWith("--")) {
				arg = arg.substring(2);
				if(arg.length() < 1) {
					continue;
				}
				int equals = arg.indexOf('=');
				if(equals != -1 && equals < arg.length() - 1) { //String configuration
					loadStringConfiguration(arg, equals);
				}
				else { //Boolean configuration
					loadBooleanConfiguration(arg);
				}
			}
			else if(arg.startsWith("-")) { //Boolean configuration
				loadBooleanConfiguration(arg);
			}
		}
	}
	
	public void loadBooleanConfiguration(String arg) {
		booleanConfigurations.put(arg, Boolean.TRUE);
	}
	
	public void loadStringConfiguration(String arg, int equalsPosition) {
		String key = arg.substring(0, equalsPosition);
		String value = arg.substring(equalsPosition+1);
		stringConfigurations.put(key, value);
	}
	
	public boolean getBooleanConfiguration(String arg, boolean defaultValue) {
		Boolean value = ((Boolean) booleanConfigurations.get("startWithI2P"));
		System.out.println(value);
		if(value != null) {
			return value;
		}
		return defaultValue;
	}
	
	public String getStringConfiguration(String arg, String defaultValue) {
		String value = stringConfigurations.get(arg);
		System.out.println(value);
		if(value != null) {
			return value;
		}
		return defaultValue;
	}
}
