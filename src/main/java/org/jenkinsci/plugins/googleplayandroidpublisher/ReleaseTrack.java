package org.jenkinsci.plugins.googleplayandroidpublisher;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Release tracks to which APKs can be assigned. */
public enum ReleaseTrack {

    ALPHA,
    BETA,
    PRODUCTION,
    ROLLOUT(false);

    /** Whether this track can be specified in job config. */
    private final boolean visibleForConfig;

    private ReleaseTrack() {
        this(true);
    }

    private ReleaseTrack(boolean visible) {
        this.visibleForConfig = visible;
    }

    /** @return List of release track names which may be configured. */
    public static List<String> getConfigValues() {
        List<String> tracks = new ArrayList<String>();
        for (ReleaseTrack rt : values()) {
            if (rt.visibleForConfig) {
                tracks.add(rt.getApiValue());
            }
        }
        return tracks;
    }

    /** @return Release track corresponding the name given, or {@code null} if not found. */
    public static ReleaseTrack fromConfigValue(String name) {
        for (ReleaseTrack rt : values()) {
            if (rt.visibleForConfig && rt.name().equalsIgnoreCase(name)) {
                return rt;
            }
        }
        return null;
    }

    /** @return The track name value used by the Google Play API. */
    public String getApiValue() {
        return name().toLowerCase(Locale.ENGLISH);
    }

    @Override
    public String toString() {
        return getApiValue();
    }

}
