@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

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

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val listState = rememberLazyListState()

    var showMenu by remember { mutableStateOf(false) }
    var showModelSheet by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var webSearchOn by remember { mutableStateOf(settingsManager.webSearchEnabled) }
    var menuExpanded by remember { mutableStateOf(false) }

    val context = LocalContext.current

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        viewModel.selectedImageUri = uri
    }

    LaunchedEffect(viewModel.messages.size) {
        if (viewModel.messages.isNotEmpty()) {
            listState.animateScrollToItem(viewModel.messages.size - 1)
        }
    }

    LaunchedEffect(
        viewModel.messages.size,
        viewModel.messages.lastOrNull()?.content,
        viewModel.messages.lastOrNull()?.isStreaming
    ) {
        if (
            viewModel.messages.isNotEmpty() &&
            viewModel.messages.last().isStreaming
        ) {
            listState.scrollToItem(viewModel.messages.size - 1)
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
                        .padding(horizontal = 16.dp, vertical = 16.dp)
                ) {
                    Spacer(Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                            modifier = Modifier.size(40.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Rounded.AutoAwesome,
                                    contentDescription = stringResource(R.string.app_name),
                                    Modifier.size(22.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }

                        Spacer(Modifier.width(12.dp))

                        Column {
                            Text(
                                stringResource(R.string.app_name),
                                style = MaterialTheme.typography.titleLarge,
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

                    Spacer(Modifier.height(16.dp))

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
                            .height(48.dp)
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

                    Spacer(Modifier.height(16.dp))

                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                    )

                    Spacer(Modifier.height(12.dp))

                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(bottom = 16.dp)
                    ) {
                        items(viewModel.conversations) { conv ->
                            val selected =
                                viewModel.currentConversation?.id == conv.id

                            Surface(
                                onClick = {
                                    viewModel.selectConversation(conv)
                                    scope.launch {
                                        drawerState.close()
                                    }
                                },
                                shape = RoundedCornerShape(20.dp),
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
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(
                                            horizontal = 14.dp,
                                            vertical = 12.dp
                                        ),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Rounded.ChatBubbleOutline,
                                        contentDescription = stringResource(R.string.desc_conversation_icon),
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

                                    if (
                                        viewModel.currentConversation?.id != conv.id
                                    ) {
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
                TopAppBar(
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
                                "Menu"
                            )
                        }
                    },
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.primaryContainer
                                    .copy(alpha = 0.85f),
                                border = BorderStroke(
                                    1.dp,
                                    MaterialTheme.colorScheme.primary
                                        .copy(alpha = 0.35f)
                                ),
                                tonalElevation = 2.dp,
                                modifier = Modifier.size(34.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Rounded.AutoAwesome,
                                        contentDescription = null,
                                        modifier = Modifier.size(19.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }

                            Text(
                                text = stringResource(R.string.app_name),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                softWrap = false
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
                                        modifier = Modifier.widthIn(max = 125.dp)
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
                    },
                    scrollBehavior = scrollBehavior
                )
            },
            bottomBar = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .imePadding()
                        .navigationBarsPadding()
                        .padding(
                            horizontal = 16.dp,
                            vertical = 8.dp
                        )
                ) {
                    AnimatedVisibility(
                        visible = viewModel.isLoading,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically(),
                        modifier = Modifier.padding(
                            start = 8.dp,
                            bottom = 8.dp
                        )
                    ) {
                        ThinkingIndicator(viewModel.loadingStatus)
                    }

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(32.dp),
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
                                Box {
                                    IconButton(
                                        onClick = {
                                            menuExpanded = true
                                        },
                                        shapes = ExpressiveIconButtonShapes
                                    ) {
                                        Icon(
                                            Icons.Rounded.AddCircleOutline,
                                            stringResource(R.string.menu_more),
                                            tint = MaterialTheme.colorScheme.primary
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
                                                webSearchOn = !webSearchOn
                                                settingsManager.webSearchEnabled =
                                                    webSearchOn
                                                menuExpanded = false
                                            }
                                        )
                                    }
                                }

                                if (webSearchOn) {
                                    Surface(
                                        onClick = {
                                            webSearchOn = false
                                            settingsManager.webSearchEnabled = false
                                        },
                                        shape = RoundedCornerShape(50),
                                        color = MaterialTheme.colorScheme.primaryContainer
                                            .copy(alpha = 0.7f),
                                        border = BorderStroke(
                                            1.dp,
                                            MaterialTheme.colorScheme.primary
                                                .copy(alpha = 0.5f)
                                        )
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.padding(
                                                horizontal = 8.dp,
                                                vertical = 4.dp
                                            )
                                        ) {
                                            Icon(
                                                Icons.Rounded.Public,
                                                contentDescription = null,
                                                Modifier.size(14.dp),
                                                tint = MaterialTheme.colorScheme.primary
                                            )

                                            Spacer(Modifier.width(4.dp))

                                            Text(
                                                stringResource(R.string.label_web_badge),
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }

                                    Spacer(Modifier.width(4.dp))
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

                                if (viewModel.isLoading) {
                                    FilledIconButton(
                                        onClick = {
                                            viewModel.stopGeneration()
                                        },
                                        modifier = Modifier.size(44.dp),
                                        shapes = ExpressiveIconButtonShapes,
                                        colors = IconButtonDefaults.filledIconButtonColors(
                                            containerColor = MaterialTheme.colorScheme.error,
                                            contentColor = MaterialTheme.colorScheme.onError
                                        )
                                    ) {
                                        Icon(
                                            Icons.Rounded.Stop,
                                            stringResource(R.string.desc_stop)
                                        )
                                    }
                                } else {
                                    val canSend =
                                        inputText.isNotBlank() ||
                                            viewModel.selectedImageUri != null

                                    FilledIconButton(
                                        onClick = {
                                            if (canSend) {
                                                viewModel.sendMessage(
                                                    inputText,
                                                    context
                                                )
                                                inputText = ""
                                            }
                                        },
                                        enabled = canSend,
                                        modifier = Modifier.size(44.dp),
                                        shapes = ExpressiveIconButtonShapes,
                                        colors = IconButtonDefaults.filledIconButtonColors(
                                            containerColor = MaterialTheme.colorScheme.primary,
                                            contentColor = MaterialTheme.colorScheme.onPrimary,
                                            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                                                .copy(alpha = 0.5f)
                                        )
                                    ) {
                                        Icon(
                                            Icons.AutoMirrored.Rounded.Send,
                                            stringResource(R.string.desc_send),
                                            Modifier.size(20.dp)
                                        )
                                    }
                                }
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
                        Surface(
                            shape = RoundedCornerShape(32.dp),
                            color = MaterialTheme.colorScheme.primaryContainer
                                .copy(alpha = 0.7f),
                            modifier = Modifier.size(88.dp),
                            tonalElevation = 2.dp
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Rounded.AutoAwesome,
                                    null,
                                    Modifier.size(44.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }

                        Spacer(Modifier.height(20.dp))

                        Text(
                            stringResource(R.string.empty_greeting),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
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

                        Spacer(Modifier.height(24.dp))

                        val suggestions = listOf(
                            "⚡ Explain a complex concept simply" to
                                "Explain quantum computing in simple terms with an everyday analogy.",
                            "💻 Help me debug code" to
                                "Help me find and fix common performance bottlenecks in Android Kotlin.",
                            "✍️ Draft a clear email" to
                                "Draft a polite and concise email requesting a project update.",
                            "💡 Brainstorm creative ideas" to
                                "Give me 5 creative ideas for a weekend coding project."
                        )

                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            suggestions.forEach { (title, prompt) ->
                                Surface(
                                    onClick = {
                                        inputText = prompt
                                    },
                                    shape = RoundedCornerShape(20.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    border = BorderStroke(
                                        1.dp,
                                        MaterialTheme.colorScheme.outlineVariant
                                            .copy(alpha = 0.4f)
                                    ),
                                    tonalElevation = 1.dp,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(
                                            horizontal = 16.dp,
                                            vertical = 12.dp
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
                                            null,
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
                            horizontal = 16.dp,
                            vertical = 12.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(viewModel.messages) { message ->
                            MessageBubble(
                                message,
                                settingsManager
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
        ModalBottomSheet(
            onDismissRequest = {
                showModelSheet = false
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ) {
            var visionOnly by remember {
                mutableStateOf(false)
            }

            var searchQuery by remember {
                mutableStateOf("")
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 36.dp)
            ) {
                Column(
                    modifier = Modifier.padding(
                        horizontal = 20.dp,
                        vertical = 8.dp
                    )
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.primaryContainer
                                .copy(alpha = 0.7f),
                            modifier = Modifier.size(40.dp)
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
                                        .size(22.dp)
                                        .clip(CircleShape)
                                )
                            }
                        }

                        Spacer(Modifier.width(12.dp))

                        Column(
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                stringResource(R.string.menu_change_model),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )

                            Text(
                                settingsManager.selectedProvider,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilterChip(
                            selected = !visionOnly,
                            onClick = {
                                visionOnly = false
                            },
                            label = {
                                Text(
                                    stringResource(R.string.label_all_models),
                                    fontWeight = FontWeight.SemiBold
                                )
                            },
                            leadingIcon =
                                if (!visionOnly) {
                                    {
                                        Icon(
                                            Icons.Rounded.Check,
                                            null,
                                            Modifier.size(16.dp)
                                        )
                                    }
                                } else {
                                    null
                                },
                            shape = RoundedCornerShape(12.dp)
                        )

                        FilterChip(
                            selected = visionOnly,
                            onClick = {
                                visionOnly = true
                            },
                            label = {
                                Text(
                                    stringResource(R.string.label_vision),
                                    fontWeight = FontWeight.SemiBold
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Rounded.Visibility,
                                    null,
                                    Modifier.size(16.dp)
                                )
                            },
                            shape = RoundedCornerShape(12.dp)
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = {
                            searchQuery = it
                        },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = {
                            Text(
                                stringResource(
                                    R.string.placeholder_search_models
                                )
                            )
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Rounded.Search,
                                null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(
                                    onClick = {
                                        searchQuery = ""
                                    },
                                    shapes = ExpressiveIconButtonShapes
                                ) {
                                    Icon(
                                        Icons.Rounded.Clear,
                                        contentDescription = stringResource(R.string.btn_clear_text),
                                        Modifier.size(18.dp)
                                    )
                                }
                            }
                        },
                        shape = RoundedCornerShape(22.dp),
                        singleLine = true
                    )
                }

                val filteredModels =
                    viewModel.availableModels.filter {
                        val matchesSearch =
                            (it.name?.contains(
                                searchQuery,
                                ignoreCase = true
                            ) == true) ||
                                it.displayName.contains(
                                    searchQuery,
                                    ignoreCase = true
                                ) ||
                                it.id.contains(
                                    searchQuery,
                                    ignoreCase = true
                                ) ||
                                (it.description?.contains(
                                    searchQuery,
                                    ignoreCase = true
                                ) == true)

                        val matchesVision =
                            if (visionOnly) {
                                it.capabilities.contains("vision")
                            } else {
                                true
                            }

                        matchesSearch && matchesVision
                    }

                if (filteredModels.isEmpty()) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(140.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            stringResource(R.string.empty_models_found),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 440.dp)
                            .padding(horizontal = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(bottom = 8.dp)
                    ) {
                        items(filteredModels) { model ->
                            val selected =
                                settingsManager.selectedModel == model.id

                            Surface(
                                onClick = {
                                    settingsManager.selectedModel = model.id
                                    showModelSheet = false
                                    viewModel.fetchModels()
                                },
                                shape = RoundedCornerShape(22.dp),
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
                                            2.dp,
                                            MaterialTheme.colorScheme.primary
                                        )
                                    } else {
                                        BorderStroke(
                                            1.dp,
                                            MaterialTheme.colorScheme.outlineVariant
                                                .copy(alpha = 0.4f)
                                        )
                                    },
                                tonalElevation =
                                    if (selected) 2.dp else 0.dp,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(
                                            horizontal = 16.dp,
                                            vertical = 14.dp
                                        ),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                model.displayName,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                fontWeight = FontWeight.Bold,
                                                style = MaterialTheme.typography.titleMedium,
                                                modifier = Modifier.weight(
                                                    1f,
                                                    fill = false
                                                )
                                            )

                                            if (
                                                model.capabilities.contains(
                                                    "vision"
                                                )
                                            ) {
                                                Spacer(Modifier.width(6.dp))

                                                Surface(
                                                    shape = RoundedCornerShape(50),
                                                    color = MaterialTheme.colorScheme.secondaryContainer
                                                        .copy(alpha = 0.8f)
                                                ) {
                                                    Row(
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        modifier = Modifier.padding(
                                                            horizontal = 6.dp,
                                                            vertical = 2.dp
                                                        )
                                                    ) {
                                                        Icon(
                                                            Icons.Rounded.Visibility,
                                                            contentDescription = null,
                                                            modifier = Modifier.size(12.dp),
                                                            tint = MaterialTheme.colorScheme.onSecondaryContainer
                                                        )

                                                        Spacer(Modifier.width(3.dp))

                                                        Text(
                                                            stringResource(
                                                                R.string.label_vision
                                                            ),
                                                            style = MaterialTheme.typography.labelSmall,
                                                            fontWeight = FontWeight.Bold,
                                                            color = MaterialTheme.colorScheme.onSecondaryContainer
                                                        )
                                                    }
                                                }
                                            }
                                        }

                                        val cleanDesc =
                                            model.description?.removePrefix(
                                                "models/"
                                            )

                                        if (!cleanDesc.isNullOrBlank()) {
                                            Spacer(Modifier.height(2.dp))

                                            Text(
                                                cleanDesc,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }

                                        if (
                                            model.id != model.displayName &&
                                            !model.id.equals(
                                                model.displayName,
                                                ignoreCase = true
                                            )
                                        ) {
                                            Spacer(Modifier.height(2.dp))

                                            Text(
                                                model.cleanId,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    .copy(alpha = 0.65f)
                                            )
                                        }
                                    }

                                    if (selected) {
                                        Spacer(Modifier.width(12.dp))

                                        Icon(
                                            Icons.Rounded.CheckCircle,
                                            null,
                                            Modifier.size(24.dp),
                                            tint = MaterialTheme.colorScheme.primary
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

@Composable
fun ThinkingIndicator(status: LoadingStatus) {
    val infiniteTransition =
        rememberInfiniteTransition(label = "thinking")

    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                900,
                easing = LinearEasing
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    val statusText =
        when (status) {
            LoadingStatus.SEARCHING ->
                stringResource(R.string.status_searching)

            LoadingStatus.CREATING_IMAGE ->
                stringResource(R.string.status_creating_image)

            LoadingStatus.THINKING ->
                stringResource(R.string.status_thinking)

            else ->
                stringResource(R.string.status_typing)
        }

    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.primaryContainer
            .copy(alpha = 0.6f),
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
        ),
        modifier = Modifier.alpha(alpha)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(
                horizontal = 14.dp,
                vertical = 6.dp
            )
        ) {
            Icon(
                Icons.Rounded.AutoAwesome,
                null,
                Modifier.size(16.dp),
                MaterialTheme.colorScheme.primary
            )

            Spacer(Modifier.width(8.dp))

            Text(
                text = statusText,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
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
    Column(
        modifier = Modifier
            .widthIn(max = 340.dp)
            .padding(
                horizontal = 16.dp,
                vertical = 12.dp
            )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            BlinkingCursorBar()
        }
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
    settingsManager: SettingsManager
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
                                                .substringAfter("\n")
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
                                                                "CODE"
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
                                                horizontal = 16.dp,
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
                                }
                            } else {
                                Markdown(
                                    content = content,
                                    modifier = Modifier.padding(
                                        horizontal = 16.dp,
                                        vertical = 12.dp
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

                            if (!isUser) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(
                                            end = 8.dp,
                                            bottom = 4.dp
                                        ),
                                    horizontalArrangement =
                                        Arrangement.End
                                ) {
                                    IconButton(
                                        onClick = {
                                            clipboardManager.setText(
                                                AnnotatedString(
                                                    message.content
                                                )
                                            )
                                        },
                                        modifier = Modifier.size(48.dp),
                                        shapes = ExpressiveIconButtonShapes
                                    ) {
                                        Icon(
                                            Icons.Rounded.ContentCopy,
                                            contentDescription = stringResource(R.string.desc_copy_message),
                                            modifier = Modifier.size(18.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                                .copy(alpha = 0.6f)
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
