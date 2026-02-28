package ps.reso.instaeclipse.utils.i18n;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;

import androidx.annotation.StringRes;

import java.util.Locale;

import ps.reso.instaeclipse.utils.core.CommonUtils;

public final class I18n {

    private I18n() {
    }

    private static Context getModuleContext(Context hostContext) {
        try {
            return hostContext.createPackageContext(CommonUtils.MY_PACKAGE_NAME, Context.CONTEXT_IGNORE_SECURITY);
        } catch (Exception ignored) {
            return hostContext;
        }
    }

    private static Locale resolvePreferredLocale() {
        Locale systemLocale = Resources.getSystem().getConfiguration().getLocales().get(0);
        if (systemLocale == null) {
            return Locale.ENGLISH;
        }

        if ("zh".equalsIgnoreCase(systemLocale.getLanguage())) {
            String country = systemLocale.getCountry();
            if ("TW".equalsIgnoreCase(country) || "HK".equalsIgnoreCase(country) || "MO".equalsIgnoreCase(country)) {
                return Locale.forLanguageTag("zh-TW");
            }
            return Locale.SIMPLIFIED_CHINESE;
        }

        return Locale.ENGLISH;
    }

    private static Context getLocalizedModuleContext(Context hostContext) {
        Context moduleContext = getModuleContext(hostContext);
        Resources moduleResources = moduleContext.getResources();
        Configuration localizedConfig = new Configuration(moduleResources.getConfiguration());
        localizedConfig.setLocale(resolvePreferredLocale());
        return moduleContext.createConfigurationContext(localizedConfig);
    }

    public static String t(Context hostContext, @StringRes int resId, Object... args) {
        try {
            Context localizedContext = getLocalizedModuleContext(hostContext);
            if (args == null || args.length == 0) {
                return localizedContext.getString(resId);
            }
            return localizedContext.getString(resId, args);
        } catch (Exception ignored) {
            try {
                if (args == null || args.length == 0) {
                    return hostContext.getString(resId);
                }
                return hostContext.getString(resId, args);
            } catch (Exception ignoredAgain) {
                return "";
            }
        }
    }
}
