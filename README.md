# Status Guard

A personal Android app (Android 12+) that blocks WhatsApp Status / the Updates tab outside the
times and daily limit you set, closes WhatsApp during focus times (e.g. morning devotion) after a
short quick check, and limits TikTok, YouTube Shorts, Instagram and any apps you add to one shared
daily pot of entertainment time. Works with WhatsApp and WhatsApp Business (English).

## Rules
- **Daily limit** (default 30 min): time only counts while status is on screen. Resets at midnight.
- **Allowed times** (default `19:00-21:00`): outside these, status is blocked even with minutes left.
  Several are allowed: `12:30-13:00, 19:00-21:00`.
- **Relaxed days** (default Saturday): allowed times are ignored and the relaxed limit (default 60 min) applies.
- **Lockout** (default 5 min): when blocked, you are sent home and WhatsApp can't be opened until it ends.
- All settings are behind a password. There is no reset; uninstalling is the only way out.

When blocked, a calm lockout screen with a random encouragement covers WhatsApp
(edit the list in `Encouragements.kt`).

## Strict alarms
Home screen → **Strict alarms** → **Alarms**. Each alarm has a name, time, days and a barcode.
It rings at full volume (and keeps forcing the volume up) until that barcode is scanned. After the
first scan the music drops to about a third of full volume while you do the tasks, then goes silent for
the awake watch. Stop mid-task (no push-up or jog step for 60 seconds, or the pledge not typed within
2 minutes) and it goes back to full volume with vibration until you carry on. Walking has its own time limit.

**There is no stop button.** A real alarm can only be finished. The only escape is switching the
phone off (or force-stopping the app): that counts in the report, sets the morning streak to 0 and
emails your accountability partners. Only **test** rings have a "Stop test" link.
Editing or deleting an alarm needs the password; adding one is free. **Switch off for tomorrow**
(skips only the next ring, can be undone) needs no password, but it shows in the weekly report.
Alarms can't be edited while one is ringing.

**No way out through the phone.** While an alarm is running (from the first ring to the end of the
awake watch): pulling down the notification bar closes it again straight away (its "Active apps → Stop"
would kill the alarm), Android Settings sends you back to the alarm (that's where permissions are
removed and apps force-stopped), and the power menu closes before "Power off" can be tapped. The lock
screen and its PIN pad are left alone, since you unlock to scan. Holding power + volume down for
about 10 seconds forces a restart, which no app can block: it still counts as switched off.

**Accountability partner.** The top of each alarm's settings asks you to add one: the people in
the auto-email settings get an email when you go back to sleep (levels 3 and 4) or switch the phone off.

### Levels
1. **Wake**: scan the code.
2. **Move**: scan, walk outside.
3. **Rise**: scan, walk outside, push-ups, type today's pledge.
4. **Beast**: scan, walk outside, push-ups, jog, type today's pledge.

**Walk outside.** Walk the set steps within the minutes allowed (default 5). Too slow and it rings
harder (emergency beeps, blinking flashlight) until you finish. Steps only count if they look like
walking: flipping the phone over or shaking it fast is ignored, with a message on screen.
Optional **outside checkpoint**, chosen per alarm:
- **None**: just count the steps.
- **Barcode or QR code** outside: scan it after the steps. A printed QR code taped up is the most reliable.
- **Words on a sign** (e.g. the paper at the tap): at setup the camera reads the sign and saves its words;
  at alarm time it must read the same words again (about 70% of them). The flashlight comes on, so it works in the dark.

**Push-ups.** Phone face-up, flat and still on the floor under your chest. A push-up only counts if
the phone stays flat and still, you stay down long enough (a quick hand wave is too short) and they
aren't faster than real push-ups. Each one beeps and turns the music down.
**Camera push-ups (try it)** is a switch per alarm: the front camera watches your face get bigger
(down: 40% bigger than at the top) and smaller (up: back to 3/4 of the bottom size), measured per push-up, and the page turns white at full brightness to light your face.
Nothing is saved. If no face is seen for 15 seconds (too dark), it switches to the sensor with 50% more push-ups.

**Jog (level 4).** Counted from the bounces the motion sensor feels: only hard, jogging-pace
bounces count (walking pace shows "Faster!"). The screen shows the live bounce strength. Default 100.

**Pledge (levels 3 and 4).** Type the sentence shown, e.g. "I have not cheated. I am awake and I
chose to rise." A different one each day. Capitals and punctuation don't matter.

If steps can't be counted ("Physical activity" switched off), they are skipped but it counts as a
shortcut (morning streak back to 0).

