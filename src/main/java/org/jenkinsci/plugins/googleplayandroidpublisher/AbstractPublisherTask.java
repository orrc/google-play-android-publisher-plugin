package org.jenkinsci.plugins.googleplayandroidpublisher;

import com.google.api.services.androidpublisher.AndroidPublisher;
import com.google.jenkins.plugins.credentials.oauth.GoogleRobotCredentials;
import hudson.model.BuildListener;
import hudson.model.TaskListener;
import hudson.remoting.Callable;
import jenkins.security.MasterToSlaveCallable;

import java.io.IOException;
import java.io.PrintStream;
import java.security.GeneralSecurityException;

public abstract class AbstractPublisherTask<V> extends MasterToSlaveCallable<V, UploadException> {

    private final TaskListener listener;
    private final GoogleRobotCredentials credentials;
    private final String pluginVersion;
    protected AndroidPublisher.Edits editService;
    protected String editId;
    protected PrintStream logger;

    AbstractPublisherTask(TaskListener listener, GoogleRobotCredentials credentials) {
        this.listener = listener;
        this.credentials = credentials;
        this.pluginVersion = Util.getPluginVersion();
    }

    public final V call() throws UploadException {
        editService = getEditService();
        logger = listener.getLogger();
        try {
            return execute();
        } catch (IOException e) {
            // All the remote API calls can throw IOException, so we catch and wrap them here for convenience
            throw new PublisherApiException(e);
        } catch (InterruptedException e) {
            // There's no special handling we want to do if the build is interrupted, so just wrap and rethrow
            throw new UploadException(e);
        }
    }

    protected abstract V execute() throws IOException, InterruptedException, UploadException;

    protected final AndroidPublisher.Edits getEditService() throws UploadException {
        try {
            return Util.getPublisherClient(credentials, pluginVersion).edits();
        } catch (GeneralSecurityException e) {
            throw new UploadException(e);
        }
    }

    /** Creates a new edit, assigning the {@link #editId}. Any previous edit ID will be lost. */
    protected final void createEdit(String applicationId) throws IOException {
        editId = editService.insert(applicationId, null).execute().getId();
    }

    /** @return The name of the credential being used. */
    protected String getCredentialName() {
        return credentials.getId();
    }

}