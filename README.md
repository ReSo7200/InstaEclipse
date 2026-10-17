<div align="center">
  <img src="assets/logo.png" alt="InstaEclipse" width="120" />
  <h1>InstaEclipse</h1>
  <p><b>Instagram, the way you want it.</b><br/>
  An LSPosed module for privacy, downloads, a cleaner feed, smarter stories and DMs, and your own look. Everything is a switch inside Instagram.</p>

  <p>
    <a href="https://github.com/ReSo7200/InstaEclipse/releases/latest"><img alt="GitHub Release" src="https://img.shields.io/github/v/release/ReSo7200/InstaEclipse?style=for-the-badge&logo=github&color=1a1a2e&labelColor=0d0d0d"/></a>
    <a href="https://github.com/ReSo7200/InstaEclipse/stargazers"><img alt="Stars" src="https://img.shields.io/github/stars/ReSo7200/InstaEclipse?style=for-the-badge&logo=github&color=1a1a2e&labelColor=0d0d0d"/></a>
    <a href="https://github.com/ReSo7200/InstaEclipse/releases/latest"><img alt="Downloads" src="https://img.shields.io/github/downloads/ReSo7200/InstaEclipse/total?style=for-the-badge&logo=github&color=1a1a2e&labelColor=0d0d0d"/></a>
    <a href="https://t.me/InstaEclipse"><img alt="Telegram" src="https://img.shields.io/badge/Telegram-Channel-26A5E4?style=for-the-badge&logo=telegram&labelColor=0d0d0d"/></a>
    <a href="LICENSE"><img alt="License" src="https://img.shields.io/github/license/ReSo7200/InstaEclipse?style=for-the-badge&color=1a1a2e&labelColor=0d0d0d"/></a>
  </p>

  <p>
    <a href="#features">Features</a> •
    <a href="#compatibility">Compatibility</a> •
    <a href="#installation">Installation</a> •
    <a href="#using-it">Using it</a> •
    <a href="#faq">FAQ</a> •
    <a href="#contributors">Contributors</a>
  </p>
</div>

---

<p align="center">
  <a href="https://trendshift.io/repositories/14145?utm_source=trendshift-badge&amp;utm_medium=badge&amp;utm_campaign=badge-trendshift-14145" target="_blank" rel="noopener noreferrer">
    <img src="https://trendshift.io/api/badge/trendshift/repositories/14145/daily?language=Java" alt="ReSo7200%2FInstaEclipse | Trendshift" width="250" height="55"/>
  </a>
</p>

## What is it

