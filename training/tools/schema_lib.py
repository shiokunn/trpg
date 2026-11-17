#!/usr/bin/env python3
"""Schema + contract validation for the LFM3 TRPG training set.

Every rule here is copied from the Kotlin implementation:

  core/ai/GmResponse.kt          GmResponseRules
  core/ai/GmResponseParser.kt    validate()
  core/ai/DraftModels.kt         WorldDraft / CharacterDraft / *Draft + DraftLimits
  core/ai/DraftParsers.kt        WorldDraftParser.validate / CharacterDraftParser.validate
  core/state/StateChange.kt      StateChangeTypes + StateChangeLimits
  core/state/StateChangeValidator.kt
  core/domain/*.kt               Attributes / Skill / Item / Npc / Quest / CombatStats

Two kinds of errors are reported:
  error   -> the app would reject the object (or the data is unusable)
  warning -> accepted by the app, but a quality problem for training data

The validator only knows the app contract. It does not know whether the
narrative is good, so a passing sample is "parseable", not "good".
"""

from __future__ import annotations

import json
import re

# ---------------------------------------------------------------- limits ----

MAX_NARRATIVE = 8000
MAX_CHOICE_TEXT = 200
MAX_SCENE_TITLE = 60
MAX_EVENT_SUMMARY = 300
MAX_MEMORIES = 10
MAX_MEMORY = 500
MAX_NPC_MEMORIES = 10
ALLOWED_DIFFICULTIES = {4, 6, 8, 10, 12, 14}

DRAFT = {
    "name": 80,
    "short": 500,
    "long": 4000,
    "list": 12,
    "npcs": 8,
    "quests": 8,
    "objectives": 8,
    "items": 20,
    "tags": 8,
}

STATE_LIMITS = {
    "changes": 32,
    "hp": 100,
    "xp": 1000,
    "relation": 100,
    "flag_key": 64,
    "flag_value": 200,
    "item_name": 80,
    "item_desc": 500,
    "inventory": 100,
}

ATTRIBUTES = ("STR", "DEX", "INT", "WIL", "PER", "CHA")
ROLL_MODES = ("NORMAL", "ADVANTAGE", "DISADVANTAGE")
OUTCOMES = ("CRITICAL_SUCCESS", "SUCCESS", "PARTIAL_SUCCESS", "FAILURE")
QUEST_STATUS = ("ACTIVE", "COMPLETED", "FAILED", "UNKNOWN")
MEMORY_CATEGORY = (
    "EVENT",
    "NPC_RELATION",
    "DISCOVERY",
    "UNRESOLVED",
    "FORESHADOWING",
    "WORLD",
)
STATE_CHANGE_TYPES = (
    "HP_DELTA",
    "XP_DELTA",
    "ITEM_ADD",
    "ITEM_REMOVE",
    "NPC_RELATION_DELTA",
    "QUEST_OBJECTIVE_PROGRESS",
    "QUEST_STATUS",
    "LOCATION_CHANGE",
    "FLAG_SET",
    "COMBAT_START",
    "COMBAT_END",
)

GM_KEYS = {
    "narrative",
    "sceneTitle",
    "choices",
    "stateChanges",
    "memories",
    "npcMemories",
    "eventSummary",
}
CHOICE_KEYS = {"id", "text", "check"}
CHECK_KEYS = {"attribute", "skillId", "difficulty", "rollMode"}
STATE_CHANGE_KEYS = {
    "type",
    "targetId",
    "amount",
    "value",
    "questStatus",
    "objectiveIndex",
    "item",
    "reason",
}
MEMORY_KEYS = {"category", "content", "importance", "isSecret", "isConfirmed", "relatedIds"}
NPC_MEMORY_KEYS = {"npcId", "content", "importance", "isSecret"}

WORLD_KEYS = {
    "name", "genre", "era", "setting", "atmosphere", "history", "geography",
    "socialStructure", "factions", "techMagic", "specialSettings", "objective",
    "problem", "secrets", "scenario", "opening",
}
SCENARIO_KEYS = {"title", "premise", "length", "difficulty", "openingHook"}
OPENING_KEYS = {"location", "npcs", "quests"}
LOCATION_KEYS = {"name", "description", "connections", "secrets"}
NPC_KEYS = {
    "name", "description", "personality", "occupation", "disposition",
    "locationName", "secrets", "combat",
}
QUEST_KEYS = {"title", "description", "objectives", "rewards"}
COMBAT_KEYS = {"attackPower", "defense", "attackSkillLevel", "defenseSkillLevel", "hp"}

