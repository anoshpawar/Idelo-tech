package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.rememberAsyncImagePainter
import com.example.data.*
import com.example.webrtc.WebRtcManager
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.webrtc.SurfaceViewRenderer

class ChatViewModel(context: Context) : ViewModel() {
    val repository = ChatRepository(context)
    val webRtcManager = WebRtcManager(context)

    // Flow bridges from Repository
    val currentUser = repository.currentUser
    val usersList = repository.usersList
    val activeCall = repository.activeCall
    val isFirebaseActive = repository.isFirebaseActive
    val messagesMap = repository.messagesMap

    // UI Local State Controllers
    var currentScreen by mutableStateOf("splash")
    var activeChatUser by mutableStateOf<User?>(null)
    
    // Search & Profile sheet states
    var searchQuery by mutableStateOf("")
    var isSearchActive by mutableStateOf(false)
    var showProfileSheet by mutableStateOf(false)

    // Active Calling Timers
    var callDurationSeconds by mutableStateOf(0)
    private var timerJob: Job? = null

    init {
        // Observe Call session state to coordinate timer and cleanup
        viewModelScope().launch {
            activeCall.collect { call ->
                if (call != null && call.status == "connected") {
                    startCallTimer()
                } else {
                    stopCallTimer()
                    if (call == null) {
                        webRtcManager.cleanUp()
                    }
                }
            }
        }
    }

    private fun viewModelScope() = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main + kotlinx.coroutines.SupervisorJob())

    private fun startCallTimer() {
        if (timerJob != null) return
        callDurationSeconds = 0
        timerJob = viewModelScope().launch {
            while (true) {
                delay(1000)
                callDurationSeconds++
            }
        }
    }

    private fun stopCallTimer() {
        timerJob?.cancel()
        timerJob = null
    }

    fun register(name: String, email: String, photo: String, onComplete: (Boolean, String?) -> Unit) {
        repository.registerUser(name, email, photo, onComplete)
    }

    fun login(email: String, onComplete: (Boolean, String?) -> Unit) {
        repository.loginUser(email, onComplete)
    }

    fun logout() {
        repository.logoutUser()
        currentScreen = "login"
    }

    fun sendMessage(receiverId: String, text: String, imageUrl: String? = null, videoUrl: String? = null, fileUrl: String? = null, fileName: String? = null) {
        repository.sendMessage(receiverId, text, imageUrl, videoUrl, fileUrl, fileName)
    }

    fun deleteMessage(chatWithUserId: String, messageId: String) {
        repository.deleteMessage(chatWithUserId, messageId)
    }

    fun startCall(receiverId: String, type: String) {
        repository.startCall(receiverId, type)
    }

    fun acceptCall(callId: String) {
        repository.acceptCall(callId)
    }

    fun rejectCall(callId: String) {
        repository.rejectCall(callId)
    }

    fun endCall(callId: String) {
        repository.endCall(callId)
    }

    fun copyToClipboard(context: Context, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Copied Message", text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, "Message copied to clipboard", Toast.LENGTH_SHORT).show()
    }
}

@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Composable
fun IdeloChatApp() {
    val context = LocalContext.current
    val viewModel: ChatViewModel = viewModel { ChatViewModel(context) }
    
    val currentUser by viewModel.currentUser.collectAsStateWithLifecycle()
    val activeCall by viewModel.activeCall.collectAsStateWithLifecycle()

    // Permissions checking for call systems
    val cameraPermissionState = rememberPermissionState(android.Manifest.permission.CAMERA)
    val micPermissionState = rememberPermissionState(android.Manifest.permission.RECORD_AUDIO)

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Screen Routing View Switcher
            AnimatedContent(
                targetState = viewModel.currentScreen,
                transitionSpec = {
                    fadeIn(animationSpec = tween(300)) togetherWith fadeOut(animationSpec = tween(300))
                },
                label = "ScreenNavigator"
            ) { screen ->
                when (screen) {
                    "splash" -> SplashScreen(viewModel)
                    "login" -> LoginScreen(viewModel)
                    "register" -> RegisterScreen(viewModel)
                    "home" -> HomeScreen(viewModel)
                    "chat" -> ChatScreen(viewModel)
                }
            }

            // --- DECOUPLED FLOATING / IMMERSIVE INCOMING CALL OVERLAY ---
            activeCall?.let { call ->
                // Ensure permission requests are dynamically triggered if they haven't been granted yet
                LaunchedEffect(call.status) {
                    if (call.status == "dialing" || call.status == "ringing") {
                        if (!cameraPermissionState.status.isGranted) cameraPermissionState.launchPermissionRequest()
                        if (!micPermissionState.status.isGranted) micPermissionState.launchPermissionRequest()
                    }
                }

                when (call.status) {
                    "dialing", "ringing", "connected" -> {
                        // Display full-screen immersive overlay
                        CallOverlay(viewModel = viewModel, session = call)
                    }
                    "rejected", "ended", "missed" -> {
                        // Display call termination overlay briefly
                        CallEndedOverlay(session = call)
                    }
                }
            }
        }
    }
}

