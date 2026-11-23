package com.xyrcodex.heast

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import androidx.core.content.ContextCompat
import com.xyrcodex.heast.data.model.Track
import com.xyrcodex.heast.data.update.UpdateInfo
import com.xyrcodex.heast.data.update.UpdateManager
import com.xyrcodex.heast.ui.components.FullPlayerSheet
import com.xyrcodex.heast.ui.components.MiniPlayer
import com.xyrcodex.heast.ui.components.UpdateDialog
import com.xyrcodex.heast.ui.navigation.Screen
import com.xyrcodex.heast.ui.screens.HomeScreen
import com.xyrcodex.heast.ui.screens.LibraryScreen
import com.xyrcodex.heast.ui.screens.SearchScreen
import com.xyrcodex.heast.ui.screens.VibeScreen
import com.xyrcodex.heast.ui.theme.HeastAppTheme
import com.xyrcodex.heast.ui.theme.HeastMotion
import com.xyrcodex.heast.ui.theme.HeastMotion.pagerMotionBlurTransition
import com.xyrcodex.heast.ui.theme.HeastTheme
import com.xyrcodex.heast.viewmodel.MusicViewModel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    /**
     * AppCompat's per-app locales only reach AppCompatActivity on Android 12 and below.
     * Since Heast uses ComponentActivity (platform theme), apply the saved language here
     * without accessing applicationContext before attachment.
     */
    override fun attachBaseContext(newBase: Context) {
        val language = try {
            newBase.getSharedPreferences("heast_settings", Context.MODE_PRIVATE)
                .getString("app_language", "") ?: ""
        } catch (_: Throwable) {
            ""
        }
        if (language.isEmpty()) {
            super.attachBaseContext(newBase)
            return
        }
        val wrappedContext = try {
            val locale = if (language == "in" || language == "id") {
                java.util.Locale("in", "ID")
            } else {
                java.util.Locale(language)
            }
            java.util.Locale.setDefault(locale)
            val configuration = android.content.res.Configuration(newBase.resources.configuration)
            configuration.setLocale(locale)
            newBase.createConfigurationContext(configuration)
        } catch (_: Throwable) {
            newBase
        }
        super.attachBaseContext(wrappedContext)
    }

    private val viewModel: MusicViewModel by viewModels()
    private var lastActivePage: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            } catch (_: Throwable) {}
        }

        if (intent?.getBooleanExtra(EXTRA_EXPAND_PLAYER, false) == true) {
            viewModel.expandPlayer(true)
        }

        setContent {
            val themeMode by viewModel.themeMode.collectAsState()
            HeastTheme(themeMode = themeMode) {
                MainAppScreen(viewModel = viewModel, onPageChanged = { lastActivePage = it })
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.onAppForegrounded()
    }

    override fun onStop() {
        super.onStop()
        viewModel.onAppBackgrounded(lastActivePage)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            runCatching { coil.Coil.imageLoader(this).memoryCache?.clear() }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_EXPAND_PLAYER, false)) {
            viewModel.expandPlayer(true)
        }
    }

    companion object {
        const val EXTRA_EXPAND_PLAYER = "EXTRA_EXPAND_PLAYER"
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MainAppScreen(viewModel: MusicViewModel, onPageChanged: (Int) -> Unit = {}) {
    val navItems = Screen.bottomNavItems
    val restoredPage by viewModel.restoredPage.collectAsState()
    val pagerState = rememberPagerState(initialPage = restoredPage.coerceIn(0, navItems.lastIndex), pageCount = { navItems.size })
    LaunchedEffect(restoredPage) {
        val target = restoredPage.coerceIn(0, navItems.lastIndex)
        if (pagerState.currentPage != target && !pagerState.isScrollInProgress) {
            pagerState.scrollToPage(target)
        }
    }
    LaunchedEffect(pagerState.currentPage) {
        onPageChanged(pagerState.currentPage)
    }
    val coroutineScope = rememberCoroutineScope()

    val currentTrack by viewModel.currentTrack.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val isLoadingTrack by viewModel.isLoadingTrack.collectAsState()
    val playbackPosition by viewModel.playbackPosition.collectAsState()
    val isExpanded by viewModel.isPlayerExpanded.collectAsState()
    val isShuffle by viewModel.isShuffle.collectAsState()
    val repeatMode by viewModel.repeatMode.collectAsState()

    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var availableUpdate by remember { mutableStateOf<UpdateInfo?>(null) }
    var navSwipeOffsetX by remember { mutableFloatStateOf(0f) }

    val navigateToPage: (Int) -> Unit = { targetPage ->
        val clamped = targetPage.coerceIn(0, navItems.lastIndex)
        if (pagerState.currentPage != clamped) {
            coroutineScope.launch {
                pagerState.animateScrollToPage(
                    page = clamped,
                    animationSpec = HeastMotion.appleFluidSpring()
                )
            }
        }
    }

    LaunchedEffect(Unit) {
        if (!UpdateManager.hasCheckedThisSession) {
            UpdateManager.hasCheckedThisSession = true
            val update = UpdateManager.getInstance(context).checkForUpdate(force = false)
            if (update != null) {
                availableUpdate = update
            }
        }
    }

    val colors = HeastAppTheme.colors

    Box(modifier = Modifier.fillMaxSize().background(colors.background)) {
        Scaffold(
            containerColor = colors.background,
            bottomBar = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.background)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(colors.border.copy(alpha = 0.72f))
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .height(58.dp)
                            .pointerInput(pagerState.currentPage) {
                                detectHorizontalDragGestures(
                                    onDragStart = { navSwipeOffsetX = 0f },
                                    onDragEnd = {
                                        val currentIdx = pagerState.currentPage
                                        if (navSwipeOffsetX < -64f && currentIdx + 1 < navItems.size) {
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            navigateToPage(currentIdx + 1)
                                        } else if (navSwipeOffsetX > 64f && currentIdx - 1 >= 0) {
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            navigateToPage(currentIdx - 1)
                                        }
                                        navSwipeOffsetX = 0f
                                    },
                                    onDragCancel = { navSwipeOffsetX = 0f },
                                    onHorizontalDrag = { change, dragAmount ->
                                        navSwipeOffsetX += dragAmount
                                        change.consume()
                                    }
                                )
                            }
                            .padding(horizontal = 4.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        navItems.forEachIndexed { index, screen ->
                            val selected = pagerState.currentPage == index
                            val itemAlpha by animateFloatAsState(
                                targetValue = if (selected) 1f else 0.56f,
                                animationSpec = HeastMotion.appleSnappySpring(),
                                label = "nav_item_alpha"
                            )
                            val interactionSource = remember { MutableInteractionSource() }
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable(
                                        interactionSource = interactionSource,
                                        indication = null
                                    ) {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        navigateToPage(index)
                                    }
                                    .padding(vertical = 6.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(if (selected) colors.accent else Color.Transparent)
                                        .padding(horizontal = 15.dp, vertical = 5.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = screen.icon,
                                        contentDescription = stringResource(screen.titleRes),
                                        tint = if (selected) colors.background else colors.textMuted,
                                        modifier = Modifier.size(20.dp).graphicsLayer { alpha = itemAlpha }
                                    )
                                }
                                Spacer(modifier = Modifier.height(3.dp))
                                Text(
                                    text = stringResource(screen.titleRes),
                                    fontSize = 11.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (selected) colors.accent else colors.textMuted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.graphicsLayer {
                                        alpha = itemAlpha
                                    }
                                )
                            }
                        }
                    }
                }
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                HorizontalPager(
                    state = pagerState,
                    beyondBoundsPageCount = 0,
                    modifier = Modifier.fillMaxSize()
                ) { page ->
                    val pageOffsetProvider = {
                        (pagerState.currentPage - page) + pagerState.currentPageOffsetFraction
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .pagerMotionBlurTransition(pageOffsetProvider = pageOffsetProvider)
                    ) {
                        when (page) {
                            0 -> HomeScreen(
                                viewModel = viewModel,
                                onOpenSearchTab = { navigateToPage(1) }
                            )
                            1 -> SearchScreen(
                                viewModel = viewModel,
                                pageOffsetProvider = pageOffsetProvider
                            )
                            2 -> VibeScreen(viewModel = viewModel)
                            else -> LibraryScreen(viewModel = viewModel)
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(22.dp)
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(
                                0.0f to Color.Transparent,
                                1.0f to colors.background
                            )
                        )
                )

                AnimatedVisibility(
                    visible = currentTrack != null && !isExpanded,
                    modifier = Modifier.align(Alignment.BottomCenter),
                    enter = slideInVertically(
                        animationSpec = tween(260, easing = HeastMotion.EmphasizedDecelerate),
                        initialOffsetY = { it }
                    ) + fadeIn(tween(220)),
                    exit = slideOutVertically(
                        animationSpec = tween(220, easing = HeastMotion.EmphasizedAccelerate),
                        targetOffsetY = { it }
                    ) + fadeOut(tween(180))
                ) {
                    currentTrack?.let { activeTrack ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                        ) {
                            MiniPlayerContainer(
                                viewModel = viewModel,
                                track = activeTrack,
                                isPlaying = isPlaying,
                                isLoading = isLoadingTrack
                            )
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = currentTrack != null && !isExpanded,
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 5.dp),
            enter = fadeIn(tween(180)) + slideInVertically(tween(220)) { -it },
            exit = fadeOut(tween(140))
        ) {
            currentTrack?.let { active ->
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(28.dp))
                        .background(Color(0xF20B0E0C))
                        .border(1.dp, Color(0xFF39443C), RoundedCornerShape(28.dp))
                        .clickable { viewModel.expandPlayer(true) }
                        .padding(start = 7.dp, end = 9.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AsyncImage(
                        model = active.thumbnailUrlForDisplay(96),
                        contentDescription = null,
                        modifier = Modifier.size(34.dp).clip(CircleShape)
                    )
                    Column(modifier = Modifier.widthIn(max = 132.dp)) {
                        Text(active.title, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(active.artist, color = Color(0xFFB7C0B8), fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Icon(
                        imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = "Play/Pause",
                        tint = Color(0xFFBEF570),
                        modifier = Modifier.size(23.dp).clickable { viewModel.togglePlayPause() }
                    )
                    Icon(
                        imageVector = Icons.Filled.SkipNext,
                        contentDescription = "Next",
                        tint = Color.White,
                        modifier = Modifier.size(21.dp).clickable { viewModel.skipNext() }
                    )
                }
            }
        }

        val activeBrowseDetails by viewModel.activeBrowseDetails.collectAsState()
        BackHandler(enabled = !isExpanded && activeBrowseDetails == null && pagerState.currentPage != 0) {
            navigateToPage(0)
        }

        AnimatedVisibility(
            visible = isExpanded && currentTrack != null,
            enter = slideInVertically(
                animationSpec = tween(durationMillis = 300, easing = HeastMotion.EmphasizedDecelerate),
                initialOffsetY = { it }
            ) + fadeIn(animationSpec = tween(200, easing = HeastMotion.EmphasizedDecelerate)),
            exit = slideOutVertically(
                animationSpec = tween(durationMillis = 220, easing = HeastMotion.EmphasizedDecelerate),
                targetOffsetY = { it }
            ) + fadeOut(animationSpec = tween(170, easing = HeastMotion.EmphasizedDecelerate))
        ) {
            currentTrack?.let { track ->
                FullPlayerSheet(
                    track = track,
                    viewModel = viewModel,
                    isPlaying = isPlaying,
                    isLoading = isLoadingTrack,
                    isShuffle = isShuffle,
                    repeatMode = repeatMode,
                    onPlayPauseClick = { viewModel.togglePlayPause() },
                    onSkipNextClick = { viewModel.skipNext() },
                    onSkipPreviousClick = { viewModel.skipPrevious() },
                    onShuffleClick = { viewModel.toggleShuffle() },
                    onRepeatClick = { viewModel.toggleRepeat() },
                    onFavoriteClick = { viewModel.toggleFavorite(track) },
                    onDismiss = { viewModel.expandPlayer(false) }
                )
            }
        }

        /**
         * Declared after Scaffold and HorizontalPager so LIFO BackHandler dispatch
         * always collapses FullPlayerSheet before any child screen intercepts Back.
         */
        LaunchedEffect(isExpanded, currentTrack) {
            if (isExpanded && currentTrack == null) {
                viewModel.expandPlayer(false)
            }
        }

        BackHandler(enabled = isExpanded) {
            viewModel.expandPlayer(false)
        }

        availableUpdate?.let { updateInfo ->
            UpdateDialog(
                updateInfo = updateInfo,
                onDownloadClick = {
                    availableUpdate = null
                    UpdateManager.getInstance(context).startDownloadAndInstall(updateInfo)
                },
                onDismiss = {
                    availableUpdate = null
                }
            )
        }
    }
}

@Composable
fun MiniPlayerContainer(
    viewModel: MusicViewModel,
    track: Track,
    isPlaying: Boolean,
    isLoading: Boolean,
    modifier: Modifier = Modifier
) {
    MiniPlayer(
        track = track,
        isPlaying = isPlaying,
        isLoading = isLoading,
        progressProvider = { viewModel.getProgress() },
        onPlayPauseClick = { viewModel.togglePlayPause() },
        onSkipNextClick = { viewModel.skipNext() },
        onSkipPreviousClick = { viewModel.skipPrevious() },
        onFavoriteClick = { viewModel.toggleFavorite(track) },
        onAudioOutputClick = { viewModel.openAudioOutputSettings() },
        onSwipeDown = {
            if (isPlaying) {
                viewModel.togglePlayPause()
            }
        },
        onClick = { viewModel.expandPlayer(true) },
        modifier = modifier
    )
}