CHARACTER_KEYS = {
    "name", "species", "occupation", "background", "personality",
    "attributes", "skills", "items",
}
ATTRIBUTE_KEYS = {"str", "dex", "int", "wil", "per", "cha"}
SKILL_KEYS = {"name", "level", "description"}
ITEM_KEYS = {
    "name", "description", "quantity", "type", "power", "defense", "tags",
}

FLAG_KEY = re.compile(r"^[A-Za-z0-9_.:-]{1,64}$")


class Report:
    def __init__(self) -> None:
        self.errors: list[str] = []
        self.warnings: list[str] = []

    def err(self, msg: str) -> None:
        self.errors.append(msg)

    def warn(self, msg: str) -> None:
        self.warnings.append(msg)

    @property
    def ok(self) -> bool:
        return not self.errors


# ------------------------------------------------------------- utilities ---

def _is_int(v) -> bool:
    return isinstance(v, int) and not isinstance(v, bool)


def _str(r: Report, where: str, v, *, required: bool = False, max_len: int | None = None) -> None:
    if not isinstance(v, str):
        r.err(f"{where}: 文字列ではありません ({type(v).__name__})")
        return
    if required and not v.strip():
        r.err(f"{where}: 空です")
    if max_len is not None and len(v) > max_len:
        r.err(f"{where}: {len(v)}文字（最大{max_len}）")
    if v != v.strip():
        r.warn(f"{where}: 前後に空白があります")


def _int(r: Report, where: str, v, lo: int, hi: int, *, required: bool = False) -> None:
    if not _is_int(v):
        r.err(f"{where}: 整数ではありません ({type(v).__name__})")
        return
    if v < lo or v > hi:
        r.err(f"{where}: {v} は範囲外 ({lo}..{hi})")


def _keys(r: Report, where: str, obj, expected: set[str], required: set[str] | None = None) -> None:
    """Reject unknown keys; require only keys the Kotlin decoder has no default for.

    kotlinx.serialization applies defaults for omitted keys, so a missing
    `check`, `amount` or `isSecret` is legal. Unknown keys are always rejected
    (ignoreUnknownKeys = false).
    """
    if not isinstance(obj, dict):
        r.err(f"{where}: オブジェクトではありません")
        return
    must = expected if required is None else required
    missing = must - obj.keys()
    unknown = obj.keys() - expected
    if missing:
        r.err(f"{where}: 必須キーがありません {sorted(missing)}")
    if unknown:
        r.err(f"{where}: 未知のキーがあります {sorted(unknown)}")


def _str_list(r: Report, where: str, v, max_items: int, max_len: int) -> None:
    if not isinstance(v, list):
        r.err(f"{where}: 配列ではありません")
        return
    if len(v) > max_items:
        r.err(f"{where}: {len(v)}件（最大{max_items}）")
    for i, item in enumerate(v):
        _str(r, f"{where}[{i}]", item, required=True, max_len=max_len)


# ------------------------------------------------------------- GmResponse --

def validate_gm(response, context: dict | None = None) -> Report:
    r = Report()
    _keys(r, "response", response, GM_KEYS, {"narrative", "choices"})
    if not isinstance(response, dict):
        return r

    _str(r, "narrative", response.get("narrative", ""), required=True, max_len=MAX_NARRATIVE)
    _str(r, "sceneTitle", response.get("sceneTitle", ""), max_len=MAX_SCENE_TITLE)
    _str(r, "eventSummary", response.get("eventSummary", ""), max_len=MAX_EVENT_SUMMARY)

    choices = response.get("choices", [])
    if not isinstance(choices, list):
        r.err("choices: 配列ではありません")
        choices = []
    elif not 3 <= len(choices) <= 4:
        r.err(f"choices: {len(choices)}個（3〜4個）")

    seen: set[str] = set()
    owned_skills = _skill_ids(context)
    for i, choice in enumerate(choices):
        where = f"choices[{i}]"
        _keys(r, where, choice, CHOICE_KEYS, {"id", "text"})
        if not isinstance(choice, dict):
            continue
        cid = choice.get("id")
        _str(r, f"{where}.id", cid, required=True, max_len=64)
        if isinstance(cid, str):
            if cid in seen:
                r.err(f"{where}.id: 重複 ({cid})")
            seen.add(cid)
        _str(r, f"{where}.text", choice.get("text"), required=True, max_len=MAX_CHOICE_TEXT)

        check = choice.get("check")
        if check is None:
            continue
        _keys(r, f"{where}.check", check, CHECK_KEYS, {"attribute", "difficulty"})
        if not isinstance(check, dict):
            continue
        if check.get("attribute") not in ATTRIBUTES:
            r.err(f"{where}.check.attribute: {check.get('attribute')!r} は列挙外")
        skill = check.get("skillId")
        if skill is not None:
            _str(r, f"{where}.check.skillId", skill, required=True, max_len=200)
            if owned_skills is not None and skill not in owned_skills:
                r.err(f"{where}.check.skillId: 未所持のスキル ({skill})")
        if check.get("difficulty") not in ALLOWED_DIFFICULTIES:
            r.err(f"{where}.check.difficulty: {check.get('difficulty')!r} は 4/6/8/10/12/14 以外")
        if check.get("rollMode", "NORMAL") not in ROLL_MODES:
            r.err(f"{where}.check.rollMode: {check.get('rollMode')!r} は列挙外")

    _validate_state_changes(r, response.get("stateChanges") or [], context)
    _validate_memories(r, response.get("memories") or [], response.get("npcMemories") or [], context)
    return r


