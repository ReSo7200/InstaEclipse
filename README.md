<div align="center">
  <img src="assets/logo.png" alt="InstaEclipse" width="120" />
  <h1>InstaEclipse</h1>
  <p>An LSPosed module that hands Instagram back to you. Privacy, downloads, a cleaner feed, your own look, and a pile of quality-of-life fixes, all toggled from inside the app.</p>

  <p>
    <a href="https://github.com/ReSo7200/InstaEclipse/releases/latest"><img alt="GitHub Release" src="https://img.shields.io/github/v/release/ReSo7200/InstaEclipse?style=for-the-badge&logo=github&color=1a1a2e&labelColor=0d0d0d"/></a>
    <a href="https://github.com/ReSo7200/InstaEclipse/stargazers"><img alt="Stars" src="https://img.shields.io/github/stars/ReSo7200/InstaEclipse?style=for-the-badge&logo=github&color=1a1a2e&labelColor=0d0d0d"/></a>
    <a href="https://github.com/ReSo7200/InstaEclipse/releases/latest"><img alt="Downloads" src="https://img.shields.io/github/downloads/ReSo7200/InstaEclipse/total?style=for-the-badge&logo=github&color=1a1a2e&labelColor=0d0d0d"/></a>
    <a href="https://t.me/InstaEclipse"><img alt="Telegram" src="https://img.shields.io/badge/Telegram-Channel-26A5E4?style=for-the-badge&logo=telegram&labelColor=0d0d0d"/></a>
    <a href="LICENSE"><img alt="License" src="https://img.shields.io/github/license/ReSo7200/InstaEclipse?style=for-the-badge&color=1a1a2e&labelColor=0d0d0d"/></a>
  </p>

  <p>
    <a href="#what-is-it">What is it</a> •
    <a href="#features">Features</a> •
    <a href="#compatibility">Compatibility</a> •
    <a href="#installation">Installation</a> •
    <a href="#opening-the-menu">Using it</a> •
    <a href="#faq">FAQ</a> •
    <a href="#contributors">Contributors</a>
  </p>
</div>

---

## What is it

