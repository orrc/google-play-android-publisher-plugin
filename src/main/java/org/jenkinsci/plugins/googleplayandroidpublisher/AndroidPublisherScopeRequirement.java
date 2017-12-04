package org.jenkinsci.plugins.googleplayandroidpublisher;

import com.google.api.services.androidpublisher.AndroidPublisherScopes;
import com.google.jenkins.plugins.credentials.oauth.GoogleOAuth2ScopeRequirement;

import java.util.Collection;
import java.util.Collections;

public class AndroidPublisherScopeRequirement extends GoogleOAuth2ScopeRequirement {

    @Override
    public Collection<String> getScopes() {
        return Collections.singleton(AndroidPublisherScopes.ANDROIDPUBLISHER);
    }

}