def _skill_ids(context: dict | None) -> set[str] | None:
    if not context:
        return None
    character = context.get("character") or {}
    skills = character.get("skills")
    if not isinstance(skills, list):
        return None
    return {s.get("id") for s in skills if isinstance(s, dict) and s.get("id")}


def _validate_state_changes(r: Report, changes, context: dict | None) -> None:
    if not isinstance(changes, list):
        r.err("stateChanges: 配列ではありません")
        return
    if len(changes) > STATE_LIMITS["changes"]:
        r.err(f"stateChanges: {len(changes)}件（最大{STATE_LIMITS['changes']}）")

    ctx = context or {}
    npcs = {n.get("id"): n for n in ctx.get("npcs", []) if isinstance(n, dict)}
    quests = {q.get("id"): q for q in ctx.get("quests", []) if isinstance(q, dict)}
    locations = {l.get("id"): l for l in ctx.get("locations", []) if isinstance(l, dict)}
    inventory = {
        i.get("name"): i for i in (ctx.get("character") or {}).get("inventory", []) if isinstance(i, dict)
    }
    inventory_ids = {
        i.get("id"): i for i in (ctx.get("character") or {}).get("inventory", []) if isinstance(i, dict)
    }
    active_combat = ctx.get("activeCombat")
    current_location = ctx.get("currentLocationId")
    has_ctx = bool(context)

    for i, ch in enumerate(changes):
        where = f"stateChanges[{i}]"
        _keys(r, where, ch, STATE_CHANGE_KEYS, {"type"})
        if not isinstance(ch, dict):
            continue
        typ = ch.get("type")
        if typ not in STATE_CHANGE_TYPES:
            r.err(f"{where}.type: {typ!r} は列挙外")
            continue
        target = ch.get("targetId", "")
        amount = ch.get("amount", 0)
        value = ch.get("value", "")
        status = ch.get("questStatus")
        obj_index = ch.get("objectiveIndex", -1)
        item = ch.get("item")
        reason = ch.get("reason", "")
        _str(r, f"{where}.reason", reason, max_len=200)

        # Fields that the contract ignores for this type are noise in training data.
        allowed_non_default = {
            "HP_DELTA": {"amount"},
            "XP_DELTA": {"amount"},
            "ITEM_ADD": {"item"},
            "ITEM_REMOVE": {"targetId", "amount"},
            "NPC_RELATION_DELTA": {"targetId", "amount"},
            "QUEST_OBJECTIVE_PROGRESS": {"targetId", "objectiveIndex", "amount"},
            "QUEST_STATUS": {"targetId", "questStatus"},
            "LOCATION_CHANGE": {"targetId"},
            "FLAG_SET": {"targetId", "value"},
            "COMBAT_START": {"targetId"},
            "COMBAT_END": set(),
        }[typ]
        for field, default in (
            ("targetId", ""), ("amount", 0), ("value", ""),
            ("objectiveIndex", -1),
        ):
            if field not in allowed_non_default and ch.get(field, default) != default:
                r.err(f"{where}.{field}: {typ} では既定値以外を使いません")
        if status is not None and "questStatus" not in allowed_non_default:
            r.err(f"{where}.questStatus: {typ} では null にします")
        if item is not None and "item" not in allowed_non_default:
            r.err(f"{where}.item: {typ} では null にします")

        required_by_type = {
            "HP_DELTA": {"amount"},
            "XP_DELTA": {"amount"},
            "ITEM_ADD": {"item"},
            "ITEM_REMOVE": {"targetId"},
            "NPC_RELATION_DELTA": {"targetId", "amount"},
            "QUEST_OBJECTIVE_PROGRESS": {"targetId", "objectiveIndex"},
            "QUEST_STATUS": {"targetId", "questStatus"},
            "LOCATION_CHANGE": {"targetId"},
            "FLAG_SET": {"targetId"},
            "COMBAT_START": {"targetId"},
            "COMBAT_END": set(),
        }[typ]
        missing = required_by_type - ch.keys()
        if missing:
            r.err(f"{where}: {typ} に必要なキーがありません {sorted(missing)}")

        if typ in ("HP_DELTA", "XP_DELTA", "NPC_RELATION_DELTA"):
            limit = {"HP_DELTA": STATE_LIMITS["hp"], "XP_DELTA": STATE_LIMITS["xp"],
                     "NPC_RELATION_DELTA": STATE_LIMITS["relation"]}[typ]
            _int(r, f"{where}.amount", amount, -limit, limit)
            if amount == 0:
                r.err(f"{where}.amount: 0 は不可")
            if typ == "NPC_RELATION_DELTA" and has_ctx and target not in npcs:
                r.err(f"{where}.targetId: 存在しないNPC ({target})")

        elif typ == "ITEM_ADD":
            _keys(r, f"{where}.item", item, ITEM_KEYS)
            if isinstance(item, dict):
                _str(r, f"{where}.item.name", item.get("name"), required=True,
                     max_len=STATE_LIMITS["item_name"])
                _str(r, f"{where}.item.description", item.get("description", ""),
                     max_len=STATE_LIMITS["item_desc"])
                _int(r, f"{where}.item.quantity", item.get("quantity", 1), 1, 99)
                _int(r, f"{where}.item.power", item.get("power", 0), 0, 20)
                _int(r, f"{where}.item.defense", item.get("defense", 0), 0, 20)
                if not isinstance(item.get("tags", []), list):
                    r.err(f"{where}.item.tags: 配列ではありません")
                if "id" in item:
                    r.err(f"{where}.item.id: 新規アイテムにIDを付けません（アプリが採番）")

        elif typ == "ITEM_REMOVE":
            if not isinstance(target, str) or not target.strip():
                r.err(f"{where}.targetId: 空です")
            elif has_ctx and target not in inventory_ids:
                r.err(f"{where}.targetId: 所持していない ({target})")
            _int(r, f"{where}.amount", amount, 0, 99)
            if has_ctx and target in inventory_ids:
                have = inventory_ids[target].get("quantity", 1)
                if (amount or 1) > have:
                    r.err(f"{where}.amount: 所持数{have}を超過")

        elif typ == "QUEST_OBJECTIVE_PROGRESS":
            quest = quests.get(target)
            if has_ctx and quest is None:
                r.err(f"{where}.targetId: 存在しないクエスト ({target})")
            if has_ctx and quest is not None:
                if quest.get("status") != "ACTIVE":
                    r.err(f"{where}: 終了したクエストは進められません")
                objectives = quest.get("objectives") or []
                if not _is_int(obj_index) or not 0 <= obj_index < len(objectives):
                    r.err(f"{where}.objectiveIndex: 範囲外 ({obj_index})")
            if amount == 0:
                r.err(f"{where}.amount: 0 は不可")

        elif typ == "QUEST_STATUS":
            if status not in QUEST_STATUS:
                r.err(f"{where}.questStatus: {status!r} は列挙外")
            if status not in ("COMPLETED", "FAILED"):
                r.err(f"{where}.questStatus: 完了/失敗のみ")
            quest = quests.get(target)
            if has_ctx and quest is None:
                r.err(f"{where}.targetId: 存在しないクエスト ({target})")
            elif has_ctx:
                if quest.get("status") not in ("ACTIVE", "UNKNOWN"):
                    r.err(f"{where}: 終了済みクエストは変更不可")
                if quest.get("status") == status:
                    r.err(f"{where}: 同じ状態への変更")

        elif typ == "LOCATION_CHANGE":
            if has_ctx and active_combat:
                r.err(f"{where}: 戦闘中は移動不可")
            if has_ctx and target not in locations:
                r.err(f"{where}.targetId: 存在しない場所 ({target})")
            if has_ctx and target == current_location:
                r.err(f"{where}: 既に現在地")

        elif typ == "FLAG_SET":
            if not isinstance(target, str) or not FLAG_KEY.match(target):
                r.err(f"{where}.targetId: フラグ名が不正 ({target!r})")
            _str(r, f"{where}.value", value, max_len=STATE_LIMITS["flag_value"])

        elif typ == "COMBAT_START":
            if has_ctx and active_combat:
                r.err(f"{where}: 既に戦闘中")
            npc = npcs.get(target)
            if has_ctx and npc is None:
                r.err(f"{where}.targetId: 存在しないNPC ({target})")
            elif has_ctx:
                if not npc.get("isAlive", True):
                    r.err(f"{where}: 倒れたNPCとは戦闘不可")
                combat = npc.get("combat")
                if not combat:
                    r.err(f"{where}: combat のないNPC")
                elif (combat.get("hp") or 0) <= 0:
                    r.err(f"{where}: 敵HPが0")

        elif typ == "COMBAT_END":
            if has_ctx and not active_combat:
                r.err(f"{where}: 戦闘中ではない")


