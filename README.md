<div align="center">
  <img src="assets/logo.png" alt="InstaEclipse" width="120" />
  <h1>InstaEclipse</h1>
  <p>A powerful LSPosed module that enhances your Instagram experience.</p>

  <p>
    <a href="https://github.com/ReSo7200/InstaEclipse/releases/latest"><img alt="GitHub Release" src="https://img.shields.io/github/v/release/ReSo7200/InstaEclipse?style=for-the-badge&logo=github&color=1a1a2e&labelColor=0d0d0d"/></a>
    <a href="https://github.com/ReSo7200/InstaEclipse/stargazers"><img alt="Stars" src="https://img.shields.io/github/stars/ReSo7200/InstaEclipse?style=for-the-badge&logo=github&color=1a1a2e&labelColor=0d0d0d"/></a>
    <a href="https://github.com/ReSo7200/InstaEclipse/releases/latest"><img alt="Downloads" src="https://img.shields.io/github/downloads/ReSo7200/InstaEclipse/total?style=for-the-badge&logo=github&color=1a1a2e&labelColor=0d0d0d"/></a>
    <a href="https://t.me/InstaEclipse"><img alt="Telegram" src="https://img.shields.io/badge/Telegram-Channel-26A5E4?style=for-the-badge&logo=telegram&labelColor=0d0d0d"/></a>
    <a href="LICENSE"><img alt="License" src="https://img.shields.io/github/license/ReSo7200/InstaEclipse?style=for-the-badge&color=1a1a2e&labelColor=0d0d0d"/></a>
  </p>

  <p>
    <a href="#-features">Features</a> •
    <a href="#-installation">Installation</a> •
    <a href="#-faq">FAQ</a> •
    <a href="#-contributors">Contributors</a>
  </p>
</div>

---

## Overview

