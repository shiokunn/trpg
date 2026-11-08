package io.github.shiokunn.trpg.core.domain

import kotlinx.serialization.Serializable

@Serializable
enum class QuestStatus(val label: String) {
    ACTIVE("進行中"),
    COMPLETED("完了"),
    FAILED("失敗"),
    UNKNOWN("不明"),
}

@Serializable
data class QuestObjective(
    val description: String,
    val completed: Boolean = false,
)

@Serializable
data class Quest(
    val id: String,
    val title: String,
    val description: String = "",
    val objectives: List<QuestObjective> = emptyList(),
    val status: QuestStatus = QuestStatus.ACTIVE,
    val rewards: List<String> = emptyList(),
) {
    val progress: Float
        get() = if (objectives.isEmpty()) 0f else objectives.count { it.completed }.toFloat() / objectives.size
}