def _validate_memories(r: Report, memories, npc_memories, context: dict | None) -> None:
    if not isinstance(memories, list):
        r.err("memories: 配列ではありません")
        memories = []
    if len(memories) > MAX_MEMORIES:
        r.err(f"memories: {len(memories)}件（最大{MAX_MEMORIES}）")
    for i, m in enumerate(memories):
        where = f"memories[{i}]"
        _keys(r, where, m, MEMORY_KEYS, {"category", "content"})
        if not isinstance(m, dict):
            continue
        if m.get("category") not in MEMORY_CATEGORY:
            r.err(f"{where}.category: {m.get('category')!r} は列挙外")
        _str(r, f"{where}.content", m.get("content"), required=True, max_len=MAX_MEMORY)
        _int(r, f"{where}.importance", m.get("importance", 3), 1, 5)
        for flag in ("isSecret", "isConfirmed"):
            if not isinstance(m.get(flag, False), bool):
                r.err(f"{where}.{flag}: bool ではありません")
        related = m.get("relatedIds", [])
        if not isinstance(related, list):
            r.err(f"{where}.relatedIds: 配列ではありません")
        elif len(related) > 20:
            r.err(f"{where}.relatedIds: {len(related)}件（最大20）")
        elif context:
            known = _known_ids(context)
            for rid in related:
                if rid not in known:
                    r.err(f"{where}.relatedIds: 存在しないID ({rid})")

    if not isinstance(npc_memories, list):
        r.err("npcMemories: 配列ではありません")
        npc_memories = []
    if len(npc_memories) > MAX_NPC_MEMORIES:
        r.err(f"npcMemories: {len(npc_memories)}件（最大{MAX_NPC_MEMORIES}）")
    npc_ids = {n.get("id") for n in (context or {}).get("npcs", [])}
    for i, m in enumerate(npc_memories):
        where = f"npcMemories[{i}]"
        _keys(r, where, m, NPC_MEMORY_KEYS, {"npcId", "content"})
        if not isinstance(m, dict):
            continue
        if not isinstance(m.get("npcId"), str) or not m.get("npcId", "").strip():
            r.err(f"{where}.npcId: 空です")
        elif context and m["npcId"] not in npc_ids:
            r.err(f"{where}.npcId: 存在しないNPC ({m['npcId']})")
        _str(r, f"{where}.content", m.get("content"), required=True, max_len=MAX_MEMORY)
        _int(r, f"{where}.importance", m.get("importance", 3), 1, 5)
        if not isinstance(m.get("isSecret", False), bool):
            r.err(f"{where}.isSecret: bool ではありません")


