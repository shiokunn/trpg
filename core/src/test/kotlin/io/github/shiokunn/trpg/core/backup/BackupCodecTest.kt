package io.github.shiokunn.trpg.core.backup

import io.github.shiokunn.trpg.core.domain.Attributes
import io.github.shiokunn.trpg.core.domain.Character
import io.github.shiokunn.trpg.core.domain.GameSession
import io.github.shiokunn.trpg.core.domain.Location
import io.github.shiokunn.trpg.core.domain.Npc
import io.github.shiokunn.trpg.core.domain.NpcMemory
import io.github.shiokunn.trpg.core.domain.StoryMemory
import io.github.shiokunn.trpg.core.domain.World
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupCodecTest {

    @Test
    fun validBundleRoundTripsThroughStrictJson() {
        val bundle = sampleBundle()
        val json = BackupCodec.encode(bundle)
        val decoded = BackupCodec.decode(json)

        assertTrue(decoded is BackupDecodeResult.Success)
        val restored = (decoded as BackupDecodeResult.Success).bundle
        assertEquals(bundle.sessions.single().session.id, restored.sessions.single().session.id)
        assertEquals("灯台町", restored.sessions.single().locations.single().name)
        assertEquals(listOf("GM専用"), restored.worlds.single().secrets)
    }

    @Test
    fun unknownKeysAreRejected() {
        val json = BackupCodec.encode(sampleBundle()).replaceFirst("{", """{"unexpected": 1,""")
        val result = BackupCodec.decode(json)
        assertTrue(result is BackupDecodeResult.Failure)
    }

    @Test
    fun sessionWithoutWorldIsRejected() {
        val bundle = sampleBundle().copy(worlds = emptyList())
        val errors = BackupValidator.validate(bundle)
        assertTrue(errors.any { it.contains("対応する世界がありません") })
    }

    @Test
    fun characterIdMustMatchSession() {
        val session = sampleBundle().sessions.single()
        val bundle = sampleBundle().let {
            it.copy(sessions = listOf(session.copy(character = session.character.copy(id = "other"))))
        }
        val errors = BackupValidator.validate(bundle)
        assertTrue(errors.any { it.contains("characterId") })
    }

    @Test
    fun npcMemoryMustReferenceAnExistingNpc() {
        val session = sampleBundle().sessions.single()
        val orphan = NpcMemory(id = "nm1", npcId = "missing", content = "秘密", importance = 3)
        val bundle = sampleBundle().let {
            it.copy(sessions = listOf(session.copy(npcMemories = listOf(orphan))))
        }
        val errors = BackupValidator.validate(bundle)
        assertTrue(errors.any { it.contains("npcId") })
    }

    @Test
    fun duplicateIdsAreRejected() {
        val session = sampleBundle().sessions.single()
        val bundle = sampleBundle().let {
            it.copy(sessions = listOf(session, session))
        }
        val errors = BackupValidator.validate(bundle)
        assertTrue(errors.any { it.contains("重複") })
    }

    @Test
    fun currentLocationMustExist() {
        val session = sampleBundle().sessions.single()
        val bundle = sampleBundle().let {
            it.copy(sessions = listOf(session.copy(session = session.session.copy(currentLocationId = "missing"))))
        }
        val errors = BackupValidator.validate(bundle)
        assertTrue(errors.any { it.contains("currentLocationId") })
    }

    @Test
    fun invalidHpAndAttributesAreRejected() {
        val session = sampleBundle().sessions.single()
        val broken = session.copy(
            character = session.character.copy(hp = 99, attributes = Attributes(str = 9)),
        )
        val errors = BackupValidator.validate(sampleBundle().copy(sessions = listOf(broken)))
        assertTrue(errors.any { it.contains("hp") })
        assertTrue(errors.any { it.contains("attributes") })
    }

    private fun sampleBundle(): BackupBundle {
        val world = World(
            id = "w1",
            name = "霧の港",
            genre = "海洋ファンタジー",
            scenarioTitle = "失われた航海日誌",
            scenarioPremise = "港の謎を解く",
            secrets = listOf("GM専用"),
            createdAt = 1L,
        )
        val character = Character(
            id = "c1",
            name = "ミナ",
            attributes = Attributes(str = 3, dex = 3, int = 3, wil = 3, per = 3, cha = 3),
        )
        val session = GameSession(
            id = "s1",
            title = "失われた航海日誌",
            worldId = "w1",
            characterId = "c1",
            currentLocationId = "l1",
            turnCount = 3,
            createdAt = 1L,
        )
        val location = Location(id = "l1", name = "灯台町", isDiscovered = true)
        val npc = Npc(id = "n1", name = "灯台守", isDiscovered = true, locationId = "l1")
        val memory = StoryMemory(
            id = "m1",
            category = io.github.shiokunn.trpg.core.domain.MemoryCategory.EVENT,
            content = "灯台に登った",
            importance = 3,
            turn = 1,
        )
        val npcMemory = NpcMemory(id = "nm1", npcId = "n1", content = "灯台守は警戒している", importance = 2)
        return BackupBundle(
            exportedAt = 1L,
            worlds = listOf(world),
            sessions = listOf(
                BackupSession(
                    session = session,
                    character = character,
                    locations = listOf(location),
                    npcs = listOf(npc),
                    memories = listOf(memory),
                    npcMemories = listOf(npcMemory),
                ),
            ),
        )
    }
}
