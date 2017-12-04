package org.jenkinsci.plugins.googleplayandroidpublisher;

import com.google.api.services.androidpublisher.model.Apk;
import com.google.jenkins.plugins.credentials.oauth.GoogleRobotCredentials;
import hudson.model.BuildListener;
import hudson.model.TaskListener;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.TreeSet;

import static hudson.Util.join;

class TrackAssignmentTask extends TrackPublisherTask<Boolean> {

    private final Collection<Integer> versionCodes;

    TrackAssignmentTask(TaskListener listener, GoogleRobotCredentials credentials, String applicationId,
                        Collection<Integer> versionCodes, ReleaseTrack track, double rolloutPercentage) {
        super(listener, credentials, applicationId, track, rolloutPercentage);
        this.versionCodes = versionCodes;
    }

    @Override
    protected int getNewestVersionCodeAllowed(Collection<Integer> versionCodes) {
        // Sort the version codes, so we know which is the lowest
        final TreeSet<Integer> sortedVersionCodes = new TreeSet<Integer>(versionCodes);

        // Ensure all APKs including the ones we're changing are cleared out from other tracks
        return sortedVersionCodes.last() + 1;
    }

    @Override
    protected boolean shouldReducingRolloutPercentageCauseFailure() {
        return true;
    }

    protected Boolean execute() throws IOException, InterruptedException, UploadException {
        // Open an edit via the Google Play API, thereby ensuring that our credentials etc. are working
        logger.println(String.format("Authenticating to Google Play API...\n- Credential:     %s\n- Application ID: %s",
                getCredentialName(), applicationId));
        createEdit(applicationId);

        // Log some useful information
        logger.println(String.format("Assigning %d APK(s) with application ID %s to %s release track",
                versionCodes.size(), applicationId, track));

        // Check that all version codes to assign actually exist already on the server
        ArrayList<Integer> missingVersionCodes = new ArrayList<Integer>(versionCodes);
        final List<Apk> existingApks = editService.apks().list(applicationId, editId).execute().getApks();
        for (Apk apk : existingApks) {
            missingVersionCodes.remove(apk.getVersionCode());
        }
        if (!missingVersionCodes.isEmpty()) {
            logger.println(String.format("Could not assign APK(s) %s to %s, as these APKs do not exist: %s",
                    join(versionCodes, ", "), track, join(missingVersionCodes, ", ")));
            return false;
        }

        // TODO: We could be nice and detect in advance if a user attempts to downgrade

        // Move the version codes to the configured track
        assignApksToTrack(versionCodes, track, rolloutFraction);

        // Commit the changes
        try {
            logger.println("Applying changes to Google Play...");
            editService.commit(applicationId, editId).execute();
        } catch (SocketTimeoutException e) {
            // TODO: Check, in a new session, whether the given version codes are now in the desired track
            logger.println(String.format("- An error occurred while applying changes: %s", e));
            return false;
        }

        // If committing didn't throw an exception, everything worked fine
        logger.println("Changes were successfully applied to Google Play");
        return true;
    }

}
