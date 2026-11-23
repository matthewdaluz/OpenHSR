package io.github.matthewdaluz.openhsr

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.matthewdaluz.openhsr.ui.OpenHsrScreen
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.maplibre.android.MapLibre
import org.maplibre.android.module.http.HttpRequestUtil

class MainActivity : ComponentActivity() {
    private val transit by viewModels<TransitViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val systemBars = SystemBarStyle.light(Color.TRANSPARENT, Color.WHITE)
        enableEdgeToEdge(statusBarStyle = systemBars, navigationBarStyle = systemBars)
        MapLibre.getInstance(applicationContext)
        HttpRequestUtil.setOkHttpClient(mapHttpClient)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { transit.whileVisible() }
        }
        setContent { OpenHsrScreen(transit) }
    }

    companion object {
        private val mapHttpClient = OkHttpClient.Builder().addNetworkInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("User-Agent", "OpenHSR/0.1.0 (+https://github.com/matthewdaluz/OpenHSR)")
                .build()
            chain.proceed(request)
        }.build()
    }
}
