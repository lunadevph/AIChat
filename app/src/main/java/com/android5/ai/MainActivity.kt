@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)

package com.android5.ai

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.material.icons.automirrored.rounded.NoteAdd
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import coil.compose.AsyncImage
import com.android5.ai.ui.theme.AIChatTheme
import com.android5.ai.ui.theme.ExpressiveButtonShapes
import com.android5.ai.ui.theme.ExpressiveIconButtonShapes
import com.mikepenz.markdown.coil2.Coil2ImageTransformerImpl
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var viewModel: ChatViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        val settingsManager = SettingsManager(this)
        setTheme(settingsManager.activityThemeStyle())

        if (!settingsManager.isSetupComplete) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }

        viewModel = androidx.lifecycle.ViewModelProvider(
            this,
            ChatViewModelFactory(application, settingsManager)
        )[ChatViewModel::class.java]

        enableEdgeToEdge()

        setContent {
            AIChatTheme(themeMode = settingsManager.themeMode) {
                ChatScreen(
                    viewModel = viewModel,
                    settingsManager = settingsManager,
                    onNavigateToSettings = {
                        startActivity(Intent(this, SettingsActivity::class.java))

                        if (
                            android.os.Build.VERSION.SDK_INT >=
                            android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                        ) {
                            overrideActivityTransition(
                                OVERRIDE_TRANSITION_OPEN,
                                android.R.anim.fade_in,
                                android.R.anim.fade_out
                            )
                        } else {
                            @Suppress("DEPRECATION")
                            overridePendingTransition(
                                android.R.anim.fade_in,
                                android.R.anim.fade_out
                            )
                        }
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()

        if (::viewModel.isInitialized) {
            viewModel.fetchModels()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    settingsManager: SettingsManager,
    onNavigateToSettings: () -> Unit
) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    var inputText by remember { mutableStateOf("") }

    // The expressive ("flexible") app bar grows while the reader is at the top of
    // the conversation and folds away as they scroll, instead of staying pinned.
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val listState = rememberLazyListState()

    var showMenu by remember { mutableStateOf(false) }
    var showModelSheet by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }
    // Derived from the persisted setting so a toggle changed in Settings is
    // reflected here immediately (a remember{} copy went stale until restart).
    val webSearchOn = settingsManager.webSearchEnabled
    var menuExpanded by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val webSearchDescription = stringResource(R.string.desc_web_search)

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        viewModel.selectedImageUri = uri
    }

    // One effect keeps the view pinned to the newest message: instantly while a
    // reply streams in, and with an animation once it lands. Two separate effects
    // used to fire together on every new message and cancel each other's scroll.
    val lastMessage = viewModel.messages.lastOrNull()
    LaunchedEffect(
        viewModel.messages.size,
        lastMessage?.content,
        lastMessage?.isStreaming
    ) {
        if (viewModel.messages.isEmpty()) return@LaunchedEffect
        val lastIndex = viewModel.messages.lastIndex
        if (lastMessage?.isStreaming == true) {
            listState.scrollToItem(lastIndex)
        } else {
            listState.animateScrollToItem(lastIndex)
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerShape = RoundedCornerShape(
                    topEnd = 32.dp,
                    bottomEnd = 32.dp
                ),
                drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                drawerTonalElevation = 2.dp,
                modifier = Modifier.widthIn(max = 320.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Spacer(Modifier.height(6.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AppLogo(
                            size = 38.dp,
                            contentDescription = stringResource(R.string.app_name)
                        )

                        Spacer(Modifier.width(10.dp))

                        Column {
                            Text(
                                stringResource(R.string.app_name),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )

                            Text(
                                stringResource(R.string.label_conversations),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    FilledTonalButton(
                        onClick = {
                            viewModel.createNewConversation()
                            scope.launch {
                                drawerState.close()
                            }
                        },
                        shapes = ExpressiveButtonShapes,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(46.dp)
                    ) {
                        Icon(
                            Icons.AutoMirrored.Rounded.NoteAdd,
                            contentDescription = null,
                            Modifier.size(18.dp)
                        )

                        Spacer(Modifier.width(8.dp))

                        Text(
                            stringResource(R.string.desc_new_chat),
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Spacer(Modifier.height(10.dp))

                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                    )

                    Spacer(Modifier.height(8.dp))

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        contentPadding = PaddingValues(bottom = 12.dp)
                    ) {
                        items(viewModel.conversations, key = { it.id }) { conv ->
                            val selected =
                                viewModel.currentConversation?.id == conv.id

                            Surface(
                                onClick = {
                                    viewModel.selectConversation(conv)
                                    scope.launch {
                                        drawerState.close()
                                    }
                                },
                                shape = RoundedCornerShape(16.dp),
                                color =
                                    if (selected) {
                                        MaterialTheme.colorScheme.primaryContainer
                                            .copy(alpha = 0.65f)
                                    } else {
                                        MaterialTheme.colorScheme.surfaceContainerHigh
                                    },
                                border =
                                    if (selected) {
                                        BorderStroke(
                                            1.5.dp,
                                            MaterialTheme.colorScheme.primary
                                        )
                                    } else {
                                        BorderStroke(
                                            1.dp,
                                            MaterialTheme.colorScheme.outlineVariant
                                                .copy(alpha = 0.3f)
                                        )
                                    },
                                tonalElevation = if (selected) 2.dp else 0.dp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .semantics {
                                        this.role = Role.Tab
                                        this.selected = selected
                                    }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(
                                            horizontal = 12.dp,
                                            vertical = 6.dp
                                        ),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Rounded.ChatBubbleOutline,
                                        // Decorative: every row is a chat, and the row
                                        // itself announces its title and selected state.
                                        contentDescription = null,
                                        Modifier.size(20.dp),
                                        tint =
                                            if (selected) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            }
                                    )

                                    Spacer(Modifier.width(12.dp))

                                    Text(
                                        conv.title,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontWeight =
                                            if (selected) {
                                                FontWeight.Bold
                                            } else {
                                                FontWeight.Normal
                                            },
                                        style = MaterialTheme.typography.bodyMedium
                                    )

                                    // Every row shows this button. Hiding it for the open
                                    // conversation made that row 28dp shorter than the
                                    // rest (the 48dp button is what sets row height) and
                                    // left the open chat as the one you could not delete.
                                    IconButton(
                                        onClick = {
                                            viewModel.deleteConversation(conv)
                                        },
                                        shapes = ExpressiveIconButtonShapes,
                                        modifier = Modifier.size(48.dp)
                                    ) {
                                        Icon(
                                            Icons.Rounded.Close,
                                            contentDescription = stringResource(R.string.desc_delete_conversation),
                                            Modifier.size(18.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                                .copy(alpha = 0.7f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    ) {
        if (showClearConfirm) {
            AlertDialog(
                onDismissRequest = {
                    showClearConfirm = false
                },
                icon = {
                    Icon(
                        Icons.Rounded.DeleteOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    )
                },
                title = {
                    Text(
                        stringResource(R.string.dialog_clear_title),
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Text(stringResource(R.string.dialog_clear_text))
                },
                confirmButton = {
                    Button(
                        onClick = {
                            viewModel.clearHistory()
                            showClearConfirm = false
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        ),
                        shapes = ExpressiveButtonShapes
                    ) {
                        Text(stringResource(R.string.btn_clear))
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            showClearConfirm = false
                        },
                        shapes = ExpressiveButtonShapes
                    ) {
                        Text(stringResource(R.string.btn_cancel))
                    }
                }
            )
        }

        Scaffold(
            modifier = Modifier.nestedScroll(
                scrollBehavior.nestedScrollConnection
            ),
            topBar = {
                CenterAlignedTopAppBar(
                    navigationIcon = {
                        IconButton(
                            onClick = {
                                scope.launch {
                                    drawerState.open()
                                }
                            },
                            shapes = ExpressiveIconButtonShapes
                        ) {
                            Icon(
                                Icons.Rounded.Menu,
                                stringResource(R.string.desc_open_menu)
                            )
                        }
                    },
                    // The mark, the name and the current model in one centred row,
                    // the way the bar carried them before the flexible app bar.
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            AppLogo(size = 30.dp)

                            Text(
                                text = stringResource(R.string.app_name),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                                // Bounded so the model pill keeps its room on the
                                // narrowest screens; the row is centred by the bar.
                                modifier = Modifier.widthIn(max = 96.dp)
                            )

                            Surface(
                                onClick = {
                                    showModelSheet = true
                                },
                                shape = RoundedCornerShape(50),
                                color = MaterialTheme.colorScheme.surfaceContainerHighest
                                    .copy(alpha = 0.8f),
                                border = BorderStroke(
                                    1.2.dp,
                                    MaterialTheme.colorScheme.outlineVariant
                                        .copy(alpha = 0.6f)
                                ),
                                tonalElevation = 2.dp
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(
                                        start = 6.dp,
                                        end = 8.dp,
                                        top = 4.dp,
                                        bottom = 4.dp
                                    )
                                ) {
                                    Surface(
                                        shape = CircleShape,
                                        color = MaterialTheme.colorScheme.surface,
                                        border = BorderStroke(
                                            0.8.dp,
                                            MaterialTheme.colorScheme.primary
                                                .copy(alpha = 0.3f)
                                        ),
                                        tonalElevation = 1.dp,
                                        modifier = Modifier.size(22.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Image(
                                                painter = painterResource(
                                                    providerIconRes(
                                                        settingsManager.selectedProvider
                                                    )
                                                ),
                                                contentDescription = null,
                                                modifier = Modifier
                                                    .size(15.dp)
                                                    .clip(CircleShape)
                                            )
                                        }
                                    }

                                    Spacer(Modifier.width(6.dp))

                                    val modelName =
                                        viewModel.availableModels.find {
                                            it.id == settingsManager.selectedModel
                                        }?.displayName
                                            ?: stringResource(
                                                R.string.label_select_model
                                            )

                                    Text(
                                        text = modelName,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.widthIn(max = 88.dp)
                                    )

                                    Spacer(Modifier.width(2.dp))

                                    Icon(
                                        Icons.Rounded.KeyboardArrowDown,
                                        null,
                                        Modifier.size(17.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    },
                    actions = {
                        Box {
                            IconButton(
                                onClick = {
                                    showMenu = true
                                },
                                shapes = ExpressiveIconButtonShapes
                            ) {
                                Icon(
                                    Icons.Rounded.MoreVert,
                                    stringResource(R.string.menu_more)
                                )
                            }

                            DropdownMenu(
                                expanded = showMenu,
                                onDismissRequest = {
                                    showMenu = false
                                },
                                shape = RoundedCornerShape(20.dp),
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                            ) {
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            stringResource(R.string.menu_change_model),
                                            fontWeight = FontWeight.Medium
                                        )
                                    },
                                    onClick = {
                                        showMenu = false
                                        showModelSheet = true
                                    },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Rounded.AutoAwesome,
                                            null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                )

                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            stringResource(R.string.menu_settings),
                                            fontWeight = FontWeight.Medium
                                        )
                                    },
                                    onClick = {
                                        showMenu = false
                                        onNavigateToSettings()
                                    },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Rounded.Settings,
                                            null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                )

                                HorizontalDivider(
                                    modifier = Modifier.padding(vertical = 4.dp)
                                )

                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            stringResource(R.string.menu_clear_history),
                                            fontWeight = FontWeight.Medium
                                        )
                                    },
                                    onClick = {
                                        showMenu = false
                                        showClearConfirm = true
                                    },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Rounded.DeleteOutline,
                                            null
                                        )
                                    },
                                    colors = MenuDefaults.itemColors(
                                        textColor = MaterialTheme.colorScheme.error,
                                        leadingIconColor = MaterialTheme.colorScheme.error
                                    )
                                )
                            }
                        }
                    }
                )
            },
            bottomBar = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .imePadding()
                        .navigationBarsPadding()
                        .padding(
                            horizontal = 12.dp,
                            vertical = 6.dp
                        )
                ) {
                    AnimatedVisibility(
                        visible = viewModel.isLoading,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically(),
                        modifier = Modifier.padding(
                            start = 8.dp,
                            bottom = 6.dp
                        )
                    ) {
                        ThinkingIndicator(
                            viewModel.loadingStatus,
                            viewModel.rateLimitRetryIn
                        )
                    }

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(28.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        border = BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.outlineVariant
                                .copy(alpha = 0.5f)
                        ),
                        tonalElevation = 3.dp
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(
                                    horizontal = 10.dp,
                                    vertical = 8.dp
                                )
                        ) {
                            if (viewModel.selectedImageUri != null) {
                                Box(
                                    modifier = Modifier.padding(
                                        start = 6.dp,
                                        top = 4.dp,
                                        bottom = 8.dp
                                    )
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(16.dp),
                                        border = BorderStroke(
                                            1.5.dp,
                                            MaterialTheme.colorScheme.primary
                                        )
                                    ) {
                                        AsyncImage(
                                            model = viewModel.selectedImageUri,
                                            contentDescription = stringResource(R.string.desc_attached_image),
                                            modifier = Modifier
                                                .size(72.dp)
                                                .clip(
                                                    RoundedCornerShape(16.dp)
                                                ),
                                            contentScale = ContentScale.Crop
                                        )
                                    }

                                    IconButton(
                                        onClick = {
                                            viewModel.selectedImageUri = null
                                        },
                                        shapes = ExpressiveIconButtonShapes,
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .offset(
                                                x = 8.dp,
                                                y = (-8).dp
                                            )
                                            .size(48.dp),
                                        colors = IconButtonDefaults.filledIconButtonColors(
                                            containerColor = MaterialTheme.colorScheme.errorContainer,
                                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                                        )
                                    ) {
                                        Icon(
                                            Icons.Rounded.Close,
                                            contentDescription = stringResource(R.string.desc_remove_image),
                                            Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Expressive toggle button: web search is a filter on
                                // the request, so it belongs next to the input.
                                ToggleButton(
                                    checked = webSearchOn,
                                    onCheckedChange = {
                                        settingsManager.webSearchEnabled = it
                                    },
                                    modifier = Modifier
                                        .padding(end = 6.dp)
                                        .semantics { contentDescription = webSearchDescription },
                                    contentPadding = PaddingValues(
                                        horizontal = 12.dp,
                                        vertical = 10.dp
                                    )
                                ) {
                                    Icon(
                                        Icons.Rounded.Public,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }

                                OutlinedTextField(
                                    value = inputText,
                                    onValueChange = {
                                        inputText = it
                                    },
                                    modifier = Modifier.weight(1f),
                                    placeholder = {
                                        Text(
                                            stringResource(
                                                R.string.placeholder_message
                                            ),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    },
                                    shape = RoundedCornerShape(24.dp),
                                    maxLines = 5,
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = Color.Transparent,
                                        unfocusedBorderColor = Color.Transparent,
                                        disabledBorderColor = Color.Transparent,
                                        focusedContainerColor = Color.Transparent,
                                        unfocusedContainerColor = Color.Transparent,
                                        cursorColor = MaterialTheme.colorScheme.primary,
                                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface
                                    )
                                )

                                Spacer(Modifier.width(6.dp))

                                // Expressive split button: the leading half sends (or
                                // stops), the trailing half opens the attach menu.
                                SplitButtonLayout(
                                    leadingButton = {
                                        if (viewModel.isLoading) {
                                            SplitButtonDefaults.LeadingButton(
                                                onClick = {
                                                    viewModel.stopGeneration()
                                                },
                                                colors = ButtonDefaults.buttonColors(
                                                    containerColor = MaterialTheme.colorScheme.error,
                                                    contentColor = MaterialTheme.colorScheme.onError
                                                )
                                            ) {
                                                Icon(
                                                    Icons.Rounded.Stop,
                                                    stringResource(R.string.desc_stop),
                                                    Modifier.size(20.dp)
                                                )
                                            }
                                        } else {
                                            val canSend =
                                                inputText.isNotBlank() ||
                                                    viewModel.selectedImageUri != null

                                            SplitButtonDefaults.LeadingButton(
                                                onClick = {
                                                    if (canSend) {
                                                        viewModel.sendMessage(
                                                            inputText,
                                                            context
                                                        )
                                                        inputText = ""
                                                    }
                                                },
                                                enabled = canSend
                                            ) {
                                                Icon(
                                                    Icons.AutoMirrored.Rounded.Send,
                                                    stringResource(R.string.desc_send),
                                                    Modifier.size(20.dp)
                                                )
                                            }
                                        }
                                    },
                                    trailingButton = {
                                        Box {
                                            SplitButtonDefaults.TonalTrailingButton(
                                                checked = menuExpanded,
                                                onCheckedChange = {
                                                    menuExpanded = it
                                                }
                                            ) {
                                                Icon(
                                                    Icons.Rounded.KeyboardArrowDown,
                                                    stringResource(R.string.desc_more_actions),
                                                    Modifier.size(20.dp)
                                                )
                                            }

                                            DropdownMenu(
                                                expanded = menuExpanded,
                                                onDismissRequest = {
                                                    menuExpanded = false
                                                },
                                                shape = RoundedCornerShape(20.dp),
                                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                                            ) {
                                                DropdownMenuItem(
                                                    leadingIcon = {
                                                        Icon(
                                                            Icons.Rounded.AddPhotoAlternate,
                                                            null,
                                                            tint = MaterialTheme.colorScheme.primary
                                                        )
                                                    },
                                                    text = {
                                                        Text(
                                                            stringResource(R.string.desc_add_image),
                                                            fontWeight = FontWeight.Medium
                                                        )
                                                    },
                                                    onClick = {
                                                        menuExpanded = false
                                                        imagePicker.launch("image/*")
                                                    }
                                                )

                                                DropdownMenuItem(
                                                    leadingIcon = {
                                                        Icon(
                                                            Icons.Rounded.Public,
                                                            null,
                                                            tint = MaterialTheme.colorScheme.primary
                                                        )
                                                    },
                                                    text = {
                                                        Text(
                                                            stringResource(R.string.desc_web_search),
                                                            fontWeight = FontWeight.Medium
                                                        )
                                                    },
                                                    trailingIcon = {
                                                        if (webSearchOn) {
                                                            Icon(
                                                                Icons.Rounded.Check,
                                                                null,
                                                                tint = MaterialTheme.colorScheme.primary
                                                            )
                                                        }
                                                    },
                                                    onClick = {
                                                        settingsManager.webSearchEnabled =
                                                            !settingsManager.webSearchEnabled
                                                        menuExpanded = false
                                                    }
                                                )
                                            }
                                        }
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
                if (viewModel.messages.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        AppLogo(size = 88.dp)

                        Spacer(Modifier.height(16.dp))

                        Text(
                            stringResource(R.string.empty_greeting),
                            style = MaterialTheme.typography.headlineMediumEmphasized,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center
                        )

                        Spacer(Modifier.height(8.dp))

                        Text(
                            stringResource(R.string.empty_suggestions_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )

                        Spacer(Modifier.height(16.dp))

                        val suggestions = listOf(
                            stringResource(R.string.suggestion_explain_title) to
                                stringResource(R.string.suggestion_explain_prompt),
                            stringResource(R.string.suggestion_debug_title) to
                                stringResource(R.string.suggestion_debug_prompt),
                            stringResource(R.string.suggestion_email_title) to
                                stringResource(R.string.suggestion_email_prompt),
                            stringResource(R.string.suggestion_ideas_title) to
                                stringResource(R.string.suggestion_ideas_prompt)
                        )

                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            suggestions.forEach { (title, prompt) ->
                                Surface(
                                    onClick = {
                                        inputText = prompt
                                    },
                                    shape = RoundedCornerShape(16.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    border = BorderStroke(
                                        1.dp,
                                        MaterialTheme.colorScheme.outlineVariant
                                            .copy(alpha = 0.4f)
                                    ),
                                    tonalElevation = 1.dp,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .semantics { this.role = Role.Button }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(
                                            horizontal = 14.dp,
                                            vertical = 10.dp
                                        ),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            title,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.weight(1f)
                                        )

                                        Icon(
                                            Icons.AutoMirrored.Rounded.ArrowForward,
                                            contentDescription = null,
                                            Modifier.size(18.dp),
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            horizontal = 14.dp,
                            vertical = 10.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(viewModel.messages, key = { it.id }) { message ->
                            MessageBubble(
                                message = message,
                                settingsManager = settingsManager,
                                onRetry = { viewModel.regenerateLast() },
                                onEdit = { content ->
                                    inputText = content
                                }
                            )
                        }
                    }
                }

                if (listState.canScrollForward) {
                    SmallFloatingActionButton(
                        onClick = {
                            scope.launch {
                                if (viewModel.messages.isNotEmpty()) {
                                    listState.animateScrollToItem(
                                        viewModel.messages.size - 1
                                    )
                                }
                            }
                        },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(
                                end = 20.dp,
                                bottom = 12.dp
                            ),
                        shape = ExpressiveIconButtonShapes.shape,
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ) {
                        Icon(
                            Icons.Rounded.KeyboardArrowDown,
                            contentDescription = stringResource(R.string.desc_scroll_to_bottom)
                        )
                    }
                }
            }
        }
    }

    if (showModelSheet) {
        ModelPickerSheet(
            settingsManager = settingsManager,
            models = viewModel.availableModels,
            onDismiss = {
                showModelSheet = false
            },
            onSelect = { modelId ->
                settingsManager.selectedModel = modelId
                showModelSheet = false
                viewModel.fetchModels()
            }
        )
    }
}

@Composable
fun ThinkingIndicator(status: LoadingStatus, rateLimitRetryIn: Int = 0) {
    val statusText =
        when (status) {
            LoadingStatus.SEARCHING ->
                stringResource(R.string.status_searching)

            LoadingStatus.CREATING_IMAGE ->
                stringResource(R.string.status_creating_image)

            LoadingStatus.RATE_LIMITED ->
                if (rateLimitRetryIn > 0) {
                    stringResource(R.string.status_rate_limited_in, rateLimitRetryIn)
                } else {
                    stringResource(R.string.status_rate_limited)
                }

            LoadingStatus.THINKING ->
                stringResource(R.string.status_thinking)

            else ->
                stringResource(R.string.status_typing)
        }

    // A rate limit is a wait rather than progress, so it gets its own tint.
    val throttled = status == LoadingStatus.RATE_LIMITED
    val containerColor = if (throttled) {
        MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.7f)
    } else {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
    }
    val contentColor = if (throttled) {
        MaterialTheme.colorScheme.onTertiaryContainer
    } else {
        MaterialTheme.colorScheme.primary
    }
    val outlineColor = if (throttled) {
        MaterialTheme.colorScheme.tertiary.copy(alpha = 0.4f)
    } else {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
    }

    Surface(
        shape = RoundedCornerShape(50),
        color = containerColor,
        border = BorderStroke(1.dp, outlineColor)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(
                horizontal = 14.dp,
                vertical = 8.dp
            )
        ) {
            // The expressive loading indicator morphs between shapes, which reads
            // as "working" without a spinner or a pulsing container.
            LoadingIndicator(
                modifier = Modifier.size(18.dp),
                color = contentColor
            )

            Spacer(Modifier.width(8.dp))

            Text(
                text = statusText,
                style = MaterialTheme.typography.labelLargeEmphasized,
                color = contentColor
            )
        }
    }
}

@Composable
fun BlinkingCursorBar(
    width: Dp = 2.dp,
    height: Dp = 18.dp,
    modifier: Modifier = Modifier
) {
    val infiniteTransition =
        rememberInfiniteTransition(label = "cursor")

    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.05f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                500,
                easing = LinearEasing
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "cursor_alpha"
    )

    Box(
        modifier = modifier
            .width(width)
            .height(height)
            .alpha(alpha)
            .background(
                MaterialTheme.colorScheme.primary,
                RoundedCornerShape(1.dp)
            )
    )
}

@Composable
fun TypingIndicatorRow() {
    Box(
        modifier = Modifier
            .widthIn(max = 340.dp)
            .padding(
                horizontal = 16.dp,
                vertical = 10.dp
            ),
        contentAlignment = Alignment.Center
    ) {
        LoadingIndicator(
            modifier = Modifier.size(20.dp),
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
fun StreamingText(
    content: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    val cursorTag = "\uFFFC"

    // Material typography already gives us a TextUnit.
    // Placeholder.width/height require TextUnit, NOT Dp.
    val fontSize = MaterialTheme.typography.bodyLarge.fontSize

    Text(
        text = buildAnnotatedString {
            append(content)
            appendInlineContent(cursorTag, " ")
        },
        inlineContent = mapOf(
            cursorTag to InlineTextContent(
                Placeholder(
                    width = 4.sp,
                    height = fontSize,
                    placeholderVerticalAlign =
                        PlaceholderVerticalAlign.TextCenter
                )
            ) {
                BlinkingCursorBar(
                    width = 2.5.dp,
                    height = with(
                        androidx.compose.ui.platform.LocalDensity.current
                    ) {
                        fontSize.toDp()
                    }
                )
            }
        ),
        style = MaterialTheme.typography.bodyLarge,
        color = color,
        modifier = modifier.padding(
            horizontal = 16.dp,
            vertical = 12.dp
        )
    )
}

@Composable
fun MessageBubble(
    message: Message,
    settingsManager: SettingsManager,
    onRetry: () -> Unit = {},
    onEdit: (String) -> Unit = {}
) {
    val isUser = message.role == "user"

    val alignment =
        if (isUser) Alignment.End else Alignment.Start

    val bubbleColor =
        if (isUser) {
            MaterialTheme.colorScheme.primaryContainer
                .copy(alpha = 0.9f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        }

    val contentColor =
        if (isUser) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        }

    val shape =
        if (isUser) {
            RoundedCornerShape(
                topStart = 24.dp,
                topEnd = 6.dp,
                bottomStart = 24.dp,
                bottomEnd = 24.dp
            )
        } else {
            RoundedCornerShape(
                topStart = 6.dp,
                topEnd = 24.dp,
                bottomStart = 24.dp,
                bottomEnd = 24.dp
            )
        }

    val clipboardManager =
        LocalClipboardManager.current

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment
    ) {
        Row(
            verticalAlignment = Alignment.Bottom
        ) {
            Column(
                horizontalAlignment = alignment
            ) {
                if (
                    !isUser &&
                    settingsManager.showThinking &&
                    !message.reasoning.isNullOrBlank()
                ) {
                    var showThinking by remember(message.id) {
                        mutableStateOf(false)
                    }

                    Surface(
                        modifier = Modifier
                            .padding(bottom = 6.dp)
                            .animateContentSize(),
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        border = BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.outlineVariant
                                .copy(alpha = 0.4f)
                        )
                    ) {
                        Column(
                            modifier = Modifier.widthIn(max = 340.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clickable {
                                        showThinking = !showThinking
                                    }
                                    .padding(
                                        horizontal = 14.dp,
                                        vertical = 10.dp
                                    )
                            ) {
                                Icon(
                                    Icons.Rounded.Psychology,
                                    null,
                                    Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )

                                Spacer(Modifier.width(8.dp))

                                Text(
                                    text =
                                        if (showThinking) {
                                            stringResource(R.string.desc_hide_thoughts)
                                        } else {
                                            stringResource(R.string.desc_show_thoughts)
                                        },
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )

                                Spacer(Modifier.weight(1f))

                                Icon(
                                    if (showThinking) {
                                        Icons.Rounded.ExpandLess
                                    } else {
                                        Icons.Rounded.ExpandMore
                                    },
                                    contentDescription = null,
                                    Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }

                            AnimatedVisibility(
                                visible = showThinking
                            ) {
                                Text(
                                    text = message.reasoning.orEmpty(),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontStyle = FontStyle.Italic,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(
                                        start = 14.dp,
                                        end = 14.dp,
                                        bottom = 14.dp
                                    )
                                )
                            }
                        }
                    }
                }

                if (message.localImageUrl != null) {
                    Surface(
                        modifier = Modifier
                            .padding(bottom = 6.dp)
                            .widthIn(max = 260.dp),
                        shape = RoundedCornerShape(22.dp),
                        color = bubbleColor,
                        border = BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.outlineVariant
                                .copy(alpha = 0.5f)
                        )
                    ) {
                        AsyncImage(
                            model = message.localImageUrl,
                            contentDescription = stringResource(R.string.desc_user_image),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(20.dp)),
                            contentScale = ContentScale.Fit
                        )
                    }
                }

                if (
                    message.content.isBlank() &&
                    message.isStreaming &&
                    !isUser
                ) {
                    Surface(
                        color = bubbleColor,
                        contentColor = contentColor,
                        shape = shape,
                        border = BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.outlineVariant
                                .copy(alpha = 0.4f)
                        ),
                        tonalElevation = 1.dp,
                        modifier = Modifier.widthIn(max = 340.dp)
                    ) {
                        TypingIndicatorRow()
                    }
                }

                if (!isUser && !message.tools.isNullOrEmpty()) {
                    ToolRunRow(tools = message.tools, alignment = alignment)
                }

                if (message.content.isNotBlank()) {
                    Surface(
                        color = bubbleColor,
                        contentColor = contentColor,
                        shape = shape,
                        border = BorderStroke(
                            1.dp,
                            if (isUser) {
                                MaterialTheme.colorScheme.primary
                                    .copy(alpha = 0.3f)
                            } else {
                                MaterialTheme.colorScheme.outlineVariant
                                    .copy(alpha = 0.4f)
                            }
                        ),
                        tonalElevation = 1.dp,
                        modifier = Modifier.widthIn(max = 340.dp)
                    ) {
                        Column {
                            val content = message.content

                            if (!isUser && message.isStreaming) {
                                StreamingText(
                                    content = content,
                                    color = contentColor
                                )
                            } else if (content.contains("```")) {
                                val parts =
                                    content.split("```")

                                parts.forEachIndexed { index, part ->
                                    if (index % 2 == 1) {
                                        val trimmedPart =
                                            part.trim()

                                        val lang =
                                            trimmedPart
                                                .substringBefore("\n")
                                                .trim()

                                        val code =
                                            trimmedPart
                                                .substringAfter("\n", "")
                                                .trim()

                                        Surface(
                                            modifier = Modifier
                                                .padding(8.dp)
                                                .fillMaxWidth(),
                                            shape = RoundedCornerShape(16.dp),
                                            color = MaterialTheme.colorScheme.surfaceContainerLowest,
                                            border = BorderStroke(
                                                1.dp,
                                                MaterialTheme.colorScheme.outlineVariant
                                                    .copy(alpha = 0.4f)
                                            )
                                        ) {
                                            Column {
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .background(
                                                            MaterialTheme.colorScheme.surfaceContainerHighest
                                                        )
                                                        .padding(
                                                            horizontal = 12.dp,
                                                            vertical = 6.dp
                                                        ),
                                                    horizontalArrangement =
                                                        Arrangement.SpaceBetween,
                                                    verticalAlignment =
                                                        Alignment.CenterVertically
                                                ) {
                                                    Text(
                                                        text =
                                                            if (
                                                                lang.isNotEmpty() &&
                                                                !lang.contains(" ")
                                                            ) {
                                                                lang.uppercase()
                                                            } else {
                                                                stringResource(
                                                                    R.string.label_code_block
                                                                ).uppercase()
                                                            },
                                                        style = MaterialTheme.typography.labelSmall,
                                                        fontWeight = FontWeight.Bold,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )

                                                    IconButton(
                                                        onClick = {
                                                            clipboardManager.setText(
                                                                AnnotatedString(
                                                                    code
                                                                )
                                                            )
                                                        },
                                                        modifier = Modifier.size(48.dp),
                                                        shapes = ExpressiveIconButtonShapes
                                                    ) {
                                                        Icon(
                                                            Icons.Rounded.ContentCopy,
                                                            contentDescription = stringResource(R.string.desc_copy_code),
                                                            Modifier.size(18.dp),
                                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                    }
                                                }

                                                Markdown(
                                                    content = "```$part```",
                                                    modifier = Modifier.padding(
                                                        horizontal = 12.dp,
                                                        vertical = 8.dp
                                                    ),
                                                    colors = markdownColor(
                                                        text = MaterialTheme.colorScheme.onSurface,
                                                        codeBackground = Color.Transparent
                                                    ),
                                                    typography = markdownTypography(
                                                        text = MaterialTheme.typography.bodyMedium
                                                    ),
                                                    imageTransformer =
                                                        Coil2ImageTransformerImpl
                                                )
                                            }
                                        }
                                    } else if (part.isNotBlank()) {
                                        Markdown(
                                            content = part,
                                            modifier = Modifier.padding(
                                                horizontal = 14.dp,
                                                vertical = 8.dp
                                            ),
                                            colors = markdownColor(
                                                text = contentColor
                                            ),
                                            typography = markdownTypography(
                                                text = MaterialTheme.typography.bodyLarge
                                            ),
                                            imageTransformer =
                                                Coil2ImageTransformerImpl
                                        )
                                    }
                                }
                            } else {
                                Markdown(
                                    content = content,
                                    modifier = Modifier.padding(
                                        horizontal = 14.dp,
                                        vertical = 10.dp
                                    ),
                                    colors = markdownColor(
                                        text = contentColor
                                    ),
                                    typography = markdownTypography(
                                        text = MaterialTheme.typography.bodyLarge
                                    ),
                                    imageTransformer =
                                        Coil2ImageTransformerImpl
                                )
                            }

                            val context = LocalContext.current
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(
                                        horizontal = 4.dp,
                                        vertical = 0.dp
                                    ),
                                horizontalArrangement = if (isUser) Arrangement.Start else Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // 1. Copy Action
                                IconButton(
                                    onClick = {
                                        clipboardManager.setText(
                                            AnnotatedString(message.content)
                                        )
                                    },
                                    modifier = Modifier.size(48.dp),
                                    shapes = ExpressiveIconButtonShapes
                                ) {
                                    Icon(
                                        Icons.Rounded.ContentCopy,
                                        contentDescription = stringResource(R.string.desc_action_copy),
                                        modifier = Modifier.size(17.dp),
                                        tint = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                    )
                                }

                                // 2. Share Action
                                IconButton(
                                    onClick = {
                                        val sendIntent = Intent().apply {
                                            action = Intent.ACTION_SEND
                                            putExtra(Intent.EXTRA_TEXT, message.content)
                                            type = "text/plain"
                                        }
                                        context.startActivity(Intent.createChooser(sendIntent, null))
                                    },
                                    modifier = Modifier.size(48.dp),
                                    shapes = ExpressiveIconButtonShapes
                                ) {
                                    Icon(
                                        Icons.Rounded.Share,
                                        contentDescription = stringResource(R.string.desc_action_share),
                                        modifier = Modifier.size(17.dp),
                                        tint = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                    )
                                }

                                if (!isUser) {
                                    // 3. Retry / Regenerate Action for AI Responses
                                    IconButton(
                                        onClick = onRetry,
                                        modifier = Modifier.size(48.dp),
                                        shapes = ExpressiveIconButtonShapes
                                    ) {
                                        Icon(
                                            Icons.Rounded.Refresh,
                                            contentDescription = stringResource(R.string.desc_action_retry),
                                            modifier = Modifier.size(18.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                        )
                                    }
                                } else {
                                    // 4. Edit Action for User Messages
                                    IconButton(
                                        onClick = { onEdit(message.content) },
                                        modifier = Modifier.size(48.dp),
                                        shapes = ExpressiveIconButtonShapes
                                    ) {
                                        Icon(
                                            Icons.Rounded.Edit,
                                            contentDescription = stringResource(R.string.desc_action_edit),
                                            modifier = Modifier.size(17.dp),
                                            tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Icon shown on the pill for each tool the assistant can run. */
private fun toolIcon(name: String) = when (name) {
    "read" -> Icons.Rounded.Description
    "write" -> Icons.Rounded.Save
    "list" -> Icons.AutoMirrored.Rounded.FormatListBulleted
    "delete" -> Icons.Rounded.DeleteOutline
    "search" -> Icons.Rounded.Search
    "memory" -> Icons.Rounded.Psychology
    "compact" -> Icons.Rounded.Compress
    "websearch" -> Icons.Rounded.Public
    "time" -> Icons.Rounded.Schedule
    else -> Icons.Rounded.Build
}

/**
 * The tools a reply used, as pills above its bubble. Tool activity is shown
 * rather than hidden: running on the device is the whole point of it.
 */
@Composable
fun ToolRunRow(tools: List<ToolRun>, alignment: Alignment.Horizontal) {
    var expandedIndex by remember(tools) { mutableStateOf(-1) }
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp),
        horizontalAlignment = alignment
    ) {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(R.string.label_tools_used),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            tools.forEachIndexed { index, tool ->
                // Only the file tools can be opened; the rest stay plain pills.
                val contentDescription = stringResource(
                    if (tool.ok) R.string.desc_tool_ran else R.string.desc_tool_failed,
                    tool.name
                )
                val openable = !tool.path.isNullOrBlank()
                val expanded = expandedIndex == index

                Surface(
                    onClick = {
                        expandedIndex = if (expanded) -1 else index
                    },
                    enabled = openable,
                    shape = RoundedCornerShape(50),
                    color = if (tool.ok) {
                        if (expanded) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            MaterialTheme.colorScheme.secondaryContainer
                                .copy(alpha = 0.75f)
                        }
                    } else {
                        MaterialTheme.colorScheme.errorContainer
                    },
                    contentColor = if (tool.ok) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onErrorContainer
                    },
                    tonalElevation = 1.dp,
                    modifier = Modifier.semantics {
                        this.contentDescription = contentDescription
                        if (openable) this.role = Role.Button
                    }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            toolIcon(tool.name),
                            contentDescription = null,
                            modifier = Modifier.size(13.dp)
                        )

                        Spacer(Modifier.width(5.dp))

                        Text(
                            text = if (tool.label.isBlank()) tool.name else "${tool.name} · ${tool.label}",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 200.dp)
                        )
                    }
                }
            }
        }

        val expandedTool = tools.getOrNull(expandedIndex)
        if (expandedTool?.path != null) {
            Spacer(Modifier.height(6.dp))

            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                ),
                tonalElevation = 1.dp,
                modifier = Modifier.widthIn(max = 320.dp)
            ) {
                Row(
                    modifier = Modifier.padding(
                        start = 12.dp,
                        end = 4.dp,
                        top = 6.dp,
                        bottom = 6.dp
                    ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        toolIcon(expandedTool.name),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )

                    Spacer(Modifier.width(10.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(
                                if (expandedTool.ok) {
                                    R.string.label_tool_used
                                } else {
                                    R.string.label_tool_failed
                                },
                                expandedTool.name
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )

                        Text(
                            expandedTool.path,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    TextButton(
                        onClick = {
                            context.startActivity(
                                Intent(context, ToolFilesActivity::class.java)
                                    .putExtra(
                                        ToolFilesActivity.EXTRA_FILE,
                                        expandedTool.path
                                    )
                            )
                        }
                    ) {
                        Text(
                            stringResource(R.string.btn_view_file),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

class ChatViewModelFactory(
    private val application: android.app.Application,
    private val settingsManager: SettingsManager
) : androidx.lifecycle.ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : androidx.lifecycle.ViewModel> create(
        modelClass: Class<T>
    ): T {
        return ChatViewModel(
            application,
            settingsManager
        ) as T
    }
}
