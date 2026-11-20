"""Generate synthetic PNGs for the local sketch workflow test."""

import json
import re
import struct
import zlib
from itertools import count
from urllib.parse import urlsplit
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


def png(red):
    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))

    return (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", struct.pack(">IIBBBBB", 16, 16, 8, 2, 0, 0, 0))
        + chunk(b"IDAT", zlib.compress((b"\x00" + bytes((red, 0, 125)) * 16) * 16))
        + chunk(b"IEND", b"")
    )


class Handler(BaseHTTPRequestHandler):
    ids = count(1)
    images = {}

    def do_POST(self):
        if self.headers.get("Authorization") != "Bearer local-sketch-test":
            self.send_error(401)
            return
        try:
            request = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
            if self.path == "/v1/chat/completions":
                self.plan(request)
            elif self.path == "/api/v1/networks/flux-2":
                self.generate(request)
            else:
                self.send_error(404)
        except (AssertionError, KeyError, IndexError, ValueError, TypeError):
            self.send_error(422)

    def plan(self, request):
        content = request["messages"][1]["content"]
        assert content[0] == {"type": "text", "text": "Примеры команды для эскиза:"}
        end_examples = content.index({"type": "text", "text": "Данные текущего заказа:"})
        examples, content = content[:end_examples], content[end_examples + 1:]
        assert len([item for item in examples if item["type"] == "image_url"]) == 1
        assert {"type": "text", "text": "Файл-пример: materials.txt"} in examples
        texts = [item["text"] for item in content if item["type"] == "text"]
        images = [item["image_url"]["url"] for item in content if item["type"] == "image_url"]
        browser = "Сотрудник: Пример размещения вышивки" in texts
        expected_prompt = "Подготовь эскиз с чёткими контурами." if browser else "Чёткие контуры и хлопковые нити."
        assert expected_prompt in request["messages"][0]["content"]
        expected_text = "Хлопковая нить, два цвета." if browser else "Используйте два цвета."
        assert {"type": "text", "text": expected_text} in examples
        if not browser:
            assert "Сотрудник: Здравствуйте" in texts
            assert "Покупатель: Спасибо" in texts
            assert "Покупатель: Для эскиза оставьте контур, связь: [скрыто]" in texts
        assert "Выбранная рамка вышивки: 220 × 220 мм." in texts
        assert "Фон вышивки: композиция из трёх исходных кадров автомобиля." in texts
        assert "Госномер автомобиля: вышить." in texts
        revision = "Выбранный эскиз для правки:" in texts
        assert len(images) == (5 if revision else 4)
        if revision:
            assert images[0] not in images[1:]
            assert texts[-1] == "Замечания сотрудника к выбранному эскизу: Оставьте только контур"
        expected_model = "gemini-3-1-flash-lite" if browser or revision else "gemini-2-5-flash-lite"
        assert request["model"] == expected_model
        prompts = ["standard edit"] if revision else [
            f'{"standard" if browser else "flash"} draft {index}' for index in range(2)
        ]
        self.send_json({"choices": [{"finish_reason": "stop", "message": {"content": json.dumps({"prompts": prompts})}}]})

    def generate(self, request):
        prompt = request["prompt"]
        expected = re.search(r"(?m)^(standard|flash) (edit|draft (\d+))$", prompt)
        assert expected and request["model"] == expected[1]
        revision = expected[2] == "edit"
        assert "Compose the embroidered background from all three reference frames of the car" in prompt
        assert "Include the car registration plate only when its text is clearly legible" in prompt
        references = request["image_urls"]
        assert len(references) == (6 if revision else 5)
        assert references[0].startswith("data:image/jpeg;") and references[1].startswith("data:image/png;")
        assert all(image.startswith("data:image/") for image in references)
        assert request["num_images"] == 1
        assert request["output_format"] == "png"
        assert request["enable_safety_checker"] is True
        request_id = next(self.ids)
        self.images[request_id] = png(120 if revision else 60 + int(expected[3]))
        self.send_json({"request_id": request_id, "model": "flux-2", "status": "processing"})

    def do_GET(self):
        path = urlsplit(self.path).path
        try:
            request_id = int(path.rsplit("/", 1)[1])
        except (ValueError, IndexError):
            self.send_error(404)
            return
        if request_id not in self.images:
            self.send_error(404)
        elif path.startswith("/api/v1/request/get/") and self.headers.get("Authorization") == "Bearer local-sketch-test":
            self.send_json({"status": "success", "result": [f"http://sketch-stub:8080/results/{request_id}"]})
        elif path.startswith("/results/") and "Authorization" not in self.headers:
            self.send_bytes(self.images.pop(request_id), "image/png")
        else:
            self.send_error(403)

    def send_json(self, body):
        self.send_bytes(json.dumps(body).encode(), "application/json")

    def send_bytes(self, data, content_type):
        self.send_response(200)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)


if __name__ == "__main__":
    ThreadingHTTPServer(("0.0.0.0", 8080), Handler).serve_forever()
