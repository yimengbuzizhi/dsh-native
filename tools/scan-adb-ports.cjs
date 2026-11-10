// 无线调试端口掉了以后，扫一下手机上的 adb 监听端口（adbd 会接受 TCP 连接）。
const net = require('node:net');

const host = process.argv[2] || '192.0.2.10';
const from = Number(process.argv[3] || 30000);
const to = Number(process.argv[4] || 48000);
const timeout = Number(process.argv[5] || 220);
const concurrency = Number(process.argv[6] || 180);

const ports = [];
for (let p = from; p <= to; p++) ports.push(p);

const open = [];
let index = 0;
let active = 0;
let done = 0;

function startOne() {
  const port = ports[index++];
  active++;
  let settled = false;
  const socket = net.connect({ host, port });
  const finish = (ok) => {
    if (settled) return;
    settled = true;
    socket.destroy();
    if (ok) open.push(port);
    active--;
    done++;
    if (done === ports.length) {
      console.log(JSON.stringify({ host, scanned: done, open }));
      process.exit(0);
    }
    pump();
  };
  socket.setTimeout(timeout);
  socket.once('connect', () => finish(true));
  socket.once('timeout', () => finish(false));
  socket.once('error', () => finish(false));
}

function pump() {
  while (active < concurrency && index < ports.length) startOne();
}

pump();
