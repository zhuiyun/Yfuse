import unittest
from unittest.mock import patch

from serve_session import close_emby_session, select_sources


def episode(item_id, size):
    return {"Id": item_id, "MediaSources": [
        {"Size": size, "Container": "mkv", "RunTimeTicks": 200_000_000}]}


class SelectionTest(unittest.TestCase):
    def setUp(self):
        self.catalog = {"Movie": [], "Episode": [episode("other", 55), episode("original", 44)]}
        self.resolver = {"candidates": self.catalog.__getitem__, "choose": lambda items, _: items}
        self.reference = {"e-000": {"kind": "Episode", "bytes": 44, "seconds": 20, "container": "mkv"}}

    def test_repeat_keeps_file_when_catalog_order_changes(self):
        selected = select_sources(self.resolver, {"e-000"}, self.reference)
        self.assertEqual([(label, item["Id"]) for label, item in selected], [("e-000", "original")])
        new_sample = select_sources(self.resolver, {"e-000"}, None)
        self.assertEqual(new_sample[0][1]["Id"], "other")

    def test_absent_and_ambiguous_references_stop_before_resolving(self):
        for catalog in ([episode("replacement", 33)], [episode("a", 44), episode("b", 44)]):
            with self.subTest(catalog=catalog):
                self.catalog["Episode"] = catalog
                with self.assertRaisesRegex(ValueError, "absent or ambiguous"):
                    select_sources(self.resolver, {"e-000"}, self.reference)

    def test_reference_for_a_different_kind_or_label_is_rejected(self):
        for label, kind in (("e-000", "Movie"), ("m-000", "Episode")):
            with self.subTest(label=label, kind=kind):
                ref = {label: dict(self.reference["e-000"], kind=kind)}
                with self.assertRaises(ValueError):
                    select_sources(self.resolver, {"e-000"}, ref)

    def test_logout_accepts_empty_success_and_does_not_follow_a_redirect(self):
        settings = {"ROOT": "http://127.0.0.1:19001/emby", "AUTH": "test", "TOKEN": "test"}
        for status in (204, 307):
            with self.subTest(status=status), patch("serve_session.http.client.HTTPConnection") as factory:
                connection = factory.return_value
                connection.getresponse.return_value.status = status
                if status == 204:
                    close_emby_session(settings)
                else:
                    with self.assertRaises(ValueError):
                        close_emby_session(settings)
                factory.assert_called_once_with("127.0.0.1", 19001, timeout=15)
                self.assertEqual(connection.request.call_args.args, ("POST", "/emby/Sessions/Logout"))
                connection.request.assert_called_once()
                connection.close.assert_called_once()
