# App icon source image

Drop your real logo here as `icon.png` before running the icon generator.

**Requirements:**
- File name: exactly `icon.png` (matches `image_path` in `pubspec.yaml`)
- Square image, 1024x1024px recommended (Play Store's own listing icon
  requirement is 512x512, but starting larger avoids blurry downscaling)
- PNG format, no transparency needed for the main icon (Android's adaptive
  icon system handles the background/foreground split itself via the
  `adaptive_icon_background` color already set in `pubspec.yaml`)

**Then run:**
```
flutter pub get
dart run flutter_launcher_icons
```
This generates every required Android icon size automatically (replacing
the current placeholder green-diamond-with-a-bag vector) and updates the
adaptive icon config. Re-run it any time you change the source image.
