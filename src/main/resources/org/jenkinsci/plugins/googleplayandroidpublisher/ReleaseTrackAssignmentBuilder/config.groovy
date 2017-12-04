package org.jenkinsci.plugins.googleplayandroidpublisher.ReleaseTrackAssignmentBuilder

def f = namespace(lib.FormTagLib)

f.entry(field: 'googleCredentialsId', title: _('Google Play account')) {
    f.select()
}

def fromVersionCode = instance?.fromVersionCode == null || instance.fromVersionCode
f.radioBlock(name: 'fromVersionCode', title: _('Enter version codes to be assigned'), value: true, checked: fromVersionCode, inline: true) {

    f.entry(field: 'applicationId', title: _('Application ID')) {
        f.textbox()
    }

    f.entry(field: 'versionCodes', title: _('Version code(s)'), description: _('Comma-separated list of version codes')) {
        f.textbox()
    }

}

f.radioBlock(name: 'fromVersionCode', title: _('Read version codes to be assigned from APK files'), value: false, checked: !fromVersionCode, inline: true) {

    f.entry(field: 'apkFilesPattern', title: _('APK file(s)'), description: _('Comma-separated list of filenames or patterns')) {
        f.textbox()
    }

}

f.entry(field: 'trackName', title: _('Release track')) {
    f.combobox(style: 'width:15em')
}

f.entry(field: 'rolloutPercentage', title: _('Rollout %'), description: _('Only applies to the \'production\' release track')) {
    f.combobox(style: 'width:15em')
}
