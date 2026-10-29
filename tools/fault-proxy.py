#!/usr/bin/env python3
"""Forwards one node's three ports, injecting network faults.

Each --tcp and --udp names a LISTEN:TARGET port pair on --host. Datagrams are
lost, duplicated or delayed past later ones with the given probabilities, in
both directions. SIGUSR1 aborts every TCP connection, both sides, as a network
fault that leaves the node running. The counts are printed on each cut and on
exit.
"""

import argparse
import asyncio
import random
import signal
import sys


class Faults:
    def __init__(self, loss, duplicate, reorder, seed):
        self.loss = loss
        self.duplicate = duplicate
        self.reorder = reorder
        self.random = random.Random(seed)
        self.counts = {
            "datagrams": 0,
            "lost": 0,
            "duplicated": 0,
            "reordered": 0,
            "cuts": 0,
        }

    def report(self):
        counts = " ".join(f"{name}={count}" for name, count in self.counts.items())
        print(f"fault-proxy: {counts}", flush=True)

    def send(self, transport, data, address):
        """Sends one datagram, or none, or two, now or a little later."""
        self.counts["datagrams"] += 1
        if self.random.random() < self.loss:
            self.counts["lost"] += 1
            return
        copies = 1
        if self.random.random() < self.duplicate:
            self.counts["duplicated"] += 1
            copies = 2
        for _ in range(copies):
            if self.random.random() < self.reorder:
                self.counts["reordered"] += 1
                delay = self.random.uniform(0.005, 0.03)
                asyncio.get_running_loop().call_later(
                    delay, _send, transport, data, address
                )
            else:
                _send(transport, data, address)


def _send(transport, data, address):
    if not transport.is_closing():
        transport.sendto(data, address)


class Upstream(asyncio.DatagramProtocol):
    """One client's socket toward the node, so the node answers it here."""

    def __init__(self, faults, downstream, client):
        self.faults = faults
        self.downstream = downstream
        self.client = client
        self.transport = None

    def connection_made(self, transport):
        self.transport = transport

    def datagram_received(self, data, address):
        self.faults.send(self.downstream, data, self.client)


class Downstream(asyncio.DatagramProtocol):
    """The listening socket the controller sends its requests to."""

    def __init__(self, faults, target):
        self.faults = faults
        self.target = target
        self.transport = None
        self.upstreams = {}

    def connection_made(self, transport):
        self.transport = transport

    def datagram_received(self, data, address):
        asyncio.get_running_loop().create_task(self.forward(data, address))

    async def forward(self, data, client):
        upstream = self.upstreams.get(client)
        if upstream is None:
            _, upstream = await asyncio.get_running_loop().create_datagram_endpoint(
                lambda: Upstream(self.faults, self.transport, client),
                remote_addr=self.target,
            )
            self.upstreams[client] = upstream
        self.faults.send(upstream.transport, data, None)


class Tcp:
    """Pipes each accepted connection to the node, and cuts them all on cue."""

    def __init__(self, target):
        self.target = target
        self.writers = set()

    async def accept(self, reader, writer):
        try:
            node_reader, node_writer = await asyncio.open_connection(*self.target)
        except OSError:
            writer.transport.abort()
            return
        self.writers.update((writer, node_writer))
        await asyncio.gather(
            self.pipe(reader, node_writer), self.pipe(node_reader, writer)
        )
        self.writers.difference_update((writer, node_writer))

    async def pipe(self, reader, writer):
        try:
            while data := await reader.read(65536):
                writer.write(data)
                await writer.drain()
        except (ConnectionError, OSError):
            pass
        finally:
            writer.transport.abort()

    def cut(self):
        for writer in list(self.writers):
            writer.transport.abort()


def ports(text):
    listen, target = text.split(":")
    return int(listen), int(target)


async def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--tcp", type=ports, action="append", default=[])
    parser.add_argument("--udp", type=ports, action="append", default=[])
    parser.add_argument("--loss", type=float, default=0.0)
    parser.add_argument("--duplicate", type=float, default=0.0)
    parser.add_argument("--reorder", type=float, default=0.0)
    parser.add_argument("--seed", type=int, default=1)
    args = parser.parse_args()

    faults = Faults(args.loss, args.duplicate, args.reorder, args.seed)
    loop = asyncio.get_running_loop()
    pipes = []
    for listen, target in args.tcp:
        pipe = Tcp((args.host, target))
        pipes.append(pipe)
        await asyncio.start_server(pipe.accept, args.host, listen)
    for listen, target in args.udp:
        await loop.create_datagram_endpoint(
            lambda target=target: Downstream(faults, (args.host, target)),
            local_addr=(args.host, listen),
        )

    def cut():
        faults.counts["cuts"] += 1
        for pipe in pipes:
            pipe.cut()
        faults.report()

    stopped = asyncio.Event()
    loop.add_signal_handler(signal.SIGUSR1, cut)
    loop.add_signal_handler(signal.SIGTERM, stopped.set)
    loop.add_signal_handler(signal.SIGINT, stopped.set)
    print(f"fault-proxy: listening on {args.host}", flush=True)
    await stopped.wait()
    faults.report()


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
