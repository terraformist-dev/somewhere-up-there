# Somewhere Up There

A compass for the Nothing Phone (3) that points to the International Space Station and counts
down to the next time you can see it go over.

It lives in three places:

- **Home-screen widget**, in two sizes. It points to where the ISS will rise for your next
  visible pass and counts down to it. On the widget, the top is north, like a map.
- **The app**: a live compass to wherever the ISS is right now, how far away it is, the
  countdown, and the next three passes. The button turns on a reminder 5 minutes before each pass.
- **Glyph Toy**: on the Glyph Matrix on the back. The dots on the edge point at the ISS.
  Long-press the Glyph Button to see the time to go.

As a pass gets closer, everything changes:

- **Final hour**: everything points to where it will rise. On the Glyph Matrix, the outer ring
  fills up like a clock.
- **Overhead**: the compass turns into the sky. The middle is straight up, the edge is the
  horizon, and the station crosses it. In the app the sky turns as you turn. It says **LOOK UP**.
- **Quiet spell**: sometimes nothing is visible for days. It tells you how many.

A pass counts as visible when the ISS is at least 10° up, lit by the Sun, and you're in the dark.
That's why passes come around dawn and dusk.

## Getting the app onto your phone

Every push to `main` builds the app in the cloud (GitHub Actions) and publishes it as a release.

1. On your phone, open **github.com/terraformist-dev/somewhere-up-there/releases/latest**.
2. Tap **somewhere-up-there.apk** to download it, then open it to install.
   Android may ask you to allow installs from your browser the first time.
3. New builds install over the old one.

## Setting it up

- Open the app once and allow location. A rough location is enough.
- Add the widget: long-press the home screen, then Widgets, then Somewhere Up There.
- Add the Glyph Toy: Settings, then Glyph Interface, then Glyph Toys.
- For reminders, tap **REMIND ME 5 MIN BEFORE** and allow notifications.

## Where the data comes from

The ISS's orbit comes from [CelesTrak](https://celestrak.org). Each build packs in that day's
orbit, and the app downloads a fresh one about twice a day. Everything else, including where it is
and when it passes over you, is worked out on the phone, so it keeps working offline.

Made by a fan. Nothing to do with Nothing, or NASA.
