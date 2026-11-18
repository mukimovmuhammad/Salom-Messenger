#!/usr/bin/env python3
"""Stream a gzip tar archive into age without writing plaintext archive files."""
import argparse
import os
from pathlib import Path
import subprocess
import sys
import tarfile


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--stage", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--recipient-file", required=True)
    args = parser.parse_args()

    if os.geteuid() != 0:
        parser.error("must run as root")
    recipient = Path(args.recipient_file).read_text(encoding="ascii").strip()
    if not recipient.startswith("age1") or not recipient.isalnum():
        parser.error("invalid age recipient")

    output = Path(args.output)
    output.unlink(missing_ok=True)
    process = subprocess.Popen(
        ["/usr/bin/age", "--recipient", recipient, "--output", str(output)],
        stdin=subprocess.PIPE,
    )
    try:
        assert process.stdin is not None
        with tarfile.open(fileobj=process.stdin, mode="w|gz", compresslevel=1) as archive:
            for source in (
                Path("/etc/salom-api"),
                Path("/etc/redis/redis.conf"),
                Path("/etc/postgresql/18/main"),
                Path("/etc/systemd/system/salom-api.service"),
                Path("/srv/salom-data/app"),
                Path("/srv/salom-data/media"),
                Path(args.stage),
            ):
                if source.exists():
                    archive.add(source, arcname=str(source).lstrip("/"), recursive=True)
                else:
                    raise FileNotFoundError(str(source))
        process.stdin.close()
        result = process.wait()
        if result != 0:
            raise RuntimeError(f"age exited with status {result}")
        output.chmod(0o600)
        if not output.is_file() or output.stat().st_size == 0:
            raise RuntimeError("age output is empty")
        return 0
    except BaseException:
        if process.poll() is None:
            process.kill()
            process.wait()
        output.unlink(missing_ok=True)
        raise


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(f"salom-backup-archive: {exc.__class__.__name__}", file=sys.stderr)
        raise SystemExit(1)
