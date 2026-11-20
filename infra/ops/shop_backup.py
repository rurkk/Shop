#!/usr/bin/env python3
"""Back up and restore the Shop Compose database and object volume."""

import argparse
from contextlib import contextmanager
from datetime import datetime, timezone
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import sys
import tempfile
from zipfile import BadZipFile, ZipFile


DATABASE = "shop.dump"
FILES = "files.tar.gz"
MANIFEST = "manifest.json"
BLOCK_SIZE = 1024 * 1024
DATA_VOLUMES = {
    "postgres": ("postgres-data", "/var/lib/postgresql/data"),
    "storage": ("storage-data", "/data"),
}


def command(*args, **options):
    return subprocess.run(args, check=True, **options)


def output(*args):
    return command(*args, stdout=subprocess.PIPE, text=True).stdout.strip()


def require(condition, message):
    if not condition:
        raise ValueError(message)


class Shop:
    def __init__(self, args):
        self.project = args.project
        require(re.fullmatch(r"[a-z0-9][a-z0-9_-]*", self.project), "Invalid Compose project name")
        self.compose = ["docker", "compose", "--project-name", self.project, "--env-file", str(args.env_file)]
        for path in args.file:
            self.compose.extend(("-f", str(path.resolve(strict=True))))
        config = json.loads(output(*self.compose, "config", "--format", "json"))
        self.services = config["services"]
        require(config["name"] == self.project, "Unexpected Compose project")
        for service, (volume, target) in DATA_VOLUMES.items():
            details = self.services[service]
            expected = {"name": self.volume_name(service)}
            require(config["volumes"][volume] == expected, f"{volume} must be a dedicated Compose volume")
            mounts = [m for m in details["volumes"] if m["target"] == target]
            require(len(mounts) == 1 and mounts[0]["type"] == "volume" and mounts[0]["source"] == volume,
                    f"Unexpected volume for {service}:{target}")
            require(not details.get("depends_on") and not details.get("ports"),
                    f"{service} must have no dependencies or published ports")

    def volume_name(self, service):
        return f"{self.project}_{DATA_VOLUMES[service][0]}"

    def container(self, service, required=True):
        ids = output(*self.compose, "ps", "--all", "--quiet", service).splitlines()
        if not ids and not required:
            return None
        require(len(ids) == 1, f"Expected one container for {self.project}/{service}")
        details = json.loads(output("docker", "container", "inspect", ids[0]))[0]
        if service in DATA_VOLUMES:
            volume, target = DATA_VOLUMES[service]
            mounts = [m for m in details["Mounts"] if m["Destination"] == target]
            name = self.volume_name(service)
            require(len(mounts) == 1 and mounts[0].get("Name") == name,
                    f"{service} is not mounted to {name}")
            actual = json.loads(output("docker", "volume", "inspect", name))[0]
            labels = actual.get("Labels") or {}
            require(actual["Driver"] == "local" and not actual.get("Options")
                    and labels.get("com.docker.compose.project") == self.project
                    and labels.get("com.docker.compose.volume") == volume, f"Unexpected ownership of volume {name}")
        return details

    def require_fresh(self):
        containers = output("docker", "container", "ls", "--all", "--quiet",
                            "--filter", f"label=com.docker.compose.project={self.project}")
        volumes = set(output("docker", "volume", "ls", "--quiet").splitlines())
        require(not containers and not volumes.intersection(self.volume_name(s) for s in DATA_VOLUMES),
                f"Restore requires a fresh project without containers or volumes: {self.project}")

    @contextmanager
    def stopped_writers(self):
        stopped = []
        containers = [self.container(service, required=service == "storage")
                      for service in ("nginx", "backend", "storage")]
        try:
            for container in containers:
                if container is None:
                    continue
                status = container["State"]["Status"]
                require(status in {"running", "created", "exited"},
                        f"Unexpected container state: {container['Name']} is {status}")
                if status == "running":
                    stopped.append(container["Id"])
                    command("docker", "stop", "--time", "30", container["Id"], stdout=subprocess.DEVNULL)
            yield
        finally:
            failed = []
            for container_id in reversed(stopped):
                result = subprocess.run(["docker", "start", container_id], stdout=subprocess.DEVNULL)
                if result.returncode:
                    failed.append(container_id[:12])
            if failed:
                raise ValueError(f"Could not restart containers in {self.project}: {', '.join(failed)}")

    def tar_command(self, image, *, extracting=False):
        access = "volume-nocopy" if extracting else "readonly,volume-nocopy"
        return ["docker", "run", "--rm", "--interactive", "--pull", "never", "--network", "none",
                "--read-only", "--security-opt", "no-new-privileges:true", "--cap-drop", "NET_RAW",
                "--entrypoint", "sh", "--mount",
                f"type=volume,source={self.volume_name('storage')},target=/data,{access}", image]


