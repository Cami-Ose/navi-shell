package com.navi.shell

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.compose.ui.res.stringResource
import com.navi.shell.ui.UiColors
import com.navi.shell.ui.NaviScreen

class MainActivity : ComponentActivity() {

    private val neededPermissions: Array<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    private val askPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { App() }
    }

    private fun askIfNeeded() {
        val missing = neededPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) askPermissions.launch(missing.toTypedArray())
    }

    @Composable
    private fun App() {
        val prefs = remember { getSharedPreferences("navi_shell", Context.MODE_PRIVATE) }
        var agreed by remember { mutableStateOf(prefs.getBoolean("privacy_agreed", false)) }

        if (!agreed) {
            ConsentScreen {
                prefs.edit().putBoolean("privacy_agreed", true).apply()
                agreed = true
                // 顺序要紧：先过合规，再让高德干活
                AmapPrivacy.apply(this)
                askIfNeeded()
            }
            return
        }

        // 已经同意过（上次启动点过）：进界面之前补一次合规 + 权限
        LaunchedEffect(Unit) {
            AmapPrivacy.apply(this@MainActivity)
            askIfNeeded()
        }

        NaviScreen(this)
    }
}

@Composable
private fun ConsentScreen(onAgree: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(UiColors.Background).padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.Start,
    ) {
        Text("navi-shell", color = UiColors.AccentBright, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        // 名字留空就是「你的 AI」，不显示具体名字（见 strings.xml 的 ai_name）
        val who = stringResource(R.string.ai_name).ifBlank { "你的 AI" }
        Text(
            "导航由高德地图提供（路线、地图、定位）。\n" +
                "说话的是$who —— 录音会发给它；语音由它合成。\n\n" +
                "点「同意」表示你接受高德地图的服务条款与隐私政策。",
            color = UiColors.TextSecondary,
            fontSize = 14.sp,
            modifier = Modifier.padding(top = 16.dp, bottom = 28.dp),
        )
        Button(
            onClick = onAgree,
            colors = ButtonDefaults.buttonColors(
                containerColor = UiColors.Accent,
                contentColor = UiColors.Background,
            ),
        ) { Text("同意", fontWeight = FontWeight.Bold) }
    }
}
