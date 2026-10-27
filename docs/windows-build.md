# Building on Windows

Tested on Windows 11 with Git Bash, 27 October 2026.

## 1. Java 17 (Eclipse Temurin)

Download the official zip `OpenJDK17U-jdk_x64_windows_hotspot_17.0.20.1_1.zip` from
<https://adoptium.net> and check its SHA-256 against the `.sha256.txt` file published next to it:

```
e53a79c3c3d86865bd7e787903884331068e71321714ffd44f145785affc7cb0
```

Unzip it to `%LOCALAPPDATA%\Programs\Eclipse Adoptium\jdk-17.0.20.1+1`. This needs no admin rights.
(`winget install EclipseAdoptium.Temurin.17.JDK` also works, but it installs machine-wide and shows an admin prompt.)

## 2. Android SDK (command-line tools)

Download `commandlinetools-win-16111833_latest.zip` from
<https://developer.android.com/studio#command-line-tools-only> (SHA-1 `57d04f2d75eb8e8fffc5000a987e5de4b5a63e9d`).
Unzip it so the tools end up in `%LOCALAPPDATA%\Android\Sdk\cmdline-tools\latest\bin`.

Then, in Git Bash:

```bash
export JAVA_HOME="$LOCALAPPDATA/Programs/Eclipse Adoptium/jdk-17.0.20.1+1"
export ANDROID_HOME="$LOCALAPPDATA/Android/Sdk"
printf 'platforms;android-35\nbuild-tools;35.0.0\nplatform-tools\n' > /tmp/sdk-packages.txt
yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager.bat" --package_file=/tmp/sdk-packages.txt
```

`sdkmanager.bat` splits arguments on `;` when you call it from Git Bash, so pass the
package names in a file (`--package_file`) rather than on the command line.
`yes |` accepts the licences.

## 3. Build

```bash
export JAVA_HOME="$LOCALAPPDATA/Programs/Eclipse Adoptium/jdk-17.0.20.1+1"
export ANDROID_HOME="$LOCALAPPDATA/Android/Sdk"
./gradlew assembleRelease
```

The APK is written to `app/build/outputs/apk/release/app-release.apk` (about 250 KB).
Release builds are signed with the Android debug key (see `app/build.gradle`).

Instead of `ANDROID_HOME` you can put `sdk.dir=C\:\\Users\\<you>\\AppData\\Local\\Android\\Sdk`
in `local.properties`. That file is git-ignored.

## Gradle wrapper check

`gradle/wrapper/gradle-wrapper.jar` does not match Gradle's published checksum for
8.14.3. It is byte-identical to Gradle's official wrapper jar for 9.8.1 and 9.8.2:

```
3b8a25775a69158b5ad2b1d17a80a88dc7b40a352a73eb27d06c612a7ce68e98
```

(see `https://services.gradle.org/distributions/gradle-9.8.1-wrapper.jar.sha256`).
It is left unchanged so this fork stays identical to upstream. The Gradle version
that actually runs is still 8.14.3, and `distributionSha256Sum` in
`gradle/wrapper/gradle-wrapper.properties` pins that download to its checksum.