**InstaEclipse** is an [LSPosed](https://github.com/JingMatrix/LSPosed) module built to unlock a richer Instagram experience — without root required (via [LSPatch](https://github.com/JingMatrix/LSPatch)).

It uses [DexKit](https://github.com/LuckyPray/DexKit) for dynamic class/method detection, keeping it compatible with new Instagram releases automatically.

---

## ✨ Features

<details>
<summary><b>👻 Ghost Mode</b> — Stay invisible without giving up functionality</summary>

<br/>

| Feature | Description |
|---|---|
| Hide DM Seen | Read messages without sending the read receipt |
| Hide Typing Indicator | Type freely without the other person knowing |
| Hide Story Views | View stories without appearing in the viewer list |
| Hide Live Presence | Join lives anonymously |
| Bypass Screenshot Detection | Take screenshots in DMs without triggering alerts |
| Allow Screenshots in DMs | Re-enable screenshot capability in restricted chats |
| Hide View Once Opened | Open view-once media without marking it as seen |
| Unlimited View-Once Replays | Replay view-once media as many times as you want |
| Permanent View Once Media | Prevent view-once media from disappearing |
| Keep Disappearing Messages | Stop ephemeral messages from deleting |
| Quick Toggle | Enable/disable all ghost options from inside Instagram with one tap |

</details>

<details>
<summary><b>📥 Downloader</b> — Save media directly from Instagram</summary>

<br/>

| Feature | Description |
|---|---|
| Download Posts | Save single photos and carousel posts |
| Download Reels | Save reels to your device |
| Download Stories | Save stories before they disappear |
| Download Profile Pictures | Long-press a profile to save their picture |
| Custom Download Folder | Choose where files are saved |
| Username Subfolders | Organize downloads by username automatically |

</details>

<details>
<summary><b>🎛️ Developer Options</b> — Access Instagram's hidden internal settings</summary>

<br/>

- Unlock the full MetaConfig developer panel
- Import/Export your config as JSON
- Remove the "Build Expired" popup on older builds

> **Note:** Use Beta or Alpha Instagram builds for best results. Stable builds apply obfuscation that makes some labels appear as numbers.

</details>

<details>
<summary><b>🛡️ Ad & Analytics Blocking</b> — Browse without being tracked</summary>

<br/>

- Block sponsored posts and ads from your feed
- Block Instagram's analytics and telemetry
- Disable tracking links in DMs and posts

</details>

<details>
<summary><b>🧘 Distraction-Free Mode</b> — Take back control of your attention</summary>

<br/>

- Disable Stories, Feed, Reels, Explore, or Comments independently
- **Extreme Mode** — permanently removes distractions until reinstall

> After enabling, force stop Instagram and clear its cache.

</details>

<details>
<summary><b>⚙️ Miscellaneous</b> — Quality of life improvements</summary>

<br/>

| Feature | Description |
|---|---|
| Disable Story Auto-Swipe | Stop stories from flipping automatically |
| Disable Video Autoplay | Videos don't play until you tap them |
| Follower Toast | See if someone follows you back when you visit their profile |
| Copy Comment | Copy any comment text with one tap |
| View Story Mentions | See all @mentions in a story at once |
| Disable Discover People | Remove the "People you may know" section |

</details>

<details>
<summary><b>💾 Backup & Restore</b> — Keep your settings safe</summary>

<br/>

Export and restore all your InstaEclipse settings as a file — useful when switching devices or reinstalling.

</details>

---

## 📱 Instagram Compatibility

While InstaEclipse is built to stay compatible with new Instagram releases automatically, some features may be unstable on specific versions. If something stops working after an Instagram update, check the [Telegram channel](https://t.me/InstaEclipse) for status updates before opening an issue.

| | |
|---|---|
| **Latest tested version** | [`425.0.0.0.0`](https://www.apkmirror.com/apk/instagram/instagram-instagram/instagram-425-0-0-0-0-release/) |
| **Recommended build type** | Beta or Alpha (from APKMirror) |

---

## 📲 Installation

> ⚠️ **Use Instagram from [APKMirror](https://www.apkmirror.com/apk/instagram/instagram-instagram/)** — the Google Play version may not be fully supported.

Download the latest InstaEclipse APK from [**Releases →**](https://github.com/ReSo7200/InstaEclipse/releases/latest)

---

### ✅ Root — LSPosed

> Requires [JingMatrix's LSPosed](https://github.com/JingMatrix/LSPosed/releases/latest)

**1. Install InstaEclipse**
Download and install the InstaEclipse APK.

**2. Enable the module**
Open **LSPosed Manager** → **Modules** → find **InstaEclipse** → enable it and scope it to **Instagram**.

**3. Restart Instagram**
Force stop Instagram, then reopen it.

**4. Open InstaEclipse**
Inside Instagram, **long-press the search icon** to open the InstaEclipse menu.

---

### 🟡 No Root — LSPatch

> Requires [JingMatrix's LSPatch](https://github.com/JingMatrix/LSPatch/releases/latest)

**1. Install InstaEclipse**
Download and install the InstaEclipse APK.

**2. Install LSPatch**
Download and install LSPatch (JingMatrix fork).

**3. Patch Instagram**
- Open LSPatch → tap **+** → select the Instagram APK (or the installed app)
- Choose **Local Patch Mode**
- Enable **"Inject loader dex"**
- Tap **Start Patch** and wait

**4. Install the patched APK**
Install the output APK and log in to Instagram.

**5. Enable the module**
Reopen LSPatch → **Manage** → find Instagram → **Modules** → enable **InstaEclipse**.

**6. Open InstaEclipse**
Inside Instagram, **long-press the search icon** to open the InstaEclipse menu.

---

## ❓ FAQ

**Module not working / features not applying?**
Disable and re-enable the module in LSPosed/LSPatch, then force stop and restart Instagram.

**Developer options labels look like numbers?**
This is obfuscation from Instagram's Stable build. Switch to a Beta or Alpha version from APKMirror.

**Distraction-Free enabled but content still shows?**
Force stop Instagram and clear its cache after enabling.

**Not working on the Google Play version?**
Download Instagram from [APKMirror](https://www.apkmirror.com/apk/instagram/instagram-instagram/) instead.

**Still stuck?**
Join the [Telegram group](https://t.me/instaEclipse_discussion) and ask — someone will help.

---

## 🗺️ Using the Features

Once InstaEclipse is installed and active, **long-press the search icon** inside Instagram to open the InstaEclipse menu. From there you can toggle any feature on or off without restarting.

For guides on specific features, tips, and video walkthroughs:

- 📢 **Announcements & updates** → [Telegram Channel](https://t.me/InstaEclipse)
- 💬 **Questions & community help** → [Telegram Discussion Group](https://t.me/instaEclipse_discussion)

---

## 👥 Contributors

<div align="center">

### Project Owner

<a href="https://github.com/ReSo7200">
  <img src="https://github.com/ReSo7200.png" width="80" alt="ReSo7200" style="border-radius:50%"/><br/>
  <b>ReSo7200</b>
</a>

<br/><br/>

### All Contributors

<a href="https://github.com/ReSo7200/InstaEclipse/graphs/contributors">
  <img src="https://contrib.rocks/image?repo=ReSo7200/InstaEclipse" alt="Contributors"/>
</a>

<sub>Made with <a href="https://contrib.rocks">contrib.rocks</a></sub>

<br/>

**Translation Contributors**
A big thank you to everyone who helped translate InstaEclipse into multiple languages — you make this accessible to users around the world.

</div>

---

## 🛠️ Built With

- [JingMatrix/LSPosed](https://github.com/JingMatrix/LSPosed) — Xposed framework foundation
- [LuckyPray/DexKit](https://github.com/LuckyPray/DexKit) — Dynamic DEX analysis for Instagram compatibility

---

## 🤝 Contributing

Contributions are welcome — whether it's a bug report, feature request, translation, or pull request.

- **Bug?** → [Open a bug report](https://github.com/ReSo7200/InstaEclipse/issues/new/choose)
- **Idea?** → [Submit a feature request](https://github.com/ReSo7200/InstaEclipse/issues/new/choose)
- **Code?** → Fork the repo and open a PR

---

<div align="center">
  <sub>Made with ❤️ by the InstaEclipse team</sub><br/>
  <sub>InstaEclipse is not affiliated with Meta or Instagram. See <a href="DISCLAIMER.md">Disclaimer</a>.</sub>
</div>