def _known_ids(context: dict) -> set[str]:
    ids: set[str] = set()
    for npc in context.get("npcs", []):
        if isinstance(npc, dict) and npc.get("id"):
            ids.add(npc["id"])
    for quest in context.get("quests", []):
        if isinstance(quest, dict) and quest.get("id"):
            ids.add(quest["id"])
    for loc in context.get("locations", []):
        if isinstance(loc, dict) and loc.get("id"):
            ids.add(loc["id"])
    for item in (context.get("character") or {}).get("inventory", []):
        if isinstance(item, dict) and item.get("id"):
            ids.add(item["id"])
    for skill in (context.get("character") or {}).get("skills", []):
        if isinstance(skill, dict) and skill.get("id"):
            ids.add(skill["id"])
    return ids


# -------------------------------------------------------------- WorldDraft --

def validate_world(w) -> Report:
    r = Report()
    _keys(r, "world", w, WORLD_KEYS)
    if not isinstance(w, dict):
        return r

    _str(r, "name", w.get("name"), required=True, max_len=DRAFT["name"])
    _str(r, "genre", w.get("genre"), required=True, max_len=DRAFT["name"])
    _str(r, "era", w.get("era", ""), max_len=DRAFT["short"])
    _str(r, "setting", w.get("setting", ""), max_len=DRAFT["long"])
    _str(r, "atmosphere", w.get("atmosphere", ""), max_len=DRAFT["short"])
    _str(r, "history", w.get("history", ""), max_len=DRAFT["long"])
    _str(r, "geography", w.get("geography", ""), max_len=DRAFT["long"])
    _str(r, "socialStructure", w.get("socialStructure", ""), max_len=DRAFT["long"])
    _str(r, "techMagic", w.get("techMagic", ""), max_len=DRAFT["long"])
    _str(r, "objective", w.get("objective", ""), max_len=DRAFT["short"])
    _str(r, "problem", w.get("problem", ""), max_len=DRAFT["short"])
    _str_list(r, "factions", w.get("factions", []), DRAFT["list"], DRAFT["name"])
    _str_list(r, "specialSettings", w.get("specialSettings", []), DRAFT["list"], DRAFT["short"])
    _str_list(r, "secrets", w.get("secrets", []), DRAFT["list"], DRAFT["short"])

    scenario = w.get("scenario")
    _keys(r, "scenario", scenario, SCENARIO_KEYS)
    if isinstance(scenario, dict):
        _str(r, "scenario.title", scenario.get("title"), required=True, max_len=100)
        _str(r, "scenario.premise", scenario.get("premise"), required=True, max_len=DRAFT["long"])
        _str(r, "scenario.length", scenario.get("length", ""), max_len=100)
        _str(r, "scenario.difficulty", scenario.get("difficulty", ""), max_len=100)
        _str(r, "scenario.openingHook", scenario.get("openingHook", ""), max_len=DRAFT["short"])

    opening = w.get("opening")
    _keys(r, "opening", opening, OPENING_KEYS)
    if not isinstance(opening, dict):
        return r

    location = opening.get("location")
    _keys(r, "opening.location", location, LOCATION_KEYS)
    if isinstance(location, dict):
        _str(r, "opening.location.name", location.get("name"), required=True, max_len=DRAFT["name"])
        _str(r, "opening.location.description", location.get("description", ""), max_len=DRAFT["long"])
        _str_list(r, "opening.location.connections", location.get("connections", []),
                  DRAFT["list"], DRAFT["name"])
        _str_list(r, "opening.location.secrets", location.get("secrets", []),
                  DRAFT["list"], DRAFT["short"])

    npcs = opening.get("npcs")
    if not isinstance(npcs, list):
        r.err("opening.npcs: 配列ではありません")
        npcs = []
    elif len(npcs) > DRAFT["npcs"]:
        r.err(f"opening.npcs: {len(npcs)}人（最大{DRAFT['npcs']}）")
    for i, npc in enumerate(npcs):
        where = f"opening.npcs[{i}]"
        _keys(r, where, npc, NPC_KEYS)
        if not isinstance(npc, dict):
            continue
        _str(r, f"{where}.name", npc.get("name"), required=True, max_len=DRAFT["name"])
        _str(r, f"{where}.description", npc.get("description", ""), max_len=DRAFT["long"])
        _str(r, f"{where}.personality", npc.get("personality", ""), max_len=DRAFT["short"])
        _str(r, f"{where}.occupation", npc.get("occupation", ""), max_len=DRAFT["name"])
        _str(r, f"{where}.disposition", npc.get("disposition", ""), max_len=DRAFT["short"])
        _str(r, f"{where}.locationName", npc.get("locationName", ""), max_len=DRAFT["name"])
        _str_list(r, f"{where}.secrets", npc.get("secrets", []), DRAFT["list"], DRAFT["short"])
        combat = npc.get("combat")
        if combat is not None:
            _keys(r, f"{where}.combat", combat, COMBAT_KEYS)
            if isinstance(combat, dict):
                _int(r, f"{where}.combat.attackPower", combat.get("attackPower", 0), 0, 20)
                _int(r, f"{where}.combat.defense", combat.get("defense", 0), 0, 20)
                _int(r, f"{where}.combat.attackSkillLevel", combat.get("attackSkillLevel", 0), 0, 3)
                _int(r, f"{where}.combat.defenseSkillLevel", combat.get("defenseSkillLevel", 0), 0, 3)
                _int(r, f"{where}.combat.hp", combat.get("hp", 10), 1, 999)
                if combat.get("hp", 10) > 30:
                    r.warn(f"{where}.combat.hp: アプリのプロンプトは1〜30を指示（パーサは999まで許容）")

    quests = opening.get("quests")
    if not isinstance(quests, list):
        r.err("opening.quests: 配列ではありません")
        quests = []
    elif len(quests) > DRAFT["quests"]:
        r.err(f"opening.quests: {len(quests)}件（最大{DRAFT['quests']}）")
    for i, quest in enumerate(quests):
        where = f"opening.quests[{i}]"
        _keys(r, where, quest, QUEST_KEYS)
        if not isinstance(quest, dict):
            continue
        _str(r, f"{where}.title", quest.get("title"), required=True, max_len=DRAFT["name"])
        _str(r, f"{where}.description", quest.get("description", ""), max_len=DRAFT["short"])
        objectives = quest.get("objectives")
        if not isinstance(objectives, list) or not objectives:
            r.err(f"{where}.objectives: 空です")
        elif len(objectives) > DRAFT["objectives"]:
            r.err(f"{where}.objectives: {len(objectives)}件（最大{DRAFT['objectives']}）")
        else:
            for j, obj in enumerate(objectives):
                _str(r, f"{where}.objectives[{j}]", obj, required=True, max_len=DRAFT["short"])
        _str_list(r, f"{where}.rewards", quest.get("rewards", []), DRAFT["list"], DRAFT["short"])

    # WorldDraft has only one opening location; `connections` are free-form names
    # of adjacent places and are not required to resolve to an NPC or to a
    # location object inside the same draft. The Kotlin parser does not check
    # this either, so no warning is emitted here.
    return r


