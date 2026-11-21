#!/usr/bin/env python3
"""Renders the GM user message exactly like core/prompt/PromptBuilder.kt.

Keep this file in sync with PromptBuilder.buildUserMessage / buildSections.
If the Kotlin renderer changes, training prompts must be regenerated, otherwise
the fine-tune learns a prompt layout the app no longer sends.
"""

from __future__ import annotations

ATTR_LABEL = {"STR": "筋力", "DEX": "敏捷", "INT": "知力", "WIL": "意志", "PER": "感覚", "CHA": "魅力"}
ATTR_ORDER = ("STR", "DEX", "INT", "WIL", "PER", "CHA")
ROLL_LABEL = {"NORMAL": "通常", "ADVANTAGE": "有利", "DISADVANTAGE": "不利"}
OUTCOME_LABEL = {
    "CRITICAL_SUCCESS": "大成功",
    "SUCCESS": "成功",
    "PARTIAL_SUCCESS": "部分的成功",
    "FAILURE": "失敗",
}
QUEST_LABEL = {"ACTIVE": "進行中", "COMPLETED": "完了", "FAILED": "失敗", "UNKNOWN": "不明"}
MEMORY_LABEL = {
    "EVENT": "重要な出来事",
    "NPC_RELATION": "NPCとの関係",
    "DISCOVERY": "発見した情報",
    "UNRESOLVED": "未解決の問題",
    "FORESHADOWING": "将来の伏線",
    "WORLD": "世界設定に影響する出来事",
}


def relationship_label(value: int) -> str:
    if value <= -60:
        return "敵対"
    if value <= -20:
        return "不信"
    if value < 20:
        return "中立"
    if value < 60:
        return "友好"
    return "信頼"


def render_user(context: dict, action: dict) -> str:
    parts = [f"プレイヤーの選択: {action['text'].strip()}"]
    check = action.get("check")
    if check:
        block = [
            "",
            "",
            "【アプリが確定した判定結果】",
            f"能力値: {ATTR_LABEL[check['attribute']]} {check['attributeValue']}",
        ]
        if check.get("skillId"):
            block[-1] += f" / スキル: {check.get('skillName', '')} +{check.get('skillLevel', 0)}"
        block.append(
            "出目: " + ", ".join(str(d) for d in check["dice"])
            + f"（{ROLL_LABEL[check.get('rollMode', 'NORMAL')]}）"
        )
        block.append(f"合計: {check['total']} / 難易度: {check['difficulty']}")
        block.append(f"結果: {OUTCOME_LABEL[check['outcome']]}")
        if check.get("reason"):
            block.append(f"理由: {check['reason']}")
        block.append("この結果に従って描写してください。判定をやり直したり、出目を変更してはいけません。")
        parts.append("\n".join(block))
    parts.append("次の場面を描写し、JSON契約に従って1つだけ出力してください。")
    user = parts[0] + "".join(
        (p if p.startswith("\n") else "\n\n" + p) for p in parts[1:]
    )
    return user


def render_history_action(action: dict) -> str:
    """The player chat message the app stores after a turn (GmTurnService)."""
    check = action.get("check")
    prefix = ""
    if check:
        prefix = f"【判定: {ATTR_LABEL[check['attribute']]}"
        if check.get("skillName"):
            prefix += f" + {check['skillName']}"
        prefix += " / 出目 " + ", ".join(str(d) for d in check["dice"])
        prefix += f" = {check['total']} / 難易度 {check['difficulty']}"
        prefix += f" → {OUTCOME_LABEL[check['outcome']]}】\n"
    return prefix + action["text"].strip()


def _state(context: dict) -> str:
    c = context["character"]
    out = [f"PC: {c['name']}" + (f"（{c['species']}）" if c.get("species") else "")]
    if c.get("occupation"):
        out[-1] += f" / {c['occupation']}"
    out.append(f"HP: {c['hp']}/{c['maxHp']} / レベル: {c.get('experience', 0) // 10 + 1}（XP {c.get('experience', 0)}）")
    attrs = c["attributes"]
    out.append("能力値: " + " ".join(f"{ATTR_LABEL[a]}{attrs[a.lower()]}" for a in ATTR_ORDER))
    if c.get("skills"):
        out.append("スキル: " + "、".join(
            f"{s['name']} Lv{s['level']}(id={s['id']})" for s in c["skills"]
        ))
    if c.get("inventory"):
        out.append("所持品: " + "、".join(
            f"{i['name']}×{i.get('quantity', 1)}(id={i['id']})" for i in c["inventory"]
        ))
    combat = context.get("activeCombat")
    if combat:
        out.append(
            f"戦闘中: ラウンド{combat['round']} / 敵 {combat['enemyName']} "
            f"HP {combat['enemyHp']}/{combat['enemyMaxHp']}"
            f"（攻撃{combat['enemyAttackPower']} 防御{combat['enemyDefense']}）"
        )
        out.append("戦闘中は移動できません。")
    if context.get("flags"):
        out.append("フラグ: " + "、".join(f"{k}={v}" for k, v in context["flags"].items()))
    location = next(
        (l for l in context.get("locations", []) if l["id"] == context.get("currentLocationId")),
        None,
    )
    if location:
        out.append(f"現在地: {location['name']}")
    out.append(f"ターン: {context.get('turn', 0) + 1}")
    if context.get("summary"):
        out.append(f"これまでのあらすじ: {context['summary']}")
    return "\n".join(out)


