package zw.co.accisafe.rdc.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import zw.co.accisafe.rdc.BuildConfig
import zw.co.accisafe.rdc.data.AuthRepository
import zw.co.accisafe.rdc.data.SessionStore
import zw.co.accisafe.rdc.data.apiCall
import zw.co.accisafe.rdc.data.net.ApiClient
import zw.co.accisafe.rdc.data.net.CouncilPublic
import zw.co.accisafe.rdc.ui.components.ErrorBanner
import zw.co.accisafe.rdc.ui.components.GradientScaffold
import zw.co.accisafe.rdc.ui.components.SectionCard
import zw.co.accisafe.rdc.ui.theme.BrandGradient
import javax.inject.Inject

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val auth: AuthRepository,
    private val session: SessionStore,
    private val client: ApiClient,
) : ViewModel() {
    val busy = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    val lastCouncil = session.lastCouncilCode
    val serverUrl = MutableStateFlow(session.serverUrl)
    val council = MutableStateFlow<CouncilPublic?>(null)

    fun login(code: String, user: String, password: String) = run { auth.login(code, user, password) }
    fun register(code: String, user: String, name: String, phone: String?, password: String) = run { auth.register(code, user, name, phone, password) }

    private fun run(block: suspend () -> Result<*>) = viewModelScope.launch {
        if (busy.value) return@launch
        busy.value = true
        error.value = null
        block().onFailure { error.value = it.message }
        busy.value = false
    }

    fun setServer(input: String): Boolean {
        val url = ApiClient.normaliseServerUrl(input) ?: return false
        viewModelScope.launch { session.setServerUrl(url); serverUrl.value = url }
        return true
    }

    fun lookupCouncil(code: String) = viewModelScope.launch {
        error.value = null
        apiCall { client.api.councilPublic(code.trim()) }
            .onSuccess { council.value = it }
            .onFailure { error.value = it.message; council.value = null }
    }
}

@Composable
private fun AuthLayout(title: String, subtitle: String, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(BrandGradient)) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(28.dp))
            Box(
                Modifier.size(72.dp).background(Color.White.copy(alpha = 0.18f), RoundedCornerShape(22.dp)),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Default.Shield, null, tint = Color.White, modifier = Modifier.size(40.dp)) }
            Spacer(Modifier.height(14.dp))
            Text("AcciSafe RDC", style = MaterialTheme.typography.headlineMedium, color = Color.White)
            Text("Emergency incident reporting and response", color = Color.White.copy(alpha = 0.85f))
            Spacer(Modifier.height(24.dp))
            Card(shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(title, style = MaterialTheme.typography.titleLarge)
                    Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    content()
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "In a life-threatening emergency, also phone the emergency services directly.",
                color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
fun LoginScreen(nav: NavHostController, vm: AuthViewModel = hiltViewModel()) {
    val busy by vm.busy.collectAsState()
    val error by vm.error.collectAsState()
    val last by vm.lastCouncil.collectAsState("")
    val server by vm.serverUrl.collectAsState()
    var code by remember(last) { mutableStateOf(last) }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var showServer by remember { mutableStateOf(false) }
    var serverInput by remember(server) { mutableStateOf(server) }
    var serverError by remember { mutableStateOf<String?>(null) }

    AuthLayout("Sign in", "Use the account issued by your council, or register as a community reporter.") {
        OutlinedTextField(code, { code = it.uppercase() }, label = { Text("Council code (e.g. MRDC)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(user, { user = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            pass, { pass = it }, label = { Text("Password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth(),
        )
        error?.let { ErrorBanner(it) }
        Button(
            onClick = { vm.login(code, user, pass) }, enabled = !busy && code.isNotBlank() && user.isNotBlank() && pass.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(54.dp),
        ) { if (busy) CircularProgressIndicator(Modifier.size(22.dp), color = Color.White) else Text("Sign in") }
        TextButton(onClick = { nav.navigate("register") }, modifier = Modifier.fillMaxWidth()) { Text("Register as a community reporter") }
        TextButton(onClick = { nav.navigate("recover") }, modifier = Modifier.fillMaxWidth()) { Text("Forgotten password?") }
        if (BuildConfig.DEBUG) {
            TextButton(onClick = { showServer = !showServer }) { Text("Development server: $server") }
            if (showServer) {
                OutlinedTextField(serverInput, { serverInput = it; serverError = null }, label = { Text("Server address") }, singleLine = true,
                    isError = serverError != null, supportingText = serverError?.let { { Text(it) } }, modifier = Modifier.fillMaxWidth())
                Button(onClick = { if (!vm.setServer(serverInput)) serverError = "Enter a valid address" else showServer = false }) { Text("Save server") }
            }
        }
    }
}

@Composable
fun RegisterScreen(nav: NavHostController, vm: AuthViewModel = hiltViewModel()) {
    val busy by vm.busy.collectAsState()
    val error by vm.error.collectAsState()
    var code by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var pass2 by remember { mutableStateOf("") }
    val mismatch = pass2.isNotEmpty() && pass != pass2
    val weak = pass.isNotEmpty() && (pass.length < 10 || pass.all { it.isDigit() } || pass.all { it.isLetter() })

    AuthLayout("Register", "Community reporters can submit reports and follow their own reports only.") {
        OutlinedTextField(code, { code = it.uppercase() }, label = { Text("Council code") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(name, { name = it }, label = { Text("Full name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(phone, { phone = it }, label = { Text("Phone number (optional)") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(user, { user = it.lowercase().filter { c -> c.isLetterOrDigit() || c in "._-" } }, label = { Text("Choose a username") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(pass, { pass = it }, label = { Text("Password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
            isError = weak, supportingText = { Text("At least 10 characters with letters and numbers") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(pass2, { pass2 = it }, label = { Text("Repeat password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
            isError = mismatch, modifier = Modifier.fillMaxWidth())
        error?.let { ErrorBanner(it) }
        Button(
            onClick = { vm.register(code, user, name, phone.ifBlank { null }, pass) },
            enabled = !busy && code.isNotBlank() && user.length >= 3 && name.length >= 2 && !weak && !mismatch && pass2.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().height(54.dp),
        ) { Text("Create account") }
        TextButton(onClick = { nav.popBackStack() }, modifier = Modifier.fillMaxWidth()) { Text("Back to sign in") }
    }
}

@Composable
fun PasswordRecoveryScreen(nav: NavHostController, vm: AuthViewModel = hiltViewModel()) {
    val council by vm.council.collectAsState()
    val error by vm.error.collectAsState()
    var code by remember { mutableStateOf("") }
    GradientScaffold("Password recovery", onBack = { nav.popBackStack() }) { padding ->
        Column(Modifier.padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionCard("How to reset your password") {
                Text(
                    "Passwords are reset by your council's system administrator, who will give you a temporary password " +
                        "that you must change after signing in. The app does not send reset emails.",
                )
            }
            OutlinedTextField(code, { code = it.uppercase() }, label = { Text("Council code") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(onClick = { vm.lookupCouncil(code) }, enabled = code.isNotBlank()) { Text("Show council contact details") }
            error?.let { ErrorBanner(it) }
            council?.let {
                SectionCard(it.name) {
                    Text("Phone: ${it.phone ?: "not configured"}")
                    Text("Email: ${it.email ?: "not configured"}")
                }
            }
        }
    }
}