def container_env(details):
    return dict(line.split("=", 1) for line in details["Config"]["Env"])


def postgres_command(container, *, restoring=False):
    options = ("pg_restore --single-transaction --exit-on-error" if restoring else "pg_dump --format=custom")
    script = f'export PGPASSWORD="$POSTGRES_PASSWORD"\nexec {options} -h 127.0.0.1 -U postgres -d "$POSTGRES_DB"'
    return ["docker", "exec", "-i", container["Id"], "sh", "-ec", script]


def add_stream(archive, name, args):
    digest = hashlib.sha256()
    with subprocess.Popen(args, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE) as child:
        with archive.open(name, "w", force_zip64=True) as entry:
            for data in iter(lambda: child.stdout.read(BLOCK_SIZE), b""):
                digest.update(data)
                entry.write(data)
        if child.wait():
            raise subprocess.CalledProcessError(child.returncode, args)
    return digest.hexdigest()


def backup(args, shop):
    dest = args.archive
    require(not os.path.lexists(dest), f"Archive already exists: {dest}")
    encrypt = ("age", "--encrypt", "--recipient", args.recipient)
    command(*encrypt, input=b"", stdout=subprocess.DEVNULL)
    database = shop.container("postgres")
    storage = shop.container("storage")
    require(database["State"]["Status"] == "running", "PostgreSQL must be running during backup")
    db_env = container_env(database)
    manifest = {
        "version": 1,
        "project": shop.project,
        "created_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "database": db_env["POSTGRES_DB"],
        "database_user": db_env["SHOP_DB_USER"],
        "bucket": container_env(storage)["S3_BUCKET"],
        "postgres_image": shop.services["postgres"]["image"],
        "storage_image": shop.services["storage"]["image"],
    }
    with tempfile.NamedTemporaryFile(dir=dest.parent, prefix=f".{dest.name}.", suffix=".partial") as temp:
        with subprocess.Popen(encrypt, stdin=subprocess.PIPE, stdout=temp) as cipher:
            with shop.stopped_writers():
                mounted = output("docker", "ps", "--quiet", "--filter", f"volume={shop.volume_name('storage')}")
                require(not mounted, "Another running container has the storage volume mounted")
                with ZipFile(cipher.stdin, "w") as archive:
                    manifest["sha256"] = {
                        DATABASE: add_stream(archive, DATABASE, postgres_command(database)),
                        FILES: add_stream(archive, FILES, shop.tar_command(manifest["postgres_image"])
                                          + ["-ec", "exec tar -czf - -C /data ."]),
                    }
                    archive.writestr(MANIFEST, json.dumps(manifest, indent=2) + "\n")
            cipher.stdin.close()
            if cipher.wait():
                raise subprocess.CalledProcessError(cipher.returncode, encrypt)
        os.fsync(temp.fileno())
        os.link(temp.name, dest)
    print(f"Backup written to {dest}")


@contextmanager
def verified_archive(args):
    with tempfile.TemporaryFile() as plaintext:
        command("age", "--decrypt", "--identity", str(args.identity), str(args.archive), stdout=plaintext)
        plaintext.seek(0)
        with ZipFile(plaintext) as archive:
            require(sorted(archive.namelist()) == sorted((MANIFEST, DATABASE, FILES)), "Unexpected archive entries")
            require(archive.getinfo(MANIFEST).file_size <= 16_384, "Backup manifest is too large")
            manifest = json.loads(archive.read(MANIFEST))
            require(manifest["version"] == 1, "Unsupported backup format")
            for name in (DATABASE, FILES):
                digest = hashlib.sha256()
                with archive.open(name) as entry:
                    for data in iter(lambda: entry.read(BLOCK_SIZE), b""):
                        digest.update(data)
                require(digest.hexdigest() == manifest["sha256"][name], f"Checksum mismatch: {name}")
            yield archive, manifest


