package io.github.shiokunn.trpg.data.db

import io.github.shiokunn.trpg.core.domain.Attributes
import io.github.shiokunn.trpg.core.domain.Character
import io.github.shiokunn.trpg.core.domain.ChatMessage
import io.github.shiokunn.trpg.core.domain.ChatRole
import io.github.shiokunn.trpg.core.domain.CombatState
import io.github.shiokunn.trpg.core.domain.CombatStats
import io.github.shiokunn.trpg.core.domain.DiceResult
import io.github.shiokunn.trpg.core.domain.GameEvent
import io.github.shiokunn.trpg.core.domain.GameSession
import io.github.shiokunn.trpg.core.domain.Item
import io.github.shiokunn.trpg.core.domain.Location
import io.github.shiokunn.trpg.core.domain.MemoryCategory
import io.github.shiokunn.trpg.core.domain.ModelSettings
import io.github.shiokunn.trpg.core.domain.Npc
import io.github.shiokunn.trpg.core.domain.NpcMemory
import io.github.shiokunn.trpg.core.domain.Quest
import io.github.shiokunn.trpg.core.domain.QuestObjective
import io.github.shiokunn.trpg.core.domain.QuestStatus
import io.github.shiokunn.trpg.core.domain.SessionStatus
import io.github.shiokunn.trpg.core.domain.Skill
import io.github.shiokunn.trpg.core.domain.StoryMemory
import io.github.shiokunn.trpg.core.domain.World
import kotlinx.serialization.json.Json

/** Single JSON configuration used for every persisted structured column. */
object JsonCodec {
    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    inline fun <reified T> encode(value: T): String = json.encodeToString(value)

    inline fun <reified T> decode(raw: String, fallback: T): T =
        runCatching { json.decodeFromString<T>(raw) }.getOrDefault(fallback)

    inline fun <reified T> decodeOrNull(raw: String?): T? =
        raw?.let { runCatching { json.decodeFromString<T>(it) }.getOrNull() }
}

internal fun decodeStringList(raw: String): List<String> = JsonCodec.decode(raw, emptyList())

internal fun encodeStringList(value: List<String>): String = JsonCodec.encode(value)

internal fun <T : Enum<T>> enumValue(raw: String, fallback: T): T =
    fallback.declaringJavaClass.enumConstants.firstOrNull { it.name == raw } ?: fallback

fun WorldEntity.toDomain(): World = World(
    id = id,
    name = name,
    genre = genre,
    era = era,
    setting = setting,
    atmosphere = atmosphere,
    history = history,
    geography = geography,
    socialStructure = socialStructure,
    factions = decodeStringList(factionsJson),
    techMagic = techMagic,
    specialSettings = decodeStringList(specialSettingsJson),
    objective = objective,
    problem = problem,
    secrets = decodeStringList(secretsJson),
    scenarioTitle = scenarioTitle,
    scenarioPremise = scenarioPremise,
    scenarioLength = scenarioLength,
    difficulty = difficulty,
    createdAt = createdAt,
)

fun World.toEntity(): WorldEntity = WorldEntity(
    id = id,
    name = name,
    genre = genre,
    era = era,
    setting = setting,
    atmosphere = atmosphere,
    history = history,
    geography = geography,
    socialStructure = socialStructure,
    factionsJson = encodeStringList(factions),
    techMagic = techMagic,
    specialSettingsJson = encodeStringList(specialSettings),
    objective = objective,
    problem = problem,
    secretsJson = encodeStringList(secrets),
    scenarioTitle = scenarioTitle,
    scenarioPremise = scenarioPremise,
    scenarioLength = scenarioLength,
    difficulty = difficulty,
    createdAt = createdAt,
)

fun SessionEntity.toDomain(): GameSession = GameSession(
    id = id,
    title = title,
    worldId = worldId,
    characterId = characterId,
    currentLocationId = currentLocationId,
    currentChapter = currentChapter,
    status = enumValue(status, SessionStatus.ACTIVE),
    turnCount = turnCount,
    summary = summary,
    activeCombat = JsonCodec.decodeOrNull(activeCombatJson),
    createdAt = createdAt,
    updatedAt = updatedAt,
    lastPlayedAt = lastPlayedAt,
)

