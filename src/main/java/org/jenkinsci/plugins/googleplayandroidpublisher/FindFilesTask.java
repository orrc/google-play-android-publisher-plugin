package org.jenkinsci.plugins.googleplayandroidpublisher;

import hudson.remoting.VirtualChannel;
import jenkins.MasterToSlaveFileCallable;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Task which searches for files using an Ant Fileset pattern. */
public class FindFilesTask extends MasterToSlaveFileCallable<List<String>> {

    private final String includes;

    FindFilesTask(String includes) {
        this.includes = includes;
    }

    @Override
    public List<String> invoke(File baseDir, VirtualChannel channel) throws IOException, InterruptedException {
        // If we're being called from a Pipeline, the workspace directory may not necessarily exist, and because
        // Util#createFileset doesn't guard against the given directory not existing, we need to check it here first
        if (!baseDir.exists()) {
            return Collections.emptyList();
        }

        // Scan for files matching the given pattern
        String[] files = hudson.Util.createFileSet(baseDir, includes).getDirectoryScanner().getIncludedFiles();
        return Collections.unmodifiableList(Arrays.asList(files));
    }

}