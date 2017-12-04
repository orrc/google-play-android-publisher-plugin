package org.jenkinsci.plugins.googleplayandroidpublisher;

import com.google.jenkins.plugins.credentials.oauth.GoogleRobotCredentials;
import hudson.AbortException;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.Publisher;
import hudson.util.ComboBoxModel;
import hudson.util.FormValidation;
import net.dongliu.apk.parser.exception.ParserException;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.export.Exported;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.io.PrintStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.zip.ZipException;

import static hudson.Util.fixEmptyAndTrim;
import static hudson.Util.join;
import static hudson.Util.tryParseNumber;
import static org.jenkinsci.plugins.googleplayandroidpublisher.Constants.DEFAULT_PERCENTAGE;
import static org.jenkinsci.plugins.googleplayandroidpublisher.Constants.OBB_FILE_REGEX;
import static org.jenkinsci.plugins.googleplayandroidpublisher.Constants.OBB_FILE_TYPE_MAIN;
import static org.jenkinsci.plugins.googleplayandroidpublisher.Constants.PERCENTAGE_FORMATTER;
import static org.jenkinsci.plugins.googleplayandroidpublisher.Constants.ROLLOUT_PERCENTAGES;
import static org.jenkinsci.plugins.googleplayandroidpublisher.ReleaseTrack.PRODUCTION;
import static org.jenkinsci.plugins.googleplayandroidpublisher.ReleaseTrack.fromConfigValue;
import static org.jenkinsci.plugins.googleplayandroidpublisher.Util.REGEX_LANGUAGE;
import static org.jenkinsci.plugins.googleplayandroidpublisher.Util.REGEX_VARIABLE;
import static org.jenkinsci.plugins.googleplayandroidpublisher.Util.SUPPORTED_LANGUAGES;
import static org.jenkinsci.plugins.googleplayandroidpublisher.Util.getApplicationId;
import static org.jenkinsci.plugins.googleplayandroidpublisher.Util.getPublisherErrorMessage;
import static org.jenkinsci.plugins.googleplayandroidpublisher.Util.getVersionCode;

/** Uploads Android application files to the Google Play Developer Console. */
public class ApkPublisher extends GooglePlayPublisher {

    @DataBoundSetter
    private String apkFilesPattern;

    @DataBoundSetter
    private String expansionFilesPattern;

    @DataBoundSetter
    private boolean usePreviousExpansionFilesIfMissing;

    @DataBoundSetter
    private String trackName;

    @DataBoundSetter
    private String rolloutPercentage;

    @DataBoundSetter
    private RecentChanges[] recentChangeList;

    @DataBoundConstructor
    public ApkPublisher() {}

    public String getApkFilesPattern() {
        return fixEmptyAndTrim(apkFilesPattern);
    }

    private String getExpandedApkFilesPattern() throws IOException, InterruptedException {
        return expand(getApkFilesPattern());
    }

    public String getExpansionFilesPattern() {
        return fixEmptyAndTrim(expansionFilesPattern);
    }

    private String getExpandedExpansionFilesPattern() throws IOException, InterruptedException {
        return expand(getExpansionFilesPattern());
    }

    public boolean getUsePreviousExpansionFilesIfMissing() {
        return usePreviousExpansionFilesIfMissing;
    }

    public String getTrackName() {
        return fixEmptyAndTrim(trackName);
    }

    private String getCanonicalTrackName() throws IOException, InterruptedException {
        String name = expand(getTrackName());
        if (name == null) {
            return null;
        }
        return name.toLowerCase(Locale.ENGLISH);
    }

    public String getRolloutPercentage() {
        return fixEmptyAndTrim(rolloutPercentage);
    }

    private double getRolloutPercentageValue() throws IOException, InterruptedException {
        String pct = getRolloutPercentage();
        if (pct != null) {
            // Allow % characters in the config
            pct = pct.replace("%", "");
        }
        // If no valid numeric value was set, we will roll out to 100%
        return tryParseNumber(expand(pct), DEFAULT_PERCENTAGE).doubleValue();
    }

    public RecentChanges[] getRecentChangeList() {
        return recentChangeList;
    }

    private RecentChanges[] getExpandedRecentChangesList() throws IOException, InterruptedException {
        if (recentChangeList == null) {
            return null;
        }
        RecentChanges[] expanded = new RecentChanges[recentChangeList.length];
        for (int i = 0; i < recentChangeList.length; i++) {
            RecentChanges r = recentChangeList[i];
            expanded[i] = new RecentChanges(expand(r.language), expand(r.text));
        }
        return expanded;
    }

