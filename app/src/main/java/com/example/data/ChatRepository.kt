package com.example.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

class ChatRepository(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var useFirebase = false

    // Firebase instances
    private var firebaseAuth: FirebaseAuth? = null
    private var firebaseDatabase: FirebaseDatabase? = null

    // State flows
    private val _currentUser = MutableStateFlow<User?>(null)
    val currentUser: StateFlow<User?> = _currentUser.asStateFlow()

    private val _usersList = MutableStateFlow<List<User>>(emptyList())
    val usersList: StateFlow<List<User>> = _usersList.asStateFlow()

    private val _messagesMap = MutableStateFlow<Map<String, List<Message>>>(emptyMap())
    val messagesMap: StateFlow<Map<String, List<Message>>> = _messagesMap.asStateFlow()

    private val _activeCall = MutableStateFlow<CallSession?>(null)
    val activeCall: StateFlow<CallSession?> = _activeCall.asStateFlow()

    private val _isFirebaseActive = MutableStateFlow(false)
    val isFirebaseActive: StateFlow<Boolean> = _isFirebaseActive.asStateFlow()

    // Mock Offline database storage (for Sandbox mode)
    private var mockUsers = mutableListOf<User>()
    private var mockMessages = mutableListOf<Message>()
    private var botJob: Job? = null

    init {
        detectAndInitializeFirebase()
        if (!useFirebase) {
            setupMockData()
        }
    }

    private fun detectAndInitializeFirebase() {
        try {
            // Check if Google Services resources are actually compile-bound
            val options = FirebaseOptions.fromResource(context)
            if (options != null) {
                FirebaseApp.initializeApp(context)
                firebaseAuth = FirebaseAuth.getInstance()
                firebaseDatabase = FirebaseDatabase.getInstance().apply {
                    setPersistenceEnabled(true)
                }
                useFirebase = true
                _isFirebaseActive.value = true
                Log.d("IdeloChat", "Firebase initialized successfully. Using Production Cloud Database.")
                
                // Keep track of auth status and sync user
                firebaseAuth?.addAuthStateListener { auth ->
                    val firebaseUser = auth.currentUser
                    if (firebaseUser != null) {
                        syncCurrentUserFromFirebase(firebaseUser.uid)
                    } else {
                        _currentUser.value = null
                    }
                }
                
                listenToUsersFromFirebase()
                listenToCallsFromFirebase()
            }
        } catch (e: Exception) {
            Log.e("IdeloChat", "Firebase configuration not present. Activating Offline High-Fidelity Sandbox Mode.", e)
            useFirebase = false
            _isFirebaseActive.value = false
        }
    }

    // --- MOCK OFFLINE SANDBOX ENGINE ---
    private fun setupMockData() {
        mockUsers = mutableListOf(
            User(
                userId = "sarah_connor",
                name = "Sarah Connor",
                email = "sarah@idelochat.com",
                photo = "avatar_1",
                status = "Online",
                lastSeen = System.currentTimeMillis()
            ),
            User(
                userId = "alex_rivera",
                name = "Alex Rivera",
                email = "alex@idelochat.com",
                photo = "avatar_2",
                status = "Offline",
                lastSeen = System.currentTimeMillis() - 300000
            ),
            User(
                userId = "maria_chen",
                name = "Maria Chen",
                email = "maria@idelochat.com",
                photo = "avatar_3",
                status = "Online",
                lastSeen = System.currentTimeMillis()
            ),
            User(
                userId = "john_watson",
                name = "Dr. John Watson",
                email = "john@idelochat.com",
                photo = "avatar_4",
                status = "Online",
                lastSeen = System.currentTimeMillis()
            )
        )
        _usersList.value = mockUsers

        // Populate introductory messages
        val now = System.currentTimeMillis()
        mockMessages = mutableListOf(
            Message(
                messageId = "init_1",
                senderId = "sarah_connor",
                receiverId = "me",
                message = "Welcome to IdeloChat! This is the High-Fidelity Offline Sandbox mode.",
                timestamp = now - 60000,
                seen = true,
                delivered = true
            ),
            Message(
                messageId = "init_2",
                senderId = "sarah_connor",
                receiverId = "me",
                message = "Send me a message or try calling me! I am an active AI simulation bot.",
                timestamp = now - 30000,
                seen = true,
                delivered = true
            )
        )
        updateMessagesFlow()
    }

    private fun updateMessagesFlow() {
        val grouped = mockMessages.groupBy { msg ->
            if (msg.senderId == "me" || msg.senderId == (_currentUser.value?.userId ?: "me")) {
                msg.receiverId
            } else {
                msg.senderId
            }
        }
        _messagesMap.value = grouped
    }

    // --- REGISTRATION & LOGIN ---
    fun registerUser(name: String, email: String, photo: String, onComplete: (Boolean, String?) -> Unit) {
        if (useFirebase) {
            firebaseAuth?.createUserWithEmailAndPassword(email, "default123")?.addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val uid = task.result?.user?.uid ?: ""
                    val newUser = User(userId = uid, name = name, email = email, photo = photo, status = "Online", lastSeen = System.currentTimeMillis())
                    firebaseDatabase?.getReference("users")?.child(uid)?.setValue(newUser)?.addOnCompleteListener { dbTask ->
                        if (dbTask.isSuccessful) {
                            _currentUser.value = newUser
                            onComplete(true, null)
                        } else {
                            onComplete(false, dbTask.exception?.message)
                        }
                    }
                } else {
                    onComplete(false, task.exception?.message)
                }
            }
        } else {
            // Local sandbox registration
            val uid = "user_" + UUID.randomUUID().toString().take(6)
            val newUser = User(userId = uid, name = name, email = email, photo = photo, status = "Online", lastSeen = System.currentTimeMillis())
            _currentUser.value = newUser
            
            // Add user to local list
            mockUsers.add(newUser)
            _usersList.value = mockUsers
            onComplete(true, null)
        }
    }

    fun loginUser(email: String, onComplete: (Boolean, String?) -> Unit) {
        if (useFirebase) {
            firebaseAuth?.signInWithEmailAndPassword(email, "default123")?.addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val uid = task.result?.user?.uid ?: ""
                    syncCurrentUserFromFirebase(uid)
                    onComplete(true, null)
                } else {
                    onComplete(false, task.exception?.message)
                }
            }
        } else {
            // Local login lookup or creation
            val existing = mockUsers.find { it.email.equals(email, ignoreCase = true) }
            if (existing != null) {
                _currentUser.value = User(userId = "me", name = existing.name, email = existing.email, photo = existing.photo, status = "Online", lastSeen = System.currentTimeMillis())
            } else {
                val name = email.substringBefore("@").replaceFirstChar { it.uppercase() }
                _currentUser.value = User(userId = "me", name = name, email = email, photo = "avatar_1", status = "Online", lastSeen = System.currentTimeMillis())
            }
            onComplete(true, null)
        }
    }

    fun logoutUser() {
        if (useFirebase) {
            val uid = firebaseAuth?.currentUser?.uid
            if (uid != null) {
                firebaseDatabase?.getReference("users")?.child(uid)?.child("status")?.setValue("Offline")
                firebaseDatabase?.getReference("users")?.child(uid)?.child("lastSeen")?.setValue(System.currentTimeMillis())
            }
            firebaseAuth?.signOut()
        }
        _currentUser.value = null
    }

    // --- REALTIME SYNC AND LISTENERS ---
    private fun syncCurrentUserFromFirebase(uid: String) {
        firebaseDatabase?.getReference("users")?.child(uid)?.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val user = snapshot.getValue(User::class.java)
                if (user != null) {
                    _currentUser.value = user
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        })
        
        // Mark status as Online
        firebaseDatabase?.getReference("users")?.child(uid)?.child("status")?.setValue("Online")
    }

    private fun listenToUsersFromFirebase() {
        firebaseDatabase?.getReference("users")?.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val list = mutableListOf<User>()
                val myUid = firebaseAuth?.currentUser?.uid
                for (child in snapshot.children) {
                    val u = child.getValue(User::class.java)
                    if (u != null && u.userId != myUid) {
                        list.add(u)
                    }
                }
                _usersList.value = list
            }
            override fun onCancelled(error: DatabaseError) {}
        })
    }

    fun getMessages(chatWithUserId: String): StateFlow<List<Message>> {
        val flow = MutableStateFlow<List<Message>>(emptyList())
        if (useFirebase) {
            val myUid = firebaseAuth?.currentUser?.uid ?: return flow.asStateFlow()
            val chatId = if (myUid < chatWithUserId) "${myUid}_$chatWithUserId" else "${chatWithUserId}_$myUid"
            firebaseDatabase?.getReference("chats")?.child(chatId)?.addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val messages = mutableListOf<Message>()
                    for (child in snapshot.children) {
                        val m = child.getValue(Message::class.java)
                        if (m != null) {
                            messages.add(m)
                            // Auto-mark seen if received
                            if (m.receiverId == myUid && !m.seen) {
                                child.ref.child("seen").setValue(true)
                            }
                        }
                    }
                    flow.value = messages.sortedBy { it.timestamp }
                }
                override fun onCancelled(error: DatabaseError) {}
            })
        } else {
            // Local state flow update helper
            scope.launch {
                messagesMap.collect { map ->
                    val list = map[chatWithUserId] ?: emptyList()
                    // Auto-mark local seen
                    list.forEach { m ->
                        if (m.receiverId == "me" && !m.seen) {
                            m.seen = true
                        }
                    }
                    flow.value = list.sortedBy { it.timestamp }
                }
            }
        }
        return flow.asStateFlow()
    }

    // --- SEND AND DELETE MESSAGES ---
    fun sendMessage(
        receiverId: String,
        text: String,
        imageUrl: String? = null,
        videoUrl: String? = null,
        fileUrl: String? = null,
        fileName: String? = null
    ) {
        val myId = _currentUser.value?.userId ?: "me"
        val messageId = UUID.randomUUID().toString()
        val timestamp = System.currentTimeMillis()
        val newMessage = Message(
            messageId = messageId,
            senderId = myId,
            receiverId = receiverId,
            message = text,
            timestamp = timestamp,
            seen = false,
            delivered = true,
            imageUrl = imageUrl,
            videoUrl = videoUrl,
            fileUrl = fileUrl,
            fileName = fileName
        )

        if (useFirebase) {
            val chatId = if (myId < receiverId) "${myId}_$receiverId" else "${receiverId}_$myId"
            firebaseDatabase?.getReference("chats")?.child(chatId)?.child(messageId)?.setValue(newMessage)
        } else {
            // Mock message sending
            mockMessages.add(newMessage)
            updateMessagesFlow()

            // Trigger AI Bot reaction in sandbox mode!
            triggerBotReaction(receiverId, text)
        }
    }

    fun deleteMessage(chatWithUserId: String, messageId: String) {
        if (useFirebase) {
            val myId = _currentUser.value?.userId ?: return
            val chatId = if (myId < chatWithUserId) "${myId}_$chatWithUserId" else "${chatWithUserId}_$myId"
            firebaseDatabase?.getReference("chats")?.child(chatId)?.child(messageId)?.removeValue()
        } else {
            mockMessages.removeAll { it.messageId == messageId }
            updateMessagesFlow()
        }
    }

    fun updateTypingStatus(receiverId: String, isTyping: Boolean) {
        val myId = _currentUser.value?.userId ?: return
        if (useFirebase) {
            firebaseDatabase?.getReference("users")?.child(myId)?.child("isTypingWith")
                ?.setValue(if (isTyping) receiverId else null)
        } else {
            // Local typing indicator updates can be handled in-memory
        }
    }

    // --- BOT REACTION SIMULATOR ---
    private fun triggerBotReaction(senderId: String, messageText: String) {
        botJob?.cancel()
        botJob = scope.launch {
            // Simulated delay for reading message
            delay(1000)
            
            // Set bot status as Typing
            setBotTyping(senderId, true)
            delay(1500)
            
            val botResponse = generateBotResponse(senderId, messageText)
            
            val responseMsg = Message(
                messageId = UUID.randomUUID().toString(),
                senderId = senderId,
                receiverId = "me",
                message = botResponse,
                timestamp = System.currentTimeMillis(),
                seen = false,
                delivered = true
            )
            
            setBotTyping(senderId, false)
            mockMessages.add(responseMsg)
            updateMessagesFlow()
        }
    }

    private fun setBotTyping(botId: String, isTyping: Boolean) {
        mockUsers = mockUsers.map {
            if (it.userId == botId) {
                it.copy(isTypingWith = if (isTyping) "me" else null)
            } else it
        }.toMutableList()
        _usersList.value = mockUsers
    }

    private fun generateBotResponse(botId: String, text: String): String {
        val botName = mockUsers.find { it.userId == botId }?.name ?: "Contact"
        val query = text.lowercase().trim()
        return when {
            query.contains("hello") || query.contains("hi") -> "Hello! I am $botName. Hope you are enjoying IdeloChat!"
            query.contains("call") -> "Yes! Click the Voice 📞 or Video 📹 icons at the top to call me right now!"
            query.contains("how are you") -> "I am doing great, simulating complex real-time operations on this awesome Android app."
            query.contains("weather") -> "It looks beautiful out there! Perfectly sunny for building some high-fidelity code."
            query.contains("image") || query.contains("photo") -> "I received your image attachment request. It is fully supported offline and online!"
            else -> "That is awesome! Try making a simulated Voice/Video call to test the full WebRTC state machine integration."
        }
    }

    // --- CALL SIGNALLING SYSTEM ---
    private fun listenToCallsFromFirebase() {
        val myUid = firebaseAuth?.currentUser?.uid ?: return
        // Listen to personal calls path
        firebaseDatabase?.getReference("calls")?.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                var foundActiveCall: CallSession? = null
                for (child in snapshot.children) {
                    val session = child.getValue(CallSession::class.java)
                    if (session != null && (session.callerId == myUid || session.receiverId == myUid)) {
                        if (session.status == "dialing" || session.status == "ringing" || session.status == "connected") {
                            foundActiveCall = session
                        }
                    }
                }
                _activeCall.value = foundActiveCall
            }
            override fun onCancelled(error: DatabaseError) {}
        })
    }

    fun startCall(receiverId: String, type: String) {
        val myId = _currentUser.value?.userId ?: "me"
        val myName = _currentUser.value?.name ?: "Me"
        val myPhoto = _currentUser.value?.photo ?: "avatar_1"
        
        val targetUser = _usersList.value.find { it.userId == receiverId }
        val targetName = targetUser?.name ?: "Contact"
        val targetPhoto = targetUser?.photo ?: "avatar_1"

        val callId = "call_" + UUID.randomUUID().toString().take(8)
        val session = CallSession(
            callId = callId,
            callerId = myId,
            receiverId = receiverId,
            callerName = myName,
            callerPhoto = myPhoto,
            receiverName = targetName,
            receiverPhoto = targetPhoto,
            type = type,
            status = "dialing",
            timestamp = System.currentTimeMillis()
        )

        if (useFirebase) {
            firebaseDatabase?.getReference("calls")?.child(callId)?.setValue(session)
        } else {
            _activeCall.value = session
            
            // Trigger automatic connection after dialing/ringing
            scope.launch {
                delay(1500)
                if (_activeCall.value?.callId == callId && _activeCall.value?.status == "dialing") {
                    _activeCall.value = _activeCall.value?.copy(status = "ringing")
                }
                delay(2000)
                if (_activeCall.value?.callId == callId && _activeCall.value?.status == "ringing") {
                    // Auto accept mock call in Sandbox mode for demonstration
                    _activeCall.value = _activeCall.value?.copy(status = "connected")
                }
            }
        }
    }

    fun acceptCall(callId: String) {
        val current = _activeCall.value ?: return
        if (current.callId == callId) {
            val updated = current.copy(status = "connected")
            if (useFirebase) {
                firebaseDatabase?.getReference("calls")?.child(callId)?.setValue(updated)
            } else {
                _activeCall.value = updated
            }
        }
    }

    fun rejectCall(callId: String) {
        val current = _activeCall.value ?: return
        if (current.callId == callId) {
            val updated = current.copy(status = "rejected")
            if (useFirebase) {
                firebaseDatabase?.getReference("calls")?.child(callId)?.setValue(updated)
                // Clean up after small delay
                scope.launch {
                    delay(1000)
                    firebaseDatabase?.getReference("calls")?.child(callId)?.removeValue()
                }
            } else {
                _activeCall.value = updated
                scope.launch {
                    delay(1000)
                    _activeCall.value = null
                }
            }
        }
    }

    fun endCall(callId: String) {
        val current = _activeCall.value ?: return
        if (current.callId == callId) {
            val updated = current.copy(status = "ended")
            if (useFirebase) {
                firebaseDatabase?.getReference("calls")?.child(callId)?.setValue(updated)
                scope.launch {
                    delay(1000)
                    firebaseDatabase?.getReference("calls")?.child(callId)?.removeValue()
                }
            } else {
                _activeCall.value = updated
                scope.launch {
                    delay(1000)
                    _activeCall.value = null
                }
            }
        }
    }

    // --- SDP CALL SIGNALLING ---
    fun sendSdpOffer(callId: String, offerSdp: String) {
        if (useFirebase) {
            firebaseDatabase?.getReference("calls")?.child(callId)?.child("sdpOffer")?.setValue(offerSdp)
        } else {
            _activeCall.value = _activeCall.value?.copy(sdpOffer = offerSdp)
        }
    }

    fun sendSdpAnswer(callId: String, answerSdp: String) {
        if (useFirebase) {
            firebaseDatabase?.getReference("calls")?.child(callId)?.child("sdpAnswer")?.setValue(answerSdp)
        } else {
            _activeCall.value = _activeCall.value?.copy(sdpAnswer = answerSdp)
        }
    }
}
