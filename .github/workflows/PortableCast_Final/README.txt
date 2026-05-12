╔══════════════════════════════════════════════════════════╗
║           PortableCast — Wireless Portable Monitor       ║
║   Samsung A26 acts as a Miracast display for Windows     ║
╚══════════════════════════════════════════════════════════╝

HOW IT WORKS
────────────
1. App broadcasts your phone as a wireless display via Wi-Fi Direct
2. Windows Win+K sees your phone like a TV/monitor
3. You click your phone's name in the Win+K panel
4. Windows streams its screen to your phone over Miracast (H.264)
5. Your phone shows the PC screen live — like a portable monitor!

No extra PC software needed. Uses Windows' built-in Cast.


SETUP (ONE TIME)
────────────────

Step 1 — Install Android Studio
  Download: https://developer.android.com/studio

Step 2 — Open the project
  Android Studio → File → Open → select the "android" folder

Step 3 — Wait for Gradle sync (2–5 minutes)

Step 4 — Enable Developer Mode on Samsung A26
  Settings → About phone → Software information
  Tap "Build number" 7 times
  → "Developer mode enabled!"

Step 5 — Enable USB Debugging
  Settings → Developer options → USB debugging → ON

Step 6 — Connect A26 to PC with USB cable
  Allow the connection on your phone when asked

Step 7 — Build & Install
  Click the green ▶ Run button in Android Studio
  Select your Samsung A26 from the device list
  App installs automatically


DAILY USE (4 STEPS)
───────────────────

1. Open PortableCast on your Samsung A26
   Tap "📡 Start Broadcasting"
   Allow permissions when asked

2. On your Windows PC:
   Press  Win + K
   A panel appears on the right side of screen

3. Wait a few seconds — your phone name appears in the list
   (e.g. "Samsung Galaxy A26")
   Click it!

4. Windows asks "Do you want to allow projection?"
   Click YES on your phone if asked
   → Your PC screen appears on your phone! 🎉


TIPS
────
• Both devices must be on the same Wi-Fi network
• Keep the phone screen on (don't let it sleep)
• For best quality: stay within 5 meters of your Wi-Fi router
• To disconnect: press Win+K again → Disconnect
  OR tap "Stop Broadcasting" in the app


TROUBLESHOOTING
───────────────

Phone not appearing in Win+K list:
  → Make sure Wi-Fi is ON (not just mobile data)
  → Allow "Location" permission in the app
  → Toggle Wi-Fi off and back on on the phone
  → Wait 10–15 seconds after tapping Start

"Connection failed" on Windows:
  → Stop and restart broadcasting
  → On PC: Win+K → Disconnect → reconnect
  → Restart Wi-Fi on both devices

Black screen after connecting:
  → Swipe down on phone, tap the PortableCast notification
  → This brings the SurfaceView back to front

Very laggy:
  → Move closer to Wi-Fi router
  → Close other apps on the phone
  → Lower Windows display resolution temporarily


PROJECT FILES
─────────────
android/
  app/src/main/
    AndroidManifest.xml          ← Permissions & components
    java/com/phonecast/miracast/
      MainActivity.kt            ← UI & permission handling
      MiracastService.kt         ← Wi-Fi Direct + RTSP + H.264 decoder
    res/layout/
      activity_main.xml          ← Screen layout
  app/build.gradle               ← Build config
