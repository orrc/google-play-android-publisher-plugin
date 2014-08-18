package org.jenkinsci.plugins.googleplayandroidpublisher;

public class UploadException extends Exception {

    public UploadException(String message, Throwable cause) {
        super(message, cause);
    }

    public UploadException(Throwable cause) {
        super(cause);
    }

}
