"""Extract only report files/directories, with Python's data safety filter."""
from pathlib import Path


def extract_reports(archive, destination):
    # Require the backported/modern tar data filter; never fall back to unsafe extraction.
    for member in archive.getmembers():
        path = Path(member.name)
        if path.is_absolute() or '..' in path.parts or not (member.isfile() or member.isdir()):
            raise ValueError('Unsafe report archive')
    archive.extractall(destination, filter='data')
