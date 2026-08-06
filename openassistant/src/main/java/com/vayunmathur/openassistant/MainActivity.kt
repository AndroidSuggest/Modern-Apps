package com.vayunmathur.openassistant

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.vayunmathur.library.util.NavKey
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.util.OfflineAware
import com.vayunmathur.library.downloadservice.InitialModelDownloadChecker
import com.vayunmathur.library.downloadservice.ModelUrls
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.library.util.IntentLauncher
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.room.buildDatabase
import com.vayunmathur.library.util.rememberNavBackStack
import kotlinx.serialization.Serializable
import com.vayunmathur.openassistant.data.AppDatabase
import com.vayunmathur.openassistant.data.ConversationDao
import com.vayunmathur.openassistant.data.MemoryDao
import com.vayunmathur.openassistant.data.MessageDao
import com.vayunmathur.openassistant.ui.LiteRTChatUi
import com.vayunmathur.openassistant.ui.SettingsPage
import com.vayunmathur.openassistant.util.AssistantViewModel

class MainActivity : ComponentActivity() {

    companion object {
        lateinit var intentLauncher: IntentLauncher
    }

    private lateinit var conversationDao: ConversationDao
    private lateinit var messageDao: MessageDao
    private lateinit var memoryDao: MemoryDao
    private val assistantViewModel: AssistantViewModel by viewModels {
        viewModelFactory {
            initializer {
                AssistantViewModel(application, conversationDao, messageDao, memoryDao)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        intentLauncher = IntentLauncher(this)

        val ds = DataStoreUtils.getInstance(this)
        val db = buildDatabase<AppDatabase>()
        conversationDao = db.conversationDao()
        messageDao = db.messageDao()
        memoryDao = db.memoryDao()

        setContent {
            DynamicTheme {
                // Gemma + SigLIP2 semantic-search models, mirror-first with a
                // HuggingFace fallback (supply-chain mitigation #1). SigLIP2 is
                // fetched upfront so the photos app's first embed is served
                // immediately.
                InitialModelDownloadChecker(ds, ModelUrls.INITIAL) {
                    // Touching the assistantViewModel triggers init, which pre-warms
                    // the inference service and runs the legacy model-file cleanup.
                    OfflineAware {
                        Navigation(assistantViewModel)
                    }
                }
            }
        }
    }
}

@Serializable
sealed interface Route: NavKey {
    @Serializable
    data class ConversationPage(val id: Long): Route
    @Serializable
    data object SettingsPage: Route
}

@Composable
fun Navigation(assistantViewModel: AssistantViewModel) {
    val backStack = rememberNavBackStack<Route>(Route.ConversationPage(0))
    MainNavigation(backStack) {
        entry<Route.ConversationPage> {
            LiteRTChatUi(backStack, it.id, assistantViewModel)
        }
        entry<Route.SettingsPage> {
            SettingsPage(backStack, assistantViewModel)
        }
    }
}
