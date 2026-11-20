"""Local fixture for the YooKassa HTTP contract used by the smoke test."""

import base64
from http.server import BaseHTTPRequestHandler, HTTPServer
import json
import uuid


class Provider(BaseHTTPRequestHandler):
    payments = {}
    refunds = {}
    keys = {}
    fail_next_payment_response = False
    shop_is_test = True
    authorization = "Basic " + base64.b64encode(b"12345:test-ci-key").decode()

    def reply(self, code, value):
        body = json.dumps(value).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.headers.get("Authorization") != self.authorization:
            self.reply(401, {})
            return
        if self.path == "/v3/me":
            self.reply(200, {"account_id": "12345", "test": self.shop_is_test, "status": "enabled"})
            return
        routes = {"/v3/payments/": self.payments, "/v3/refunds/": self.refunds}
        for prefix, items in routes.items():
            if self.path.startswith(prefix):
                value = items.get(self.path[len(prefix):])
                self.reply(200 if value else 404, value or {})
                return
        self.reply(404, {})

    def do_POST(self):
        payload = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        if self.path.startswith("/test/"):
            self.test_command(payload)
            return
        key = self.headers.get("Idempotence-Key")
        if self.headers.get("Authorization") != self.authorization or not key:
            self.reply(401, {})
            return
        previous = self.keys.get(key)
        if previous:
            route, previous_payload, value = previous
            if self.path == route and payload == previous_payload:
                self.reply(200, value)
            else:
                self.reply(400, {})
            return
        identifier = str(uuid.uuid4())
        if self.path == "/v3/payments" and payload["capture"] and payload["confirmation"]["type"] == "redirect":
            value = {
                "id": identifier, "status": "pending", "amount": payload["amount"],
                "test": True, "paid": False, "recipient": {"account_id": "12345"},
                "confirmation": {"confirmation_url": f"https://yoomoney.ru/test-payment/{identifier}"},
                "metadata": payload["metadata"],
            }
            self.payments[identifier] = value
        elif self.path == "/v3/refunds":
            payment = self.payments[payload["payment_id"]]
            if payment["status"] != "succeeded" or payment["amount"] != payload["amount"]:
                self.reply(400, {})
                return
            value = {"id": identifier, "status": "pending", **payload}
            self.refunds[identifier] = value
        else:
            self.reply(400, {})
            return
        self.keys[key] = (self.path, payload, value)
        if self.path == "/v3/payments" and Provider.fail_next_payment_response:
            Provider.fail_next_payment_response = False
            self.reply(503, {})
            return
        self.reply(200, value)

    def test_command(self, payload):
        if self.path == "/test/shop-mode":
            Provider.shop_is_test = payload["test"]
            self.reply(200, {})
            return
        if self.path == "/test/lose-payment-response":
            Provider.fail_next_payment_response = True
            self.reply(200, {})
            return
        if self.path == "/test/refund":
            value = next((r for r in self.refunds.values() if r["payment_id"] == payload["paymentId"]), None)
        else:
            value = self.payments.get(payload["paymentId"])
        if not value:
            self.reply(404, {})
            return
        if "status" in payload:
            value["status"] = payload["status"]
            if "paid" in value:
                value["paid"] = value["status"] == "succeeded"
        if "value" in payload:
            value["amount"] = {"currency": "RUB", "value": payload["value"]}
        self.reply(200, value)


if __name__ == "__main__":
    HTTPServer(("0.0.0.0", 8080), Provider).serve_forever()
