package org.jenkinsci.plugins.googleplayandroidpublisher;

import com.google.jenkins.plugins.credentials.oauth.GoogleRobotCredentials;
import hudson.AbortException;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.Builder;
import net.dongliu.apk.parser.exception.ParserException;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipException;

import static hudson.Util.fixEmptyAndTrim;
import static hudson.Util.tryParseNumber;
import static org.jenkinsci.plugins.googleplayandroidpublisher.Constants.DEFAULT_PERCENTAGE;
import static org.jenkinsci.plugins.googleplayandroidpublisher.Constants.PERCENTAGE_FORMATTER;
import static org.jenkinsci.plugins.googleplayandroidpublisher.Constants.ROLLOUT_PERCENTAGES;
import static org.jenkinsci.plugins.googleplayandroidpublisher.ReleaseTrack.PRODUCTION;
import static org.jenkinsci.plugins.googleplayandroidpublisher.ReleaseTrack.fromConfigValue;
import static org.jenkinsci.plugins.googleplayandroidpublisher.Util.getPublisherErrorMessage;
import static org.jenkinsci.plugins.googleplayandroidpublisher.Util.getVersionCode;

public class ReleaseTrackAssignmentBuilder extends GooglePlayBuilder {

    @DataBoundSetter
    private Boolean fromVersionCode;

    @DataBoundSetter
    private String applicationId;

    @DataBoundSetter
    private String versionCodes;

    @DataBoundSetter
    private String apkFilesPattern;

    @DataBoundSetter
    private String trackName;

    @DataBoundSetter
    private String rolloutPercentage;

    @DataBoundConstructor
    public ReleaseTrackAssignmentBuilder() {}

    public boolean isFromVersionCode() {
        return fromVersionCode == null || fromVersionCode;
    }

    public String getApplicationId() {
        return applicationId;
    }

    private String getExpandedApplicationId() throws IOException, InterruptedException {
        return expand(getApplicationId());
    }

    public String getVersionCodes() {
        return versionCodes;
    }

    private String getExpandedVersionCodes() throws IOException, InterruptedException {
        return expand(getVersionCodes());
    }

    public String getApkFilesPattern() {
        return fixEmptyAndTrim(apkFilesPattern);
    }

    private String getExpandedApkFilesPattern() throws IOException, InterruptedException {
        return expand(getApkFilesPattern());
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

    private boolean isConfigValid(PrintStream logger) throws IOException, InterruptedException {
        final List<String> errors = new ArrayList<String>();

        // Check whether the relevant values were provided, based on the method chosen
        if (isFromVersionCode()) {
            if (getExpandedApplicationId() == null) {
                errors.add("No application ID was specified");
            }
            if (getExpandedVersionCodes() == null) {
                errors.add("No version codes were specified");
            }
        } else if (getExpandedApkFilesPattern() == null) {
            errors.add("Path or pattern to APK file(s) was not specified");
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
            logger.println("Cannot make changes to Google Play:");
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

        // Calling assignApk logs the reason when a failure occurs, so in that case we just need to throw here
        if (!assignApk(run, workspace, listener)) {
            throw new AbortException("APK assignment failed");
        }
    }

    private boolean assignApk(@Nonnull Run<?, ?> run, @Nonnull FilePath workspace, @Nonnull TaskListener listener)
            throws IOException, InterruptedException {
        final PrintStream logger = listener.getLogger();

        // Check that the job has been configured correctly
        if (!isConfigValid(logger)) {
            return false;
        }

        String applicationId;
        Collection<Integer> versionCodeList = new TreeSet<Integer>();
        if (isFromVersionCode()) {
            applicationId = getExpandedApplicationId();
            String codes = getExpandedVersionCodes();
            for (String s : codes.split("[,\\s]+")) {
                int versionCode = tryParseNumber(s.trim(), -1).intValue();
                if (versionCode != -1) {
                    versionCodeList.add(versionCode);
                }
            }
        } else {
            AppInfo info = getApplicationInfoForApks(workspace, logger, getExpandedApkFilesPattern());
            if (info == null) {
                return false;
            }
            applicationId = info.applicationId;
            versionCodeList.addAll(info.versionCodes);
        }

        // Assign the APKs to the desired track
        try {
            GoogleRobotCredentials credentials = getCredentialsHandler().getServiceAccountCredentials();
            return workspace.act(new TrackAssignmentTask(listener, credentials, applicationId, versionCodeList,
                            fromConfigValue(getCanonicalTrackName()), getRolloutPercentageValue()));
        } catch (UploadException e) {
            logger.println(String.format("Upload failed: %s", getPublisherErrorMessage(e)));
            logger.println("- No changes have been applied to the Google Play account");
        }
        return false;
    }

    private AppInfo getApplicationInfoForApks(FilePath workspace, PrintStream logger, String apkFilesPattern)
            throws IOException, InterruptedException {
        // Find the APK filename(s) which match the pattern after variable expansion
        List<String> relativePaths = workspace.act(new FindFilesTask(apkFilesPattern));
        if (relativePaths.isEmpty()) {
            logger.println(String.format("No APK files matching the pattern '%s' could be found", apkFilesPattern));
            return null;
        }

        // Read the metadata from each APK file
        final Set<String> applicationIds = new HashSet<String>();
        final List<Integer> versionCodes = new ArrayList<Integer>();
        for (String path : relativePaths) {
            FilePath apk = workspace.child(path);
            final int versionCode;
            try {
                applicationIds.add(Util.getApplicationId(apk));
                versionCode = getVersionCode(apk);
                versionCodes.add(versionCode);
            } catch (ZipException e) {
                throw new IOException(String.format("File does not appear to be a valid APK: %s", apk.getRemote()), e);
            } catch (ParserException e) {
                logger.println(String.format("File does not appear to be a valid APK: %s\n- %s",
                        apk.getRemote(), e.getMessage()));
                throw e;
            }
            logger.println(String.format("Found APK file with version code %d: %s", versionCode, path));
        }

        // If there are multiple APKs, ensure that all have the same application ID
        if (applicationIds.size() != 1) {
            logger.println("Multiple APKs were found but they have inconsistent application IDs:");
            for (String id : applicationIds) {
                logger.print("- ");
                logger.println(id);
            }
            return null;
        }

        return new AppInfo(applicationIds.iterator().next(), versionCodes);
    }

    private static final class AppInfo {
        final String applicationId;
        final List<Integer> versionCodes;

        AppInfo(String applicationId, List<Integer> versionCodes) {
            this.applicationId = applicationId;
            this.versionCodes = versionCodes;
        }
    }

    @Symbol("androidApkMove")
    @Extension
    public static final class DescriptorImpl extends GooglePlayBuildStepDescriptor<Builder> {

        public String getDisplayName() {
            return "Move Android APKs to a different release track";
        }

    }

}
