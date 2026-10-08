"""Minimal TreeAI connector to Ollama Cloud. Standard-library HTTP only."""
import json
import os
from urllib.error import HTTPError, URLError
from urllib.request import Request, build_opener, HTTPRedirectHandler

BASE_URL = "https://ollama.com"
DEFAULT_MODEL = "deepseek-v4-pro:0813"
MAX_RESPONSE_BYTES = 1_000_000

class OllamaError(RuntimeError):
    pass

class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None

def api_key():
    value = os.environ.get("OLLAMA_API_KEY", "").strip()
    if not value:
        raise OllamaError("OLLAMA_API_KEY absent : ajouter le secret à l'environnement GitHub jarvis.")
    return value

def request_json(path, body=None, key=None):
    # Fixed service origin; never forward credentials to another host.
    request = Request(
        BASE_URL + path,
        data=None if body is None else json.dumps(body).encode("utf-8"),
        headers={"Content-Type": "application/json", **({"Authorization": "Bearer " + key} if key else {})},
    )
    try:
        with build_opener(NoRedirect()).open(request, timeout=90) as response:
            data = response.read(MAX_RESPONSE_BYTES + 1)
        if len(data) > MAX_RESPONSE_BYTES:
            raise OllamaError("Réponse Ollama trop volumineuse.")
        return json.loads(data)
    except HTTPError as error:
        # Never print headers, request objects, credentials, or remote error bodies.
        raise OllamaError("Ollama HTTP " + str(error.code)) from None
    except (URLError, TimeoutError, OSError):
        raise OllamaError("Connexion Ollama impossible ou délai dépassé.") from None
    except (ValueError, UnicodeError):
        raise OllamaError("Réponse JSON Ollama invalide.") from None

def chat(messages, model=DEFAULT_MODEL, max_tokens=1024):
    key = api_key()
    if not model or not 1 <= max_tokens <= 8192:
        raise ValueError("Modèle ou limite de réponse invalide.")
    result = request_json("/api/chat", {
        "model": model, "messages": messages, "stream": False,
        "options": {"num_predict": max_tokens},
    }, key=key)
    content = result.get("message", {}).get("content")
    if result.get("done") is not True or not isinstance(content, str) or not content.strip():
        raise OllamaError("Aucune réponse complète reçue du modèle.")
    return {"model": result.get("model", model), "response": content,
            "input_tokens": result.get("prompt_eval_count"),
            "output_tokens": result.get("eval_count")}
