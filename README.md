# TyrionDictionary

A T9-style predictive text keypad for Filipino words — no on-screen (soft) keyboard is ever shown. Type using the numeric keypad (2–9), just like an old-school phone, and the app predicts Filipino words from a built-in dictionary of 17,000+ words.

- **App icon:** the dachshund logo you provided.
- **Package name:** `com.tyrion.dictionary`
- **Dictionary:** `app/src/main/assets/dictionary_fil.txt` (17,160 Filipino/Tagalog words)

## How the keypad works

| Key | Function |
|-----|----------|
| 2–9 | Type letters, T9-style (e.g. "26" could mean "am", "bo", "an"...) |
| 0   | SPACE — commits the current suggested word and adds a space |
| *   | Susunod — cycle to the next suggested word for the current digits |
| #   | ⌫ Backspace — removes the last digit, or the last committed character |

## Getting the installable APK — no Android Studio required

This repo has a GitHub Actions workflow (`.github/workflows/build-apk.yml`) that builds the APK automatically on GitHub's own servers.

1. **Create a new repo on GitHub** (e.g. `TyrionDictionary`) and push this project to it:
   ```bash
   cd TyrionDictionary
   git init
   git add .
   git commit -m "Initial commit"
   git branch -M main
   git remote add origin https://github.com/<your-username>/TyrionDictionary.git
   git push -u origin main
   ```
2. Go to your repo's **Actions** tab on GitHub. The "Build TyrionDictionary APK" workflow will run automatically on push (or trigger it manually with "Run workflow").
3. When it finishes (a few minutes):
   - Download the APK from the workflow run's **Artifacts** section, **or**
   - Go to the **Releases** tab — a release is auto-created with `app-debug.apk` attached.
4. Transfer the `.apk` to your Android phone and tap it to install (you may need to allow "install from unknown sources" the first time).

## Building locally instead (optional)

If you have Android Studio installed, just open this folder as a project and click **Run**, or use:
```bash
./gradlew assembleDebug
```
(Android Studio will generate the Gradle wrapper files for you the first time you open the project.)

## Notes

- This is a debug-signed APK, which is fine for installing on your own device but not for Play Store distribution.
- The word list comes from an open-source Tagalog wordlist and is a solid common-word set, not an exhaustive dictionary — you can add more words by editing `app/src/main/assets/dictionary_fil.txt` (one word per line).
