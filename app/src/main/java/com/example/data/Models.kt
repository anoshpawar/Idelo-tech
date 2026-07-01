package com.example.data

import androidx.annotation.Keep

@Keep
data class User(
    val userId: String = "",
    val name: String = "",
    val email: String = "",
    val photo: String = "", // Base64 or standard URL
    val status: String = "Offline", // "Online" or "Offline"
    val lastSeen: Long = 0,
    val isTypingWith: String? = null
)

@Keep
data class Message(
    val messageId: String = "",
    val senderId: String = "",
    val receiverId: String = "",
    val message: String = "",
    val timestamp: Long = 0,
    var seen: Boolean = false,
    var delivered: Boolean = false,
    // Media support
    val imageUrl: String? = null,
    val videoUrl: String? = null,
    val fileUrl: String? = null,
    val fileName: String? = null
)

@Keep
data class CallSession(
    val callId: String = "",
    val callerId: String = "",
    val receiverId: String = "",
    val callerName: String = "",
    val callerPhoto: String = "",
    val receiverName: String = "",
    val receiverPhoto: String = "",
    val type: String = "voice", // "voice" or "video"
    val status: String = "idle", // "dialing", "ringing", "connected", "rejected", "ended", "missed"
    val timestamp: Long = 0,
    val sdpOffer: String? = null,
    val sdpAnswer: String? = null
)
