import path from 'node:path';
import fs from 'node:fs';
import os from 'node:os';
import { Buffer } from 'node:buffer';
import { EventEmitter } from 'node:events';
import { setTimeout as delay } from 'node:timers';
import { execSync } from 'node:child_process';

export const name = 'm84p-node-matrix';

// M84-P 验收：插件里 `import` Node 内置模块，逐个跑真实断言，结果作为工具结果回给模型/聊天。
// 覆盖宿主声明支持的兼容面：path / fs(含 promises) / os / buffer(Buffer) / events / timers /
// child_process / 全局 fetch。失败项也照实返回（不静默吞）。
export function apply(ctx) {
  ctx.tools.register({
    name: 'm84p_node_matrix',
    description: 'M84-P 验收：跑一遍 Node 内置模块矩阵（path/fs/os/Buffer/events/timers/child_process/http）',
    parameters: { type: 'object', properties: {} },
    execute: async function () {
      const results = [];

      function check(name, fn) {
        try {
          const detail = fn();
          results.push({ name: name, ok: true, detail: String(detail) });
        } catch (e) {
          results.push({ name: name, ok: false, detail: String((e && e.message) || e) });
        }
      }

      async function checkAsync(name, fn) {
        try {
          const detail = await fn();
          results.push({ name: name, ok: true, detail: String(detail) });
        } catch (e) {
          results.push({ name: name, ok: false, detail: String((e && e.message) || e) });
        }
      }

      function expect(actual, wanted, label) {
        if (actual !== wanted) { throw new Error(label + ': got=' + JSON.stringify(actual) + ' want=' + JSON.stringify(wanted)); }
        return actual;
      }

      // ---- path ----
      check('path.join', function () { return expect(path.join('a', 'b', 'c.txt'), 'a/b/c.txt', 'join'); });
      check('path.basename', function () { return expect(path.basename('/x/y/z.md'), 'z.md', 'basename'); });
      check('path.dirname', function () { return expect(path.dirname('/x/y/z.md'), '/x/y', 'dirname'); });
      check('path.sep', function () { return expect(path.sep, '/', 'sep'); });

      // ---- fs（同步 + promises）----
      const file = 'm84p-node-matrix.txt';
      check('fs.writeFileSync/readFileSync', function () {
        fs.writeFileSync(file, 'matrix-矩阵-123');
        return expect(fs.readFileSync(file), 'matrix-矩阵-123', 'readFileSync');
      });
      check('fs.existsSync', function () { return expect(fs.existsSync(file), true, 'existsSync'); });
      check('fs.statSync', function () {
        const st = fs.statSync(file);
        if (!st.isFile()) { throw new Error('statSync.isFile=false'); }
        return 'size=' + st.size;
      });
      check('fs.readdirSync', function () {
        const names = fs.readdirSync('.');
        if (names.indexOf(file) < 0) { throw new Error('readdir 里没有 ' + file); }
        return 'entries=' + names.length;
      });
      await checkAsync('fs.promises', async function () {
        await fs.promises.writeFile('m84p-node-async.txt', 'async-ok');
        const text = await fs.promises.readFile('m84p-node-async.txt', 'utf8');
        fs.unlinkSync('m84p-node-async.txt');
        return expect(text, 'async-ok', 'promises.readFile');
      });

      // ---- os ----
      check('os.platform', function () { return os.platform(); });
      check('os.homedir', function () { return os.homedir(); });
      check('os.EOL', function () { return JSON.stringify(os.EOL); });

      // ---- Buffer（node:buffer + 全局）----
      check('Buffer.from utf8→hex', function () {
        return expect(Buffer.from('鲸歌').toString('hex'), 'e9b2b8e6ad8c', 'utf8→hex');
      });
      check('Buffer.from hex→utf8', function () {
        return expect(Buffer.from('e9b2b8e6ad8c', 'hex').toString('utf8'), '鲸歌', 'hex→utf8');
      });
      check('Buffer base64 往返', function () {
        const b64 = Buffer.from('鲸歌').toString('base64');
        const back = Buffer.from(b64, 'base64').toString('utf8');
        return expect(back, '鲸歌', 'base64 往返 b64=' + b64);
      });
      check('Buffer.byteLength', function () { return expect(Buffer.byteLength('鲸歌'), 6, 'byteLength'); });
      check('Buffer.alloc/concat/equals', function () {
        const a = Buffer.alloc(3, 65);
        const b = Buffer.from('AA', 'utf8');
        const joined = Buffer.concat([a, b]);
        if (!Buffer.isBuffer(joined)) { throw new Error('concat 不是 Buffer'); }
        if (!Buffer.from(a).equals(Buffer.alloc(3, 65))) { throw new Error('equals=false'); }
        return 'a=' + a.toString() + ' joined=' + joined.toString();
      });
      check('全局 Buffer', function () {
        if (typeof Buffer !== 'function') { throw new Error('globalThis.Buffer 不是函数'); }
        return Buffer.from('x').toString('hex');
      });

      // ---- events ----
      check('EventEmitter', function () {
        const emitter = new EventEmitter();
        const seen = [];
        function onPing(value) { seen.push('on:' + value); }
        emitter.on('ping', onPing);
        emitter.once('ping', function (value) { seen.push('once:' + value); });
        const first = emitter.emit('ping', 1);
        const second = emitter.emit('ping', 2);
        const count = emitter.listenerCount('ping');
        emitter.off('ping', onPing);
        const third = emitter.emit('ping', 3);
        if (!first || !second || third) { throw new Error('emit 返回值不对 ' + [first, second, third].join(',')); }
        return expect(seen.join('|') + ' count=' + count, 'on:1|once:1|on:2 count=1', 'emit 顺序');
      });

      // ---- timers ----
      await checkAsync('node:timers setTimeout', async function () {
        const start = Date.now();
        await new Promise(function (resolve) { delay(resolve, 30); });
        const elapsed = Date.now() - start;
        if (elapsed < 20) { throw new Error('等待太短 ' + elapsed + 'ms'); }
        return 'elapsed=' + elapsed + 'ms';
      });

      // ---- child_process ----
      check('child_process.execSync', function () {
        return expect(execSync('echo m84p-node').trim(), 'm84p-node', 'execSync');
      });

      // ---- http ----
      await checkAsync('fetch https://example.com', async function () {
        const response = await fetch('https://example.com');
        if (!response || response.status !== 200) { throw new Error('status=' + (response && response.status)); }
        return 'status=' + response.status + ' bytes=' + String(response.body || '').length;
      });

      // 清理同步测试文件
      try { fs.unlinkSync(file); } catch (e) {}

      const failed = results.filter(function (item) { return !item.ok; });
      const summary = 'M84P_NODE_MATRIX ' + (results.length - failed.length) + '/' + results.length +
        (failed.length ? ' FAILED=' + failed.map(function (item) { return item.name; }).join(',') : '');
      return { text: summary + '\n' + results.map(function (item) {
        return (item.ok ? 'PASS ' : 'FAIL ') + item.name + ' :: ' + item.detail;
      }).join('\n') };
    }
  });
}
