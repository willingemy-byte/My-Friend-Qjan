import io
import json
import os
import unittest
from unittest.mock import patch, MagicMock
from urllib.error import HTTPError
from server.model_client import ModelConfig, ModelError, NoRedirect, chat

class ProviderTests(unittest.TestCase):
    def config(self, extra=None):
        return patch.dict(os.environ, {"OLLAMA_API_KEY": "test-only", **(extra or {})}, clear=True)

    def response(self, finish="stop"):
        return {"choices": [{"message": {"content": "TREEAI_OK"}, "finish_reason": finish}],
                "usage": {"prompt_tokens": 9, "completion_tokens": 3}}

    def send(self, config, result):
        opener = MagicMock()
        opener.open.return_value.__enter__.return_value.read.return_value = json.dumps(result).encode()
        with patch("server.model_client.build_opener", return_value=opener):
            answer = chat([{"role": "user", "content": "test"}], config=config, max_tokens=128)
        return answer, opener.open.call_args.args[0]

    def test_default_free_model(self):
        with self.config():
            config = ModelConfig.from_env()
        answer, request = self.send(config, self.response())
        self.assertEqual(config.model, "gemma4:31b")
        self.assertEqual(request.full_url, "https://ollama.com/v1/chat/completions")
        self.assertEqual(answer["response"], "TREEAI_OK")
        self.assertEqual(json.loads(request.data)["max_tokens"], 128)
        self.assertNotIn("test-only", repr(config))

    def test_switch_endpoint_model_and_key_without_changing_code(self):
        with self.config({"TREEAI_BASE_URL": "https://ecs.example.test/v1",
                          "TREEAI_MODEL": "my-model", "TREEAI_KEY_ENV": "ECS_API_KEY",
                          "ECS_API_KEY": "different-test-key"}):
            config = ModelConfig.from_env()
        _, request = self.send(config, self.response())
        self.assertEqual(request.full_url, "https://ecs.example.test/v1/chat/completions")
        self.assertEqual(request.get_header("Authorization"), "Bearer different-test-key")
        self.assertEqual(json.loads(request.data)["model"], "my-model")

    def test_custom_host_does_not_reuse_ollama_key_implicitly(self):
        with self.config({"TREEAI_BASE_URL": "https://ecs.example.test/v1"}):
            with self.assertRaises(ModelError):
                ModelConfig.from_env()

    def test_missing_secret_fails_before_network(self):
        with patch.dict(os.environ, {}, clear=True), patch("server.model_client.build_opener") as opener:
            with self.assertRaises(ModelError):
                chat([])
            opener.assert_not_called()

    def test_invalid_urls_rejected(self):
        for url in ["http://ecs.example.test/v1", "https://user:pass@ecs.example.test/v1",
                    "https://ecs.example.test/v1?key=x", "https://ecs.example.test/v1#part"]:
            with self.subTest(url=url), self.config({"TREEAI_BASE_URL": url}):
                with self.assertRaises(ModelError):
                    ModelConfig.from_env()

    def test_truncated_response_not_saved_as_complete(self):
        with self.config():
            config = ModelConfig.from_env()
        with self.assertRaises(ModelError):
            self.send(config, self.response("length"))

    def test_payment_error_has_no_fallback_and_no_body_exposure(self):
        with self.config():
            config = ModelConfig.from_env()
        opener = MagicMock()
        opener.open.side_effect = HTTPError("https://ollama.com/v1/chat/completions",
                                           402, "private text", {}, io.BytesIO(b"private body"))
        with patch("server.model_client.build_opener", return_value=opener):
            with self.assertRaisesRegex(ModelError, "^API modèle HTTP 402$"):
                chat([], config=config)
        self.assertEqual(opener.open.call_count, 1)

    def test_credentials_not_forwarded_on_redirect(self):
        self.assertIsNone(NoRedirect().redirect_request(None, None, 302, "", {}, "https://other.test"))

if __name__ == "__main__":
    unittest.main()
