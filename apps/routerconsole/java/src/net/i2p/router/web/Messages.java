package net.i2p.router.web;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.i2p.I2PAppContext;
import net.i2p.util.ConcurrentHashSet;

/**
 * Translate strings efficiently.
 * We don't include an English or default ResourceBundle, we simply check
 * for "en" and return the original string.
 * Support real-time language changing with the routerconsole.lang property.
 *
 * @author zzz, from a base generated by eclipse.
 */
public class Messages {
    public static final String PROP_LANG = "routerconsole.lang";
    private static final String BUNDLE_NAME = "net.i2p.router.web.messages";
    private static final String _localeLang = Locale.getDefault().getLanguage();
    private static final Map<String, ResourceBundle> _bundles = new ConcurrentHashMap(2);
    private static final Set<String> _missing = new ConcurrentHashSet(2);
    /** use to look for untagged strings */
    private static final String TEST_LANG = "xx";
    private static final String TEST_STRING = "XXXX";

    /** current locale **/
/* unused
    public static String getString(String key) {
        if (_localeLang.equals("en"))
            return key;
        ResourceBundle bundle = findBundle(_localeLang);
        if (bundle == null)
            return key;
        try {
            return bundle.getString(key);
        } catch (MissingResourceException e) {
            return key;
        }
    }
*/

    /** lang in routerconsole.lang property, else current locale */
    public static String getString(String key, I2PAppContext ctx) {
        String lang = getLanguage(ctx);
        if (lang.equals("en"))
            return key;
        else if (lang.equals(TEST_LANG))
            return TEST_STRING;
        ResourceBundle bundle = findBundle(lang);
        if (bundle == null)
            return key;
        try {
            return bundle.getString(key);
        } catch (MissingResourceException e) {
            return key;
        }
    }

    /**
     *  translate a string with a parameter
     *  This is a lot more expensive than getString(s, ctx), so use sparingly.
     *
     *  @param s string to be translated containing {0}
     *    The {0} will be replaced by the parameter.
     *    Single quotes must be doubled, i.e. ' -> '' in the string.
     *  @param o parameter, not translated.
     *    To tranlslate parameter also, use _("foo {0} bar", _("baz"))
     *    Do not double the single quotes in the parameter.
     *    Use autoboxing to call with ints, longs, floats, etc.
     */
    public static String getString(String s, Object o, I2PAppContext ctx) {
        String lang = getLanguage(ctx);
        if (lang.equals(TEST_LANG))
            return TEST_STRING + '(' + o + ')' + TEST_STRING;
        String x = getString(s, ctx);
        Object[] oArray = new Object[1];
        oArray[0] = o;
        try {
            MessageFormat fmt = new MessageFormat(x, new Locale(lang));
            return fmt.format(oArray, new StringBuffer(), null).toString();
        } catch (IllegalArgumentException iae) {
            System.err.println("Bad format: orig: \"" + s +
                               "\" trans: \"" + x +
                               "\" param: \"" + o +
                               "\" lang: " + lang);
            return "FIXME: " + x + ' ' + o;
        }
    }

    public static String getLanguage(I2PAppContext ctx) {
        String lang = ctx.getProperty(PROP_LANG);
        if (lang == null || lang.length() <= 0)
            lang = _localeLang;
        return lang;
    }

    /** cache both found and not found for speed */
    private static ResourceBundle findBundle(String lang) {
        ResourceBundle rv = _bundles.get(lang);
        if (rv == null && !_missing.contains(lang)) {
            try {
                // Would it be faster to specify a class loader?
                // No matter we only do this once per lang.
                rv = ResourceBundle.getBundle(BUNDLE_NAME, new Locale(lang));
                if (rv != null)
                    _bundles.put(lang, rv);
            } catch (MissingResourceException e) {
                _missing.add(lang);
            }
        }
        return rv;
    }
}
