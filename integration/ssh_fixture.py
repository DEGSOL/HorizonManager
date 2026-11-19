"""Loopback-only SSH server for Gradle transport tests. Requires asyncssh in a separate Python environment."""
import asyncio
import sys
from pathlib import Path

import asyncssh


class TestSsh(asyncssh.SSHServer):
    def begin_auth(self, username):
        return True

    def password_auth_supported(self):
        return True

    def validate_password(self, username, password):
        return username == "horizon" and password == "fixture-ssh-password"

    def public_key_auth_supported(self):
        return True

    def validate_public_key(self, username, key):
        return username == "horizon" and key == CLIENT.convert_to_public()

    def connection_requested(self, dest_host, dest_port, orig_host, orig_port):
        return dest_host == "127.0.0.1" and dest_port == 18777


async def shell(process):
    if process.command == "printf READY":
        process.stdout.write("READY")
        process.exit(0)
    else:
        process.exit(126)


async def main(path, port):
    path.write_bytes(CLIENT.export_private_key("openssh", passphrase="fixture-key-password"))
    path.chmod(0o600)
    host_key = asyncssh.generate_private_key("ssh-ed25519")
    server = await asyncssh.create_server(TestSsh, "127.0.0.1", port,
                                          server_host_keys=[host_key], process_factory=shell)
    print(f"SSH fixture listening on {server.get_port()}", flush=True)
    await server.wait_closed()


if __name__ == "__main__":
    CLIENT = asyncssh.generate_private_key("ssh-ed25519")
    asyncio.run(main(Path(sys.argv[1]), int(sys.argv[2])))
