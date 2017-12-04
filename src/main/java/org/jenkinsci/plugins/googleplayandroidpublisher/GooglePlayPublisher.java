package org.jenkinsci.plugins.googleplayandroidpublisher;

import com.google.jenkins.plugins.credentials.domains.RequiresDomain;
import hudson.FilePath;
import hudson.Launcher;
import static hudson.Util.fixEmptyAndTrim;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Recorder;
import jenkins.tasks.SimpleBuildStep;
import org.kohsuke.stapler.DataBoundSetter;

import javax.annotation.Nonnull;
import java.io.IOException;

@RequiresDomain(value = AndroidPublisherScopeRequirement.class)
public abstract class GooglePlayPublisher extends Recorder implements SimpleBuildStep {

    protected static transient final ThreadLocal<Run<?, ?>> currentBuild = new ThreadLocal<>();
    protected static transient final ThreadLocal<TaskListener> currentListener = new ThreadLocal<>();

    private transient CredentialsHandler credentialsHandler;

    private String googleCredentialsId;

    @DataBoundSetter
    public void setGoogleCredentialsId(String googleCredentialsId) {
        this.googleCredentialsId = googleCredentialsId;
    }

    public String getGoogleCredentialsId() {
        return fixEmptyAndTrim(googleCredentialsId);
    }
    
    private String getExpandedGoogleCredentialsId() throws IOException, InterruptedException {
        return expand(getGoogleCredentialsId());
    }

    @Override
    public void perform(@Nonnull Run<?, ?> run, @Nonnull FilePath workspace, @Nonnull Launcher launcher,
                        @Nonnull TaskListener listener) throws InterruptedException, IOException {
        currentBuild.set(run);
        currentListener.set(listener);
    }

    protected CredentialsHandler getCredentialsHandler() throws CredentialsException, IOException, InterruptedException {
        if (credentialsHandler == null) {
            credentialsHandler = new CredentialsHandler(getExpandedGoogleCredentialsId());
        }
        return credentialsHandler;
    }

    /** @return An expanded value, using the build and environment variables, plus token macro expansion. */
    protected String expand(String value) throws IOException, InterruptedException {
        return Util.expand(currentBuild.get(), currentListener.get(), value);
    }

    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.NONE;
    }

}