### Awake watch (levels 2 to 4)
After the tasks, the alarm watches you for the minutes you set (at least 15, default 30) and asks
"Still awake?" at random times, every 2 to 6 minutes. Each check is gentle: a short buzz and a soft
beep, then again at 30 seconds, with an honesty reminder on screen. Tap **I'm awake** within 60 seconds.
The alarm is only finished when the watch is over.

Miss a check and you went back to sleep:
- the alarm rings again and everything starts over (scan, tasks, then a new watch);
- every streak is cut in half (once per morning), and that morning doesn't add to the morning streak;
- it's counted in the report as a day you went back to sleep;
- **levels 3 and 4:** your accountability partners get an email.

**Scanning** uses the app's own camera screen with the **flashlight on by default** (button to switch it
off), for codes and signs alike, so it works in the dark or without electricity.

**Practice a step.** Under **Add alarm**, each step has its own practice button: scan code, walk,
checkpoint, jog, push-ups, camera push-ups, pledge and awake check. It uses your alarms' settings and
nothing counts (no streaks, report or emails). "Stop test" ends it early.

Open the alarms screen once while online so the code, face and sign readers download
(Google Play services); after that they work offline.

## Social media
Home screen → **Social media** → **Social media settings** (password). Off until you tick
**Block social media** and save.

- **Apps**: TikTok (whole app), YouTube **Shorts** (normal YouTube stays free) and Instagram (whole app).
  **Choose other apps…** adds any other app (Facebook, X, a game…): whole app, same pot, same times,
  same force open, its own line in the report.
- **One shared pot**: one daily limit (default 30 min) for all of them together, counted only while
  watching. Resets at midnight.
- **Allowed times** (default `19:00-21:00`, several allowed). Outside them, or once the pot is used up,
  the apps are covered and every try is counted per app.
