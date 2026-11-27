// Forgot-password for Chat4Chat.
// 1) requestReset: emails a 6-digit code to the email the person signed up with.
// 2) confirmReset: checks the code and sets the new password.
// Codes expire after 15 minutes, allow 5 wrong tries, and are stored only as a hash.
const { onCall, HttpsError } = require("firebase-functions/v2/https");
const admin = require("firebase-admin");
const crypto = require("crypto");

admin.initializeApp();
const db = admin.firestore();

const sha = (s) => crypto.createHash("sha256").update(String(s).trim().toLowerCase()).digest("hex");
const hashCode = (uid, code) => crypto.createHash("sha256").update(uid + ":" + code).digest("hex");

// Accepts a username or an email, returns the user's uid (or null).
async function findUid(identifier) {
  const v = String(identifier || "").trim();
  if (!v) return null;
  if (v.includes("@")) {
    const d = await db.collection("emailIndex").doc(sha(v)).get();
    return d.exists ? d.data().uid : null;
  }
  if (!/^[A-Za-z0-9_]{3,20}$/.test(v)) return null;
  try {
    return (await admin.auth().getUserByEmail(`${v.toLowerCase()}@users.chat4chat.app`)).uid;
  } catch (e) {
    return null;
  }
}

exports.requestReset = onCall(async (req) => {
  const uid = await findUid(req.data && req.data.identifier);
  if (uid) {
    const ref = db.collection("resetCodes").doc(uid);
    const old = await ref.get();
    const tooSoon = old.exists && Date.now() - old.data().sentAt < 60 * 1000;
    if (!tooSoon) {
      const user = await db.collection("users").doc(uid).get();
      const email = user.exists ? user.data().email : null;
      if (email) {
        const code = String(crypto.randomInt(0, 1000000)).padStart(6, "0");
        await ref.set({
          hash: hashCode(uid, code),
          sentAt: Date.now(),
          expires: Date.now() + 15 * 60 * 1000,
          tries: 0,
        });
        // The "Trigger Email from Firestore" extension sends anything added to `mail`.
        await db.collection("mail").add({
          to: email,
          message: {
            subject: "Your Chat4Chat password reset code",
            text: `Your Chat4Chat password reset code is ${code}. It expires in 15 minutes. If you didn’t ask for this, you can ignore this email.`,
          },
        });
      }
    }
  }
  // Always the same answer, so nobody can use this to find out who has an account.
  return { ok: true };
});

exports.confirmReset = onCall(async (req) => {
  const { identifier, code, newPassword } = req.data || {};
  if (typeof newPassword !== "string" || newPassword.length < 6) {
    throw new HttpsError("invalid-argument", "Password needs at least 6 characters.");
  }
  const bad = new HttpsError("permission-denied", "That code is wrong or has expired.");
  const uid = await findUid(identifier);
  if (!uid) throw bad;
  const ref = db.collection("resetCodes").doc(uid);
  const snap = await ref.get();
  if (!snap.exists) throw bad;
  const d = snap.data();
  if (Date.now() > d.expires || d.tries >= 5) {
    await ref.delete();
    throw bad;
  }
  if (d.hash !== hashCode(uid, String(code || "").trim())) {
    await ref.update({ tries: d.tries + 1 });
    throw bad;
  }
  await admin.auth().updateUser(uid, { password: newPassword });
  await admin.auth().revokeRefreshTokens(uid);
  await ref.delete();
  return { ok: true };
});
