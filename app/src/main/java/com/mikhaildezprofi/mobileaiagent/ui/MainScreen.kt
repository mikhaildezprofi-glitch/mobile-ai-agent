package com.mikhaildezprofi.mobileaiagent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*

import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private enum class AgentMode { LOCAL, HYBRID }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    var mode by remember { mutableStateOf(AgentMode.LOCAL) }
    var status by remember { mutableStateOf("Готов к работе") }
    var diagnostics by remember { mutableStateOf(false) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("MOBILE AI AGENT") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Ваш персональный AI-ассистент", style = MaterialTheme.typography.titleMedium)
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Режим работы", style = MaterialTheme.typography.titleMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Локальный")
                        Spacer(Modifier.weight(1f))
                        Switch(
                            checked = mode == AgentMode.HYBRID,
                            onCheckedChange = { mode = if (it) AgentMode.HYBRID else AgentMode.LOCAL }
                        )
                        Text("Гибридный", modifier = Modifier.padding(start = 8.dp))
                    }
                    Text(
                        if (mode == AgentMode.LOCAL)
                            "Выбран локальный режим. Подключение локальной модели будет добавлено следующим этапом."
                        else
                            "Выбран гибридный режим. Облачные провайдеры пока не подключены."
                    )
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Состояние агента", style = MaterialTheme.typography.titleMedium)
                    Text(status)
                    OutlinedButton(onClick = { status = "Интерфейс запущен. Модули ИИ ещё не подключены." }) {
                        Text("Проверить состояние")
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Возможности", style = MaterialTheme.typography.titleMedium)
                    Text("• Локальная языковая модель — следующий этап")
                    Text("• Облачные модели — опционально")
                    Text("• Голос, камера и анализ файлов — запланировано")
                    Text("• Инструменты Android — только с разрешения пользователя")
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Диагностический режим")
                Spacer(Modifier.weight(1f))
                Switch(checked = diagnostics, onCheckedChange = { diagnostics = it })
            }
            if (diagnostics) {
                Text("Версия 0.1.0 • Android • UI scaffold")
            }
        }
    }
}
