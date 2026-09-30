package com.bhanu.attendance

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.bhanu.attendance.core.designsystem.theme.BhanuTheme
import com.bhanu.attendance.nav.AppNavHost
import dagger.hilt.android.AndroidEntryPoint

/**
 * The single activity.
 *
 * `enableEdgeToEdge` plus edge-to-edge insets handled per screen, rather than
 * `setDecorFitsSystemWindows(true)`, so content draws behind the system bars and each screen
 * applies the insets it actually needs.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            BhanuTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    AppNavHost()
                }
            }
        }
    }
}
