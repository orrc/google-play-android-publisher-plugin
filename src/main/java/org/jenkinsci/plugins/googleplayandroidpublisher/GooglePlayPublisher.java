package org.jenkinsci.plugins.googleplayandroidpublisher;

import com.google.jenkins.plugins.credentials.domains.RequiresDomain;
import com.google.jenkins.plugins.credentials.oauth.GoogleOAuth2ScopeRequirement;
import com.google.jenkins.plugins.credentials.oauth.GoogleRobotCredentials;
import hudson.FilePath;
import hudson.remoting.VirtualChannel;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Recorder;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.kohsuke.stapler.DataBoundSetter;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@RequiresDomain(value = AndroidPublisherScopeRequirement.class)
public abstract class GooglePlayPublisher extends Recorder {

    @DataBoundSetter
    private String googleCredentialsId;

    public BuildStepMonitor getRequiredMonitorService() {
        // Try to minimise concurrent editing, as the Google Play Developer Publishing API does not allow it
        return BuildStepMonitor.STEP;
    }

    /** @return The Google API credentials configured for this job. */
    protected final GoogleRobotCredentials getServiceAccountCredentials() throws UploadException {
        try {
            GoogleOAuth2ScopeRequirement req = new AndroidPublisherScopeRequirement();
            GoogleRobotCredentials credentials = GoogleRobotCredentials.getById(googleCredentialsId);
            if (credentials == null) {
                throw new UploadException("Credentials for the configured Google Account could not be found");
            }
            return credentials.forRemote(req);
        } catch (NullPointerException e) {
            // This should really be handled by the Google OAuth plugin
            throw new UploadException("Failed to get Google service account info.\n" +
                    "\tCheck that the correct 'Client Secrets JSON' file has been uploaded for the " +
                    "'"+ googleCredentialsId +"' credential.\n" +
                    "\tThe correct JSON file can be obtained by visiting the *old* Google APIs Console, selecting "+
                    "'API Access' and then clicking 'Download JSON' for the appropriate service account.\n" +
                    "\tSee: https://code.google.com/apis/console/?noredirect", e);
        } catch (IllegalStateException e) {
            if (ExceptionUtils.getRootCause(e) instanceof FileNotFoundException) {
                throw new UploadException("Failed to get Google service account info. Ensure that the JSON file and " +
                        "P12 private key for the '"+ googleCredentialsId +"' credential have both been uploaded.", e);
            }
            throw new UploadException(e);
        } catch (GeneralSecurityException e) {
            throw new UploadException(e);
        }
    }

    /** @return A user-friendly(ish) Google Play API error message, if one could be found in the given exception. */
    protected static String getPublisherErrorMessage(UploadException e) {
        if (e instanceof PublisherApiException) {
            // TODO: Here we could map error reasons like "apkUpgradeVersionConflict" to better (and localised) text
            return ((PublisherApiException) e).getDetailsMessage();
        }
        return e.getMessage();
    }

    /** Task which searches for files using an Ant Fileset pattern. */
    protected static final class FindFilesTask implements FilePath.FileCallable<List<String>> {

        private final String includes;

        FindFilesTask(String includes) {
            this.includes = includes;
        }

        @Override
        public List<String> invoke(File baseDir, VirtualChannel channel) throws IOException, InterruptedException {
            String[] files = hudson.Util.createFileSet(baseDir, includes).getDirectoryScanner().getIncludedFiles();
            return Collections.unmodifiableList(Arrays.asList(files));
        }

    }

}

