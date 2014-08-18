package org.jenkinsci.plugins.googleplayandroidpublisher;

import com.google.api.client.googleapis.json.GoogleJsonResponseException;

import java.io.IOException;

/** Thrown when a call to the Google Play API throws an exception. */
public class PublisherApiException extends UploadException {

    private String detailsMessage;

    public PublisherApiException(IOException cause) {
        super(cause);
        // We need to extract the meaningful part of the exception here because GJRE isn't Serializable,
        // and so cannot be passed back from the build slave to Jenkins master without losing information
        if (cause instanceof GoogleJsonResponseException) {
            // TODO: It could be nice to serialise more of this structure, e.g. error reason codes
            this.detailsMessage = ((GoogleJsonResponseException) cause).getDetails().getMessage();
        }
    }

    public String getDetailsMessage() {
        return detailsMessage;
    }

}