# ---------------------------------------------------------- CharacterDraft --

def validate_character(c, *, prompt_rules: bool = True) -> Report:
    r = Report()
    _keys(r, "character", c, CHARACTER_KEYS)
    if not isinstance(c, dict):
        return r

    _str(r, "name", c.get("name"), required=True, max_len=40)
    _str(r, "species", c.get("species", ""), max_len=DRAFT["name"])
    _str(r, "occupation", c.get("occupation", ""), max_len=DRAFT["name"])
    _str(r, "background", c.get("background", ""), max_len=DRAFT["long"])
    _str(r, "personality", c.get("personality", ""), max_len=DRAFT["short"])

    attributes = c.get("attributes")
    _keys(r, "attributes", attributes, ATTRIBUTE_KEYS)
    if isinstance(attributes, dict):
        for key in ATTRIBUTE_KEYS:
            _int(r, f"attributes.{key}", attributes.get(key), 1, 5)

    skills = c.get("skills")
    if not isinstance(skills, list):
        r.err("skills: 配列ではありません")
        skills = []
    if prompt_rules and not 3 <= len(skills) <= 5:
        r.err(f"skills: {len(skills)}個（アプリのプロンプトは3〜5個）")
    names: list[str] = []
    for i, skill in enumerate(skills):
        where = f"skills[{i}]"
        _keys(r, where, skill, SKILL_KEYS)
        if not isinstance(skill, dict):
            continue
        _str(r, f"{where}.name", skill.get("name"), required=True, max_len=40)
        _int(r, f"{where}.level", skill.get("level", 0), 0, 2)
        _str(r, f"{where}.description", skill.get("description", ""), max_len=DRAFT["short"])
        if isinstance(skill.get("name"), str):
            names.append(skill["name"].strip())
    if len(set(names)) != len(names):
        r.err("skills: 同じ名前のスキルがあります")

    items = c.get("items")
    if not isinstance(items, list):
        r.err("items: 配列ではありません")
        items = []
    if len(items) > DRAFT["items"]:
        r.err(f"items: {len(items)}件（最大{DRAFT['items']}）")
    if prompt_rules and not 2 <= len(items) <= 5:
        r.err(f"items: {len(items)}個（アプリのプロンプトは2〜5個）")
    for i, item in enumerate(items):
        where = f"items[{i}]"
        _keys(r, where, item, ITEM_KEYS)
        if not isinstance(item, dict):
            continue
        _str(r, f"{where}.name", item.get("name"), required=True, max_len=DRAFT["name"])
        _str(r, f"{where}.description", item.get("description", ""), max_len=DRAFT["short"])
        _int(r, f"{where}.quantity", item.get("quantity", 1), 1, 99)
        _str(r, f"{where}.type", item.get("type", "misc"), max_len=40)
        _int(r, f"{where}.power", item.get("power", 0), 0, 20)
        _int(r, f"{where}.defense", item.get("defense", 0), 0, 20)
        _str_list(r, f"{where}.tags", item.get("tags", []), DRAFT["tags"], 40)
    return r