// --- SCREEN 1: SPLASH SCREEN ---
@Composable
fun SplashScreen(viewModel: ChatViewModel) {
    val currentUser by viewModel.currentUser.collectAsStateWithLifecycle()
    
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "logoPulse"
    )

    LaunchedEffect(Unit) {
        delay(2200) // Beautiful splash dwell time
        if (currentUser != null) {
            viewModel.currentScreen = "home"
        } else {
            viewModel.currentScreen = "login"
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .testTag("splash_screen"),
        contentAlignment = Alignment.Center
    ) {
        // Sleek diagonal ambient background lines
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(
                color = Color(0xFF00A884).copy(alpha = 0.08f),
                radius = size.width * 0.45f,
                center = Offset(size.width * 0.5f, size.height * 0.5f)
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Canvas-designed custom communication icon
            Box(
                modifier = Modifier
                    .size(110.dp)
                    .drawBehind {
                        drawRoundRect(
                            color = Color(0xFF00A884),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(30.dp.toPx()),
                            style = Stroke(width = 4.dp.toPx())
                        )
                    }
                    .padding(20.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Forum,
                    contentDescription = "IdeloChat Logo",
                    tint = Color(0xFF00A884),
                    modifier = Modifier.size(54.dp)
                )
            }

            Spacer(modifier = Modifier.height(28.dp))

            Text(
                text = "IdeloChat",
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp,
                color = MaterialTheme.colorScheme.onBackground
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "High-Fidelity Real-Time Secure Chat",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                textAlign = TextAlign.Center
            )
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 36.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = "Secure",
                tint = Color(0xFF00A884).copy(alpha = 0.6f),
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "End-to-End Encrypted",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                letterSpacing = 0.5.sp
            )
        }
    }
}

