package org.jenkinsci.plugins.googleplayandroidpublisher;

import com.google.api.client.http.FileContent;
import com.google.api.services.androidpublisher.AndroidPublisher;
import com.google.api.services.androidpublisher.model.Apk;
import com.google.api.services.androidpublisher.model.ApkListing;
import com.google.api.services.androidpublisher.model.Track;
import com.google.jenkins.plugins.credentials.oauth.GoogleRobotCredentials;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.AbstractDescribableImpl;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.Descriptor;
import hudson.model.Result;
import hudson.remoting.VirtualChannel;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Publisher;
import hudson.util.ComboBoxModel;
import hudson.util.FormValidation;
import net.dongliu.apk.parser.ApkParser;
import net.dongliu.apk.parser.bean.ApkMeta;
import net.sf.json.JSONObject;
import org.apache.commons.codec.digest.DigestUtils;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.export.Exported;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.Serializable;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

import static hudson.Util.fixEmptyAndTrim;
import static hudson.Util.join;
import static hudson.Util.tryParseNumber;
import static org.jenkinsci.plugins.googleplayandroidpublisher.ReleaseTrack.*;
import static org.jenkinsci.plugins.googleplayandroidpublisher.Util.REGEX_LANGUAGE;
import static org.jenkinsci.plugins.googleplayandroidpublisher.Util.REGEX_VARIABLE;
import static org.jenkinsci.plugins.googleplayandroidpublisher.Util.SUPPORTED_LANGUAGES;
import static org.jenkinsci.plugins.googleplayandroidpublisher.Util.expand;

/** Uploads Android application files to the Google Play Developer Console. */
public class ApkPublisher extends GooglePlayPublisher {

    /** Allowed percentage values when doing a staged rollout to production. */
    private static final double[] ROLLOUT_PERCENTAGES = { 0.5, 1, 5, 10, 20, 50, 100 };
    private static final double DEFAULT_PERCENTAGE = 100;
    private static final DecimalFormat PERCENTAGE_FORMATTER = new DecimalFormat("#.#");

    @DataBoundSetter
    private String apkFilesPattern;

    @DataBoundSetter
    private String trackName;

    @DataBoundSetter
    private String rolloutPercentage;

    @DataBoundSetter
    private RecentChanges[] recentChangeList;

    // TODO: Support expansion files

    @DataBoundConstructor
    public ApkPublisher() {}

    public String getApkFilesPattern() {
        return fixEmptyAndTrim(apkFilesPattern);
    }

    private String getExpandedApkFilesPattern(EnvVars env) {
        return expand(env, getApkFilesPattern());
    }

    public String getTrackName() {
        return fixEmptyAndTrim(trackName);
    }

    private String getCanonicalTrackName(EnvVars env) {
        String name = expand(env, getTrackName());
        if (name == null) {
            return null;
        }
        return name.toLowerCase(Locale.ENGLISH);
    }

    public String getRolloutPercentage() {
        return fixEmptyAndTrim(rolloutPercentage);
    }

    private double getRolloutPercentageValue(EnvVars env) {
        String pct = getRolloutPercentage();
        if (pct != null) {
            // Allow % characters in the config
            pct = pct.replace("%", "");
        }
        // If no valid numeric value was set, we will roll out to 100%
        return tryParseNumber(expand(env, pct), DEFAULT_PERCENTAGE).doubleValue();
    }

    public RecentChanges[] getRecentChangeList() {
        return recentChangeList;
    }

    private RecentChanges[] getExpandedRecentChangesList(EnvVars env) {
        if (recentChangeList == null) {
            return null;
        }
        RecentChanges[] expanded = new RecentChanges[recentChangeList.length];
        for (int i = 0; i < recentChangeList.length; i++) {
            RecentChanges r = recentChangeList[i];
            expanded[i] = new RecentChanges(expand(env, r.language), expand(env, r.text));
        }
        return expanded;
    }