fun GameSession.toEntity(): SessionEntity = SessionEntity(
    id = id,
    title = title,
    worldId = worldId,
    characterId = characterId,
    currentLocationId = currentLocationId,
    currentChapter = currentChapter,
    status = status.name,
    turnCount = turnCount,
    summary = summary,
    activeCombatJson = activeCombat?.let { JsonCodec.encode(it) },
    createdAt = createdAt,
    updatedAt = updatedAt,
    lastPlayedAt = lastPlayedAt,
)

fun CharacterEntity.toDomain(skills: List<SkillEntity>, items: List<InventoryItemEntity>): Character = Character(
    id = id,
    name = name,
    species = species,
    occupation = occupation,
    background = background,
    personality = personality,
    attributes = JsonCodec.decode(attributesJson, Attributes()),
    skills = skills.map { it.toDomain() },
    hp = hp,
    maxHp = maxHp,
    inventory = items.map { it.toDomain() },
    equipment = items.filter { it.equipped }.map { it.id },
    experience = experience,
)

fun Character.toEntity(sessionId: String): CharacterEntity = CharacterEntity(
    id = id,
    sessionId = sessionId,
    name = name,
    species = species,
    occupation = occupation,
    background = background,
    personality = personality,
    attributesJson = JsonCodec.encode(attributes),
    hp = hp,
    maxHp = maxHp,
    experience = experience,
)

fun SkillEntity.toDomain(): Skill = Skill(id = id, name = name, level = level, description = description)

fun Skill.toEntity(characterId: String): SkillEntity =
    SkillEntity(id = id, characterId = characterId, name = name, level = level, description = description)

fun InventoryItemEntity.toDomain(): Item = Item(
    id = id,
    name = name,
    description = description,
    quantity = quantity,
    type = type,
    equipped = equipped,
    power = power,
    defense = defense,
    tags = decodeStringList(tagsJson),
)

fun Item.toEntity(characterId: String): InventoryItemEntity = InventoryItemEntity(
    id = id,
    characterId = characterId,
    name = name,
    description = description,
    quantity = quantity,
    type = type,
    equipped = equipped,
    power = power,
    defense = defense,
    tagsJson = encodeStringList(tags),
)

fun NpcEntity.toDomain(): Npc = Npc(
    id = id,
    name = name,
    description = description,
    personality = personality,
    occupation = occupation,
    relationship = relationship,
    disposition = disposition,
    secrets = decodeStringList(secretsJson),
    isAlive = isAlive,
    isDiscovered = isDiscovered,
    locationId = locationId,
    combat = JsonCodec.decodeOrNull(combatJson),
)

fun Npc.toEntity(sessionId: String): NpcEntity = NpcEntity(
    id = id,
    sessionId = sessionId,
    name = name,
    description = description,
    personality = personality,
    occupation = occupation,
    relationship = relationship,
    disposition = disposition,
    secretsJson = encodeStringList(secrets),
    isAlive = isAlive,
    isDiscovered = isDiscovered,
    locationId = locationId,
    combatJson = combat?.let { JsonCodec.encode(it) },
)

fun NpcMemoryEntity.toDomain(): NpcMemory = NpcMemory(
    id = id,
    npcId = npcId,
    content = content,
    importance = importance,
    isSecret = isSecret,
    turn = turn,
    createdAt = createdAt,
)

fun NpcMemory.toEntity(sessionId: String): NpcMemoryEntity = NpcMemoryEntity(
    id = id,
    npcId = npcId,
    sessionId = sessionId,
    content = content,
    importance = importance,
    isSecret = isSecret,
    turn = turn,
    createdAt = createdAt,
)

fun LocationEntity.toDomain(): Location = Location(
    id = id,
    name = name,
    description = description,
    isDiscovered = isDiscovered,
    connections = decodeStringList(connectionsJson),
    secrets = decodeStringList(secretsJson),
)

fun Location.toEntity(sessionId: String): LocationEntity = LocationEntity(
    id = id,
    sessionId = sessionId,
    name = name,
    description = description,
    isDiscovered = isDiscovered,
    connectionsJson = encodeStringList(connections),
    secretsJson = encodeStringList(secrets),
)

fun QuestEntity.toDomain(): Quest = Quest(
    id = id,
    title = title,
    description = description,
    objectives = JsonCodec.decode(objectivesJson, emptyList<QuestObjective>()),
    status = enumValue(status, QuestStatus.ACTIVE),
    rewards = decodeStringList(rewardsJson),
)

