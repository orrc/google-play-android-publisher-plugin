package org.jenkinsci.plugins.googleplayandroidpublisher;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.services.androidpublisher.AndroidPublisher;
import com.google.jenkins.plugins.credentials.oauth.GoogleRobotCredentials;
import hudson.EnvVars;
import jenkins.model.Jenkins;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.security.GeneralSecurityException;

import static hudson.Util.fixEmptyAndTrim;

public class Util {

    /** Regex for the BCP 47 language codes used by Google Play. */
    static final String REGEX_LANGUAGE = "[a-z]{2,3}([-_][0-9A-Z]{2,})?";

    // From hudson.Util.VARIABLE
    static final String REGEX_VARIABLE = "\\$([A-Za-z0-9_]+|\\{[A-Za-z0-9_]+\\}|\\$)";

    /** A (potentially non-exhaustive) list of languages supported by Google Play for app description text etc.. */
    static final String[] SUPPORTED_LANGUAGES =
            { "af", "am", "ar", "be", "bg", "ca", "cs-CZ", "da-DK", "de-DE", "el-GR", "en-GB", "en-US", "es-419",
                    "es-ES", "es-US", "et", "fa", "fi-FI", "fil", "fr-CA", "fr-FR", "hi-IN", "hr", "hu-HU", "id",
                    "it-IT", "iw-IL", "ja-JP", "ko-KR", "lt", "lv", "ms", "nl-NL", "no-NO", "pl-PL", "pt-BR", "pt-PT",
                    "rm", "ro", "ru-RU", "sk", "sl", "sr", "sv-SE", "sw", "th", "tr-TR", "uk", "vi", "zh-CN", "zh-TW",
                    "zu" };

    /** @return The version of this Jenkins plugin, e.g. "1.0" or "1.1-SNAPSHOT" (for dev releases). */
    public static String getPluginVersion() {
        final String version = Jenkins.getInstance().getPluginManager().whichPlugin(Util.class).getVersion();
        int index = version.indexOf(' ');
        return (index == -1) ? version : version.substring(0, index);
    }

    /** @return The given value with variables expanded and trimmed; {@code null} if that results in an empty string. */
    static String expand(EnvVars env, String value) {
        return fixEmptyAndTrim(env.expand(value));
    }

    /**
     * @return An Android Publisher client, using the configured credentials.
     * @throws GeneralSecurityException If reading the service account credentials failed.
     */
    static AndroidPublisher getPublisherClient(GoogleRobotCredentials credentials,
            String pluginVersion) throws GeneralSecurityException {
        final Credential credential = credentials.getGoogleCredential(new AndroidPublisherScopeRequirement());

        HttpTransport credentialgetTransport = credential.getTransport();
        NetHttpTransport.Builder builder = new NetHttpTransport.Builder();
        builder.doNotValidateCertificate();
        builder.setProxy(new Proxy(Proxy.Type.SOCKS, new InetSocketAddress("127.0.0.1", 8889)));
        credentialgetTransport = builder.build();

        return new AndroidPublisher.Builder(credentialgetTransport, credential.getJsonFactory(), credential)
                .setApplicationName(getClientUserAgent(pluginVersion))
                .build();
    }

    /** @return The Google API "application name" that the plugin should identify as when sending requests. */
    private static String getClientUserAgent(String pluginVersion) {
        return String.format("Jenkins-GooglePlayAndroidPublisher/%s", pluginVersion);
    }

}
