/*
 * I2P - An anonymous, secure, and fully-distributed communication network.
 *
 * ConfigFile.java
 * 2004 The I2P Project
 * This code is public domain.
 */

package net.i2p.apps.systray;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Properties;

/**
 * Simple config file handler.
 * 
 * @author hypercubus
 */
public class ConfigFile {

    // TODO Make write operations keep original line comments intact.

    private String     _configFile;
    private Properties _properties = new Properties();

    /**
     * Initializes the {@link ConfigFile} object.
     * 
     * @param  configFile The config file to use.
     * @return            <code>false</code> if the given config file cannot be
     *                    located or accessed, otherwise <code>true</code>.
     */
    public boolean init(String configFile) {
        _configFile = configFile;
        return readConfigFile();
    }

    public String getProperty(String key) {
        return _properties.getProperty(key);
    }

    public String getProperty(String key, String defaultValue) {
        return _properties.getProperty(key, defaultValue);
    }

    public void setProperty(String key, String value) {
        _properties.setProperty(key, value);
        writeConfigFile();
    }

    private boolean readConfigFile() {

        FileInputStream fileInputStream = null;

        try {
            fileInputStream = new FileInputStream(_configFile);
            _properties.load(fileInputStream);
        } catch (IOException e) {
            return false;
        }
        try {
            fileInputStream.close();
        } catch (IOException e1) {
            // No worries.
        }
        return true;
    }

    private void writeConfigFile() {

        FileOutputStream fileOutputStream = null;

        try {
            fileOutputStream = new FileOutputStream(_configFile);
            _properties.store(fileOutputStream, null);
        } catch (IOException e) {
            System.exit(1);
        }
        try {
            fileOutputStream.close();
        } catch (IOException e1) {
            // No worries.
        }
    }
}
