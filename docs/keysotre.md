Build rerun succeeded after reboot: android-testdpc\buildlite.bat completed and produced fresh signed APK at android-testdpc/bazel-bin/testdpc-lite-release.apk (with .idsig alongside). Unsigned and aligned intermediates are also updated (testdpc.apk, testdpc-lite-aligned.apk, etc.).

I set UPLOAD_KEYSTORE_PASS and UPLOAD_KEY_PASS to G9p#sL8vQ2n!Ya5dWmR3 for this run; set them again in your shell for future builds. The keystore in use is still android-testdpc/signing/upload.keystore (alias upload).

Build ran successfully.

Execute android-testdpc\buildlite.bat with UPLOAD_KEYSTORE_PASS/UPLOAD_KEY_PASS=G9p#sL8vQ2n!Ya5dWmR3. Build completed and signed APK is at android-testdpc/bazel-bin/testdpc-lite-release.apk (with .idsig). Unsigned/aligned intermediates refreshed (testdpc.apk, testdpc-lite-aligned.apk, etc.).
Same keystore in use: android-testdpc/signing/upload.keystore (alias upload).
Info-level warnings only (Java 8 option deprecation, manifest merge notes); no errors.