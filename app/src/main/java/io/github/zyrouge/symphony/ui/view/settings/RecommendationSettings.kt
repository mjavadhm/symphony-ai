package io.github.zyrouge.symphony.ui.view.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.zyrouge.symphony.ui.components.ScaffoldTopAppBar
import io.github.zyrouge.symphony.ui.helpers.ViewContext
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable
object RecommendationSettingsRoute

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecommendationSettings(context: ViewContext) {
    val coroutineScope = rememberCoroutineScope()
    val engine = context.symphony.recommendation
    
    var discoveryRatio by remember { mutableStateOf(engine.discoveryRatio) }
    var recencyHalfLife by remember { mutableStateOf(engine.recencyHalfLifeDays.toFloat()) }
    var dailyMixSize by remember { mutableStateOf(engine.dailyMixSize.toFloat()) }
    var dailyMixCount by remember { mutableStateOf(engine.dailyMixCount.toFloat()) }

    Scaffold(
        topBar = { ScaffoldTopAppBar(context, title = "تنظیمات هوش مصنوعی") },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .padding(16.dp)
        ) {
            Text("نسبت آهنگ‌های جدید به قدیمی", style = MaterialTheme.typography.titleMedium)
            Slider(
                value = discoveryRatio,
                onValueChange = { 
                    discoveryRatio = it
                    engine.discoveryRatio = it
                },
                valueRange = 0f..1f,
                steps = 9
            )
            Text("تنوع میکس: ${(discoveryRatio * 100).toInt()}% آهنگ‌های جدید", style = MaterialTheme.typography.bodySmall)
            Spacer(modifier = Modifier.height(24.dp))

            Text("حافظهٔ سلیقه (نیمه‌عمر)", style = MaterialTheme.typography.titleMedium)
            Slider(
                value = recencyHalfLife,
                onValueChange = { 
                    recencyHalfLife = it
                    engine.recencyHalfLifeDays = it.toInt()
                },
                valueRange = 1f..30f,
                steps = 29
            )
            Text("تأثیر آهنگ‌ها بعد از ${recencyHalfLife.toInt()} روز نصف میشه", style = MaterialTheme.typography.bodySmall)
            Spacer(modifier = Modifier.height(24.dp))

            Text("تعداد آهنگ در هر میکس", style = MaterialTheme.typography.titleMedium)
            Slider(
                value = dailyMixSize,
                onValueChange = { 
                    dailyMixSize = it
                    engine.dailyMixSize = it.toInt()
                },
                valueRange = 10f..100f,
                steps = 90
            )
            Text("${dailyMixSize.toInt()} آهنگ", style = MaterialTheme.typography.bodySmall)
            Spacer(modifier = Modifier.height(24.dp))

            Text("تعداد خوشه‌های Daily Mix", style = MaterialTheme.typography.titleMedium)
            Slider(
                value = dailyMixCount,
                onValueChange = { 
                    dailyMixCount = it
                    engine.dailyMixCount = it.toInt()
                },
                valueRange = 1f..3f,
                steps = 2
            )
            Text("${dailyMixCount.toInt()} میکس روزانه", style = MaterialTheme.typography.bodySmall)
            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = {
                    coroutineScope.launch {
                        engine.resetToDefaults()
                        discoveryRatio = engine.discoveryRatio
                        recencyHalfLife = engine.recencyHalfLifeDays.toFloat()
                        dailyMixSize = engine.dailyMixSize.toFloat()
                        dailyMixCount = engine.dailyMixCount.toFloat()
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("بازنشانی به تنظیمات پیش‌فرض")
            }
        }
    }
}