- **Force open**: the only way in when blocked. Opens that app for 5 minutes, then it closes again.
  It **resets the social streak** (and breaks today's general streak) and shows in the report.
- **Social streak**: days since you set it up or since the last force open.
- The report (screen, PDF and email) shows time per app and per day, tries per app, force opens,
  days the pot was used up, the hour with most tries, and all four streaks.

If Shorts detection misses, `adb logcat -s StatusGuard` shows `social: shorts watching=…`.
Detection lives in `ShortsDetector` in `app/src/main/java/com/personal/statusguard/Social.kt`.

## Home page
Sections: **My streak**, **Today** (phone time, blocked tries, force opens), **WhatsApp** (status and focus times),
**Social media**, **Strict alarms**, **Sleep time**, **My words** and **Account**.
Settings marked with a lock need the password.

## Night screen (sleep time)
From bedtime to wake-up the phone shows a dim, dark night screen: clock, time slept so far tonight,
wake-up time, a good-night quote (sometimes one of yours) and the apps you can still use.

- **Still works**: Phone (and incoming/ongoing calls), Clock, WhatsApp and WhatsApp Business calls,
  Status Guard itself, the keyboard, and any extra apps you add in Sleep settings → **Apps you can
  still use**. Everything else, including the home screen and Android Settings, is covered.
- Turning the screen on at night shows the night screen straight away, over the lock screen.
  It never covers a ringing or ongoing call, or a ringing alarm.
- **Sleep counter**: time with the screen off during sleep time. Touching the phone pauses it.
  Each night is saved under the morning it ended.
- Opening a covered app counts as a sleep-time try (the home screen doesn't).
- **Music stops at bedtime.** Anything playing (Spotify, YouTube, Boomplay…) is paused, and so is
  anything started later. If an app keeps playing, Status Guard takes the audio focus, then mutes
  media until wake-up (unmuted again in the morning; the alarm has its own volume).
- **Power menu** closes straight away during sleep time.
- **Force bypass**: opens the whole phone for 5 minutes, then the night screen returns. It resets the
  sleep streak and breaks today's general streak; other streaks aren't touched. Counted in the report.
- The report shows sleep per night, the average, the best night, tries and bypasses.

## Streak
The big number is the **general streak**: days in a row where every streak held. Any one slip
breaks it for that day. Under it are the four area streaks, and **each slip only costs its own one**:

| Streak | Counts | Reset by |
|---|---|---|
| WhatsApp | days without opening anyway | "Open anyway" during a focus time |
| Social | days since the last force open | a social media force open |
| Mornings | alarms finished in a row | going back to sleep, a shortcut, switching the phone off |
| Sleep | nights without forcing the phone open | the night screen's force bypass |

**Blocked tries don't break streaks.** Trying to open status outside your rules, WhatsApp during a
lockout or after the quick check, social apps outside your times, or apps at night just blocks you
with a quote and is counted in the report.

## WhatsApp focus times
WhatsApp card → **Focus times** (password). Add as many as you like, e.g. *Morning devotion*
05:30–07:00, *Lunch* 13:00–14:00. Each has a name, start, end, days and a **quick check** (default 10 min).

- During a focus time WhatsApp works for the quick check, counted only while WhatsApp is on screen
  (so you can check twice for 5 minutes). Then it closes until the focus time ends. Calls always come through.
- **Open anyway…**: wait (10 s the first time, 20 s the second, 30 s…), then WhatsApp opens for
  2 minutes and closes again. Each one resets the WhatsApp streak and breaks today's general streak.
  The minutes and the wait are set on the same screen.
- Status rules still apply on top. The report shows focus-time minutes, tries and opens anyway.

## Account
Home screen → **Account**: your name (used in partner emails) and the password. One password protects every setting (status, social media, sleep,
alarms, auto-email). Changing it needs the current one. There is no reset.

## Look
Icons are Material vector icons in `res/drawable/ic_*.xml`. Spacing and text sizes come from
`res/values/dimens.xml` (spacing 4 · 8 · 12 · 16 · 24 · 32) and the shared styles in `styles.xml`
(`Card`, `Heading` with an icon, `LinkButton`, `LockedLinkButton`, ...).

- **Pause before status**: even when allowed, a "Breathe…" screen counts down (seconds set in
  status settings, 0 = off). "Never mind" goes to Chats and counts as a win.
- **Attempt counter**: every blocked try is counted and shown ("your 3rd try today").
- **Sleep time** (password): several schedules, each with bedtime, wake time and nights.
  See "Night screen" below.
- **Bedtime reminder**: 15 minutes before bedtime, shows tomorrow's alarm and asks for your
  intention, which appears when the morning alarm is done.
- **My words**: your whys and quotes, mixed into every lockout/pause/sleep/alarm screen.
- **Weekly report**: phone time per day (needs "Usage access"), top apps, unlocks, status minutes,
  attempts, mornings, sleep, insights and a grade. Notifies weekly (default Sunday 19:00).
  **Save PDF to Downloads** or **Share PDF** (WhatsApp, email, anything).
  - **Highlights** first: up to 3 wins and 3 things to watch out for this week.
  - **My mornings**, from the alarm log (every real alarm is logged): how many minutes from the alarm
    to getting up, steps walked outside, checkpoints, push-ups (and how many were watched by the camera
    or counted in the dark), jogging steps, pledges, awake checks answered, and how each morning ended
    (done, slept again, phone switched off). Plus your best morning. The PDF has a row per morning.

## Auto-email report
Weekly report → **Auto-email settings** (password). Every week the report (message + PDF) is
emailed through Gmail to the people you choose. The email text is short (grade and streaks);
the details are in the attached PDF. The report is written in the first person, as if you're
telling someone about your week ("This week I slept…"), with plain facts and no pep talk.

**Offline?** The email waits in a queue and goes out as soon as there's internet, even after a
restart. If Gmail fails it tries again (up to 10 times, waiting longer each time). A late email
still reports the week it was meant for, and a waiting week is never dropped when the next one is
queued. The auto-email settings show the latest status ("Queued…", "Sent…", or "Not sent yet…").

The same people are your **accountability partners**: fill in **Your name** and keep
**Email them when I go back to sleep** ticked. When a level 3 or 4 alarm finds you asleep again,
they get a short email saying what time it woke you and when you stopped answering. It's sent as soon
as the phone has internet. This works even if the weekly auto-send is off, as long as Gmail is set up.

1. Use a Gmail account (a separate one just for this is best) with 2-Step Verification on.
2. Create an app password at myaccount.google.com/apppasswords (name it "Status Guard").
3. Fill in the **Gmail address**, the 16-letter **App password** (your normal password won't work),
   **Send to** (several addresses separated by commas), and the **Day** and **Time**.
4. Tick **Send automatically every week**, then **Save and send a test now** and check the
   inboxes (and spam).

If there's no internet at send time it waits and retries; after 5 failures you get a notification.
The result of the last send is shown at the bottom of the settings screen.

## Build
```
./gradlew assembleDebug
```
The APK is at `app/build/outputs/apk/debug/app-debug.apk`.

## Install
1. Copy the APK to the phone and open it (allow installing from your file manager when asked),
   or with USB debugging on: `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
2. Android 13+: Settings → Apps → Status Guard → ⋮ → **Allow restricted settings**.
3. Open Status Guard → **Turn on in Accessibility settings** → Status Guard → On.
4. Tap **Set name & password** at the bottom of the home screen: add your name, and let your friend choose the password.
5. Open **Status settings** (and the other settings) and adjust the rules.

## If detection misses something
WhatsApp changes its layout sometimes. Watch what the app sees with:
```
adb logcat -s StatusGuard
```
Detection lives in `app/src/main/java/com/personal/statusguard/StatusDetector.kt`.