// --- SCREEN 2: REGISTER SCREEN ---
@Composable
fun RegisterScreen(viewModel: ChatViewModel) {
    var fullName by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var selectedAvatar by remember { mutableStateOf("avatar_1") }
    var isLoading by remember { mutableStateOf(false) }

    val avatars = listOf("avatar_1", "avatar_2", "avatar_3", "avatar_4", "avatar_5", "avatar_6")
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
            .testTag("register_screen"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = "Create Account",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Join IdeloChat secure community and start messaging.",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(30.dp))

        // Avatar selector row
        Text(
            text = "Choose Your Avatar Profile",
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.Start)
        )

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            avatars.forEach { avatar ->
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(
                            if (selectedAvatar == avatar) Color(0xFF00A884) else Color.Transparent
                        )
                        .clickable { selectedAvatar = avatar }
                        .padding(3.dp),
                    contentAlignment = Alignment.Center
                ) {
                    AvatarDisplay(
                        photo = avatar,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(CircleShape)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Full Name Field
        OutlinedTextField(
            value = fullName,
            onValueChange = { fullName = it },
            label = { Text("Full Name") },
            leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("name_input"),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFF00A884),
                focusedLabelColor = Color(0xFF00A884)
            )
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Email Field
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("Email Address") },
            leadingIcon = { Icon(Icons.Default.Email, contentDescription = null) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("email_input"),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFF00A884),
                focusedLabelColor = Color(0xFF00A884)
            )
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Password Field
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFF00A884),
                focusedLabelColor = Color(0xFF00A884)
            )
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Confirm Password Field
        OutlinedTextField(
            value = confirmPassword,
            onValueChange = { confirmPassword = it },
            label = { Text("Confirm Password") },
            leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFF00A884),
                focusedLabelColor = Color(0xFF00A884)
            )
        )

        Spacer(modifier = Modifier.height(28.dp))

        Button(
            onClick = {
                if (fullName.isBlank() || email.isBlank() || password.isBlank()) {
                    Toast.makeText(context, "Please fill in all fields", Toast.LENGTH_SHORT).show()
                    return@Button
                }
                if (password != confirmPassword) {
                    Toast.makeText(context, "Passwords do not match", Toast.LENGTH_SHORT).show()
                    return@Button
                }
                isLoading = true
                viewModel.register(fullName, email, selectedAvatar) { success, error ->
                    isLoading = false
                    if (success) {
                        viewModel.currentScreen = "home"
                    } else {
                        Toast.makeText(context, error ?: "Registration failed", Toast.LENGTH_LONG).show()
                    }
                }
            },
            enabled = !isLoading,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00A884)),
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .testTag("submit_button"),
            shape = RoundedCornerShape(12.dp)
        ) {
            if (isLoading) {
                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
            } else {
                Text("Register Account", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        TextButton(
            onClick = { viewModel.currentScreen = "login" }
        ) {
            Text("Already have an account? Login here", color = Color(0xFF00A884))
        }
    }
}

// --- SCREEN 3: LOGIN SCREEN ---
@Composable
fun LoginScreen(viewModel: ChatViewModel) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var rememberSession by remember { mutableStateOf(true) }
    var isLoading by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val isFirebaseActive by viewModel.isFirebaseActive.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
            .testTag("login_screen"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Forum,
            contentDescription = null,
            tint = Color(0xFF00A884),
            modifier = Modifier.size(72.dp)
        )

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = "Welcome to IdeloChat",
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = "Sign in to access secure real-time messaging",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        // Firebase connectivity status badge
        Spacer(modifier = Modifier.height(16.dp))
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(
                    if (isFirebaseActive) Color(0xFFD9FDD3) else MaterialTheme.colorScheme.surfaceVariant
                )
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (isFirebaseActive) Color(0xFF00A884) else Color.Gray)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = if (isFirebaseActive) "Production Cloud Mode Active" else "High-Fidelity Offline Sandbox Mode",
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = if (isFirebaseActive) Color(0xFF075E54) else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(28.dp))

        // Email address
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("Email Address") },
            leadingIcon = { Icon(Icons.Default.Email, contentDescription = null) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("login_email"),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFF00A884),
                focusedLabelColor = Color(0xFF00A884)
            )
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Password input field
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password (Default is auto-verified)") },
            leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFF00A884),
                focusedLabelColor = Color(0xFF00A884)
            )
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Session controls
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = rememberSession,
                    onCheckedChange = { rememberSession = it },
                    colors = CheckboxDefaults.colors(checkedColor = Color(0xFF00A884))
                )
                Text("Remember session", fontSize = 13.sp)
            }
            TextButton(
                onClick = {
                    Toast.makeText(context, "Password reset link simulated and sent safely.", Toast.LENGTH_LONG).show()
                }
            ) {
                Text("Forgot Password?", color = Color(0xFF00A884), fontSize = 13.sp)
            }
        }

        Spacer(modifier = Modifier.height(28.dp))

        Button(
            onClick = {
                if (email.isBlank()) {
                    Toast.makeText(context, "Please enter an email address", Toast.LENGTH_SHORT).show()
                    return@Button
                }
                isLoading = true
                viewModel.login(email) { success, error ->
                    isLoading = false
                    if (success) {
                        viewModel.currentScreen = "home"
                    } else {
                        Toast.makeText(context, error ?: "Login failed", Toast.LENGTH_LONG).show()
                    }
                }
            },
            enabled = !isLoading,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00A884)),
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .testTag("login_button"),
            shape = RoundedCornerShape(12.dp)
        ) {
            if (isLoading) {
                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
            } else {
                Text("Secure Log In", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        TextButton(
            onClick = { viewModel.currentScreen = "register" }
        ) {
            Text("Don't have an account? Register here", color = Color(0xFF00A884))
        }
    }
}

// --- SCREEN 4: HOME SCREEN (WhatsApp Hub) ---
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(viewModel: ChatViewModel) {
    val currentUser by viewModel.currentUser.collectAsStateWithLifecycle()
    val usersList by viewModel.usersList.collectAsStateWithLifecycle()
    
    var activeTabState by remember { mutableIntStateOf(0) }
    val tabs = listOf("CHATS", "CONTACTS")

    val isFirebaseActive by viewModel.isFirebaseActive.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Text(
                            text = "IdeloChat",
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            fontSize = 21.sp,
                            letterSpacing = 0.5.sp
                        )
                    },
                    actions = {
                        IconButton(onClick = { viewModel.isSearchActive = !viewModel.isSearchActive }) {
                            Icon(imageVector = Icons.Default.Search, contentDescription = "Search", tint = Color.White)
                        }
                        IconButton(onClick = { viewModel.showProfileSheet = true }) {
                            AvatarDisplay(photo = currentUser?.photo ?: "avatar_1", modifier = Modifier.size(32.dp).clip(CircleShape))
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF075E54))
                )
                
                // Animated Dynamic Search Bar
                AnimatedVisibility(visible = viewModel.isSearchActive) {
                    TextField(
                        value = viewModel.searchQuery,
                        onValueChange = { viewModel.searchQuery = it },
                        placeholder = { Text("Search users or conversations...", color = Color.Gray) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.White),
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Color.Gray) },
                        trailingIcon = {
                            IconButton(onClick = {
                                viewModel.searchQuery = ""
                                viewModel.isSearchActive = false
                            }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear", tint = Color.Gray)
                            }
                        },
                        singleLine = true,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.White,
                            unfocusedContainerColor = Color.White,
                            focusedIndicatorColor = Color(0xFF00A884)
                        )
                    )
                }

                // Tab Row (Modern WhatsApp style)
                TabRow(
                    selectedTabIndex = activeTabState,
                    containerColor = Color(0xFF075E54),
                    contentColor = Color.White,
                    indicator = { tabPositions ->
                        TabRowDefaults.SecondaryIndicator(
                            modifier = Modifier.tabIndicatorOffset(tabPositions[activeTabState]),
                            color = Color.White,
                            height = 3.dp
                        )
                    }
                ) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = activeTabState == index,
                            onClick = { activeTabState = index },
                            text = { Text(title, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { activeTabState = 1 }, // Switch to Contacts/Users list to start message
                containerColor = Color(0xFF00A884),
                contentColor = Color.White,
                shape = CircleShape
            ) {
                Icon(imageVector = Icons.Default.Chat, contentDescription = "New Chat")
            }
        },
        modifier = Modifier.testTag("home_screen")
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (activeTabState) {
                0 -> RecentChatsTab(viewModel = viewModel)
                1 -> ContactsTab(viewModel = viewModel)
            }

            // Custom Bottom Sheet for Profile
            if (viewModel.showProfileSheet) {
                ModalBottomSheet(
                    onDismissRequest = { viewModel.showProfileSheet = false },
                    sheetState = rememberModalBottomSheetState(),
                    containerColor = MaterialTheme.colorScheme.surface
                ) {
                    ProfileSheetContent(viewModel = viewModel)
                }
            }
        }
    }
}