    private boolean isConfigValid(PrintStream logger) throws IOException, InterruptedException {
        final List<String> errors = new ArrayList<String>();

        // Check whether a file pattern was provided
        if (getExpandedApkFilesPattern() == null) {
            errors.add("Path or pattern to APK file was not specified");
        }

        // Track name is also required
        final String trackName = getCanonicalTrackName();
        final ReleaseTrack track = fromConfigValue(trackName);
        if (trackName == null) {
            errors.add("Release track was not specified");
        } else if (track == null) {
            errors.add(String.format("'%s' is not a valid release track", trackName));
        } else if (track == PRODUCTION) {
            // Check for valid rollout percentage
            double pct = getRolloutPercentageValue();
            if (Arrays.binarySearch(ROLLOUT_PERCENTAGES, pct) < 0) {
                errors.add(String.format("%s%% is not a valid rollout percentage", PERCENTAGE_FORMATTER.format(pct)));
            }
        }

        // Print accumulated errors
        if (!errors.isEmpty()) {
            logger.println("Cannot upload to Google Play:");
            for (String error : errors) {
                logger.print("- ");
                logger.println(error);
            }
        }

        return errors.isEmpty();
    }

    @Override
    public void perform(@Nonnull Run<?, ?> run, @Nonnull FilePath workspace, @Nonnull Launcher launcher,
                        @Nonnull TaskListener listener) throws InterruptedException, IOException {
        super.perform(run, workspace, launcher, listener);

        // Calling publishApk logs the reason when a failure occurs, so in that case we just need to throw here
        if (!publishApk(run, workspace, listener)) {
            throw new AbortException("APK upload failed");
        }
    }

    private boolean publishApk(@Nonnull Run<?, ?> run, @Nonnull FilePath workspace, @Nonnull TaskListener listener)
            throws InterruptedException, IOException {
        final PrintStream logger = listener.getLogger();

        // Check whether we should execute at all
        final Result buildResult = run.getResult();
        if (buildResult != null && buildResult.isWorseThan(Result.UNSTABLE)) {
            logger.println("Skipping upload to Google Play due to build result");
            return true;
        }

        // Check that the job has been configured correctly
        if (!isConfigValid(logger)) {
            return false;
        }

        // Find the APK filename(s) which match the pattern after variable expansion
        final String filesPattern = getExpandedApkFilesPattern();
        List<String> relativePaths = workspace.act(new FindFilesTask(filesPattern));
        if (relativePaths.isEmpty()) {
            logger.println(String.format("No APK files matching the pattern '%s' could be found", filesPattern));
            return false;
        }

        // Get the full remote path in the workspace for each filename
        final List<FilePath> apkFiles = new ArrayList<FilePath>();
        final Set<String> applicationIds = new HashSet<String>();
        final Set<Integer> versionCodes = new TreeSet<Integer>();
        for (String path : relativePaths) {
            FilePath apk = workspace.child(path);
            try {
                applicationIds.add(getApplicationId(apk));
                versionCodes.add(getVersionCode(apk));
            } catch (ZipException e) {
                // If the file is empty or not a zip file, we don't need to dump the whole stacktrace
                logger.println(String.format("File does not appear to be a valid APK: %s", apk.getRemote()));
                return false;
            } catch (ParserException e) {
                // Show a bit more information for APK parse exceptions
                logger.println(String.format("File does not appear to be a valid APK: %s\n- %s",
                        apk.getRemote(), e.getMessage()));
                return false;
            } catch (IOException e) {
                // Otherwise, it's something more esoteric, so rethrow, dumping the stacktrace to the log
                logger.println(String.format("File does not appear to be a valid APK: %s", apk.getRemote()));
                throw e;
            }
            apkFiles.add(apk);
        }

        // If there are multiple APKs, ensure that all have the same application ID
        if (applicationIds.size() != 1) {
            logger.println("Multiple APKs were found but they have inconsistent application IDs:");
            for (String id : applicationIds) {
                logger.print("- ");
                logger.println(id);
            }
            return false;
        }

        final String applicationId = applicationIds.iterator().next();

        // Find the expansion filename(s) which match the pattern after variable expansion
        final Map<Integer, ExpansionFileSet> expansionFiles = new TreeMap<Integer, ExpansionFileSet>();
        final String expansionPattern = getExpandedExpansionFilesPattern();
        if (expansionPattern != null) {
            List<String> expansionPaths = workspace.act(new FindFilesTask(expansionPattern));

            // Check that the expansion files found apply to the APKs to be uploaded
            for (String path : expansionPaths) {
                FilePath file = workspace.child(path);

                // Check that the filename is in the right format
                Matcher matcher = OBB_FILE_REGEX.matcher(file.getName());
                if (!matcher.matches()) {
                    logger.println(String.format("Expansion file '%s' doesn't match the required naming scheme", path));
                    return false;
                }

                // We can only associate expansion files with the application ID we're going to upload
                final String appId = matcher.group(3);
                if (!applicationId.equals(appId)) {
                    logger.println(String.format("Expansion filename '%s' doesn't match the application ID to be "
                            + "uploaded: %s", path, applicationId));
                    return false;
                }

                // We can only associate expansion files with version codes we're going to upload
                final int versionCode = Integer.parseInt(matcher.group(2));
                if (!versionCodes.contains(versionCode)) {
                    logger.println(String.format("Expansion filename '%s' doesn't match the versionCode of any of "
                            + "APK(s) to be uploaded: %s", path, join(versionCodes, ", ")));
                    return false;
                }

                // File looks good, so add it to the fileset for this version code
                final String type = matcher.group(1).toLowerCase(Locale.ENGLISH);
                ExpansionFileSet fileSet = expansionFiles.get(versionCode);
                if (fileSet == null) {
                    fileSet = new ExpansionFileSet();
                    expansionFiles.put(versionCode, fileSet);
                }
                if (type.equals(OBB_FILE_TYPE_MAIN)) {
                    fileSet.setMainFile(file);
                } else {
                    fileSet.setPatchFile(file);
                }
            }

            // If there are patch files, make sure that each has a main file, or "use previous if missing" is enabled
            for (ExpansionFileSet fileSet : expansionFiles.values()) {
                if (!usePreviousExpansionFilesIfMissing && fileSet.getPatchFile() != null
                        && fileSet.getMainFile() == null) {
                    logger.println(String.format("Patch expansion file '%s' was provided, but no main expansion file " +
                            "was provided, and the option to reuse a pre-existing expansion file was " +
                            "disabled.\nGoogle Play requires that each APK with a patch file also has a main " +
                            "file.", fileSet.getPatchFile().getName()));
                    return false;
                }
            }
        }

        // Upload the file(s) from the workspace
        try {
            GoogleRobotCredentials credentials = getCredentialsHandler().getServiceAccountCredentials();
            return workspace.act(new ApkUploadTask(listener, credentials, applicationId, workspace, apkFiles,
                    expansionFiles, usePreviousExpansionFilesIfMissing, fromConfigValue(getCanonicalTrackName()),
                    getRolloutPercentageValue(), getExpandedRecentChangesList()));
        } catch (UploadException e) {
            logger.println(String.format("Upload failed: %s", getPublisherErrorMessage(e)));
            logger.println("- No changes have been applied to the Google Play account");
        }
        return false;
    }

