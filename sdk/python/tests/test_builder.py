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


class Paste(unittest.TestCase):
    def queued(self, **kwargs):
        b = Builder(url="https://panel.example/s/abc", token="aibc_test")
        b.paste(**kwargs)
        return b._ops[-1]

    def test_origin_and_rotation(self):
        self.assertEqual(self.queued(name="window", origin=[1, 2, 3], rotation=90),
                         {"op": "paste", "name": "window", "origin": [1, 2, 3], "rotation": 90})

    def test_at_and_facing(self):
        self.assertEqual(self.queued(name="window", at=[1, 2, 3], facing="east"),
                         {"op": "paste", "name": "window", "at": [1, 2, 3], "facing": "east"})
        self.assertEqual(self.queued(name="window", at="@door"), {"op": "paste", "name": "window", "at": "@door", "rotation": 0})

    def test_needs_one_place_and_one_turn(self):
        from aibuilder import AIBuilderError
        for kwargs in ({"name": "w"}, {"name": "w", "origin": [0, 0, 0], "at": [0, 0, 0]},
                       {"name": "w", "at": [0, 0, 0], "facing": "east", "rotation": 90}):
            with self.assertRaises(AIBuilderError):
                self.queued(**kwargs)


if __name__ == "__main__":
    unittest.main()
