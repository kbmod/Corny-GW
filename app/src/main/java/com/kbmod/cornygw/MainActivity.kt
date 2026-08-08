package com.kbmod.cornygw

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.kbmod.cornygw.ui.CornyApp
import com.kbmod.cornygw.ui.PermissionGate
import com.kbmod.cornygw.ui.theme.CornyTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CornyTheme {
                PermissionGate {
                    CornyApp()
                }
            }
        }
    }
}