    static final class ExpansionFileSet implements Serializable {

        private static final long serialVersionUID = 1;

        FilePath mainFile;
        FilePath patchFile;

        public FilePath getMainFile() {
            return mainFile;
        }

        public void setMainFile(FilePath mainFile) {
            this.mainFile = mainFile;
        }

        public FilePath getPatchFile() {
            return patchFile;
        }

        public void setPatchFile(FilePath patchFile) {
            this.patchFile = patchFile;
        }

    }

    public static final class RecentChanges extends AbstractDescribableImpl<RecentChanges> implements Serializable {

        private static final long serialVersionUID = 1;

        @Exported
        public final String language;

        @Exported
        public final String text;

        @DataBoundConstructor
        public RecentChanges(String language, String text) {
            this.language = language;
            this.text = text;
        }

        @Extension
        public static class DescriptorImpl extends Descriptor<RecentChanges> {

            @Override
            public String getDisplayName() {
                return null;
            }

            public ComboBoxModel doFillLanguageItems() {
                return new ComboBoxModel(SUPPORTED_LANGUAGES);
            }

            public FormValidation doCheckLanguage(@QueryParameter String value) {
                value = fixEmptyAndTrim(value);
                if (value != null && !value.matches(REGEX_LANGUAGE) && !value.matches(REGEX_VARIABLE)) {
                    return FormValidation.warning("Should be a language code like 'be' or 'en-GB'");
                }
                return FormValidation.ok();
            }

            public FormValidation doCheckText(@QueryParameter String value) {
                value = fixEmptyAndTrim(value);
                if (value != null && value.length() > 500) {
                    return FormValidation.error("Recent changes text must be 500 characters or fewer");
                }
                return FormValidation.ok();
            }

        }

    }

    @Symbol("androidApkUpload")
    @Extension
    public static final class DescriptorImpl extends GooglePlayBuildStepDescriptor<Publisher> {

        public String getDisplayName() {
            return "Upload Android APK to Google Play";
        }

    }

}
