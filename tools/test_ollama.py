"""One bounded model request. Never transmits personal conversations or memories."""
import json
import os
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from server.model_client import ModelConfig, ModelError, chat

def main():
    try:
        config = ModelConfig.from_env()  # Fail before any network if credentials are missing.
        answer = chat([{"role": "user", "content": "Réponds uniquement avec le texte TREEAI_OK."}],
                      config=config, max_tokens=128)
        print("TREEAI_OLLAMA_RESULT " + json.dumps({"status": "success", **answer}, ensure_ascii=True))
        summary = os.environ.get("GITHUB_STEP_SUMMARY")
        if summary:
            with open(summary, "a", encoding="utf-8") as out:
                out.write("Test du modèle terminé.\n\n")
                out.write("Modèle : " + config.model + "\n\n")
                out.write("Réponse réelle (JSON) :\n\n    " +
                          json.dumps(answer["response"], ensure_ascii=True) + "\n")
        return 0
    except ModelError as error:
        print("TREEAI_OLLAMA_RESULT " +
              json.dumps({"status": "failed", "error": str(error)}, ensure_ascii=True))
        return 1

if __name__ == "__main__":
    raise SystemExit(main())