    private boolean isConfigValid(PrintStream logger, EnvVars env) {
        final List<String> errors = new ArrayList<String>();

        // Check whether a file pattern was provided
        if (getExpandedApkFilesPattern(env) == null) {
            errors.add("Path or pattern to APK file was not specified");
        }

        // Track name is also required
        final String trackName = getCanonicalTrackName(env);
        final ReleaseTrack track = fromConfigValue(trackName);
        if (trackName == null) {
            errors.add("Release track was not specified");
        } else if (track == null) {
            errors.add(String.format("'%s' is not a valid release track", trackName));
        } else if (track == PRODUCTION) {
            // Check for valid rollout percentage
            double pct = getRolloutPercentageValue(env);
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
    public boolean perform(AbstractBuild build, Launcher launcher,
            BuildListener listener) throws IOException, InterruptedException {
        PrintStream logger = listener.getLogger();

        // Check whether we should execute at all
        final Result buildResult = build.getResult();
        if (buildResult != null && buildResult.isWorseThan(Result.UNSTABLE)) {
            logger.println("Skipping upload to Google Play due to build result");
            return true;
        }

        // Check that the job has been configured correctly
        final EnvVars env = build.getEnvironment(listener);
        if (!isConfigValid(logger, env)) {
            return false;
        }

        // Find the filename(s) which match the pattern after variable expansion
        final String filesPattern = getExpandedApkFilesPattern(env);
        final FilePath ws = build.getWorkspace();
        List<String> relativePaths = ws.act(new FindFilesTask(filesPattern));
        if (relativePaths.isEmpty()) {
            logger.println(String.format("No APK files matching the pattern '%s' could be found", filesPattern));
            return false;
        }

        // Get the full remote path in the workspace for each filename
        final List<FilePath> apkFiles = new ArrayList<FilePath>();
        final Set<String> applicationIds = new HashSet<String>();
        for (String path : relativePaths) {
            FilePath apk = ws.child(path);
            applicationIds.add(getApplicationId(apk));
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

        // Upload the APK(s) from the workspace
        try {
            GoogleRobotCredentials credentials = getServiceAccountCredentials();
            return build.getWorkspace()
                    .act(new UploadTask(listener, credentials, applicationIds.iterator().next(), apkFiles,
                            fromConfigValue(getCanonicalTrackName(env)), getRolloutPercentageValue(env),
                            getExpandedRecentChangesList(env)));
        } catch (UploadException e) {
            logger.println(String.format("Upload failed: %s", getPublisherErrorMessage(e)));
            logger.println("- No changes were applied to the Google Play account");
        }
        return false;
    }

    /** @return The application ID of the given APK file. */
    private static String getApplicationId(FilePath apk) throws IOException, InterruptedException {
        return apk.act(new FilePath.FileCallable<String>() {
            public String invoke(File f, VirtualChannel channel) throws IOException, InterruptedException {
                return getApkMetadata(f).getPackageName();
            }
        });
    }

    /** @return The application metadata of the given APK file. */
    private static ApkMeta getApkMetadata(File apk) throws IOException, InterruptedException {
        ApkParser apkParser = new ApkParser(apk);
        try {
            return apkParser.getApkMeta();
        } finally {
            apkParser.close();
        }
    }

    private static class UploadTask extends AbstractPublisherTask<Boolean> {

        private final String applicationId;
        private final List<FilePath> apkFiles;
        private final ReleaseTrack track;
        private final double rolloutFraction;
        private final RecentChanges[] recentChangeList;

        UploadTask(BuildListener listener, GoogleRobotCredentials credentials, String applicationId,
                List<FilePath> apkFiles, ReleaseTrack track, double rolloutPercentage,
                RecentChanges[] recentChangeList) {
            super(listener, credentials);
            this.applicationId = applicationId;
            this.apkFiles = apkFiles;
            this.track = track;
            this.rolloutFraction = rolloutPercentage / 100d;
            this.recentChangeList = recentChangeList;
        }

        protected Boolean execute() throws IOException, InterruptedException, UploadException {
            // Open an edit via the Google Play API, thereby ensuring that our credentials etc. are working
            createEdit(applicationId);

            // Upload each of the APKs
            logger.println(String.format("Uploading APK(s) with application ID: %s", applicationId));
            final List<Apk> existingApks = editService.apks().list(applicationId, editId).execute().getApks();
            final SortedSet<Integer> uploadedVersionCodes = new TreeSet<Integer>();
            for (FilePath apkFile : apkFiles) {
                final ApkMeta metadata = getApkMetadata(new File(apkFile.getRemote()));
                final String apkSha1Hash = getSha1Hash(apkFile.getRemote());

                // Log some useful information about the file that will be uploaded
                logger.println(String.format("      APK file: %s", apkFile.getName()));
                logger.println(String.format("    SHA-1 hash: %s", apkSha1Hash));
                logger.println(String.format("   versionCode: %d", metadata.getVersionCode()));
                logger.println(String.format(" minSdkVersion: %s", metadata.getMinSdkVersion()));
                logger.println();

                // Check whether this APK already exists on the server (i.e. uploading it would fail)
                for (Apk apk : existingApks) {
                    if (apk.getBinary().getSha1().toLowerCase(Locale.ENGLISH).equals(apkSha1Hash)) {
                        logger.println("This APK already exists on the server; it cannot be uploaded again");
                        return false;
                    }
                }

                // If not, we can upload the file
                FileContent apk =
                        new FileContent("application/vnd.android.package-archive", new File(apkFile.getRemote()));
                Apk uploadedApk = editService.apks().upload(applicationId, editId, apk).execute();
                uploadedVersionCodes.add(uploadedApk.getVersionCode());
            }

            // Prepare to assign the APK(s) to the desired track
            final Track trackToAssign = new Track();
            trackToAssign.setTrack(track.getApiValue());
            trackToAssign.setVersionCodes(new ArrayList<Integer>(uploadedVersionCodes));
            if (track == PRODUCTION) {
                // Remove older APKs from the beta track
                unassignOlderApks(BETA, uploadedVersionCodes.first());

                // If there's an existing rollout, we need to clear it out so a new production/rollout APK can be added
                final Track rolloutTrack = fetchTrack(ROLLOUT);
                if (rolloutTrack != null) {
                    logger.println(String.format("Removing existing staged rollout APK(s): %s",
                            join(rolloutTrack.getVersionCodes(), ", ")));
                    rolloutTrack.setVersionCodes(null);
                    editService.tracks().update(applicationId, editId, rolloutTrack.getTrack(), rolloutTrack).execute();
                }

                // Check whether we want a new staged rollout
                if (rolloutFraction < 1) {
                    // Override the track name
                    trackToAssign.setTrack(ROLLOUT.getApiValue());
                    trackToAssign.setUserFraction(rolloutFraction);

                    // Check whether we also need to override the desired rollout percentage
                    Double currentFraction = rolloutTrack == null ? rolloutFraction : rolloutTrack.getUserFraction();
                    if (currentFraction != null && currentFraction > rolloutFraction) {
                        logger.println(String.format("Staged rollout percentage will remain at %s%% rather than the " +
                                        "configured %s%% because there were APK(s) already in a staged rollout, and " +
                                        "Google Play makes it impossible to reduce the rollout percentage in this case",
                                PERCENTAGE_FORMATTER.format(currentFraction * 100),
                                PERCENTAGE_FORMATTER.format(rolloutFraction * 100)));
                        trackToAssign.setUserFraction(currentFraction);
                    }
                }
            } else if (rolloutFraction < 1) {
                logger.println("Ignoring staged rollout percentage as it only applies to production releases");
            }

            // Remove older APKs from the alpha track
            unassignOlderApks(ALPHA, uploadedVersionCodes.first());

            // Assign the new APK(s) to the desired track
            if (trackToAssign.getTrack().equals(ROLLOUT.getApiValue())) {
                logger.println(
                        String.format("Assigning uploaded APK(s) to be rolled out to %s%% of production users...",
                                PERCENTAGE_FORMATTER.format(trackToAssign.getUserFraction() * 100)));
            } else {
                logger.println(String.format("Assigning uploaded APK(s) to %s release track...", track));
            }
            Track updatedTrack = editService.tracks()
                    .update(applicationId, editId, trackToAssign.getTrack(), trackToAssign)
                    .execute();
            logger.println(String.format("The %s release track will now contain the APK(s): %s", track,
                    join(updatedTrack.getVersionCodes(), ", ")));

            // Apply recent changes text to the APK(s), if provided
            if (recentChangeList != null) {
                for (Integer versionCode : uploadedVersionCodes) {
                    AndroidPublisher.Edits.Apklistings listings = editService.apklistings();
                    for (RecentChanges changes : recentChangeList) {
                        ApkListing listing =
                                new ApkListing().setLanguage(changes.language).setRecentChanges(changes.text);
                        listings.update(applicationId, editId, versionCode, changes.language, listing).execute();
                    }
                }
            }

            // Commit all the changes
            editService.commit(applicationId, editId).execute();
            logger.println("Changes were successfully applied to Google Play");

            return true;
        }

        /** @return The SHA-1 hash of the given file, as a lower-case hex string. */
        private static String getSha1Hash(String path) throws IOException {
            return DigestUtils.shaHex(new FileInputStream(path)).toLowerCase(Locale.ENGLISH);
        }

        /** @return The desired track fetched from the API, or {@code null} if the track has no APKs assigned. */
        private Track fetchTrack(ReleaseTrack track) throws IOException {
            final List<Track> existingTracks = editService.tracks().list(applicationId, editId).execute().getTracks();
            for (Track t : existingTracks) {
                if (t.getTrack().equals(track.getApiValue())) {
                    return t;
                }
            }
            return null;
        }

        /**
         * Removes old version codes from the given track on the server, if it exists.
         *
         * @param track The track whose assigned versions should be changed.
         * @param maxVersionCode The maximum allowed version code; all lower than this will be removed from the track.
         */
        private void unassignOlderApks(ReleaseTrack track, int maxVersionCode) throws IOException {
            final Track trackToAssign = fetchTrack(track);
            if (trackToAssign == null || trackToAssign.getVersionCodes() == null) {
                return;
            }

            List<Integer> versionCodes = new ArrayList<Integer>(trackToAssign.getVersionCodes());
            for (Iterator<Integer> it = versionCodes.iterator(); it.hasNext(); ) {
                if (it.next() < maxVersionCode) {
                    it.remove();
                }
            }
            trackToAssign.setVersionCodes(versionCodes);
            editService.tracks().update(applicationId, editId, trackToAssign.getTrack(), trackToAssign).execute();
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

            // TODO: Additional validation that no duplicate languages have been entered could be nice
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

    @Extension
    public static final class DescriptorImpl extends BuildStepDescriptor<Publisher> {

        public DescriptorImpl() {
            load();
        }

        public String getDisplayName() {
            return "Upload Android APK to Google Play";
        }

        public FormValidation doCheckApkFiles(@QueryParameter String value) {
            if (fixEmptyAndTrim(value) == null) {
                return FormValidation.error("An APK file path or pattern is required");
            }
            return FormValidation.ok();
        }

        public FormValidation doCheckTrackName(@QueryParameter String value) {
            if (fixEmptyAndTrim(value) == null) {
                return FormValidation.error("A release track is required");
            }
            return FormValidation.ok();
        }

        public FormValidation doCheckRolloutPercentage(@QueryParameter String value) {
            value = fixEmptyAndTrim(value);
            if (value == null || value.matches(REGEX_VARIABLE)) {
                return FormValidation.ok();
            }

            final double lowest = ROLLOUT_PERCENTAGES[0];
            final double highest = DEFAULT_PERCENTAGE;
            double pct = tryParseNumber(value.replace("%", ""), highest).doubleValue();
            if (Double.compare(pct, 0.5) < 0 || Double.compare(pct, DEFAULT_PERCENTAGE) > 0) {
                return FormValidation.error("Percentage value must be between %s and %s%%",
                        PERCENTAGE_FORMATTER.format(lowest), PERCENTAGE_FORMATTER.format(highest));
            }
            return FormValidation.ok();
        }

        public ComboBoxModel doFillTrackNameItems() {
            return new ComboBoxModel(getConfigValues());
        }

        public ComboBoxModel doFillRolloutPercentageItems() {
            ComboBoxModel list = new ComboBoxModel();
            for (double pct : ROLLOUT_PERCENTAGES) {
                list.add(String.format("%s%%", PERCENTAGE_FORMATTER.format(pct)));
            }
            return list;
        }

        public boolean isApplicable(Class<? extends AbstractProject> c) {
            return true;
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject formData) throws FormException {
            save();
            return super.configure(req, formData);
        }

    }

}
