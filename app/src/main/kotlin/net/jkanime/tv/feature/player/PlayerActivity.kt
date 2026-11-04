package net.jkanime.tv.feature.player

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import net.jkanime.tv.ui.theme.JkTheme

class PlayerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val episodeUrl = intent.getStringExtra(EXTRA_EPISODE_URL).orEmpty()
        if (episodeUrl.isBlank()) {
            finish()
            return
        }

        setContent {
            JkTheme {
                PlayerScreen(
                    slug = intent.getStringExtra(EXTRA_SLUG).orEmpty(),
                    episodeNumber = intent.getIntExtra(EXTRA_NUMBER, -1),
                    episodeUrl = episodeUrl,
                    title = intent.getStringExtra(EXTRA_TITLE).orEmpty(),
                    onExit = { finish() },
                )
            }
        }
    }

    companion object {
        private const val EXTRA_SLUG = "extra_slug"
        private const val EXTRA_NUMBER = "extra_number"
        private const val EXTRA_EPISODE_URL = "extra_episode_url"
        private const val EXTRA_TITLE = "extra_title"

        fun intent(
            context: Context,
            slug: String,
            episodeNumber: Int?,
            episodeUrl: String,
            title: String,
        ): Intent = Intent(context, PlayerActivity::class.java).apply {
            putExtra(EXTRA_SLUG, slug)
            putExtra(EXTRA_NUMBER, episodeNumber ?: -1)
            putExtra(EXTRA_EPISODE_URL, episodeUrl)
            putExtra(EXTRA_TITLE, title)
        }
    }
}
