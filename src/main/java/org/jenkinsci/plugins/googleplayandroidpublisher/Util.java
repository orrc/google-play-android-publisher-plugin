package org.jenkinsci.plugins.googleplayandroidpublisher;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.services.androidpublisher.AndroidPublisher;
import com.google.common.base.Throwables;
import com.google.jenkins.plugins.credentials.oauth.GoogleRobotCredentials;
import hudson.FilePath;
import hudson.model.AbstractBuild;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.remoting.VirtualChannel;
import jenkins.MasterToSlaveFileCallable;
import jenkins.model.Jenkins;
import net.dongliu.apk.parser.ApkParser;
import net.dongliu.apk.parser.bean.ApkMeta;
import org.jenkinsci.plugins.tokenmacro.MacroEvaluationException;
import org.jenkinsci.plugins.tokenmacro.TokenMacro;

import java.io.File;
import java.io.IOException;
import java.security.GeneralSecurityException;

import static hudson.Util.fixEmptyAndTrim;
import static hudson.Util.replaceMacro;

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

    /** @return The application ID of the given APK file. */
    public static String getApplicationId(FilePath apk) throws IOException, InterruptedException {
        return apk.act(new MasterToSlaveFileCallable<String>() {
            public String invoke(File f, VirtualChannel channel) throws IOException, InterruptedException {
                return getApkMetadata(f).getPackageName();
            }
        });
    }

    /** @return The version code of the given APK file. */
    public static int getVersionCode(FilePath apk) throws IOException, InterruptedException {
        return apk.act(new MasterToSlaveFileCallable<Integer>() {
            public Integer invoke(File f, VirtualChannel channel) throws IOException, InterruptedException {
                return getApkMetadata(f).getVersionCode().intValue();
            }
        });
    }

    /** @return The application metadata of the given APK file. */
    public static ApkMeta getApkMetadata(File apk) throws IOException, InterruptedException {
        ApkParser apkParser = new ApkParser(apk);
        try {
            return apkParser.getApkMeta();
        } finally {
            apkParser.close();
        }
    }

    /** @return The given value with variables expanded and trimmed; {@code null} if that results in an empty string. */
    static String expand(Run<?, ?> run, TaskListener listener, String value)
            throws InterruptedException, IOException {
        // If this is a pipeline run, there's no need to expand tokens
        if (!(run instanceof AbstractBuild)) {
            return value;
        }

        try {
            final AbstractBuild build = (AbstractBuild) run;
            return fixEmptyAndTrim(TokenMacro.expandAll(build, listener, value));
        } catch (MacroEvaluationException e) {
            listener.getLogger().println(e.getMessage());
            return value;
        }
    }

    /** @return A user-friendly(ish) Google Play API error message, if one could be found in the given exception. */
    static String getPublisherErrorMessage(UploadException e) {
        if (e instanceof CredentialsException) {
            return e.getMessage();
        }
        if (e instanceof PublisherApiException) {
            // TODO: Here we could map error reasons like "apkUpgradeVersionConflict" to better (and localised) text
            String message = ((PublisherApiException) e).getDetailsMessage();
            if (message != null && message.trim().length() > 0) {
                return message;
            }
        }

        // Otherwise print the whole stacktrace, as it's something unrelated to this plugin
        return Throwables.getStackTraceAsString(e);
    }

    /**
     * @return An Android Publisher client, using the configured credentials.
     * @throws GeneralSecurityException If reading the service account credentials failed.
     */
    static AndroidPublisher getPublisherClient(GoogleRobotCredentials credentials,
            String pluginVersion) throws GeneralSecurityException {
        final Credential credential = credentials.getGoogleCredential(new AndroidPublisherScopeRequirement());
        return new AndroidPublisher.Builder(credential.getTransport(), credential.getJsonFactory(), credential)
                .setApplicationName(getClientUserAgent(pluginVersion))
                .build();
    }

    /** @return The Google API "application name" that the plugin should identify as when sending requests. */
    private static String getClientUserAgent(String pluginVersion) {
        return String.format("Jenkins-GooglePlayAndroidPublisher/%s", pluginVersion);
    }

}
