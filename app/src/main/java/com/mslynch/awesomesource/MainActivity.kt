package com.mslynch.awesomesource

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview

/**
 * Scaffold entry point only - proves the Compose/Media3/Room toolchain actually
 * builds and runs on a real emulator/device. The real Setup/Library/Settings
 * screens and the re-ported organization pipeline are the next phase; see
 * Claude/ANDROID ARCHITECTURE.md and Claude/To Do list.md.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AwesomeSourceApp()
        }
    }
}

@Composable
fun AwesomeSourceApp() {
    MaterialTheme {
        Scaffold { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                Surface {
                    Text("AwesomeSource - scaffold builds and runs.")
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun AwesomeSourceAppPreview() {
    AwesomeSourceApp()
}