def _quests(context: dict) -> str:
    quests = context.get("quests") or []
    if not quests:
        return ""
    ordered = sorted(quests, key=lambda q: 0 if q.get("status") == "ACTIVE" else 1)
    lines = []
    for q in ordered:
        line = f"- [{QUEST_LABEL[q.get('status', 'ACTIVE')]}] {q['title']}(id={q['id']})"
        if q.get("description"):
            line += f": {q['description']}"
        if q.get("objectives"):
            line += "（" + " / ".join(
                f"{i}: " + ("達成: " if o.get("completed") else "未達: ") + o["description"]
                for i, o in enumerate(q["objectives"])
            ) + "）"
        lines.append(line)
    return "\n".join(lines)


def _npcs(context: dict) -> str:
    visible = [n for n in context.get("npcs", []) if n.get("isDiscovered", True)]
    if not visible:
        return ""
    blocks = []
    for npc in visible:
        block = [f"- {npc['name']}(id={npc['id']})"]
        if npc.get("occupation"):
            block[-1] += f"（{npc['occupation']}）"
        if not npc.get("isAlive", True):
            block[-1] += " ※倒れている"
        block.append(f"  関係: {relationship_label(npc.get('relationship', 0))} ({npc.get('relationship', 0)})")
        if npc.get("description"):
            block.append(f"  説明: {npc['description']}")
        if npc.get("personality"):
            block.append(f"  性格: {npc['personality']}")
        memories = [m for m in context.get("npcMemories", []) if m["npcId"] == npc["id"]]
        if memories:
            block.append("  記憶: " + " / ".join(m["content"] for m in memories))
        if npc.get("secrets"):
            block.append("  【GM専用・プレイヤーに直接開示しない】" + " / ".join(npc["secrets"]))
        blocks.append("\n".join(block))
    return "\n\n".join(blocks)


def _memories(context: dict) -> str:
    memories = context.get("memories") or []
    if not memories:
        return ""
    lines = []
    for m in memories:
        tag = MEMORY_LABEL[m["category"]] + f"/重要度{m.get('importance', 3)}"
        if not m.get("isConfirmed", True):
            tag += "/推測"
        if m.get("isSecret", False):
            tag += "/GM専用"
        lines.append(f"- [{tag}] {m['content']}")
    return "\n".join(lines)


def _locations(context: dict) -> str:
    locations = [l for l in context.get("locations", []) if l.get("isDiscovered", False)]
    if not locations:
        return ""
    lines = []
    for loc in locations:
        line = f"- {loc['name']}(id={loc['id']})"
        if loc["id"] == context.get("currentLocationId"):
            line += "（現在地）"
        if loc.get("description"):
            line += f": {loc['description']}"
        if loc.get("secrets"):
            line += " 【GM専用: " + " / ".join(loc["secrets"]) + "】"
        lines.append(line)
    return "\n".join(lines)


def _world(context: dict) -> str:
    w = context.get("world") or {}
    if not w:
        return ""
    out = [f"名前: {w.get('name', '')} / ジャンル: {w.get('genre', '')}"]
    if w.get("era"):
        out[0] += f" / 時代: {w['era']}"
    for key, label in (
        ("setting", "設定"), ("atmosphere", "雰囲気"), ("history", "歴史"),
        ("geography", "地理"), ("socialStructure", "社会構造"),
    ):
        if w.get(key):
            out.append(f"{label}: {w[key]}")
    if w.get("factions"):
        out.append("勢力: " + "、".join(w["factions"]))
    if w.get("techMagic"):
        out.append(f"技術・魔法: {w['techMagic']}")
    if w.get("specialSettings"):
        out.append("特殊設定: " + "、".join(w["specialSettings"]))
    if w.get("objective"):
        out.append(f"目的: {w['objective']}")
    if w.get("problem"):
        out.append(f"問題: {w['problem']}")
    if w.get("scenarioPremise"):
        out.append(f"シナリオ前提: {w['scenarioPremise']}")
    if w.get("secrets"):
        out.append("【GM専用・プレイヤーに直接開示しない秘密】" + " / ".join(w["secrets"]))
    return "\n".join(out)


def render_gm_system(context: dict) -> str:
    from prompts import GM_SYSTEM

    sections = []
    for name, body in (
        ("現在の状態", _state(context)),
        ("クエスト", _quests(context)),
        ("NPC", _npcs(context)),
        ("長期記憶", _memories(context)),
        ("場所", _locations(context)),
        ("世界設定", _world(context)),
    ):
        if body.strip():
            sections.append(f"【{name}】\n{body}")
    text = GM_SYSTEM
    if sections:
        text += "\n\n" + "\n\n".join(sections)
    return text
