"""One real, bounded request. No user conversations or memory are transmitted."""
import json
import os
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from server.ollama_client import DEFAULT_MODEL, OllamaError, api_key, chat, request_json

def main():
    try:
        api_key()  # Fail before any network operation if the secret is absent.
        model = os.environ.get("OLLAMA_MODEL", DEFAULT_MODEL)
        models = request_json("/api/tags")
        names = {item.get("name") for item in models.get("models", [])}
        if model not in names:
            raise OllamaError("Le modèle sélectionné ne figure pas dans le catalogue cloud.")
        answer = chat([{"role": "user", "content": "Réponds uniquement avec le texte TREEAI_OK."}], model=model)
        print("TREEAI_OLLAMA_RESULT " + json.dumps({"status": "success", **answer}, ensure_ascii=True))
        summary = os.environ.get("GITHUB_STEP_SUMMARY")
        if summary:
            with open(summary, "a", encoding="utf-8") as out:
                out.write("Test Ollama Cloud terminé.\n\n")
                out.write("Modèle : " + model + "\n\n")
                out.write("Réponse réelle (JSON) :\n\n")
                out.write("    " + json.dumps(answer["response"], ensure_ascii=True) + "\n")
        return 0
    except OllamaError as error:
        print("TREEAI_OLLAMA_RESULT " + json.dumps({"status": "failed", "error": str(error)}, ensure_ascii=True))
        return 1

if __name__ == "__main__":
    raise SystemExit(main())
