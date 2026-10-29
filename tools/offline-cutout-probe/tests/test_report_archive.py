import io
from pathlib import Path
import sys
import tarfile
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from report_archive import extract_reports


class ReportArchiveTest(unittest.TestCase):
    def extract(self, destination, name, kind=tarfile.REGTYPE):
        data = io.BytesIO()
        with tarfile.open(fileobj=data, mode='w') as archive:
            entry = tarfile.TarInfo(name)
            entry.type = kind
            if kind == tarfile.REGTYPE:
                entry.size = 2
                archive.addfile(entry, io.BytesIO(b'{}'))
            else:
                entry.linkname = '../outside'
                archive.addfile(entry)
        data.seek(0)
        with tarfile.open(fileobj=data) as archive:
            extract_reports(archive, destination)

    def test_regular_report_is_preserved(self):
        with tempfile.TemporaryDirectory() as work:
            self.extract(work, 'offline-probe/pp-human/report.json')
            self.assertEqual((Path(work) / 'offline-probe/pp-human/report.json').read_bytes(), b'{}')

    def test_traversal_and_absolute_names_are_rejected(self):
        with tempfile.TemporaryDirectory() as work:
            out = Path(work) / 'evidence'
            out.mkdir()
            for name in ('../outside', 'offline-probe/../../outside', str(Path(work) / 'outside')):
                with self.subTest(name=name), self.assertRaises(ValueError):
                    self.extract(out, name)
            self.assertFalse((Path(work) / 'outside').exists())

    def test_links_and_special_files_are_rejected(self):
        with tempfile.TemporaryDirectory() as work:
            for kind in (tarfile.SYMTYPE, tarfile.LNKTYPE, tarfile.FIFOTYPE, tarfile.CHRTYPE):
                with self.subTest(kind=kind), self.assertRaises(ValueError):
                    self.extract(work, 'offline-probe/report', kind)

    def test_existing_parent_symlink_cannot_escape_destination(self):
        with tempfile.TemporaryDirectory() as work:
            root = Path(work)
            out, outside = root / 'evidence', root / 'outside'
            out.mkdir()
            outside.mkdir()
            try:
                (out / 'offline-probe').symlink_to(outside, target_is_directory=True)
            except OSError:
                self.skipTest('Creating directory symlinks is not permitted on this host')
            with self.assertRaises(tarfile.OutsideDestinationError):
                self.extract(out, 'offline-probe/report.json')
            self.assertFalse((outside / 'report.json').exists())


if __name__ == '__main__':
    unittest.main()