def restore_stream(archive, name, args):
    with archive.open(name) as entry, subprocess.Popen(args, stdin=subprocess.PIPE) as child:
        shutil.copyfileobj(entry, child.stdin, length=BLOCK_SIZE)
        child.stdin.close()
        if child.wait():
            raise subprocess.CalledProcessError(child.returncode, args)


def restore(args, shop):
    with verified_archive(args) as (archive, manifest):
        require(shop.project != manifest["project"], "Choose a restore project name different from the source")
        postgres = shop.services["postgres"]
        storage = shop.services["storage"]
        require(postgres["environment"]["POSTGRES_DB"] == manifest["database"]
                and postgres["environment"]["SHOP_DB_USER"] == manifest["database_user"],
                "The destination database name and application role must match the backup")
        require(postgres["image"] == manifest["postgres_image"] and storage["image"] == manifest["storage_image"]
                and storage["environment"]["S3_BUCKET"] == manifest["bucket"],
                "The destination PostgreSQL image, SeaweedFS image and bucket must match the backup")
        shop.require_fresh()
        # Keep Docker from populating the fresh volume from SeaweedFS' image layers.
        override = {"services": {"storage": {"volumes": [
            {"type": "volume", "source": "storage-data", "target": "/data", "volume": {"nocopy": True}}
        ]}}}
        with tempfile.NamedTemporaryFile(mode="w", suffix=".compose.yaml") as config:
            json.dump(override, config)
            config.flush()
            command(*shop.compose, "-f", config.name,
                    "create", "--no-build", "--no-recreate", "--pull", "never", "postgres", "storage")
        storage_container = shop.container("storage")
        require(storage_container["State"]["Status"] == "created", "Destination storage must be stopped")
        command(*shop.compose, "up", "--no-deps", "--no-build", "--pull", "never", "--wait", "--wait-timeout", "120",
                "postgres")
        database = shop.container("postgres")
        restore_stream(archive, DATABASE, postgres_command(database, restoring=True))
        restore_stream(archive, FILES, shop.tar_command(postgres["image"], extracting=True)
                       + ["-ec", 'test -z "$(ls -A /data)" || { echo "Storage volume is not empty" >&2; exit 1; }\n'
                                 "exec tar -xzf - -C /data"])
    print(f"Backup restored into {shop.project}. Only PostgreSQL is running.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    operations = parser.add_subparsers(dest="operation", required=True)
    for name in ("backup", "restore", "verify"):
        operation = operations.add_parser(name)
        operation.add_argument("--archive", required=True, type=Path)
        if name == "backup":
            operation.add_argument("--recipient", required=True, help="age public recipient")
        else:
            operation.add_argument("--identity", required=True, type=Path)
        if name != "verify":
            operation.add_argument("--project", required=True)
            operation.add_argument("--env-file", required=True, type=Path)
            operation.add_argument("-f", "--file", action="append", required=True, type=Path)
    args = parser.parse_args()
    require(args.archive.name.endswith(".zip.age"), "The archive name must end in .zip.age")
    args.archive = args.archive.absolute()
    if args.operation == "verify":
        with verified_archive(args):
            print(f"Backup verified: {args.archive}")
        return

    # Serialize operations that use the same environment file without writing a lock file.
    with args.env_file.open("rb") as env:
        fcntl.flock(env, fcntl.LOCK_EX | fcntl.LOCK_NB)
        shop = Shop(args)
        if args.operation == "backup":
            backup(args, shop)
        else:
            restore(args, shop)


def interrupt(signum, frame):
    raise KeyboardInterrupt


if __name__ == "__main__":
    signal.signal(signal.SIGTERM, interrupt)
    try:
        main()
    except (BadZipFile, KeyError, OSError, ValueError, subprocess.CalledProcessError, KeyboardInterrupt) as error:
        print(f"Backup operation failed: {error or type(error).__name__}", file=sys.stderr)
        sys.exit(1)