InstaEclipse is a module for [LSPosed](https://github.com/JingMatrix/LSPosed) (rooted) and [LSPatch](https://github.com/JingMatrix/LSPatch) (no root). Once it is enabled, a long press on the search icon inside Instagram opens a settings sheet where you flip features on and off. No restart needed for most of them.

There is also a small companion app that ships alongside it. That is where you check module status, read the live logs, back up your settings, and open the theme editor.

The whole thing leans on [DexKit](https://github.com/LuckyPray/DexKit) to find Instagram's classes and methods at runtime instead of hard-coding them. In plain terms: when Instagram ships an update and shuffles its code around, InstaEclipse re-finds what it needs on the next launch rather than breaking outright. It will not survive every update untouched, but it holds up far better than a module pinned to fixed names.

> InstaEclipse is a personal, educational project. It is not affiliated with Meta or Instagram in any way. See the [Disclaimer](DISCLAIMER.md).

---

## Features

Everything below is a toggle in the in-app menu. Tap a section to expand it.

<details>
<summary><b>👻 Ghost Mode:</b> browse without leaving footprints</summary>

<br/>

| Feature | What it does |
|---|---|
| Hide DM seen | Read messages without sending a read receipt |
| Hide typing indicator | Type freely, the other person never sees the dots |
| Hide story views | Watch stories without showing up in the viewer list |
| Hide live presence | Join lives without being counted as a viewer |
| Bypass screenshot detection | Screenshot disappearing DM media without tipping anyone off |
| Allow screenshots in DMs | Re-enable screenshots where Instagram blocks them |
| Hide view-once opened | Open view-once media without marking it as seen |
| Permanent view-once media | Keep view-once and view-twice media around instead of it vanishing |
| Keep disappearing messages | Stop ephemeral messages from deleting themselves |
| Quick Toggle | Flip all of the above at once, right from inside Instagram |

</details>

<details>
<summary><b>🔒 Privacy and DMs:</b> lock things down and keep what matters</summary>

<br/>

| Feature | What it does |
|---|---|
| Lock the whole app | Require a passcode (or your fingerprint) to open Instagram at all |
| Lock DMs | Passcode gate just the inbox, leave the rest of the app open |
| Fingerprint unlock | Use the device biometric prompt instead of typing the code, if your phone supports it |
| Hide specific chats | Pick chats that should quietly disappear from your inbox, manage them later from the menu |
| Keep unsent messages | When someone unsends a message, keep a private copy grouped per person |
| Spoof last seen | Freeze your "active" status instead of updating it live |

</details>

<details>
<summary><b>📥 Downloads and media:</b> save and share anything</summary>

<br/>

| Feature | What it does |
|---|---|
| Download posts | Save single photos and full carousels |
| Download reels | Save reels straight to your gallery |
| Download stories | Grab stories before they expire, including your own with the music kept intact |
| Download profile pictures | Long press a profile to save the full-size picture |
| Copy media link | Copy the direct CDN link for a post, reel, or a specific carousel slide |
| Save Instants | Long press to save an Instant you are viewing |
| Upload Instant from gallery | Pick an image from your gallery and send it as an Instant |
| 24h story cache | Quietly keep viewed stories for a day so you can reopen or save ones that were deleted or expired, sorted into per-username folders |
| Custom download folder | Choose exactly where files land |
| Username subfolders | Sort saved media into a folder per account automatically |
| Timestamped filenames | Add the download date and time to each saved file |

</details>

<details>
<summary><b>🎨 Appearance:</b> make Instagram look the way you like</summary>

<br/>

| Feature | What it does |
|---|---|
| Custom theme | Recolor the app with built-in presets or a full color picker for background, surface, text, accent, and icons |
| Live apply | Color changes take effect immediately, no restart |
| Custom font | Load your own .ttf or .otf and use it across the app, chats and captions included |
| Custom emoji | Swap in your own color emoji font (an Apple emoji font, for example) |
| Force reels quality | Pin reels to a fixed quality (Auto, 360p to 1080p, or Max Available) instead of the adaptive bitrate |

> Fonts and emoji apply the next time Instagram starts.

</details>

<details>
<summary><b>✨ Clean Feed:</b> see the people you actually follow</summary>

<br/>

| Feature | What it does |
|---|---|
| Hide suggestions in feed | Strip out suggested posts, suggested reels, and other non-followed clutter |
| Hide Threads suggestions | Drop the Threads cross-promo units on their own |
| Remove Meta AI | Take the Meta AI entry points out of search, the composer, and the reel more-options button |

</details>

<details>
<summary><b>🛡️ Ads and analytics:</b> browse without the tracking</summary>

<br/>

| Feature | What it does |
|---|---|
| Block ads | Drop sponsored posts and ad units |
| Block analytics | Cut Instagram's analytics and telemetry calls |
| Disable tracking links | Strip tracking parameters from links you share and copy, referral tokens included |

</details>

<details>
<summary><b>🧘 Distraction-Free:</b> quiet the parts that pull you in</summary>

<br/>

| Feature | What it does |
|---|---|
| Disable sections | Turn off Stories, Feed, Reels, Explore, or Comments one by one |
| Extreme mode | Strip distractions hard until you reinstall |

> After turning these on, force stop Instagram and clear its cache so the change takes hold.

</details>

<details>
<summary><b>📍 Location:</b> decide what Instagram thinks your GPS says</summary>

<br/>

| Feature | What it does |
|---|---|
| Spoof GPS location | Report a location of your choosing to Instagram |
| Map picker | Search for a place or drop a pin on a map to set it |

</details>

<details>
<summary><b>🎛️ Developer Options:</b> reach Instagram's hidden internal panel</summary>

<br/>

| Feature | What it does |
|---|---|
| MetaConfig panel | Open the full internal developer/QE panel |
| Import and export config | Move your config in and out as JSON |
| Restore default config | Reset the developer config to a bundled, known-good default |
| Remove build-expired popup | Dismiss the "build expired" nag on older builds |
| Clear hooks cache | Force a fresh re-scan of Instagram on the next launch |

> Beta or Alpha Instagram builds work best here. Stable builds obfuscate the panel so some labels show up as numbers.

</details>

<details>
<summary><b>⚙️ Everyday extras:</b> the small stuff that adds up</summary>

<br/>

| Feature | What it does |
|---|---|
| Disable story auto-swipe | Stop stories from advancing on their own |
| Disable video autoplay | Videos wait until you tap them |
| Auto-clear cache | Clear Instagram's media cache when you leave the app, once it grows past a size you set |
| Copy comment | Copy any comment with one tap |
| Copy caption | Copy a post or reel caption from the overflow menu |
| Photo zoom | Long press a feed photo to open it full screen with pinch to zoom |
| View story mentions | See every @mention in a story at once |
| Follower toast | Get a heads-up on whether someone follows you back when you open their profile |
| Disable discover people | Remove the "people you may know" row |
| Disable double-tap to like | Stop accidental likes from a stray double tap |
| Disable repost | Keep the repost button from actually reposting |

</details>

<details>
<summary><b>🧰 Companion app:</b> logs, backups, and status</summary>

<br/>

| Feature | What it does |
|---|---|
| Module status | Confirm at a glance that the module is active and see which Instagram build is installed |
| In-app log viewer | Read hook status and activity from both Instagram and the companion app, no adb needed |
| Backup and restore | Save every setting to a file and load it back after a reinstall or on a new phone |
| 17 languages | The interface is translated into Arabic, German, Greek, Spanish, French, Hebrew, Indonesian, Italian, Polish, Portuguese, Russian, Swedish, Turkish, Simplified and Traditional Chinese, and more |

</details>

---

## Compatibility

InstaEclipse is designed to keep up with Instagram automatically, but no module is bulletproof. A big Instagram update can still knock a feature out until it is patched. If something breaks right after an update, check the [Telegram channel](https://t.me/InstaEclipse) before opening an issue, there is a good chance it is already known.

| | |
|---|---|
| **Latest tested version** | `447.0.0.21.81` |
| **Recommended build** | Beta or Alpha, from [APKMirror](https://www.apkmirror.com/apk/instagram/instagram-instagram/instagram-447-0-0-21-81-release/) |
| **Auto-compatibility** | Yes, classes and methods are resolved at runtime with DexKit |

---

## Installation

> Grab Instagram from [APKMirror](https://www.apkmirror.com/apk/instagram/instagram-instagram/) rather than the Play Store. The Play build is not always fully supported.

Download the latest InstaEclipse APK from [**Releases**](https://github.com/ReSo7200/InstaEclipse/releases/latest), then follow the path that matches your setup.

### Rooted, with LSPosed

> Needs [JingMatrix's LSPosed](https://github.com/JingMatrix/LSPosed/releases/latest).

1. **Install InstaEclipse.** Open the APK you downloaded and install it.
2. **Enable the module.** Open LSPosed Manager, go to Modules, find InstaEclipse, enable it, and scope it to Instagram.
3. **Restart Instagram.** Force stop it, then open it again.
4. **Open the menu.** Long press the search icon inside Instagram.

> [!CAUTION]
> Using Hide My Applist? Do not add InstaEclipse to the hidden list. Instagram needs to see it. Hiding it causes crashes and features that silently stop working.

### No root, with LSPatch

> Needs [JingMatrix's LSPatch](https://github.com/JingMatrix/LSPatch/releases/latest).

1. **Install InstaEclipse.** Open the APK and install it.
2. **Install LSPatch** (the JingMatrix fork).
3. **Patch Instagram.** In LSPatch, tap **+**, pick the Instagram APK or the installed app, choose **Local Patch Mode**, turn on **Inject loader dex**, then tap **Start Patch** and wait.
4. **Install the patched APK** it produces, and log in to Instagram.
5. **Enable the module.** Back in LSPatch, go to Manage, find Instagram, open Modules, and enable InstaEclipse.
6. **Open the menu.** Long press the search icon inside Instagram.

---

## Opening the menu

Long press the search icon inside Instagram and the InstaEclipse sheet slides up. Features are grouped into Appearance, Privacy, Media, and Tools, and most take effect the moment you toggle them.

For walkthroughs, tips, and update notes:

- 📢 Announcements and updates: [Telegram channel](https://t.me/InstaEclipse)
- 💬 Questions and community help: [Telegram discussion group](https://t.me/instaEclipse_discussion)

---

## FAQ

**A feature is not doing anything.**
Disable and re-enable the module in LSPosed or LSPatch, then force stop and reopen Instagram. That reloads the hooks cleanly.

**Developer Options labels show up as numbers.**
That is obfuscation on Instagram's Stable build. Switch to a Beta or Alpha build from APKMirror.

**I turned on Distraction-Free but the content is still there.**
Force stop Instagram and clear its cache after enabling it, then reopen.

**It is not working on the Play Store version.**
Install Instagram from [APKMirror](https://www.apkmirror.com/apk/instagram/instagram-instagram/) instead.

**Some features still will not work even when enabled.**
Instagram's own internal config can silently block certain features. There is a ready-made config that clears the common ones, and InstaEclipse ships it as the default under Developer Options, Restore Default Config. You can also grab it from [Telegram](https://t.me/InstaEclipse/52).

**Still stuck.**
Drop into the [Telegram group](https://t.me/instaEclipse_discussion) and ask. Someone will point you in the right direction.

---

## Contributors

<div align="center">

### Maintainer

<a href="https://github.com/ReSo7200">
  <img src="https://github.com/ReSo7200.png" width="80" alt="ReSo7200" style="border-radius:50%"/><br/>
  <b>ReSo7200</b>
</a>

<br/><br/>

### Everyone who has pitched in

<a href="https://github.com/ReSo7200/InstaEclipse/graphs/contributors">
  <img src="https://contrib.rocks/image?repo=ReSo7200/InstaEclipse" alt="Contributors"/>
</a>

<sub>Made with <a href="https://contrib.rocks">contrib.rocks</a></sub>

<br/>

**Translators**
Huge thanks to everyone who translated InstaEclipse into their language. You are the reason people all over the world can actually use it.

</div>

---

## Built with

- [JingMatrix/LSPosed](https://github.com/JingMatrix/LSPosed), the Xposed framework this runs on
- [JingMatrix/LSPatch](https://github.com/JingMatrix/LSPatch), the no-root path
- [LuckyPray/DexKit](https://github.com/LuckyPray/DexKit), the runtime DEX analysis that keeps it compatible

---

## Contributing

Pull requests, bug reports, feature ideas, and translations are all welcome.

- Found a bug? [Open a bug report](https://github.com/ReSo7200/InstaEclipse/issues/new/choose)
- Have an idea? [Submit a feature request](https://github.com/ReSo7200/InstaEclipse/issues/new/choose)
- Want to build something? Fork the repo and open a PR

---

<div align="center">
  <sub>Made with care by the InstaEclipse community.</sub><br/>
  <sub>Not affiliated with Meta or Instagram. See the <a href="DISCLAIMER.md">Disclaimer</a>.</sub>
</div>
