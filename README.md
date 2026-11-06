# VidGrab
Personal sideloaded YouTube downloader. Kotlin app (`android/`), package `com.mostafa.vidgrab`, using
io.github.junkfood02.youtubedl-android 0.18.1 (library + ffmpeg + aria2c), which runs yt-dlp/ffmpeg on the device. No backend.

Rebuild: `cd android && JAVA_HOME=/opt/tools/jdk ./gradlew assembleRelease && cp app/build/outputs/apk/release/app-release.apk ../vidgrab.apk`
Signing: /home/box/secrets/vidgrab-release.jks (alias `vidgrab`), password in /home/box/secrets/vidgrab.env. Bump versionCode for updates.

Format selector (Formats.kt), per height H and codec C, original-audio on:
`bv*[height=H][vcodec^=C]+(ba[format_note*=original][ext=m4a]/ba[format_note*=original]/ba[language_preference>=10]/ba[ext=m4a]/ba)/bv*[height=H]+(...)/b[height<=H]`
with `-S lang --merge-output-format mp4/mkv` (MP4 when compatible, else MKV). Audio only uses `-x` (best, or `--audio-format mp3`).
Files are downloaded to cache and then copied through MediaStore to Download/VidGrab.
