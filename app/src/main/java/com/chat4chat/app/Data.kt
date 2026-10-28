package com.chat4chat.app

import android.net.Uri
import com.google.firebase.Timestamp
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.UUID

// Get a free key at https://developers.giphy.com and paste it here.
const val GIPHY_KEY = "PASTE_YOUR_GIPHY_API_KEY"

data class Room(val id: String, val icon: String, val name: String, val about: String)

val ROOMS = listOf(
    Room("anime", "🎌", "Anime & Comic Lovers", "Talk about anime and comics."),
    Room("art", "🎨", "Art & Design", "Share and talk about the art you’ve made."),
    Room("gf", "🎮", "Gaming & Films Lovers", "Talk about the films and games you love."),
    Room("mm", "🎬", "Movie Making", "Chat about the movie projects you’re working on."),
    Room("sport", "⚽", "Sport Lovers", "Talk about all things sport."),
    Room("fight", "🥊", "Fight Club", "MMA, boxing, karate, judo and more."),
    Room("dance", "💃", "Dance & Music Lovers", "Talk about dancing and music."),
    Room("music", "🎸", "Musicians", "Chat with fellow musicians."),
    Room("book", "📚", "Book Club", "Talk about books you’ve written or read."),
    Room("new", "👋", "Meeting New People", "Make new friends."),
    Room("hol", "🏖️", "Holidays", "Share holiday ideas and trips."),
    Room("any", "💬", "Anything In-Between", "Talk about anything you want."),
)

val EMOJIS = "😀 😂 🤣 😊 😍 😎 🥳 😢 😡 🤔 🙌 👏 👍 👎 🔥 ❤️ 💯 🎉 ✨ 🙏 💪 👀 😴 🤯 🥺 😈 👻 🤝 🎶 ⚡ 🌍 🍕".split(" ")

// Raise or lower this to change the minimum sign-up age (also change 18 in firestore.rules).
const val MIN_AGE = 18

data class Msg(
    val id: String = "", val room: String = "", val uid: String = "", val name: String = "", val photo: String = "",
    val kind: String = "text", val text: String = "", val url: String = "", val ts: Timestamp? = null,
)

data class Profile(val uid: String, val username: String, val photo: String)
data class Peer(val uid: String, val name: String, val photo: String)
data class Convo(
    val id: String = "", val members: List<String> = emptyList(),
    val memberNames: Map<String, String> = emptyMap(), val memberPhotos: Map<String, String> = emptyMap(),
    val lastText: String = "", val lastTs: Timestamp? = null,
) {
    fun other(me: String): Peer {
        val o = members.firstOrNull { it != me } ?: ""
        return Peer(o, memberNames[o] ?: "?", memberPhotos[o] ?: "")
    }
}

object Repo {
    private val db get() = FirebaseFirestore.getInstance()
    private val auth get() = FirebaseAuth.getInstance()

    fun uid() = auth.currentUser?.uid ?: ""
    fun logout() = auth.signOut()
    fun pairId(a: String, b: String) = listOf(a, b).sorted().joinToString("_")

    // Firebase Auth identity is built from the username, so people can log in with a username.
    // The real email is kept in a private document that only its owner can read.
    private fun authEmail(username: String) = "${username.trim().lowercase()}@users.chat4chat.app"
    private fun sha(s: String) = MessageDigest.getInstance("SHA-256")
        .digest(s.trim().lowercase().toByteArray()).joinToString("") { "%02x".format(it) }

    suspend fun currentProfile(): Profile? {
        val id = auth.currentUser?.uid ?: return null
        val d = db.collection("profiles").document(id).get().await()
        if (!d.exists()) return null
        return Profile(id, d.getString("username") ?: "", d.getString("photo") ?: "")
    }

    suspend fun signUp(username: String, email: String, pw: String, fullName: String, age: Int, photo: Uri?): Profile {
        val u = username.trim()
        val h = sha(email)
        if (db.collection("emailIndex").document(h).get().await().exists())
            throw IllegalStateException("That email is already registered.")
        try {
            auth.createUserWithEmailAndPassword(authEmail(u), pw).await()
        } catch (e: FirebaseAuthUserCollisionException) {
            throw IllegalStateException("That username is taken.")
        }
        val id = uid()
        try {
            val url = if (photo != null) runCatching { uploadTo("avatars/$id.jpg", photo) }.getOrDefault("") else ""
            val b = db.batch()
            b.set(db.collection("users").document(id),
                mapOf("username" to u, "fullName" to fullName.trim(), "email" to email.trim(), "age" to age))
            b.set(db.collection("profiles").document(id), mapOf("username" to u, "photo" to url))
            b.set(db.collection("emailIndex").document(h), mapOf("username" to u, "uid" to id))
            b.commit().await()
            return Profile(id, u, url)
        } catch (e: Exception) {
            auth.currentUser?.delete()
            throw e
        }
    }

    suspend fun login(idOrEmail: String, pw: String): Profile {
        var u = idOrEmail.trim()
        val wrong = IllegalStateException("Wrong username/email or password.")
        if (u.contains("@")) {
            u = db.collection("emailIndex").document(sha(u)).get().await().getString("username") ?: throw wrong
        }
        try {
            auth.signInWithEmailAndPassword(authEmail(u), pw).await()
        } catch (e: FirebaseAuthException) {
            throw wrong
        }
        return currentProfile() ?: throw IllegalStateException("Account not found.")
    }

    suspend fun requestReset(identifier: String) {
        FirebaseFunctions.getInstance().getHttpsCallable("requestReset")
            .call(mapOf("identifier" to identifier.trim())).await()
    }

