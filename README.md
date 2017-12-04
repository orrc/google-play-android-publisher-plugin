# Google Play Android Publisher plugin for Jenkins

Enables Jenkins to upload Android apps (APK files) and related info to Google Play.

https://wiki.jenkins-ci.org/display/JENKINS/Google+Play+Android+Publisher+Plugin

CHANGES:

We have modified the classes:
- GooglePlayBuilder (): The variable 'googleCredentialsId' has been expanded to allow other values.
- GooglePlayPublisher (): The variable 'googleCredentialsId' has been expanded to allow other values.
---------------------------------
- config.jelly of the ApkPubliser folder, we have removed the drop-down and we have added textBox.
