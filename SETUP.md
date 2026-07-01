# IdeloChat — Production Integration & Deployment Guide

IdeloChat is a secure, high-fidelity real-time messaging, voice, and video calling application built with **Jetpack Compose**, **Kotlin**, **Firebase**, and **WebRTC**.

The application has been designed with a **Dual-Mode state engine**. If a valid Firebase configuration (`google-services.json`) is not detected at compile-time or initialization, the application seamlessly activates an **Interactive High-Fidelity Local Sandbox Mode**. This mode launches active simulation bots, realistic typing states, seen tick markers, and interactive voice/video calls displaying real-time local camera feeds so the application can be fully tested on standard offline development emulators right out of the box!

---

## 1. Firebase Backend Provisioning

To migrate from the local offline sandbox to your live production cloud, complete these steps:

### A. Create Firebase Project
1. Open the [Firebase Console](https://console.firebase.google.com/).
2. Click **Add Project** and name it `IdeloChat`.
3. Enable **Google Analytics** for the project (optional but recommended for user retention analytics).

### B. Register Android Application
1. Click the **Android Icon** in the center of the project overview page.
2. Enter your precise package `applicationId`:
   ```
   com.aistudio.idelochat.wquymz
   ```
3. Enter your SHA-1 signing key (strictly required for Firebase Authentication and phone-auth):
   - In Android Studio, open the right-side **Gradle** tab, expand `:app` -> `Tasks` -> `android` -> double-click `signingReport`.
   - Copy the SHA-1 fingerprint of the debug/release keys and paste it in the Firebase console.
4. Click **Register App**.
5. Download the generated `google-services.json` file.
6. Copy `google-services.json` into the `/app` directory of this project in Android Studio.

---

## 2. Firebase Security & Database Configuration

Enable these services in the Firebase Console:

### A. Firebase Authentication
1. Go to **Build** -> **Authentication** -> click **Get Started**.
2. Under the **Sign-in method** tab, enable **Email/Password**.

### B. Firebase Realtime Database
1. Go to **Build** -> **Realtime Database** -> click **Create Database**.
2. Select your preferred database location (e.g. United States or Europe) and start in **Locked Mode**.
3. Under the **Rules** tab, paste the following security rules to protect private chats and user telemetry:

```json
{
  "rules": {
    "users": {
      ".read": "auth != null",
      "$uid": {
        ".write": "auth != null && auth.uid == $uid"
      }
    },
    "chats": {
      "$chatId": {
        // Only allow users who are part of this 1-to-1 conversation to read or write messages
        ".read": "auth != null && ($chatId.contains(auth.uid) || $chatId == 'me')",
        ".write": "auth != null && ($chatId.contains(auth.uid) || $chatId == 'me')",
        "$messageId": {
          ".validate": "newData.hasChildren(['messageId', 'senderId', 'receiverId', 'message', 'timestamp'])"
        }
      }
    },
    "calls": {
      ".read": "auth != null",
      "$callId": {
        ".write": "auth != null && (newData.child('callerId').val() == auth.uid || newData.child('receiverId').val() == auth.uid || !newData.exists())"
      }
    }
  }
}
```

### C. Firebase Storage (Media Messages)
1. Go to **Build** -> **Storage** -> click **Get Started**.
2. Start in **Production Mode**.
3. In the **Rules** tab, apply rules restricting read/write permission to authenticated users:

```javascript
rules_version = '2';
service firebase.storage {
  match /b/{bucket}/o {
    match /profile_images/{userId} {
      allow read: if request.auth != null;
      allow write: if request.auth != null && request.auth.uid == userId;
    }
    match /chat_media/{chatId}/{mediaId} {
      allow read, write: if request.auth != null && chatId.contains(request.auth.uid);
    }
  }
}
```

---

## 3. WebRTC Call Signalling STUN/TURN Configuration

WebRTC connects callers directly Peer-to-Peer (P2P). However, when peers are behind firewalls, symmetric NATs, or dynamic cellular networks, connection fails unless guided by ICE helpers.

In `/app/src/main/java/com/example/webrtc/WebRtcManager.kt`, customize the ICE Servers list in `createPeerConnection` with STUN/TURN credentials:

```kotlin
val iceServersList = listOf(
    // Standard Google Public STUN Servers (Used for public IP discovery)
    PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
    PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
    
    // Production TURN Servers (Relays voice/video audio when P2P is blocked)
    // Replace with your Turn server (e.g. Twilio Network Traversal or Xirsys credentials)
    PeerConnection.IceServer.builder("turn:your-turn-server.com:3478")
        .setUsername("your-username")
        .setPassword("your-password")
        .createIceServer()
)
```

---

## 4. Compilation & Running

### Build APK
To compile a signed debug APK for testing on physical devices:
1. Open the Android Studio terminal.
2. Run the Gradle build command:
   ```bash
   gradle assembleDebug
   ```
3. The generated APK will be available in:
   `/app/build/outputs/apk/debug/app-debug.apk`

### Compile Release AAB (Android App Bundle)
To build the bundle for publishing to the Google Play Store:
1. In Android Studio, go to **Build** -> **Generate Signed Bundle / APK**.
2. Select **Android App Bundle** -> click **Next**.
3. Create or select an existing Keystore path, set secure passwords, and name your key alias `upload`.
4. Select **release** build variant -> click **Create**.
5. The signed bundle will be generated in:
   `/app/release/app-release.aab`

---

## 5. Publishing to Google Play Store

1. Sign in to the [Google Play Console](https://play.google.com/console).
2. Click **Create App** -> enter `IdeloChat` -> select app language -> click **Create**.
3. Go to **Set up your app** and complete the declarations:
   - **App Access**: Declare that all parts of the app are accessible without restriction (or provide a test credential).
   - **Permissions**: Declare usage of `CAMERA` and `RECORD_AUDIO` for WebRTC communication.
   - **Privacy Policy**: Provide a valid URL explaining that user chats, profiles, and media are safely synchronized to Firebase and never shared.
4. Go to **App Bundle Explorer** and upload the signed `app-release.aab`.
5. Define your Release Notes, set pricing/regions, and submit for Review!
