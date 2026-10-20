# Build environment (Windows, no Android Studio)

The app is built from the command line on Windows and installed on a physical phone over USB.
Everything below is installed per user, without administrator rights.

## Installed tools

| Tool | Version | Location | Installed with |
|---|---|---|---|
| JDK (Eclipse Temurin) | 21.0.12 | `%USERPROFILE%\scoop\apps\temurin21-jdk\current` | `scoop install temurin21-jdk` (bucket `java`) |
| Gradle (only to generate the wrapper) | 9.8.2 | `%USERPROFILE%\scoop\apps\gradle\current` | `scoop install gradle` |
| Gradle wrapper | 9.8.2 (bin distribution) | `gradlew`, `gradlew.bat`, `gradle/wrapper/` in this repo | `gradle wrapper --gradle-version 9.8.2 --distribution-type bin` |
| Android SDK command-line tools | 23.0 (`commandlinetools-win-16111833`) | `C:\Android\Sdk\cmdline-tools\latest` | Official zip from `dl.google.com`, SHA-1 checked |
| Android SDK Platform-Tools (adb) | 37.0.1 | `C:\Android\Sdk\platform-tools` | `sdkmanager "platform-tools"` |
| Android SDK Platform | API 36 | `C:\Android\Sdk\platforms\android-36` | `sdkmanager "platforms/android-36"` |
| Android SDK Build-Tools | 36.1.0 | `C:\Android\Sdk\build-tools\36.1.0` | `sdkmanager "build-tools/36.1.0"` |

Always build with the wrapper (`.\gradlew.bat`), never with the global `gradle`.

## Environment variables (user scope)

| Variable | Value |
|---|---|
| `JAVA_HOME` | `%USERPROFILE%\scoop\apps\temurin21-jdk\current` (set by scoop) |
| `ANDROID_HOME` | `C:\Android\Sdk` |
| `GRADLE_USER_HOME` | `%USERPROFILE%\scoop\apps\gradle\current\.gradle` (set by scoop's Gradle package) |
| `Path` (added) | `%JAVA_HOME%\bin`, `C:\Android\Sdk\cmdline-tools\latest\bin`, `C:\Android\Sdk\platform-tools` |

Open a new terminal after installing so the variables are picked up.

## Checking the setup

```powershell
java -version            # 21.0.12
.\gradlew.bat --version  # Gradle 9.8.2, JVM 21
adb version              # 37.0.1
adb devices              # the phone appears as "device" once USB debugging is authorized
```

## Notes

- `sdkmanager` prints a deprecation warning pointing to the new `android sdk` command; both work.
- Package names use `/` (`platforms/android-36`). The old `;` form fails when calling `sdkmanager.bat` from PowerShell, because the batch file splits arguments on `;`.
- Installing SDK packages requires accepting the Android SDK license (`sdkmanager --licenses`).
