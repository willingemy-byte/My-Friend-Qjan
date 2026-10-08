"""Provider-independent, stateless chat transport for the future TreeAI backend."""
import json
import os
from dataclasses import dataclass, field
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import Request, build_opener, HTTPRedirectHandler

DEFAULT_MODEL = "gemma4:31b"
MAX_RESPONSE_BYTES = 1_000_000


class ModelError(RuntimeError):
    pass


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


@dataclass(frozen=True)
class ModelConfig:
    base_url: str
    model: str
    api_key: str = field(repr=False)

    @classmethod
    def from_env(cls):
        base = os.environ.get("TREEAI_BASE_URL", "https://ollama.com/v1").strip().rstrip("/")
        parsed = urlsplit(base)
        if (parsed.scheme != "https" or not parsed.hostname or parsed.username
                or parsed.password or parsed.query or parsed.fragment):
            raise ModelError("Configurer une adresse API HTTPS sans identifiants ni paramètres.")
        model = os.environ.get("TREEAI_MODEL", DEFAULT_MODEL).strip()
        if not model:
            raise ModelError("TREEAI_MODEL absent.")
        # A different endpoint must explicitly select its own credential variable.
        # The Ollama key is never implicitly reused on another service.
        key_env = os.environ.get("TREEAI_KEY_ENV", "").strip()
        if not key_env:
            if base != "https://ollama.com/v1":
                raise ModelError("Configurer TREEAI_KEY_ENV pour ce serveur.")
            key_env = "OLLAMA_API_KEY"
        key = os.environ.get(key_env, "").strip()
        if not key or "\n" in key or "\r" in key:
            raise ModelError("Clé du fournisseur absente ou invalide; configurer son secret serveur.")
        return cls(base, model, key)


def chat(messages, *, config=None, max_tokens=1024):
    config = config or ModelConfig.from_env()
    if isinstance(max_tokens, bool) or not isinstance(max_tokens, int) or not 1 <= max_tokens <= 8192:
        raise ValueError("Limite de réponse invalide.")
    request = Request(
        config.base_url + "/chat/completions",
        data=json.dumps({"model": config.model, "messages": messages,
                         "stream": False, "max_tokens": max_tokens}).encode("utf-8"),
        headers={"Content-Type": "application/json", "Authorization": "Bearer " + config.api_key},
    )
    try:
        with build_opener(NoRedirect()).open(request, timeout=90) as response:
            data = response.read(MAX_RESPONSE_BYTES + 1)
        if len(data) > MAX_RESPONSE_BYTES:
            raise ModelError("Réponse du modèle trop volumineuse.")
        result = json.loads(data)
    except HTTPError as error:
        # No retries/fallbacks, remote error bodies, headers or secrets in logs.
        raise ModelError("API modèle HTTP " + str(error.code)) from None
    except (URLError, TimeoutError, OSError):
        raise ModelError("Connexion au modèle impossible ou délai dépassé.") from None
    except (ValueError, UnicodeError):
        raise ModelError("Réponse JSON du modèle invalide.") from None
    try:
        choice = result["choices"][0]
        content = choice["message"]["content"]
        if choice.get("finish_reason") != "stop" or not isinstance(content, str) or not content.strip():
            raise ModelError("Aucune réponse complète reçue du modèle.")
        usage = result.get("usage") or {}
        return {"model": result.get("model", config.model), "response": content,
                "input_tokens": usage.get("prompt_tokens"),
                "output_tokens": usage.get("completion_tokens")}
    except (KeyError, IndexError, TypeError, AttributeError):
        raise ModelError("Structure de réponse du modèle invalide.") from None
