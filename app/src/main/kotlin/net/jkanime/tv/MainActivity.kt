package net.jkanime.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import net.jkanime.tv.nav.JkNavHost
import net.jkanime.tv.ui.theme.JkBackground
import net.jkanime.tv.ui.theme.JkTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            JkTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(JkBackground),
                ) {
                    JkNavHost()
                }
            }
        }
    }
}