    suspend fun confirmReset(identifier: String, code: String, newPassword: String) {
        try {
            FirebaseFunctions.getInstance().getHttpsCallable("confirmReset")
                .call(mapOf("identifier" to identifier.trim(), "code" to code.trim(), "newPassword" to newPassword)).await()
        } catch (e: FirebaseFunctionsException) {
            throw IllegalStateException(e.message ?: "That code is wrong or has expired.")
        }
    }

    suspend fun deleteAccount(username: String, pw: String) {
        val user = auth.currentUser ?: return
        try {
            user.reauthenticate(EmailAuthProvider.getCredential(authEmail(username), pw)).await()
        } catch (e: Exception) {
            throw IllegalStateException("Wrong password.")
        }
        val id = user.uid
        val email = db.collection("users").document(id).get().await().getString("email")
        val b = db.batch()
        if (email != null) b.delete(db.collection("emailIndex").document(sha(email)))
        b.delete(db.collection("users").document(id))
        b.delete(db.collection("profiles").document(id))
        b.delete(db.collection("presence").document(id))
        b.commit().await()
        runCatching { FirebaseStorage.getInstance().reference.child("avatars/$id.jpg").delete().await() }
        user.delete().await()
    }

    fun listenMessages(room: String, cb: (List<Msg>) -> Unit): ListenerRegistration =
        db.collection("messages").whereEqualTo("room", room)
            .orderBy("ts", Query.Direction.DESCENDING).limit(100)
            .addSnapshotListener { s, _ ->
                if (s != null) cb(s.documents.mapNotNull { d -> d.toObject(Msg::class.java)?.copy(id = d.id) }.reversed())
            }

    suspend fun send(room: String, me: Profile, kind: String, text: String = "", url: String = "") {
        db.collection("messages").add(
            mapOf("room" to room, "uid" to me.uid, "name" to me.username, "photo" to me.photo, "kind" to kind,
                "text" to text, "url" to url, "ts" to FieldValue.serverTimestamp())
        ).await()
    }

    // ---- private messages ----
    fun listenConvos(cb: (List<Convo>) -> Unit): ListenerRegistration =
        db.collection("dms").whereArrayContains("members", uid())
            .orderBy("lastTs", Query.Direction.DESCENDING)
            .addSnapshotListener { s, _ ->
                if (s != null) cb(s.documents.mapNotNull { d -> d.toObject(Convo::class.java)?.copy(id = d.id) })
            }

    fun listenDm(pair: String, cb: (List<Msg>) -> Unit): ListenerRegistration =
        db.collection("dms").document(pair).collection("messages")
            .orderBy("ts", Query.Direction.DESCENDING).limit(100)
            .addSnapshotListener { s, _ ->
                if (s != null) cb(s.documents.mapNotNull { d -> d.toObject(Msg::class.java)?.copy(id = d.id) }.reversed())
            }

    suspend fun sendDm(me: Profile, peer: Peer, kind: String, text: String = "", url: String = "") {
        val ref = db.collection("dms").document(pairId(me.uid, peer.uid))
        val preview = when (kind) { "text" -> text; "video" -> "🎥 Video"; "gif" -> "GIF"; else -> "📷 Photo" }
        val b = db.batch()
        b.set(ref, mapOf(
            "members" to listOf(me.uid, peer.uid),
            "memberNames" to mapOf(me.uid to me.username, peer.uid to peer.name),
            "memberPhotos" to mapOf(me.uid to me.photo, peer.uid to peer.photo),
            "lastText" to preview, "lastTs" to FieldValue.serverTimestamp()))
        b.set(ref.collection("messages").document(), mapOf(
            "uid" to me.uid, "name" to me.username, "photo" to me.photo, "kind" to kind,
            "text" to text, "url" to url, "ts" to FieldValue.serverTimestamp()))
        b.commit().await()
    }

    private suspend fun uploadTo(path: String, uri: Uri): String {
        val ref = FirebaseStorage.getInstance().reference.child(path)
        ref.putFile(uri).await()
        return ref.downloadUrl.await().toString()
    }

    suspend fun upload(uri: Uri, video: Boolean): String =
        uploadTo("uploads/${uid()}/${UUID.randomUUID()}${if (video) ".mp4" else ".jpg"}", uri)

    suspend fun report(m: Msg, by: String) {
        db.collection("reports").add(
            mapOf("messageId" to m.id, "room" to m.room, "offender" to m.uid, "reporter" to by,
                "text" to m.text, "url" to m.url, "ts" to FieldValue.serverTimestamp())
        ).await()
    }

    suspend fun heartbeat(room: String?) {
        db.collection("presence").document(uid())
            .set(mapOf("room" to room, "seen" to System.currentTimeMillis())).await()
    }

    fun listenPresence(cb: (List<Pair<String?, Long>>) -> Unit): ListenerRegistration =
        db.collection("presence").whereGreaterThan("seen", System.currentTimeMillis() - 300_000)
            .addSnapshotListener { s, _ ->
                if (s != null) cb(s.documents.map { it.getString("room") to (it.getLong("seen") ?: 0L) })
            }

    suspend fun giphy(q: String): List<String> = withContext(Dispatchers.IO) {
        val ep = if (q.isBlank()) "trending?" else "search?q=${URLEncoder.encode(q, "UTF-8")}&"
        val j = JSONObject(URL("https://api.giphy.com/v1/gifs/${ep}api_key=$GIPHY_KEY&limit=24&rating=pg-13").readText())
        val a = j.getJSONArray("data")
        List(a.length()) { a.getJSONObject(it).getJSONObject("images").getJSONObject("fixed_height").getString("url") }
    }
}
