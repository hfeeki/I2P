package i2p.susi.dns;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.Properties;

import net.i2p.I2PAppContext;

/**
 * Holds methods common to several Beans.
 * @since 0.9.1
 */
public class BaseBean
{
    private final I2PAppContext _context;
    protected final Properties properties;
    private String _theme;

    private long configLastLoaded = 0;
    private static final String PRIVATE_BOOK = "private_addressbook";
    private static final String DEFAULT_PRIVATE_BOOK = "../privatehosts.txt";

    public static final String THEME_CONFIG_FILE = "themes.config";
    public static final String PROP_THEME_NAME = "susidns.theme";
    public static final String DEFAULT_THEME = "light";
    public static final String BASE_THEME_PATH = "/themes/susidns/";

    public BaseBean()
    {
        _context = I2PAppContext.getGlobalContext();
        properties = new Properties();
    }

    protected void loadConfig()
    {
        long currentTime = System.currentTimeMillis();

        if( !properties.isEmpty() &&  currentTime - configLastLoaded < 10000 )
            return;

        FileInputStream fis = null;
        try {
            properties.clear();
            fis = new FileInputStream( ConfigBean.configFileName );
            properties.load( fis );
            // added in 0.5, for compatibility with 0.4 config.txt
            if( properties.getProperty(PRIVATE_BOOK) == null)
                properties.setProperty(PRIVATE_BOOK, DEFAULT_PRIVATE_BOOK);
            // Fetch theme
            _theme = _context.readConfigFile(THEME_CONFIG_FILE).getProperty(PROP_THEME_NAME, DEFAULT_THEME);
            configLastLoaded = currentTime;
        }
        catch (Exception e) {
            Debug.debug( e.getClass().getName() + ": " + e.getMessage() );
        } finally {
            if (fis != null)
                try { fis.close(); } catch (IOException ioe) {}
        }
    }

    /**
     * Returns the theme path
     * @since 0.9.1
     */
    public String getTheme() {
        loadConfig();
        String url = BASE_THEME_PATH;
        url += _theme + "/";
        return url;
    }
}