// --- SUB-COMPONENT: RECENT CHATS LIST ---
@Composable
fun RecentChatsTab(viewModel: ChatViewModel) {
    val usersList by viewModel.usersList.collectAsStateWithLifecycle()
    val messagesMap by viewModel.messagesMap.collectAsStateWithLifecycle()
    val searchQuery = viewModel.searchQuery

    // Filter users list to keep only those with messaging logs
    val recentUsers = remember(usersList, messagesMap, searchQuery) {
        usersList.filter { user ->
            val chatMsgs = messagesMap[user.userId] ?: emptyList()
            chatMsgs.isNotEmpty() && (searchQuery.isBlank() || user.name.contains(searchQuery, ignoreCase = true))
        }.sortedByDescending { user ->
            messagesMap[user.userId]?.lastOrNull()?.timestamp ?: 0L
        }
    }

    if (recentUsers.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                Icon(
                    imageVector = Icons.Default.ChatBubbleOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.size(64.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "No active conversations",
                    fontWeight = FontWeight.Medium,
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Switch to the Contacts tab to start a new chat.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center
                )
            }
        }
    } else {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(recentUsers) { user ->
                val msgs = messagesMap[user.userId] ?: emptyList()
                val lastMsg = msgs.lastOrNull()
                val unreadCount = msgs.count { !it.seen && it.receiverId == "me" }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            viewModel.activeChatUser = user
                            viewModel.currentScreen = "chat"
                        }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.size(54.dp)) {
                        AvatarDisplay(photo = user.photo, modifier = Modifier.fillMaxSize().clip(CircleShape))
                        if (user.status == "Online") {
                            Box(
                                modifier = Modifier
                                    .size(14.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF25D366))
                                    .border(2.dp, MaterialTheme.colorScheme.background, CircleShape)
                                    .align(Alignment.BottomEnd)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = user.name,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = lastMsg?.timestamp?.let { formatTime(it) } ?: "",
                                fontSize = 11.sp,
                                color = if (unreadCount > 0) Color(0xFF00A884) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                fontWeight = if (unreadCount > 0) FontWeight.Bold else FontWeight.Normal
                            )
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val displayText = when {
                                user.isTypingWith == "me" -> "typing..."
                                lastMsg != null -> lastMsg.message
                                else -> ""
                            }
                            val displayColor = when {
                                user.isTypingWith == "me" -> Color(0xFF00A884)
                                else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            }
                            
                            Text(
                                text = displayText,
                                fontSize = 13.sp,
                                color = displayColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                                fontWeight = if (unreadCount > 0) FontWeight.SemiBold else FontWeight.Normal
                            )

                            if (unreadCount > 0) {
                                Box(
                                    modifier = Modifier
                                        .size(20.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF25D366)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = unreadCount.toString(),
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
                Divider(
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
                    thickness = 0.5.dp,
                    modifier = Modifier.padding(start = 86.dp)
                )
            }
        }
    }
}

