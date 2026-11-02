"""Copy a selected Nimbalyst conversation into Tethium through its authenticated local API.

Nimbalyst is opened read-only. Only user/assistant conversation and answered forms
are copied; execution events and provider internals are not conversation messages.
The source IDs make retries idempotent. No model run is started by this script.
"""
import argparse
import json
import os
from pathlib import Path
import sqlite3
import urllib.request


def extract(database, session_id):
    connection = sqlite3.connect(database.resolve().as_uri() + "?mode=ro", uri=True)
    connection.row_factory = sqlite3.Row
    with connection:
        source = connection.execute(
            "SELECT id,title,provider,model,created_at FROM ai_sessions WHERE id=?", (session_id,)
        ).fetchone()
        if source is None:
            raise ValueError("Source session does not exist")
        rows = connection.execute(
            "SELECT id,created_at,direction,message_kind,content FROM ai_agent_messages "
            "WHERE session_id=? AND hidden=0 ORDER BY id", (session_id,)
        ).fetchall()
    connection.close()
    messages = []

    def add(source_id, role, text, timestamp):
        if text and text.strip():
            messages.append({"sourceId": str(source_id), "role": role, "text": text, "timestamp": timestamp})

    for row in rows:
        raw = row["content"]
        try:
            value = json.loads(raw)
        except json.JSONDecodeError:
            value = {}
        if not isinstance(value, dict):
            value = {}
        item = value.get("params", {}).get("item", {})
        if row["direction"] == "input" and row["message_kind"] == "user":
            add(row["id"], "user", raw, row["created_at"])
        elif row["message_kind"] == "assistant" and item.get("type") == "agentMessage":
            add(row["id"], "assistant", item.get("text", ""), row["created_at"])
        elif row["message_kind"] == "assistant" and value.get("type") == "assistant" and not value.get("parent_tool_use_id"):
            blocks = value.get("message", {}).get("content", [])
            text = "\n\n".join(block.get("text", "") for block in blocks if block.get("type") == "text")
            add(row["id"], "assistant", text, row["created_at"])
        elif row["message_kind"] == "assistant" and value.get("type") == "result" and isinstance(value.get("result"), str):
            text = value["result"]
            if not messages or messages[-1]["role"] != "assistant" or messages[-1]["text"].strip() != text.strip():
                add(row["id"], "assistant", text, row["created_at"])
        elif item.get("type") == "mcpToolCall" and item.get("tool") == "PromptForUserInput" and item.get("status") == "completed":
            form = item.get("arguments", {})
            answers = None
            for block in (item.get("result") or {}).get("content", []):
                if block.get("type") == "text":
                    try:
                        answers = json.loads(block["text"]).get("answers")
                    except (ValueError, AttributeError):
                        pass
            if not answers:
                continue
            prompt = [form.get("title", "Вопросы"), form.get("intro", "")]
            reply = []
            for field in form.get("fields", []):
                label = field.get("label", field["id"])
                prompt.append("\n" + label)
                options = {option["id"]: option for option in field.get("options", [])}
                for option in options.values():
                    prompt.append("- " + option["label"] + (": " + option["description"] if option.get("description") else ""))
                answer = answers.get(field["id"], {})
                if answer.get("otherText"):
                    text = answer["otherText"]
                elif answer.get("selectedId"):
                    text = options.get(answer["selectedId"], {}).get("label", answer["selectedId"])
                else:
                    text = json.dumps(answer, ensure_ascii=False)
                reply.append(label + "\n" + text)
            add(str(row["id"]) + ":question", "assistant", "\n".join(prompt), row["created_at"])
            add(str(row["id"]) + ":answer", "user", "\n\n".join(reply), row["created_at"])
    return dict(source), messages


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--session", required=True)
    parser.add_argument("--project", type=Path, required=True)
    parser.add_argument("--title", default="Разработка Tethium")
    parser.add_argument("--database", type=Path, default=Path(os.environ["APPDATA"]) / "@nimbalyst/electron/sqlite-db/nimbalyst.sqlite")
    parser.add_argument("--host", type=Path, default=Path(os.environ["LOCALAPPDATA"]) / "Tethium/host.json")
    parser.add_argument("--handoff", type=Path, required=True, help="JSON object containing current-state text")
    parser.add_argument("--export-only", action="store_true")
    args = parser.parse_args()
    source, messages = extract(args.database, args.session)
    handoff = json.loads(args.handoff.read_text(encoding="utf-8"))
    messages.append({"sourceId": "tethium-handoff-v1", "role": "system", "text": handoff["text"]})
    payload = {"projectPath": str(args.project.resolve()), "title": args.title,
               "sourceKey": "nimbalyst:" + args.session, "source": source, "messages": messages}
    output = args.project / ".local/imports" / args.session
    output.mkdir(parents=True, exist_ok=True)
    (output / "conversation.json").write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    result = {"messages": len(messages), "userMessages": sum(m["role"] == "user" for m in messages),
              "characters": sum(len(m["text"]) for m in messages), "export": str(output / "conversation.json")}
    if not args.export_only:
        host = json.loads(args.host.read_text(encoding="utf-8"))

        def post(path, value):
            request = urllib.request.Request("http://127.0.0.1:" + str(host["port"]) + path,
                                             data=json.dumps(value, ensure_ascii=False).encode("utf-8"),
                                             headers={"Authorization": "Bearer " + host["token"], "Content-Type": "application/json"})
            with urllib.request.urlopen(request, timeout=20) as response:
                return json.load(response)

        imported = post("/imports/conversation", payload)
        result["sessionId"] = imported["id"]
        model = (source.get("model") or "").removeprefix("openai-codex:") if source.get("provider") == "openai-codex" else ""
        post("/sessions/" + imported["id"] + "/settings", {"model": model, "access": "danger-full-access"})
        (output / "result.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    main()
