package com.folio.hermeslive.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.folio.hermeslive.data.LiveSettings

/** What the sign-in form shows. */
data class SignInUi(
    val busy: Boolean = false,
    val error: String? = null,
    /** A reason the old session ended ("Sign in again"). */
    val reason: String? = null,
    val serverUrl: String = "",
)

/** Server address (the user's own Hermes dashboard), user name and password. The password is kept only in the Keystore-sealed store. */
@Composable
fun SignInScreen(
    ui: SignInUi,
    onSignIn: (url: String, username: String, password: String) -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var url by rememberSaveable { mutableStateOf(ui.serverUrl) }
    var user by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    val submit = { if (!ui.busy) onSignIn(url, user, password) }
    Column(
        modifier.fillMaxSize().background(VoidBlack)
            .windowInsetsPadding(WindowInsets.safeDrawing).imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp),
    ) {
        Spacer(Modifier.height(48.dp))
        Orb(OrbMood.Idle, 0f, Modifier.size(120.dp).align(Alignment.CenterHorizontally))
        Text("Hermes Live", style = TitleStyle.copy(fontSize = TitleStyle.fontSize * 1.4f, shadow = PrintShadow), color = PrintInk, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 12.dp))
        Text(
            "Talk to your own Hermes. Sign in with your Hermes dashboard user.",
            style = BodyStyle, color = PrintMute, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp, bottom = 28.dp),
        )
        ui.reason?.let { Text(it, style = SmallStyle, color = Ember, modifier = Modifier.padding(bottom = 12.dp)) }
        OutlinedTextField(
            value = url, onValueChange = { url = it }, label = { Text("Server") }, singleLine = true,
            placeholder = { Text(LiveSettings.SERVER_HINT, style = BodyStyle, color = PrintMute) },
            colors = fieldColors(), textStyle = BodyStyle,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth().testTag("server"),
        )
        Text(
            "Your Hermes dashboard address. On Tailscale use the MagicDNS name, like ${LiveSettings.TAILNET_HINT} (plain http is allowed only for *.ts.net names).",
            style = SmallStyle, color = PrintMute, modifier = Modifier.padding(top = 6.dp, bottom = 14.dp),
        )
        OutlinedTextField(
            value = user, onValueChange = { user = it }, label = { Text("User name") }, singleLine = true,
            colors = fieldColors(), textStyle = BodyStyle,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth().testTag("username"),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = password, onValueChange = { password = it }, label = { Text("Password") }, singleLine = true,
            colors = fieldColors(), textStyle = BodyStyle,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { submit() }),
            modifier = Modifier.fillMaxWidth().testTag("password"),
        )
        ui.error?.let { Text(it, style = SmallStyle, color = EndRed, modifier = Modifier.padding(top = 10.dp).testTag("signin-error")) }
        Row(Modifier.fillMaxWidth().padding(top = 22.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Pill("Settings", filled = false, tag = "signin-settings", onClick = onSettings)
            if (ui.busy) {
                CircularProgressIndicator(color = PrintInk, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
            } else {
                Pill("Sign in", filled = true, tag = "signin") { submit() }
            }
        }
        Text(
            "Your password is sealed with the phone's Keystore and used only to sign in again when the session lapses. Sign out removes it.",
            style = SmallStyle, color = PrintMute, modifier = Modifier.padding(top = 24.dp, bottom = 24.dp),
        )
    }
}