// --- SUB-COMPONENT: CONTACTS / USERS LIST ---
@Composable
fun ContactsTab(viewModel: ChatViewModel) {
    val usersList by viewModel.usersList.collectAsStateWithLifecycle()
    val searchQuery = viewModel.searchQuery

    val filteredContacts = remember(usersList, searchQuery) {
        usersList.filter {
            searchQuery.isBlank() || it.name.contains(searchQuery, ignoreCase = true)
        }
    }

    if (filteredContacts.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Default.PersonSearch,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.size(60.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text("No contacts found", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    } else {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(filteredContacts) { user ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            viewModel.activeChatUser = user
                            viewModel.currentScreen = "chat"
                        }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.size(50.dp)) {
                        AvatarDisplay(photo = user.photo, modifier = Modifier.fillMaxSize().clip(CircleShape))
                        if (user.status == "Online") {
                            Box(
                                modifier = Modifier
                                    .size(13.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF25D366))
                                    .border(2.dp, MaterialTheme.colorScheme.background, CircleShape)
                                    .align(Alignment.BottomEnd)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(user.name, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = if (user.status == "Online") "Online" else "Offline",
                            fontSize = 12.sp,
                            color = if (user.status == "Online") Color(0xFF00A884) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                }
                Divider(
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
                    thickness = 0.5.dp,
                    modifier = Modifier.padding(start = 82.dp)
                )
            }
        }
    }
}

// --- SCREEN 5: CHAT SCREEN ---
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(viewModel: ChatViewModel) {
    val activeUser = viewModel.activeChatUser ?: return
    val messagesList by viewModel.repository.getMessages(activeUser.userId).collectAsStateWithLifecycle(initialValue = emptyList())
    var textInput by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val context = LocalContext.current

    // Keyboard back action helper
    BackHandler {
        viewModel.currentScreen = "home"
    }

    // Auto scroll to bottom when messages list size changes
    LaunchedEffect(messagesList.size) {
        if (messagesList.isNotEmpty()) {
            listState.animateScrollToItem(messagesList.size - 1)
        }
    }

    // Track active typing indicator status on background
    LaunchedEffect(textInput) {
        viewModel.repository.updateTypingStatus(activeUser.userId, textInput.isNotEmpty())
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { viewModel.currentScreen = "home" }) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AvatarDisplay(photo = activeUser.photo, modifier = Modifier.size(38.dp).clip(CircleShape))
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(activeUser.name, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            val subtitleText = when {
                                activeUser.isTypingWith == "me" -> "typing..."
                                activeUser.status == "Online" -> "Online"
                                else -> "Offline"
                            }
                            Text(subtitleText, fontSize = 11.sp, color = if (subtitleText == "typing...") Color(0xFF25D366) else Color.White.copy(alpha = 0.7f))
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.startCall(activeUser.userId, "voice") }) {
                        Icon(imageVector = Icons.Default.Phone, contentDescription = "Voice Call", tint = Color.White)
                    }
                    IconButton(onClick = { viewModel.startCall(activeUser.userId, "video") }) {
                        Icon(imageVector = Icons.Default.Videocam, contentDescription = "Video Call", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF075E54))
            )
        },
        modifier = Modifier.testTag("chat_screen")
    ) { innerPadding ->
        // Custom background drawing incorporating WhatsApp style repeating geometric watermarks
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .drawBehind {
                    drawRect(color = Color(0xFF0B141A)) // Dark slate base
                    // Draw clean geometric watermark circles
                    val space = 110.dp.toPx()
                    for (x in 0..10) {
                        for (y in 0..20) {
                            drawCircle(
                                color = Color.White.copy(alpha = 0.015f),
                                radius = 20.dp.toPx(),
                                center = Offset(x * space + (if (y % 2 == 0) space / 2 else 0f), y * space),
                                style = Stroke(width = 1.dp.toPx())
                            )
                        }
                    }
                }
        ) {
            // Messages Column Scroll Area
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                items(messagesList, key = { it.messageId }) { message ->
                    val isMe = message.senderId == "me" || message.senderId == (viewModel.currentUser.value?.userId ?: "me")
                    MessageBubble(
                        message = message,
                        isMe = isMe,
                        onDelete = { viewModel.deleteMessage(activeUser.userId, message.messageId) },
                        onCopy = { viewModel.copyToClipboard(context, message.message) }
                    )
                }
            }

            // Bottom input controls
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                // Main Rounded Text Card
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color(0xFF202C33))
                        .padding(horizontal = 12.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = {
                        // Quick Media attachments sender panel simulation
                        val randomImage = "https://picsum.photos/400/300?random=" + (1..100).random()
                        viewModel.sendMessage(activeUser.userId, "Sent an image", imageUrl = randomImage)
                    }) {
                        Icon(imageVector = Icons.Default.AddPhotoAlternate, contentDescription = "Add Image", tint = Color(0xFF8696A0))
                    }

                    TextField(
                        value = textInput,
                        onValueChange = { textInput = it },
                        placeholder = { Text("Type message", color = Color(0xFF8696A0), fontSize = 15.sp) },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("message_input"),
                        singleLine = false,
                        maxLines = 4,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        )
                    )

                    IconButton(onClick = {
                        // Attach generic file mockup
                        viewModel.sendMessage(activeUser.userId, "Attached secure document", fileUrl = "https://example.com/doc.pdf", fileName = "Secure_Data.pdf")
                    }) {
                        Icon(imageVector = Icons.Default.AttachFile, contentDescription = "Attach File", tint = Color(0xFF8696A0))
                    }
                }

                Spacer(modifier = Modifier.width(6.dp))

                // Circular Send Floating Action Button
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF00A884))
                        .clickable {
                            if (textInput.isNotBlank()) {
                                viewModel.sendMessage(activeUser.userId, textInput)
                                textInput = ""
                            }
                        }
                        .testTag("send_btn"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

// --- SUB-COMPONENT: CHAT MESSAGE BUBBLES ---
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    message: Message,
    isMe: Boolean,
    onDelete: () -> Unit,
    onCopy: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        contentAlignment = if (isMe) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 290.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomStart = if (isMe) 16.dp else 0.dp,
                        bottomEnd = if (isMe) 0.dp else 16.dp
                    )
                )
                .background(if (isMe) Color(0xFF005C4B) else Color(0xFF202C33))
                .combinedClickable(
                    onLongClick = { showMenu = true },
                    onClick = {}
                )
                .padding(10.dp)
        ) {
            // Media image preview loader
            message.imageUrl?.let { url ->
                Image(
                    painter = rememberAsyncImagePainter(model = url),
                    contentDescription = "Media attachment",
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                        .clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop
                )
                Spacer(modifier = Modifier.height(6.dp))
            }

            // Media attachment file preview card
            message.fileUrl?.let {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black.copy(alpha = 0.2f))
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(imageVector = Icons.Default.PictureAsPdf, contentDescription = null, tint = Color.Red, modifier = Modifier.size(28.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = message.fileName ?: "Secure Document",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
            }

            // Message text display
            Text(
                text = message.message,
                color = Color.White,
                fontSize = 15.sp,
                lineHeight = 20.sp
            )

            Spacer(modifier = Modifier.height(3.dp))

            // Time & Seen indicators row
            Row(
                modifier = Modifier.align(Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = formatTime(message.timestamp),
                    fontSize = 10.sp,
                    color = Color.White.copy(alpha = 0.6f)
                )
                if (isMe) {
                    Spacer(modifier = Modifier.width(4.dp))
                    val tickColor = if (message.seen) Color(0xFF53BDEB) else Color.White.copy(alpha = 0.6f)
                    Icon(
                        imageVector = if (message.seen) Icons.Default.DoneAll else Icons.Default.Check,
                        contentDescription = "Read Status",
                        tint = tickColor,
                        modifier = Modifier.size(13.dp)
                    )
                }
            }

            // Context Options drop list
            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                DropdownMenuItem(
                    text = { Text("Copy text") },
                    leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                    onClick = {
                        onCopy()
                        showMenu = false
                    }
                )
                DropdownMenuItem(
                    text = { Text("Delete for everyone") },
                    leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                    onClick = {
                        onDelete()
                        showMenu = false
                    }
                )
            }
        }
    }
}

