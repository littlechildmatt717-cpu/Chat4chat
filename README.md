# Chat4Chat: Android app (Kotlin, Jetpack Compose, Firebase)

Sign up / log in (username or email + password, profile picture), 12 chat rooms with live head-count badges,
private messages (tap a name in a room), emojis, GIPHY GIFs, photo/video posting, report/block, delete account.

## 1. Firebase setup (about 10 minutes)
1. console.firebase.google.com: create a project, then add an Android app with package name `com.chat4chat.app`.
2. Download `google-services.json` and put it in the `app/` folder.
3. Build > Authentication > Sign-in method: enable **Email/Password**.
4. Build > Firestore Database: create it. Open Rules and paste in `firestore.rules`. Then create the two indexes in
   `firestore.indexes.json` (or run the app and tap the index links Logcat shows when a screen is empty).
5. Build > Storage: create it. Open Rules and paste in `storage.rules`.

6. **Forgot-password emails** (needs the Firebase Blaze pay-as-you-go plan; normal use stays inside the free allowance):
   - Install the Firebase extension **Trigger Email from Firestore**: set the collection to `mail` and add an SMTP
     connection for sending (for example a free Brevo account, or Gmail with an app password) and a "from" address.
   - Install Node.js and the Firebase tools: `npm install -g firebase-tools`, then `firebase login`, then
     `firebase use --add` and pick your project.
   - In this folder run `firebase deploy`. That publishes the rules, indexes and both functions in one go.
   - How it works: tapping "Forgot password?" emails a 6-digit code to the email the person signed up with. The code
     expires after 15 minutes and allows 5 wrong tries. The app says the same thing whether or not the account exists.

## 2. GIPHY
Get a key at developers.giphy.com and paste it into `GIPHY_KEY` in `Data.kt`. Before launch, apply for a
production key (beta keys are rate-limited).

## 3. Run it
Open this folder in Android Studio, let it sync (accept the Gradle/AGP upgrade prompts if shown), press Run.

## 4. Publish on Google Play
1. Check Play Console for the current required target API level. If it is higher than 35, raise
   `compileSdk` and `targetSdk` (Android Studio: Tools > AGP Upgrade Assistant).
2. Build > Generate Signed App Bundle (.aab). Create a keystore and back it up safely. If you lose it, you can’t update the app.
3. Play Console (one-off $25 developer fee): create app, upload the .aab, fill in the store listing
   (use `play-store-icon-512.png`, plus a 1024x500 feature graphic and phone screenshots).
4. Required forms: privacy policy URL, Data safety (you collect: name, user ID, messages, photos/videos),
   content rating, and the user-generated-content declaration. Reporting and blocking are already built in; you must
   also review the `reports` collection in Firestore and remove bad content.
5. New personal developer accounts must usually run a closed test with testers for 14 days before production
   access. Check the Console for current rules.

## How accounts work
- Only the **username** and **photo** are public. Full name, email and age sit in a private document only that person can read.
- People can log in with username or email. The username is also the Firebase sign-in identity (as a hidden address).
- **Forgot password** works through the two Cloud Functions in `functions/` (see step 6 below).
- Minimum age is `MIN_AGE = 18` in `Data.kt`, repeated as `18` in `firestore.rules`. Because strangers can message each other, 18+ is the safer choice for Play review.
- Google Play requires an in-app way to delete the account (included: tap your avatar > Delete account) and a web page or link
  explaining how to request deletion in the Data safety form.