# ------------------------------------------------------- fragment drafts ---

def validate_items(items) -> Report:
    r = Report()
    if not isinstance(items, list):
        r.err("items: 配列ではありません")
        return r
    if len(items) > DRAFT["items"]:
        r.err(f"items: {len(items)}件（最大{DRAFT['items']}）")
    for i, item in enumerate(items):
        where = f"items[{i}]"
        _keys(r, where, item, ITEM_KEYS)
        if not isinstance(item, dict):
            continue
        _str(r, f"{where}.name", item.get("name"), required=True, max_len=DRAFT["name"])
        _str(r, f"{where}.description", item.get("description", ""), max_len=DRAFT["short"])
        _int(r, f"{where}.quantity", item.get("quantity", 1), 1, 99)
        _int(r, f"{where}.power", item.get("power", 0), 0, 20)
        _int(r, f"{where}.defense", item.get("defense", 0), 0, 20)
        _str_list(r, f"{where}.tags", item.get("tags", []), DRAFT["tags"], 40)
    return r


def validate_locations(locations) -> Report:
    r = Report()
    if not isinstance(locations, list):
        r.err("locations: 配列ではありません")
        return r
    for i, location in enumerate(locations):
        where = f"locations[{i}]"
        _keys(r, where, location, LOCATION_KEYS)
        if not isinstance(location, dict):
            continue
        _str(r, f"{where}.name", location.get("name"), required=True, max_len=DRAFT["name"])
        _str(r, f"{where}.description", location.get("description", ""), max_len=DRAFT["long"])
        _str_list(r, f"{where}.connections", location.get("connections", []),
                  DRAFT["list"], DRAFT["name"])
        _str_list(r, f"{where}.secrets", location.get("secrets", []),
                  DRAFT["list"], DRAFT["short"])
    return r