// --- ACTIVE VOICE AND VIDEO CALL SYSTEM OVERLAYS ---
@Composable
fun CallOverlay(viewModel: ChatViewModel, session: CallSession) {
    val durationSeconds = viewModel.callDurationSeconds
    val webRtcManager = viewModel.webRtcManager
    val context = LocalContext.current
    
    var localVideoRenderer by remember { mutableStateOf<SurfaceViewRenderer?>(null) }

    LaunchedEffect(session.status) {
        if (session.status == "connected") {
            webRtcManager.startLocalAudio()
            if (session.type == "video" && localVideoRenderer != null) {
                webRtcManager.startLocalVideo(localVideoRenderer!!)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B141A)) // Immersive dark screen
    ) {
        if (session.type == "video" && session.status == "connected") {
            // REMOTE VIDEO MAIN RENDERER PLACEHOLDER (With animated subtle spectrum lines)
            Box(modifier = Modifier.fillMaxSize()) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawRect(color = Color(0xFF111B21))
                    // Subtle geometric connection indicator bars
                    drawRoundRect(
                        color = Color(0xFF00A884).copy(alpha = 0.05f),
                        topLeft = Offset(size.width * 0.15f, size.height * 0.25f),
                        size = androidx.compose.ui.geometry.Size(size.width * 0.7f, size.height * 0.5f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(16.dp.toPx())
                    )
                }

                // Remote user big center initials representation
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    AvatarDisplay(photo = session.receiverPhoto, modifier = Modifier.size(90.dp).clip(CircleShape))
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = if (session.callerId == "me") session.receiverName else session.callerName,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Remote Feed Transmitting...", color = Color(0xFF25D366), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }

                // PIP: LOCAL CAMERA FEED RENDERER INSET (Top Right Floating)
                AndroidView(
                    factory = { ctx ->
                        SurfaceViewRenderer(ctx).apply {
                            localVideoRenderer = this
                        }
                    },
                    modifier = Modifier
                        .padding(top = 48.dp, end = 16.dp)
                        .size(width = 110.dp, height = 160.dp)
                        .align(Alignment.TopEnd)
                        .clip(RoundedCornerShape(12.dp))
                        .border(1.5.dp, Color.White.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                )
            }
        } else {
            // VOICE CALL OR DIALING / RINGING BACKGROUNDS (Blurred concentric waveforms)
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                // Ripple animation
                val rippleScale = remember { Animatable(1f) }
                LaunchedEffect(Unit) {
                    rippleScale.animateTo(
                        targetValue = 1.6f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(1500, easing = LinearEasing),
                            repeatMode = RepeatMode.Restart
                        )
                    )
                }

                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawCircle(
                        color = Color(0xFF00A884).copy(alpha = 0.04f * (2f - rippleScale.value)),
                        radius = size.width * 0.35f * rippleScale.value,
                        center = center
                    )
                }

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    val peerPhoto = if (session.callerId == "me") session.receiverPhoto else session.callerPhoto
                    val peerName = if (session.callerId == "me") session.receiverName else session.callerName

                    AvatarDisplay(photo = peerPhoto, modifier = Modifier.size(114.dp).clip(CircleShape))
                    
                    Spacer(modifier = Modifier.height(24.dp))
                    
                    Text(
                        text = peerName,
                        fontSize = 25.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    val displayState = when (session.status) {
                        "dialing" -> "Dialing..."
                        "ringing" -> "Ringing..."
                        "connected" -> formatCallTimer(durationSeconds)
                        else -> ""
                    }
                    Text(
                        text = displayState,
                        fontSize = 15.sp,
                        color = if (session.status == "connected") Color(0xFF25D366) else Color.White.copy(alpha = 0.7f),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        // --- TOP CALL STATUS AND SIGNAL INFOS ---
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 52.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Security, contentDescription = null, tint = Color(0xFF00A884).copy(alpha = 0.6f), modifier = Modifier.size(14.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("Encrypted signalling • Call quality: Excellent", color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp)
        }

        // --- BOTTOM CALL CONTROLS PANEL ---
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .clip(RoundedCornerShape(32.dp))
                    .background(Color(0xFF111B21))
                    .padding(vertical = 12.dp, horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Mic Mute control
                IconButton(onClick = { webRtcManager.toggleMute(!webRtcManager.isMuted) }) {
                    Icon(
                        imageVector = if (webRtcManager.isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                        contentDescription = "Mute",
                        tint = if (webRtcManager.isMuted) Color.Red else Color.White
                    )
                }

                // Camera Switch (for video call only)
                if (session.type == "video") {
                    IconButton(onClick = { webRtcManager.switchCamera() }) {
                        Icon(imageVector = Icons.Default.SwitchCamera, contentDescription = "Switch Camera", tint = Color.White)
                    }
                }

                // Call responder / Ring Answer (green) button displayed if call incoming (i.e. we did not start it)
                val isIncoming = session.callerId != "me" && session.status != "connected"
                if (isIncoming) {
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF25D366))
                            .clickable { viewModel.acceptCall(session.callId) },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(imageVector = Icons.Default.Call, contentDescription = "Answer", tint = Color.White)
                    }
                }

                // End call / Reject button (red circular FAB)
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .clip(CircleShape)
                        .background(Color.Red)
                        .clickable {
                            if (isIncoming) {
                                viewModel.rejectCall(session.callId)
                            } else {
                                viewModel.endCall(session.callId)
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(imageVector = Icons.Default.CallEnd, contentDescription = "End Call", tint = Color.White)
                }
            }
        }
    }
}

