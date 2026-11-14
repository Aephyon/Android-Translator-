package app.chattranslator

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateRemoteModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

private enum class PackStatus { Checking, Ready, Missing, Downloading, Failed }

class MainActivity : ComponentActivity() {
    private val settings by lazy { AppSettings(this) }
    private var serviceEnabled by mutableStateOf(false)
    private var target by mutableStateOf("en")
    private var packStatus by mutableStateOf(PackStatus.Checking)
    private var showTranslations by mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        target = settings.targetLanguage
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) { Screen() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        serviceEnabled = isServiceEnabled()
        showTranslations = settings.showTranslations
        checkPack()
    }

    private fun isServiceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val me = ComponentName(this, TranslatorService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    private fun checkPack() {
        val language = target
        packStatus = PackStatus.Checking
        lifecycleScope.launch {
            val downloaded = runCatching {
                RemoteModelManager.getInstance().isModelDownloaded(TranslateRemoteModel.Builder(language).build()).await()
            }.getOrDefault(false)
            if (language == target) packStatus = if (downloaded) PackStatus.Ready else PackStatus.Missing
        }
    }

    private fun downloadPack() {
        val language = target
        packStatus = PackStatus.Downloading
        lifecycleScope.launch {
            val ok = runCatching {
                RemoteModelManager.getInstance()
                    .download(TranslateRemoteModel.Builder(language).build(), DownloadConditions.Builder().build())
                    .await()
            }.isSuccess
            if (language == target) packStatus = if (ok) PackStatus.Ready else PackStatus.Failed
        }
    }

    private fun selectLanguage(code: String) {
        settings.targetLanguage = code
        target = code
        downloadPack()
    }

    @Composable
    private fun Screen() {
        var picking by remember { mutableStateOf(false) }
        Column(
            Modifier
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Chat Translator", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("Shows WhatsApp messages in any language translated into your language, right inside the chat. Translation happens on your phone; your messages are never sent anywhere.")

            Step("1. Your language") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(AppSettings.displayName(target), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = { picking = true }) { Text("Change") }
                }
                when (packStatus) {
                    PackStatus.Checking -> Text("Checking language pack…")
                    PackStatus.Ready -> Text("✓ Language pack ready")
                    PackStatus.Downloading -> Text("Downloading language pack (about 30 MB)…")
                    PackStatus.Missing -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Language pack not downloaded yet.", Modifier.weight(1f))
                        Button(onClick = ::downloadPack) { Text("Download") }
                    }
                    PackStatus.Failed -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Download failed. Check your internet connection.", Modifier.weight(1f))
                        Button(onClick = ::downloadPack) { Text("Retry") }
                    }
                }
            }

            Step("2. Turn on the translator") {
                if (serviceEnabled) {
                    Text("✓ Chat Translator is on", fontWeight = FontWeight.Bold)
                } else {
                    Text("Android needs your permission to let the app read WhatsApp's screen. Tap the button, open \"Chat Translator\" (sometimes under \"Downloaded apps\" or \"Installed apps\") and turn it on.")
                    Button(onClick = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) {
                        Text("Open Accessibility settings")
                    }
                    Text(
                        "Greyed out or \"Restricted setting\"? Android blocks this for apps installed from an APK until you allow it: tap the button below, then ⋮ (top right) → \"Allow restricted settings\", and try again.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(onClick = {
                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
                    }) { Text("Open App info") }
                }
            }

            Step("3. Use it") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Show translations in the chat", Modifier.weight(1f))
                    Switch(checked = showTranslations, onCheckedChange = {
                        showTranslations = it
                        settings.showTranslations = it
                    })
                }
                Text("Open a WhatsApp chat. Messages in other languages are shown in your language, right inside their bubble, marked with a thin green bar and the original language (e.g. ES).")
                Text("Want to learn what they said? Tap the round green translate button at the side of the screen. The Learn panel shows each message in the original language with its meaning underneath, and 🔊 reads it aloud. Drag the button up or down to move it.")
                Text("The first time someone writes in a new language, its language pack (about 30 MB) is downloaded first, so that first translation takes a moment.")
            }
        }

        if (picking) LanguagePicker(onPick = { picking = false; selectLanguage(it) }, onDismiss = { picking = false })
    }

    @Composable
    private fun Step(title: String, content: @Composable () -> Unit) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                content()
            }
        }
    }

    @Composable
    private fun LanguagePicker(onPick: (String) -> Unit, onDismiss: () -> Unit) {
        val languages = remember {
            AppSettings.supportedLanguages.map { it to AppSettings.displayName(it) }.sortedBy { it.second }
        }
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Translate into") },
            text = {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(languages) { (code, name) ->
                        Text(
                            name + if (code == target) "  ✓" else "",
                            Modifier
                                .fillMaxWidth()
                                .clickable { onPick(code) }
                                .padding(vertical = 12.dp),
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        )
    }
}