InstaEclipse is a module for [LSPosed](https://github.com/JingMatrix/LSPosed) (rooted phones) and [LSPatch](https://github.com/JingMatrix/LSPatch) (no root). Long press the Explore (search) tab inside Instagram and the InstaEclipse menu slides up. Flip what you want, and most things apply right away.

It comes with a companion app too. That is where you see whether the module is running, read its logs, back up your settings, edit themes, and get help.

Under the hood, InstaEclipse uses [DexKit](https://github.com/LuckyPray/DexKit) to find what it needs inside Instagram every time Instagram updates, instead of relying on names that change with each build. So when Instagram ships a new version, most features keep working without waiting for a module update.

> InstaEclipse is a personal, educational project. It is not affiliated with Meta or Instagram. See the [Disclaimer](DISCLAIMER.md).

---

## Features

Everything here is a switch in the menu. Tap a section to open it.

<details>
<summary><b>👻 Ghost Mode</b>: browse without leaving footprints</summary>

<br/>

| Feature | What it does |
|---|---|
| Hide DM seen | Read messages without sending a read receipt |
| Read receipts for specific chats | Keep Hide DM seen on for everyone, but still send "Seen" in the chats you pick (set it from the chat's InstaEclipse menu) |
| Hide typing indicator | Type without the other person seeing the dots |
| Hide story views | Watch stories without appearing in the viewer list |
| Mark a story as seen | Send the view for one story on purpose, from the story menu |
| Hide live presence | Join lives without being counted |
| Bypass screenshot detection | Screenshot disappearing media without the other person being told |
| Allow screenshots in DMs | Take screenshots where Instagram blocks them |
| Hide view once opened | Open view once media without marking it as opened |
| Unlock view once and view twice | Replay view once media instead of it disappearing |
| Spoof last seen | Freeze your "active" status |
| Airplane mode | Appear offline: Instagram stops its live connection while you browse |
| Quick toggle | Turn Ghost Mode on and off from a ghost icon next to your inbox |

</details>

<details>
<summary><b>💬 Messages</b>: keep what matters in your chats</summary>

<br/>

| Feature | What it does |
|---|---|
| Keep disappearing messages | Stop ephemeral messages from deleting themselves |
| Log unsent messages | When someone unsends a message, keep a private copy, grouped per chat and named after it |
| Edit history | See every earlier version of a message that was edited |
| Chat export | Export a chat to a readable HTML page with replies, reactions, edit history and optional media |
| Jump to first message | Scroll a chat all the way back to its first message |
| Voice message downloads | Save voice messages, named by sender |
| Translate messages | Send a message to your translator app from its long press menu (under More) |
| Hide specific chats | Hide chats from your inbox and manage them later |
| Hide the Notes tray | Remove Notes from the top of your inbox |
| Hide Blend in chats | Remove the Blend button from the chat header |
| Hide "Create group" | Remove it from the share sheet |

All chat tools live in one InstaEclipse button in the chat header, next to the back arrow.

</details>

<details>
<summary><b>📖 Stories</b>: see everything a story holds</summary>

<br/>

| Feature | What it does |
|---|---|
| Story Info | Exact posted, expiry and edit times, music, location, hashtags, reshared from, original post or reel, posting app, alt text, size, countdowns, questions, Add Yours (exact joined count and top people), paid partnership and collaborators. Tap any line to copy it |
| Music links | Open the song in Spotify, YouTube Music or Apple Music, from Story Info, or from Media info on posts and reels |
| Poll, slider and quiz results | Results drawn as bars, your own answer marked, and the correct quiz answer in green |
| Copy story links | Copy the links from link stickers, or pick from a list when there are several |
| View story mentions | See every @mention at once. Tap to copy, hold to open the profile |
| Search your story viewers | A search box over your viewer list |
| Disable story auto swipe | Stories stop advancing on their own |
| Download a whole highlight | From the highlight's long press menu |

</details>

<details>
<summary><b>🎬 Reels</b>: watch on your terms</summary>

<br/>

| Feature | What it does |
|---|---|
| Force Reels quality | Pick a fixed quality (Auto, 360p to 1080p, or Max) |
| Playback speed | 0.5x to 3x for reels, stories and feed videos |
| Always show the seek bar | Scrub through any reel |
| Reels auto scroll | Move to the next reel when one ends |
| Lock Reels scrolling | Stop accidental swipes |
| Disable repost | Keep the repost button from reposting |

</details>

<details>
<summary><b>📥 Downloads and media</b>: save and share anything</summary>

<br/>

| Feature | What it does |
|---|---|
| Download posts, reels and stories | Single photos, full carousels, reels, and stories (your own included, with music) |
| Download profile pictures | Full size, from a long press on the profile picture |
| Audio only | Save just the audio of a video or reel as .m4a, including a post's music |
| Copy media link | Copy the direct link for a post, reel or a single carousel slide |
| Copy image | Copy a photo straight to the clipboard |
| Media info | Resolution, file size, the song with streaming links, and an option to open the file in another app |
| Download with InstaEclipse | Share a post or reel link from any app to download it |
| Story cache | Keep stories you watched for 24 hours, so you can save ones that expired |
| Save Instants | Long press an Instant to save it |
| Upload Instant from gallery | Send a picture from your gallery as an Instant |
| Max upload quality | Always upload at the highest quality |
| Download folder | Choose the folder, sort into a folder per account, and add the date to file names |

</details>

<details>
<summary><b>✨ Feed</b>: see the people you actually follow</summary>

<br/>

| Feature | What it does |
|---|---|
| Following only feed | Show only posts from accounts you follow |
| Hide suggestions | Remove suggested posts, reels and accounts |
| Hide Threads suggestions | Drop the Threads promos |
| Hide posts you already liked | Skip what you have seen |
| Hide community notes | Remove notes added under posts and reels |
| Hide Instagram Plus upsells | No more Plus prompts |
| Hide the story tray | Keep the feed, lose the story row |
| Disable Discover People | Remove the "people you may know" row |
| Always show full captions | No more "... more" |
| Exact dates | "17 Oct 2026 07:36" instead of "2h ago" |
| High resolution images | Load the sharpest version of every photo |
| Photo zoom | Long press a photo to zoom in |
| Disable video autoplay | Videos wait for a tap |

</details>

<details>
<summary><b>❤️ Likes, comments and profiles</b></summary>

<br/>

| Feature | What it does |
|---|---|
| Disable double tap to like | No accidental likes, in posts, comments and DMs |
| Undo a like | A few seconds to take a like back |
| Comment search | Search a post's comments, load them all, and export them |
| Copy comments and captions | Copy any comment, or a caption with or without hashtags |
| Translate | Translate captions, comments and chat messages with your own translator app |
| Save comment GIFs and images | Download the media in comments |
| "Follows you" label | See on any profile whether they follow you back |
| Follower toast | A quick message when you open a profile |
| Export followers and following | Save the whole list as CSV |
| Saved on your profile | A Saved button right on your profile |
| Lock Saved collections | Put a passcode on a collection |
| Hide the Threads button | Remove it from profiles |

</details>

<details>
<summary><b>🧭 Navigation</b>: arrange Instagram's tabs</summary>

<br/>

| Feature | What it does |
|---|---|
| Hide tabs | Hide Home, Reels, Create, Messages, Activity or Profile |
| Tab order | Put the bottom tabs in any order |
| Startup tab | Choose which tab Instagram opens on |
| Disable swipe to camera | Stop the camera from opening on a swipe |

</details>

<details>
<summary><b>🔒 Privacy and security</b></summary>

<br/>

| Feature | What it does |
|---|---|
| Lock the whole app | Passcode or fingerprint to open Instagram |
| Lock DMs | Lock just the inbox, and optionally lock it again every time you leave |
| Block ads and analytics | Drop sponsored posts and tracking calls |
| Disable tracking links | Strip only the tracking parts from links you share |
| Open links externally | Use your own browser instead of Instagram's |
| Custom share domain | Share links with a different domain |
| Remove Meta AI | Take Meta AI out of search, chats and reels |
| Hide onboarding prompts | Skip the nag screens |
| Location spoofing | Pick any place on a map; Instagram sees that location everywhere |

</details>

<details>
<summary><b>🎨 Appearance</b>: make it yours</summary>

<br/>

| Feature | What it does |
|---|---|
| Custom theme | Presets or your own colors for every part of the app, applied live |
| Light and dark themes | Every theme has both versions and follows your phone |
| Themed menus | InstaEclipse's own menus, toggles and dialogs use your theme's accent color |
| Custom font | Use any .ttf or .otf across the app |
| Custom emoji | Swap in another emoji font |

</details>

<details>
<summary><b>🧘 Distraction Free</b>: quiet the parts that pull you in</summary>

<br/>

| Feature | What it does |
|---|---|
| Turn off sections | Stories, Feed, Reels (or all Reels except in DMs), Explore and Comments, one by one |
| Extreme mode | Lock those choices in until you reinstall |

</details>

<details>
<summary><b>🛠️ Tools and developer options</b></summary>

<br/>

| Feature | What it does |
|---|---|
| Developer Options | Instagram's hidden internal panel, with config import, export and a known good default |
| Unlimited accounts | Add more accounts than Instagram allows |
| Notification fix | Repair notifications that stopped arriving |
| Auto clear cache | Clear Instagram's media cache once it gets too big |
| Backup and restore | All settings to a file and back |
| Feature toasts | See which features loaded when Instagram starts |

</details>

<details>
<summary><b>📱 Companion app</b></summary>

<br/>

| Feature | What it does |
|---|---|
| Home dashboard | Module status, Instagram version, quick launch and restart, and your pinned shortcuts |
| Feature search | Find any switch by name |
| Live logs | Hook status from Instagram and the app, no adb needed |
| Help | Frequently asked questions, setup links for LSPosed and LSPatch, and the community |
| 18 languages | English, Arabic, Azerbaijani, Chinese (Simplified and Traditional), French, German, Greek, Hebrew, Indonesian, Italian, Polish, Portuguese, Russian, Spanish, Swedish, Turkish and Vietnamese |

</details>

---

## Compatibility

| | |
|---|---|
| **Tested on** | Instagram `451.0.0.20.80` |
| **Get this version** | [Instagram 451.0.0.20.80 on APKMirror](https://www.apkmirror.com/apk/instagram/instagram-instagram/instagram-451-0-0-20-80-release/) |
| **Survives updates** | Yes, DexKit finds what it needs at runtime |
| **Android** | 9 and newer |

A big Instagram update can still knock a feature out until it is patched. The menu inside Instagram shows how many features are active (for example "62/62 active"); tap it to see which ones could not attach. If something breaks right after an update, check the [Telegram channel](https://t.me/InstaEclipse) first.

---

## Installation

> Get Instagram [451.0.0.20.80 from APKMirror](https://www.apkmirror.com/apk/instagram/instagram-instagram/instagram-451-0-0-20-80-release/) rather than the Play Store. The Play build is not always fully supported.

Download the latest InstaEclipse APK from [**Releases**](https://github.com/ReSo7200/InstaEclipse/releases/latest), then follow the path for your phone.

### Rooted, with LSPosed

> Needs [JingMatrix's LSPosed](https://github.com/JingMatrix/LSPosed/releases/latest).

1. **Install InstaEclipse** from the APK you downloaded.
2. **Enable the module.** In LSPosed Manager, open Modules, enable InstaEclipse and tick Instagram.
3. **Restart Instagram.** Force stop it and open it again.
4. **Open the menu.** Long press the Explore (search) tab.

> [!CAUTION]
> Using Hide My Applist? Do not hide InstaEclipse from Instagram. Instagram needs to see it, or the menu text goes missing and features stop working.

### No root, with LSPatch

> Needs [JingMatrix's LSPatch](https://github.com/JingMatrix/LSPatch/releases/latest).

1. **Install InstaEclipse** from the APK.
2. **Install LSPatch** (the JingMatrix fork).
3. **Patch Instagram.** In LSPatch, tap **+**, pick the Instagram APK or the installed app, choose **Local Patch Mode**, turn on **Inject loader dex**, then tap **Start Patch**.
4. **Install the patched APK** and log in.
5. **Enable the module.** In LSPatch, open Manage, pick Instagram, open Modules and enable InstaEclipse.
6. **Open the menu.** Long press the Explore (search) tab.

There is also a [step by step guide](https://t.me/instaEclipse_discussion/158/5149) in the community.

---

## Using it

- **Open the menu:** long press the Explore (search) tab inside Instagram.
- **Find things:** sections are grouped into Appearance, Privacy, Media, Social and Tools. Each shows how many of its switches are on.
- **Chat tools:** open any chat and tap the InstaEclipse button next to the back arrow.
- **Story tools:** open a story and tap its ⋯ menu for Story Info, links, mentions and results.
- **Back:** the phone's back button steps back one page at a time.

Updates and tips:

- 📢 Announcements: [Telegram channel](https://t.me/InstaEclipse)
- 💬 Questions and help: [Telegram group](https://t.me/instaEclipse_discussion)

---

## FAQ

**A feature is not doing anything.**
Disable and enable the module again in LSPosed or LSPatch, then force stop and reopen Instagram.

**The text in the InstaEclipse menus is missing.**
Turn off Hide My Applist (HMA) for Instagram. InstaEclipse reads its text from its own app, and HMA hides it.

**Some features will not work even when they are on.**
Instagram's own config can block them. Open Developer Options and choose Restore Default Config. The same config is also on [Telegram](https://t.me/InstaEclipse/52).

**Developer Options labels show up as numbers.**
That happens on Instagram's Stable build. Use a Beta or Alpha build from APKMirror.

**I turned on Distraction Free but the content is still there.**
Force stop Instagram and clear its cache, then open it again.

**It does not work on the Play Store version.**
Install [Instagram 451.0.0.20.80 from APKMirror](https://www.apkmirror.com/apk/instagram/instagram-instagram/instagram-451-0-0-20-80-release/) instead.

**Still stuck?**
Ask in the [Telegram group](https://t.me/instaEclipse_discussion). Someone will help.

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

**Thank you** to [b4lol](https://github.com/b4lol) for the extras, security and performance work in this release, to [izadiegizabal](https://github.com/izadiegizabal) for own story downloads, and to everyone who translated InstaEclipse into their language.

</div>

---

## Built with

- [JingMatrix/LSPosed](https://github.com/JingMatrix/LSPosed), the Xposed framework it runs on
- [JingMatrix/LSPatch](https://github.com/JingMatrix/LSPatch), for phones without root
- [LuckyPray/DexKit](https://github.com/LuckyPray/DexKit), which finds Instagram's code at runtime

---

## Contributing

Pull requests, bug reports, ideas and translations are all welcome.

- Found a bug? [Open a bug report](https://github.com/ReSo7200/InstaEclipse/issues/new/choose)
- Have an idea? [Request a feature](https://github.com/ReSo7200/InstaEclipse/issues/new/choose)
- Want to build something? Fork the repo and open a pull request

---

<div align="center">
  <sub>Made with care by the InstaEclipse community.</sub><br/>
  <sub>Not affiliated with Meta or Instagram. See the <a href="DISCLAIMER.md">Disclaimer</a>.</sub>
</div>