def validate_npcs(npcs) -> Report:
    r = Report()
    if not isinstance(npcs, list):
        r.err("npcs: 配列ではありません")
        return r
    if len(npcs) > DRAFT["npcs"]:
        r.err(f"npcs: {len(npcs)}人（最大{DRAFT['npcs']}）")
    for i, npc in enumerate(npcs):
        where = f"npcs[{i}]"
        _keys(r, where, npc, NPC_KEYS)
        if not isinstance(npc, dict):
            continue
        _str(r, f"{where}.name", npc.get("name"), required=True, max_len=DRAFT["name"])
        _str(r, f"{where}.description", npc.get("description", ""), max_len=DRAFT["long"])
        _str(r, f"{where}.personality", npc.get("personality", ""), max_len=DRAFT["short"])
        _str(r, f"{where}.occupation", npc.get("occupation", ""), max_len=DRAFT["name"])
        _str(r, f"{where}.disposition", npc.get("disposition", ""), max_len=DRAFT["short"])
        _str_list(r, f"{where}.secrets", npc.get("secrets", []), DRAFT["list"], DRAFT["short"])
        combat = npc.get("combat")
        if combat is not None:
            _keys(r, f"{where}.combat", combat, COMBAT_KEYS)
            if isinstance(combat, dict):
                _int(r, f"{where}.combat.hp", combat.get("hp", 10), 1, 999)
    return r


def validate_quests(quests) -> Report:
    r = Report()
    if not isinstance(quests, list):
        r.err("quests: 配列ではありません")
        return r
    for i, quest in enumerate(quests):
        where = f"quests[{i}]"
        _keys(r, where, quest, QUEST_KEYS)
        if not isinstance(quest, dict):
            continue
        _str(r, f"{where}.title", quest.get("title"), required=True, max_len=DRAFT["name"])
        _str(r, f"{where}.description", quest.get("description", ""), max_len=DRAFT["short"])
        objectives = quest.get("objectives")
        if not isinstance(objectives, list) or not objectives:
            r.err(f"{where}.objectives: 空です")
        else:
            for j, obj in enumerate(objectives):
                _str(r, f"{where}.objectives[{j}]", obj, required=True, max_len=DRAFT["short"])
    return r


# ------------------------------------------------------- extraction shapes --

EXTRACTION_SCHEMAS = {
    "character_draft": ("character", validate_character),
    "state_changes": ("stateChanges", None),
    "memories": ("memories", None),
    "items": ("items", validate_items),
    "locations": ("locations", validate_locations),
    "npcs": ("npcs", validate_npcs),
    "quests": ("quests", validate_quests),
}


def validate_extraction(task: str, output, context: dict | None = None) -> Report:
    if task not in EXTRACTION_SCHEMAS:
        r = Report()
        r.err(f"unknown extraction task: {task}")
        return r
    if task == "character_draft":
        # The app parses CharacterDraft at the JSON root, not under a wrapper key.
        return validate_character(output)
    key, fn = EXTRACTION_SCHEMAS[task]
    if task == "state_changes":
        r = Report()
        if not isinstance(output, dict):
            r.err("output: オブジェクトではありません")
            return r
        _keys(r, "output", output, {key})
        _validate_state_changes(r, output.get(key), context)
        return r
    if task == "memories":
        r = Report()
        if not isinstance(output, dict):
            r.err("output: オブジェクトではありません")
            return r
        _keys(r, "output", output, {"memories", "npcMemories"})
        _validate_memories(r, output.get("memories"), output.get("npcMemories"), context)
        return r
    if not isinstance(output, dict):
        r = Report()
        r.err("output: オブジェクトではありません")
        return r
    r = Report()
    _keys(r, "output", output, {key})
    inner = fn(output.get(key))
    r.errors.extend(inner.errors)
    r.warnings.extend(inner.warnings)
    return r


def validate_output(task: str, output, context: dict | None = None) -> Report:
    if task == "gm_turn":
        return validate_gm(output, context)
    if task == "world_json":
        return validate_world(output)
    return validate_extraction(task, output, context)


def strict_loads(text: str):
    """JSON.parse with the app's strictness (no trailing commas/comments already)."""
    return json.loads(text)
