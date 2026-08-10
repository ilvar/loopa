# Store graphics

Both F-Droid and Google Play (via `fastlane supply`) read store images from this
directory. Add the following PNG/JPEG files before submitting — they can't be
committed as text, so this folder is a placeholder describing what's needed:

```
images/
├── icon.png                     512×512, the store listing icon
├── featureGraphic.png           1024×500, Play feature graphic
└── phoneScreenshots/
    ├── 1.png                     at least 2 screenshots, portrait
    └── 2.png                     (take on a device with a telephoto lens)
```

Filenames and dimensions above follow the Fastlane / F-Droid conventions:
https://f-droid.org/docs/All_About_Descriptions_Graphics_and_Screenshots/