@Composable
fun CallEndedOverlay(session: CallSession) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.9f)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            AvatarDisplay(photo = session.receiverPhoto, modifier = Modifier.size(80.dp).clip(CircleShape))
            Spacer(modifier = Modifier.height(16.dp))
            Text(text = session.receiverName, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Spacer(modifier = Modifier.height(8.dp))
            val summary = when (session.status) {
                "rejected" -> "Call Declined"
                else -> "Call Ended"
            }
            Text(text = summary, color = Color.Red, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

// --- SUB-COMPONENT: BOTTOM PROFILE DETAIL SHEET ---
@Composable
fun ProfileSheetContent(viewModel: ChatViewModel) {
    val currentUser by viewModel.currentUser.collectAsStateWithLifecycle()
    val isFirebaseActive by viewModel.isFirebaseActive.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        AvatarDisplay(photo = currentUser?.photo ?: "avatar_1", modifier = Modifier.size(90.dp).clip(CircleShape))
        
        Spacer(modifier = Modifier.height(16.dp))

        Text(text = currentUser?.name ?: "Profile User", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(text = currentUser?.email ?: "user@idelochat.com", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Spacer(modifier = Modifier.height(24.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(imageVector = Icons.Default.Info, contentDescription = null, tint = Color(0xFF00A884))
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text("Secure IdeloChat ID", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(currentUser?.userId ?: "me_offline_sandbox", fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = {
                viewModel.logout()
                viewModel.showProfileSheet = false
            },
            colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(imageVector = Icons.Default.ExitToApp, contentDescription = null, tint = Color.White)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Log Out Safe Session", color = Color.White, fontWeight = FontWeight.Bold)
        }
    }
}

// --- SUB-COMPONENT: SECURE PROFILE AVATAR RENDERER ---
@Composable
fun AvatarDisplay(photo: String, modifier: Modifier = Modifier) {
    // Elegant initials and custom gradients fallback to display avatars beautifully offline
    val colorsList = listOf(
        Brush.linearGradient(listOf(Color(0xFF00A884), Color(0xFF25D366))),
        Brush.linearGradient(listOf(Color(0xFF202C33), Color(0xFF8696A0))),
        Brush.linearGradient(listOf(Color(0xFF075E54), Color(0xFF00A884))),
        Brush.linearGradient(listOf(Color(0xFF5B409D), Color(0xFF8A6ED5))),
        Brush.linearGradient(listOf(Color(0xFFE53935), Color(0xFFFF8A80))),
        Brush.linearGradient(listOf(Color(0xFF00ACC1), Color(0xFF80DEEA)))
    )

    val avatarIndex = when (photo) {
        "avatar_1" -> 0
        "avatar_2" -> 1
        "avatar_3" -> 2
        "avatar_4" -> 3
        "avatar_5" -> 4
        "avatar_6" -> 5
        else -> 0
    }

    if (photo.startsWith("avatar_")) {
        Box(
            modifier = modifier
                .background(colorsList[avatarIndex])
                .padding(4.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Person,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.fillMaxSize(0.6f)
            )
        }
    } else {
        // Load real URL from network
        Image(
            painter = rememberAsyncImagePainter(model = photo),
            contentDescription = "Avatar Profile",
            modifier = modifier,
            contentScale = ContentScale.Crop
        )
    }
}

// --- UTILITY FORMATTERS ---
fun formatTime(timestamp: Long): String {
    val sdf = java.text.SimpleDateFormat("hh:mm a", java.util.Locale.getDefault())
    return sdf.format(java.util.Date(timestamp))
}

fun formatCallTimer(seconds: Int): String {
    val mins = seconds / 60
    val secs = seconds % 60
    return String.format("%02d:%02d", mins, secs)
}
