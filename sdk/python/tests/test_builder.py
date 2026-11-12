"""Unit tests for aibuilder.Builder requests. Run: python3 -m unittest discover -s sdk/python/tests"""

import json
import os
import sys
import unittest
from unittest import mock

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))

from aibuilder import Builder, box  # noqa: E402


class _Response:
    def __init__(self, body):
        self.body = body

    def read(self):
        return self.body

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False


def sent(call):
    """Calls `call(builder)` and returns (url, json body) of the one request it made."""
    requests = []

    def urlopen(req, timeout=None):
        requests.append(req)
        return _Response(b'{"saved": "door", "size": [1, 2, 1], "blocks": 2}')

    with mock.patch("urllib.request.urlopen", urlopen):
        call(Builder(url="https://panel.example/s/abc", token="aibc_test"))
    assert len(requests) == 1
    return requests[0].full_url, json.loads(requests[0].data)


class SaveBlueprint(unittest.TestCase):
    region = box([0, 64, 0], [0, 65, 0])

    def test_without_description(self):
        url, body = sent(lambda b: b.save_blueprint("door", self.region))
        self.assertEqual(url, "https://panel.example/s/abc/api/save_blueprint")
        self.assertEqual(body, {"name": "door", "region": self.region})

    def test_with_description(self):
        url, body = sent(lambda b: b.save_blueprint("door", self.region, description="oak door, 2 high"))
        self.assertEqual(body, {"name": "door", "region": self.region, "description": "oak door, 2 high"})


if __name__ == "__main__":
    unittest.main()
