"""pyatv köprüsü: Apple TV'yi bulur, eşleştirir ve medya gönderir."""
import asyncio
import json
import socket
import threading
from urllib.parse import quote

import pyatv
from aiohttp import web
from pyatv.const import Protocol

PORT = 8765

# pyatv'nin çalışacağı arka plan olay döngüsü
_loop = asyncio.new_event_loop()
threading.Thread(target=_loop.run_forever, daemon=True).start()

_confs = {}
_pairing = None
_atv = None
_addr = None
_runner = None


def _run(coro, timeout=60):
    return asyncio.run_coroutine_threadsafe(coro, _loop).result(timeout)


def scan():
    async def go():
        found = await pyatv.scan(_loop, timeout=5)
        _confs.clear()
        out = []
        for c in found:
            _confs[c.identifier] = c
            out.append({"id": c.identifier, "name": c.name, "address": str(c.address)})
        return json.dumps(out)
    return _run(go())


def pair_begin(ident):
    async def go():
        global _pairing
        _pairing = await pyatv.pair(_confs[ident], Protocol.AirPlay, _loop)
        await _pairing.begin()
    _run(go())


def pair_finish(pin):
    async def go():
        _pairing.pin(int(pin))
        await _pairing.finish()
        creds = _pairing.service.credentials if _pairing.has_paired else ""
        await _pairing.close()
        return creds or ""
    return _run(go())


def connect(ident, creds):
    async def go():
        global _atv, _addr
        if _atv is not None:
            _atv.close()
        conf = _confs[ident]
        if creds:
            conf.set_credentials(Protocol.AirPlay, creds)
        _atv = await pyatv.connect(conf, _loop)
        _addr = str(conf.address)
    _run(go())


def _local_ip():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect((_addr, 7000))
        return s.getsockname()[0]
    finally:
        s.close()


async def _serve(directory):
    global _runner
    if _runner is not None:
        return
    app = web.Application()
    app.router.add_static("/media/", directory)  # Range desteği dahil
    _runner = web.AppRunner(app)
    await _runner.setup()
    await web.TCPSite(_runner, "0.0.0.0", PORT).start()


def play_url(url):
    fut = asyncio.run_coroutine_threadsafe(_atv.stream.play_url(url), _loop)
    fut.add_done_callback(lambda f: print("play_url bitti:", f.exception()))


def play_file(name, directory):
    _run(_serve(directory))
    play_url(f"http://{_local_ip()}:{PORT}/media/{quote(name)}")


def control(action):
    async def go():
        rc = _atv.remote_control
        await {"play": rc.play, "pause": rc.pause, "stop": rc.stop}[action]()
    _run(go())