fun Quest.toEntity(sessionId: String): QuestEntity = QuestEntity(
    id = id,
    sessionId = sessionId,
    title = title,
    description = description,
    objectivesJson = JsonCodec.encode(objectives),
    status = status.name,
    rewardsJson = encodeStringList(rewards),
)

fun GameEventEntity.toDomain(): GameEvent = GameEvent(
    id = id,
    turn = turn,
    type = type,
    summary = summary,
    payloadJson = payloadJson,
    createdAt = createdAt,
)

fun GameEvent.toEntity(sessionId: String): GameEventEntity = GameEventEntity(
    id = id,
    sessionId = sessionId,
    turn = turn,
    type = type,
    summary = summary,
    payloadJson = payloadJson,
    createdAt = createdAt,
)

fun StoryMemoryEntity.toDomain(): StoryMemory = StoryMemory(
    id = id,
    category = enumValue(category, MemoryCategory.EVENT),
    content = content,
    importance = importance,
    isSecret = isSecret,
    isConfirmed = isConfirmed,
    turn = turn,
    relatedIds = decodeStringList(relatedIdsJson),
    createdAt = createdAt,
)

fun StoryMemory.toEntity(sessionId: String): StoryMemoryEntity = StoryMemoryEntity(
    id = id,
    sessionId = sessionId,
    category = category.name,
    content = content,
    importance = importance,
    isSecret = isSecret,
    isConfirmed = isConfirmed,
    turn = turn,
    relatedIdsJson = encodeStringList(relatedIds),
    createdAt = createdAt,
)

fun ChatHistoryEntity.toDomain(): ChatMessage = ChatMessage(
    id = id,
    role = enumValue(role, ChatRole.GM),
    content = content,
    npcId = npcId,
    turn = turn,
    createdAt = createdAt,
)

fun ChatMessage.toEntity(sessionId: String): ChatHistoryEntity = ChatHistoryEntity(
    id = id,
    sessionId = sessionId,
    turn = turn,
    role = role.name,
    content = content,
    npcId = npcId,
    createdAt = createdAt,
)

fun DiceRollEntity.toDomain(): DiceResult = DiceResult(
    attributeId = io.github.shiokunn.trpg.core.domain.AttributeId.fromId(attributeId)
        ?: io.github.shiokunn.trpg.core.domain.AttributeId.STR,
    skillId = skillId,
    skillName = skillName,
    dice = JsonCodec.decode(diceJson, emptyList<Int>()),
    rollMode = enumValue(rollMode, io.github.shiokunn.trpg.core.domain.RollMode.NORMAL),
    attributeValue = attributeValue,
    skillLevel = skillLevel,
    modifiers = modifiers,
    total = total,
    difficulty = difficulty,
    outcome = enumValue(outcome, io.github.shiokunn.trpg.core.domain.CheckOutcome.FAILURE),
    reason = reason,
    turn = turn,
    createdAt = createdAt,
)

fun DiceResult.toEntity(sessionId: String, id: String): DiceRollEntity = DiceRollEntity(
    id = id,
    sessionId = sessionId,
    turn = turn,
    attributeId = attributeId.name,
    skillId = skillId,
    skillName = skillName,
    diceJson = JsonCodec.encode(dice),
    rollMode = rollMode.name,
    attributeValue = attributeValue,
    skillLevel = skillLevel,
    modifiers = modifiers,
    total = total,
    difficulty = difficulty,
    outcome = outcome.name,
    reason = reason,
    createdAt = createdAt,
)

fun ModelSettingsEntity.toDomain(): ModelSettings = ModelSettings(
    modelFileName = modelFileName,
    modelDisplayName = modelDisplayName,
    nCtx = nCtx,
    temperature = temperature,
    topP = topP,
    topK = topK,
    repeatPenalty = repeatPenalty,
    maxTokens = maxTokens,
    gpuLayers = gpuLayers,
    backend = backend,
    updatedAt = updatedAt,
)

fun ModelSettings.toEntity(): ModelSettingsEntity = ModelSettingsEntity(
    id = 1,
    modelFileName = modelFileName,
    modelDisplayName = modelDisplayName,
    nCtx = nCtx,
    temperature = temperature,
    topP = topP,
    topK = topK,
    repeatPenalty = repeatPenalty,
    maxTokens = maxTokens,
    gpuLayers = gpuLayers,
    backend = backend,
    updatedAt = updatedAt,
)

fun WorldFlagEntity.toPairValue(): Pair<String, String> = key to value
