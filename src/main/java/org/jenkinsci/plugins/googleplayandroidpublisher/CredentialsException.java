package org.jenkinsci.plugins.googleplayandroidpublisher;

/** Thrown when there's a local configuration error with the Google Play credentials. */
public class CredentialsException extends UploadException {

    public CredentialsException(String message) {
        super(message);
    }

}
