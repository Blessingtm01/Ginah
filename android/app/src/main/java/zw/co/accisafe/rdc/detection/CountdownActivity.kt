package zw.co.accisafe.rdc.detection

import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import zw.co.accisafe.rdc.ui.theme.AcciSafeTheme
import zw.co.accisafe.rdc.ui.theme.Emerald
import javax.inject.Inject

/** Full-screen countdown shown over the lock screen when a possible accident is detected. */
@AndroidEntryPoint
class CountdownActivity : ComponentActivity() {

    @Inject lateinit var coordinator: DetectionCoordinator

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        // Back must not silently dismiss the countdown.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = Unit
        })
        setContent {
            AcciSafeTheme {
                val alert by coordinator.pending.collectAsState()
                val current = alert
                if (current == null) {
                    LaunchedEffect(Unit) { finish() }
                    return@AcciSafeTheme
                }
                var remaining by remember { mutableLongStateOf(0L) }
                LaunchedEffect(current.id) {
                    while (true) {
                        remaining = ((current.endsAtElapsed - SystemClock.elapsedRealtime()) / 1000 + 1).coerceAtLeast(0)
                        delay(200)
                    }
                }
                Column(
                    Modifier.fillMaxSize()
                        .background(Brush.verticalGradient(listOf(Color(0xFF7F0000), Color(0xFFC62828))))
                        .padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    if (current.simulated) {
                        Text("SIMULATION – TEST ONLY", color = Color.Yellow, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Spacer(Modifier.height(8.dp))
                    }
                    Text("Possible accident detected", color = Color.White, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                    Text("$remaining", color = Color.White, fontSize = 110.sp, fontWeight = FontWeight.Black)
                    Text(
                        if (current.simulated) "When the countdown ends the simulation finishes. No council incident is created."
                        else "When the countdown ends, an accident report is queued for the council and your confirmed personal contacts are sent an SMS.",
                        color = Color.White, textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(32.dp))
                    Button(
                        onClick = { coordinator.cancel(); finish() },
                        modifier = Modifier.fillMaxWidth().height(96.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Emerald),
                    ) { Text("I'm OK — Cancel Alert", fontSize = 22.sp) }
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = { coordinator.sendNow(); finish() },
                        modifier = Modifier.fillMaxWidth().height(64.dp),
                    ) { Text("Send Help Now", color = Color.White, fontSize = 18.sp) }
                }
            }
        }
    }
}
