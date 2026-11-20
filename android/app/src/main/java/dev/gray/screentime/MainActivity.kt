package dev.gray.screentime

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

class MainActivity : ComponentActivity() {
    private val model: ScreenModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val palette = if (isSystemInDarkTheme()) darkColorScheme(primary = Color(0xff8bd4b5),
                background = Color(0xff111815), surface = Color(0xff111815))
            else lightColorScheme(primary = Color(0xff155c43), background = Color(0xfffafbf8),
                surface = Color(0xfffafbf8))
            MaterialTheme(colorScheme = palette) {
                Surface { ScreenTime(model) { openPermissions() } }
            }
        }
    }
    override fun onResume() {
        super.onResume()
        model.refresh()
    }
    private fun openPermissions() {
        try { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
        catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "请在系统设置中搜索「使用情况访问权限」。", Toast.LENGTH_LONG).show()
        }
    }
}

internal class ScreenModel(app: Application) : AndroidViewModel(app) {
    private val archive = Archive.open(app)
    val dao = archive.dao()
    private val collector = Collector(app, archive)
    val permission = MutableStateFlow(false)
    val working = MutableStateFlow(false)
    val notice = MutableStateFlow("")
    val today = MutableStateFlow(LocalDate.now())
    fun refresh() {
        today.value = LocalDate.now()
        permission.value = collector.allowed()
        if (working.value) return
        working.value = true
        viewModelScope.launch {
            try { notice.value = collector.refresh() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { notice.value = "本次刷新未成功。可以重试，原有记录仍可查看。" }
            finally { permission.value = collector.allowed(); working.value = false }
        }
    }
}
