package xin.dshaa.core.plugins

/**
 * Operit ToolPkg 的 `Tools.*` 兼容层（M67）。
 *
 * 为什么是 JS 而不是 Kotlin：Operit 包直接 `await Tools.Files.read(...)`，这些调用语义
 * （返回什么字段、失败是抛错还是返回 `successful:false`）都来自 Operit 的 JS 工具层；
 * 在 JS 里一一对照实现，比在 Kotlin 里再包一层更接近原版，也更容易随包修正。
 *
 * 能力来源分三类：
 * - 文件 / shell / 网络：走 [PluginSandbox] 已有的宿主通道（fs.* / shell.exec / net.*）；
 * - 设备（应用列表、启动应用、设置读写、无障碍手势、截图）/ 记忆库（M68）：走 [PluginHostBridge]
 *   （app 模块实现，分别需要 `device` / `memory` 授权）；
 * - 明确不支持的组（Chat / Workflow / SoftwareSettings / FFmpeg / 音乐 / 浏览器自动化 /
 *   蓝牙 / 定位 / 通知）：抛带中文说明的错误，**不做空壳**，包作者和用户都能看到真相。
 *
 * 返回形状对齐 Operit `core/tools/ToolResultDataClasses.kt`：
 * `FileOperationData{operation,env,path,successful,details}`、`FileExistsData{path,exists,isDirectory,size}`、
 * `DirectoryListingData{path,entries[]}`、`AppListData{includesSystemApps,packages}`、
 * `AppOperationData{operationType,packageName,success,details}`、`DeviceInfoResultData{...}`、
 * `SystemSettingData{namespace,setting,value}`、`VisitWebResultData{url,title,content,links,imageLinks}`。
 */
internal val OPERIT_TOOLS_JS = """
/** Operit `Tools.*` 兼容层（M67）。生成的脚本把它塞给包体当全局 `Tools`。 */
function __operitTools() {
  function hostJson(method, payload) {
    return __dshHostJson(method, payload || {});
  }
  function hostOk(method, payload) {
    return __dshHostOk(method, payload || {});
  }
  function bridgeCall(method, args) {
    var reply = hostOk('host.call', { method: method, args: args || {} });
    return reply;
  }
  function envName(environment) {
    return environment === 'linux' ? 'linux' : 'android';
  }
  function fileOp(operation, path, successful, details, environment) {
    return {
      operation: operation,
      env: envName(environment),
      path: (path === undefined || path === null) ? '' : String(path),
      successful: !!successful,
      details: (details === undefined || details === null) ? '' : String(details)
    };
  }
  function failedOp(operation, path, reply, environment) {
    var message = (reply && reply.error) ? String(reply.error) : (operation + ' 失败');
    return fileOp(operation, path, false, message, environment);
  }
  /** 只替换第一处；找不到返回 null（Operit apply 的 replace 语义）。 */
  function replaceOnce(text, oldText, newText) {
    if (!oldText) { return text; }
    var index = text.indexOf(oldText);
    if (index < 0) { return null; }
    return text.substring(0, index) + newText + text.substring(index + oldText.length);
  }
  function unquote(value) {
    var text = String(value === undefined || value === null ? '' : value).trim();
    if (text.length >= 2) {
      var first = text.charAt(0);
      if (first === '"' || first === "'") { text = text.substring(1); }
      var last = text.charAt(text.length - 1);
      if (last === '"' || last === "'") { text = text.substring(0, text.length - 1); }
    }
    return text;
  }
  function fetchRaw(url, options) {
    var opts = options || {};
    var payload = {
      url: String(url),
      method: String(opts.method || 'GET').toUpperCase(),
      headers: opts.headers || {}
    };
    if (opts.body !== undefined && opts.body !== null) { payload.body = String(opts.body); }
    if (opts.binary) { payload.binary = true; }
    return __dshCallAsync('net.fetch', JSON.stringify(payload)).then(
      function (raw) { return __dshParseHost(raw); },
      function (err) { throw new Error((err && err.message) ? err.message : String(err)); }
    );
  }
  function decodeEntities(text) {
    var value = String(text === undefined || text === null ? '' : text);
    value = value.replace(/&nbsp;/g, ' ').replace(/&amp;/g, '&').replace(/&lt;/g, '<')
      .replace(/&gt;/g, '>').replace(/&quot;/g, '"').replace(/&#39;/g, "'")
      .replace(/&#x27;/g, "'").replace(/&#x2F;/g, '/');
    value = value.replace(/&#([0-9]+);/g, function (_match, code) {
      return String.fromCharCode(parseInt(code, 10));
    });
    value = value.replace(/&#x([0-9a-fA-F]+);/g, function (_match, code) {
      return String.fromCharCode(parseInt(code, 16));
    });
    return value;
  }
  function stripTags(html) {
    return decodeEntities(String(html || '').replace(/<[^>]*>/g, ' ')).replace(/\s+/g, ' ').trim();
  }
  function htmlToText(html) {
    var text = String(html || '');
    text = text.replace(/<script[\s\S]*?<\/script>/gi, ' ');
    text = text.replace(/<style[\s\S]*?<\/style>/gi, ' ');
    text = text.replace(/<noscript[\s\S]*?<\/noscript>/gi, ' ');
    text = text.replace(/<template[\s\S]*?<\/template>/gi, ' ');
    text = text.replace(/<!--[\s\S]*?-->/g, ' ');
    text = text.replace(/<(br|hr)\s*\/?>/gi, '\n');
    text = text.replace(/<\/(p|div|li|ul|ol|h[1-6]|tr|table|section|article|header|footer|blockquote|pre)>/gi, '\n');
    text = text.replace(/<[^>]+>/g, ' ');
    text = decodeEntities(text);
    text = text.replace(/[ \t\f\v]+/g, ' ');
    text = text.replace(/\n[ ]*\n+/g, '\n\n');
    return text.trim();
  }
  /** 只做相对 URL 解析，不用 QuickJS 的 URL（不同版本不一定带）。 */
  function resolveUrl(href, base) {
    var raw = String(href === undefined || href === null ? '' : href).trim();
    if (!raw) { return ''; }
    if (/^[a-zA-Z][a-zA-Z0-9+.-]*:/.test(raw)) {
      return (raw.indexOf('http://') === 0 || raw.indexOf('https://') === 0) ? raw : '';
    }
    var match = String(base || '').match(/^([a-zA-Z][a-zA-Z0-9+.-]*:\/\/[^\/?#]+)([^?#]*)/);
    if (!match) { return ''; }
    var origin = match[1];
    if (raw.indexOf('//') === 0) { return 'https:' + raw; }
    if (raw.charAt(0) === '/') { return origin + raw; }
    var path = match[2] || '/';
    var dir = path.substring(0, path.lastIndexOf('/') + 1);
    var parts = (dir + raw).split('/');
    var out = [];
    for (var i = 0; i < parts.length; i += 1) {
      var part = parts[i];
      if (part === '..') {
        if (out.length > 3) { out.pop(); }
      } else if (part !== '.' && part !== '') {
        out.push(part);
      }
    }
    return out.join('/');
  }

  // ---------------- Tools.Files ----------------
  var Files = {
    exists: async function (path, environment) {
      var target = String(path === undefined || path === null ? '' : path);
      var reply = hostJson('fs.info', { path: target });
      if (!reply || reply.ok !== true) {
        return { path: target, exists: false, isDirectory: false, size: 0, env: envName(environment) };
      }
      return {
        path: target,
        exists: !!reply.exists,
        isDirectory: !!reply.isDirectory,
        size: reply.size || 0,
        env: envName(environment)
      };
    },
    read: async function (pathOrOptions) {
      var options = (typeof pathOrOptions === 'string') ? { path: pathOrOptions } : (pathOrOptions || {});
      var target = String(options.path === undefined || options.path === null ? '' : options.path);
      var reply = hostOk('fs.read', { path: target });
      return {
        path: target,
        content: (reply.text === undefined || reply.text === null) ? '' : String(reply.text),
        size: reply.size || 0,
        env: envName(options.environment)
      };
    },
    readPart: async function (path, startLine, endLine, environment) {
      var full = await Files.read(path, environment);
      var lines = String(full.content || '').split('\n');
      var start = (startLine === undefined || startLine === null) ? 1 : parseInt(startLine, 10);
      var end = (endLine === undefined || endLine === null) ? lines.length : parseInt(endLine, 10);
      if (!(start >= 1)) { start = 1; }
      if (!(end >= start)) { end = lines.length; }
      if (end > lines.length) { end = lines.length; }
      var content = lines.slice(start - 1, end).join('\n');
      return { path: full.path, content: content, size: content.length, env: full.env };
    },
    readBinary: async function (path, environment) {
      var target = String(path === undefined || path === null ? '' : path);
      var reply = hostOk('fs.readBinary', { path: target });
      return {
        path: target,
        contentBase64: reply.base64 || '',
        size: reply.size || 0,
        env: envName(environment)
      };
    },
    write: async function (path, content, append, environment) {
      var target = String(path === undefined || path === null ? '' : path);
      var text = (content === undefined || content === null) ? '' : String(content);
      var reply = hostJson('fs.write', { path: target, text: text, append: !!append });
      if (!reply || reply.ok !== true) { return failedOp('write', target, reply, environment); }
      return fileOp('write', target, true, '已写入 ' + text.length + ' 字符：' + target, environment);
    },
    writeBinary: async function (path, base64Content, environment) {
      var target = String(path === undefined || path === null ? '' : path);
      var reply = hostJson('fs.writeBinary', { path: target, base64: String(base64Content || '') });
      if (!reply || reply.ok !== true) { return failedOp('write_binary', target, reply, environment); }
      return fileOp('write_binary', target, true, '已写入二进制文件：' + target, environment);
    },
    deleteFile: async function (path, recursive, environment) {
      var target = String(path === undefined || path === null ? '' : path);
      var reply = hostJson('fs.remove', { path: target, recursive: !!recursive, force: true });
      if (!reply || reply.ok !== true) { return failedOp('delete', target, reply, environment); }
      return fileOp('delete', target, true, '已删除：' + target, environment);
    },
    mkdir: async function (path, createParents, environment) {
      var target = String(path === undefined || path === null ? '' : path);
      var recursive = (createParents === undefined || createParents === null) ? true : !!createParents;
      var reply = hostJson('fs.mkdir', { path: target, recursive: recursive });
      if (!reply || reply.ok !== true) { return failedOp('mkdir', target, reply, environment); }
      var details = reply.created ? ('已创建目录：' + target) : ('目录已存在：' + target);
      return fileOp('mkdir', target, true, details, environment);
    },
    move: async function (source, destination, environment) {
      var from = String(source === undefined || source === null ? '' : source);
      var to = String(destination === undefined || destination === null ? '' : destination);
      var reply = hostJson('fs.rename', { from: from, to: to });
      if (!reply || reply.ok !== true) { return failedOp('move', from, reply, environment); }
      return fileOp('move', from, true, '已移动 ' + from + ' → ' + to, environment);
    },
    copy: async function (source, destination, recursive, sourceEnvironment, destEnvironment) {
      var from = String(source === undefined || source === null ? '' : source);
      var to = String(destination === undefined || destination === null ? '' : destination);
      var reply = hostJson('fs.copy', { from: from, to: to, recursive: !!recursive });
      if (!reply || reply.ok !== true) { return failedOp('copy', from, reply, destEnvironment); }
      return fileOp('copy', from, true, '已复制 ' + from + ' → ' + to, destEnvironment);
    },
    list: async function (path, environment) {
      var target = String(path === undefined || path === null ? '' : path);
      var reply = hostOk('fs.dir', { path: target });
      return { path: target, entries: reply.entries || [], env: envName(environment) };
    },
    info: async function (path, environment) {
      var target = String(path === undefined || path === null ? '' : path);
      var reply = hostOk('fs.info', { path: target });
      return {
        path: target,
        exists: !!reply.exists,
        fileType: reply.fileType || 'none',
        size: reply.size || 0,
        permissions: reply.permissions || '',
        owner: 'app',
        group: 'app',
        lastModified: reply.mtimeMs ? new Date(reply.mtimeMs).toISOString() : '',
        rawStatOutput: '',
        env: envName(environment)
      };
    },
    zip: async function (source, destination, environment, includeRootDirectory) {
      var from = String(source === undefined || source === null ? '' : source);
      var to = String(destination === undefined || destination === null ? '' : destination);
      var reply = hostJson('fs.zip', {
        source: from,
        destination: to,
        includeRoot: (includeRootDirectory === undefined || includeRootDirectory === null) ? true : !!includeRootDirectory
      });
      if (!reply || reply.ok !== true) { return failedOp('zip', from, reply, environment); }
      return fileOp('zip', to, true, '已压缩 ' + reply.entries + ' 个条目 → ' + to, environment);
    },
    unzip: async function (source, destination, environment) {
      var from = String(source === undefined || source === null ? '' : source);
      var to = String(destination === undefined || destination === null ? '' : destination);
      var reply = hostJson('fs.unzip', { source: from, destination: to });
      if (!reply || reply.ok !== true) { return failedOp('unzip', from, reply, environment); }
      return fileOp('unzip', to, true, '已解压 ' + reply.entries + ' 个条目 → ' + to, environment);
    },
    apply: async function (path, type, oldContent, newContent, environment) {
      var target = String(path === undefined || path === null ? '' : path);
      var mode = String(type || 'replace');
      try {
        var current = await Files.read(target, environment);
        var text = String(current.content || '');
        var next = null;
        if (mode === 'replace' || mode === 'edit') {
          next = replaceOnce(text, oldContent, (newContent === undefined || newContent === null) ? '' : String(newContent));
          if (next === null) {
            return { operation: fileOp('apply', target, false, '要替换的旧内容没找到：' + target, environment), aiDiffInstructions: '', diffContent: null };
          }
        } else if (mode === 'delete') {
          next = replaceOnce(text, oldContent, '');
          if (next === null) {
            return { operation: fileOp('apply', target, false, '要删除的片段没找到：' + target, environment), aiDiffInstructions: '', diffContent: null };
          }
        } else if (mode === 'create') {
          next = (newContent === undefined || newContent === null) ? '' : String(newContent);
        } else {
          return { operation: fileOp('apply', target, false, '不支持的 apply 类型：' + mode, environment), aiDiffInstructions: '', diffContent: null };
        }
        var written = await Files.write(target, next, false, environment);
        if (!written.successful) {
          return { operation: written, aiDiffInstructions: '', diffContent: null };
        }
        return { operation: fileOp('apply', target, true, '已应用 ' + mode + '：' + target, environment), aiDiffInstructions: 'Changes applied', diffContent: null };
      } catch (error) {
        return { operation: fileOp('apply', target, false, String((error && error.message) || error), environment), aiDiffInstructions: '', diffContent: null };
      }
    },
    download: async function (url, destination, options) {
      var target = String(destination === undefined || destination === null ? '' : destination);
      var opts = options || {};
      try {
        var response = await fetchRaw(url, { binary: true });
        var status = response.status || 0;
        if (status < 200 || status >= 300) {
          return fileOp('download', target, false, '下载失败 HTTP ' + status + '：' + url, opts.environment);
        }
        var saved = hostJson('fs.writeBinary', { path: target, base64: response.base64 || '' });
        if (!saved || saved.ok !== true) { return failedOp('download', target, saved, opts.environment); }
        return fileOp('download', target, true, '已下载 ' + (saved.size || 0) + ' 字节：' + url + ' → ' + target, opts.environment);
      } catch (error) {
        return fileOp('download', target, false, String((error && error.message) || error), opts.environment);
      }
    },
    open: async function (path, environment) {
      var target = String(path === undefined || path === null ? '' : path);
      try {
        var reply = bridgeCall('file.open', { path: target });
        return fileOp('open', target, reply.opened !== false, reply.details || ('已请求打开：' + target), environment);
      } catch (error) {
        return fileOp('open', target, false, String((error && error.message) || error), environment);
      }
    },
    share: async function (path, title, environment) {
      var target = String(path === undefined || path === null ? '' : path);
      try {
        var reply = bridgeCall('file.share', { path: target, title: title === undefined || title === null ? '' : String(title) });
        return fileOp('share', target, reply.shared !== false, reply.details || ('已请求分享：' + target), environment);
      } catch (error) {
        return fileOp('share', target, false, String((error && error.message) || error), environment);
      }
    }
  };

  // ---------------- Tools.System ----------------
  // 终端（M68）：真正的 PTY 会话，走 app 侧 TerminalManager（跨工具调用有状态）。以前是
  // `shell.exec` 模拟、只在单次调用内有效，`cd`/环境变量/交互式程序都不能延续。
  function terminalLines(text) {
    var map = {};
    String(text === undefined || text === null ? '' : text).split('\n').forEach(function (line) {
      var index = line.indexOf(':');
      if (index > 0) {
        var key = line.substring(0, index).trim();
        if (key && map[key] === undefined) { map[key] = line.substring(index + 1).trim(); }
      }
    });
    return map;
  }
  function terminalOutput(text) {
    var raw = String(text === undefined || text === null ? '' : text);
    var marker = raw.indexOf('output:');
    if (marker < 0) { return raw.trim(); }
    return raw.substring(marker + 'output:'.length).replace(/^\n+/, '').trimEnd();
  }
  var shellResult = function (command, timeoutMs) {
    var payload = { command: String(command) };
    if (timeoutMs !== undefined && timeoutMs !== null) { payload.timeoutMs = parseInt(timeoutMs, 10); }
    var reply = hostJson('shell.exec', payload);
    if (!reply || reply.ok !== true) {
      return {
        success: false,
        command: String(command),
        stdout: '',
        stderr: (reply && reply.error) ? String(reply.error) : 'shell 不可用',
        output: (reply && reply.error) ? String(reply.error) : 'shell 不可用',
        exitCode: -1
      };
    }
    var stdout = reply.stdout === undefined || reply.stdout === null ? '' : String(reply.stdout);
    var stderr = reply.stderr === undefined || reply.stderr === null ? '' : String(reply.stderr);
    return {
      success: reply.status === 0,
      command: String(command),
      stdout: stdout,
      stderr: stderr,
      output: (stdout + stderr).trim(),
      exitCode: reply.status,
      timedOut: !!reply.timedOut
    };
  };
  var SystemTools = {
    sleep: async function (milliseconds) {
      var ms = parseInt(milliseconds, 10);
      if (!(ms > 0)) { return { durationMs: 0 }; }
      await __dshCallAsync('timer.wait', JSON.stringify({ ms: ms }));
      return { durationMs: ms };
    },
    shell: async function (command) {
      return shellResult(command);
    },
    terminal: {
      create: async function (sessionName) {
        var reply = bridgeCall('terminal.create', {
          session_name: String(sessionName || ('terminal-' + Date.now()))
        });
        var lines = terminalLines(reply.text);
        var sessionId = (reply.meta && reply.meta.session_id) ? String(reply.meta.session_id) : (lines.session_id || '');
        return {
          sessionId: sessionId,
          sessionName: lines.session_name || String(sessionName || ''),
          state: lines.state || 'READY',
          isNew: lines.is_new_session === 'true',
          raw: reply.text
        };
      },
      exec: async function (sessionId, command, timeoutMs) {
        var payload = {
          session_id: String(sessionId || ''),
          command: String(command === undefined || command === null ? '' : command)
        };
        if (timeoutMs !== undefined && timeoutMs !== null) { payload.timeout_ms = parseInt(timeoutMs, 10); }
        var reply = bridgeCall('terminal.exec', payload);
        var lines = terminalLines(reply.text);
        var output = terminalOutput(reply.text);
        return {
          success: lines.timed_out !== 'true',
          sessionId: String(sessionId || ''),
          command: String(command === undefined || command === null ? '' : command),
          stdout: output,
          stderr: '',
          output: output,
          exitCode: 0,
          timedOut: lines.timed_out === 'true',
          elapsedMs: lines.elapsed_ms ? parseInt(lines.elapsed_ms, 10) : undefined,
          note: lines.note || ''
        };
      },
      input: async function (sessionId, options) {
        var opts = options || {};
        var payload = { session_id: String(sessionId || '') };
        if (opts.input !== undefined && opts.input !== null) { payload.input = String(opts.input); }
        if (opts.control !== undefined && opts.control !== null) { payload.control = String(opts.control); }
        var reply = bridgeCall('terminal.input', payload);
        return { sessionId: String(sessionId || ''), ok: true, message: reply.text };
      },
      screen: async function (sessionId) {
        var reply = bridgeCall('terminal.screen', { session_id: String(sessionId || '') });
        var lines = terminalLines(reply.text);
        var marker = String(reply.text || '').indexOf('content:');
        var content = marker < 0 ? String(reply.text || '') : String(reply.text).substring(marker + 'content:'.length).replace(/^\n+/, '').trimEnd();
        return {
          sessionId: String(sessionId || ''),
          state: lines.state || '',
          rows: lines.rows ? parseInt(lines.rows, 10) : undefined,
          cols: lines.cols ? parseInt(lines.cols, 10) : undefined,
          content: content
        };
      },
      close: async function (sessionId) {
        var reply = bridgeCall('terminal.close', { session_id: String(sessionId || '') });
        return { sessionId: String(sessionId || ''), closed: true, message: reply.text };
      },
      hiddenExec: async function (command, options) {
        var opts = options || {};
        return shellResult(command, opts.timeoutMs);
      }
    },
    getDeviceInfo: async function () {
      return bridgeCall('device.info', {});
    },
    listApps: async function (includeSystem) {
      return bridgeCall('device.apps', { includeSystem: !!includeSystem });
    },
    startApp: async function (packageName, activity) {
      var args = { packageName: String(packageName || '') };
      if (activity) { args.activity = String(activity); }
      return bridgeCall('device.startApp', args);
    },
    stopApp: async function (packageName) {
      return bridgeCall('device.stopApp', { packageName: String(packageName || '') });
    },
    getSetting: async function (setting, namespace) {
      return bridgeCall('device.getSetting', { setting: String(setting || ''), namespace: String(namespace || 'system') });
    },
    setSetting: async function (setting, value, namespace) {
      return bridgeCall('device.setSetting', { setting: String(setting || ''), value: String(value === undefined || value === null ? '' : value), namespace: String(namespace || 'system') });
    },
    toast: async function (message) {
      return bridgeCall('device.toast', { message: String(message === undefined || message === null ? '' : message) });
    },
    usePackage: async function () {
      throw new Error('Tools.System.usePackage（Operit 子包机制）鲸歌暂不支持');
    },
    installApp: async function () {
      throw new Error('Tools.System.installApp（静默安装）鲸歌暂不支持');
    },
    uninstallApp: async function () {
      throw new Error('Tools.System.uninstallApp（静默卸载）鲸歌暂不支持');
    },
    sendBroadcast: async function (options) {
      var opts = options || {};
      var args = { action: String(opts.action || '') };
      if (opts.uri) { args.uri = String(opts.uri); }
      if (opts.package) { args.package = String(opts.package); }
      if (opts.component) { args.component = String(opts.component); }
      return bridgeCall('device.sendBroadcast', args);
    },
    intent: async function () {
      throw new Error('Tools.System.intent（任意 Intent）鲸歌暂不支持');
    },
    getNotifications: async function () {
      throw new Error('Tools.System.getNotifications（通知读取）鲸歌暂不支持');
    },
    getAppUsageTime: async function () {
      throw new Error('Tools.System.getAppUsageTime（应用使用时长）鲸歌暂不支持');
    },
    getLocation: async function () {
      throw new Error('Tools.System.getLocation（定位）鲸歌暂不支持');
    }
  };
  SystemTools.bluetooth = unsupportedGroup('System.bluetooth', '蓝牙');

  // ---------------- Tools.Net ----------------
  /** Operit 的 visitKey 是 UUID v4；QuickJS 不一定有 crypto，这里用 Math.random 拼同样格式。 */
  function newVisitKey() {
    function hex(length) {
      var out = '';
      for (var i = 0; i < length; i += 1) {
        out += Math.floor(Math.random() * 16).toString(16);
      }
      return out;
    }
    return hex(8) + '-' + hex(4) + '-4' + hex(3) + '-' +
      ['8', '9', 'a', 'b'][Math.floor(Math.random() * 4)] + hex(3) + '-' + hex(12);
  }
  /** `include_image_links` 的真值判断照 Operit：true/1/yes/y/on。 */
  function truthyOption(value) {
    if (value === true) { return true; }
    if (value === undefined || value === null || value === false) { return false; }
    var text = String(value).trim().toLowerCase();
    return text === '1' || text === 'true' || text === 'yes' || text === 'y' || text === 'on';
  }
  /** 抓一个 URL → Operit `VisitWebResultData` 形状；成功后把链接表存进宿主缓存并带上新 visitKey。 */
  async function visitFetch(url, options) {
    var opts = options || {};
    var headers = { 'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8' };
    if (opts.headers && typeof opts.headers === 'object') {
      Object.keys(opts.headers).forEach(function (key) {
        headers[key] = opts.headers[key];
      });
    }
    var userAgent = '';
    if (opts.user_agent !== undefined && opts.user_agent !== null && String(opts.user_agent).trim() !== '') {
      userAgent = String(opts.user_agent).trim();
    } else if (opts.user_agent_preset !== undefined && opts.user_agent_preset !== null) {
      var preset = String(opts.user_agent_preset).trim().toLowerCase();
      userAgent = (preset === 'android' || preset === 'mobile' || preset === 'mobile_android')
        ? 'Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36'
        : 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36';
    }
    if (userAgent) { headers['User-Agent'] = userAgent; }

    var response = await fetchRaw(url, { headers: headers });
    var status = response.status || 0;
    if (status < 200 || status >= 400) { throw new Error('访问失败 HTTP ' + status + '：' + url); }
    var html = (response.body === undefined || response.body === null) ? '' : String(response.body);
    var title = '';
    var titleMatch = html.match(/<title[^>]*>([\s\S]*?)<\/title>/i);
    if (titleMatch) { title = stripTags(titleMatch[1]); }

    // Operit 的 links：DOM 顺序、href 且 innerText 非空、绝对 URL、不去重。
    var links = [];
    var linkRegex = /<a\b([^>]*)>([\s\S]*?)<\/a>/gi;
    var match = linkRegex.exec(html);
    while (match !== null) {
      var hrefMatch = String(match[1] || '').match(/href\s*=\s*("[^"]*"|'[^']*'|[^\s>]+)/i);
      if (hrefMatch) {
        var target = resolveUrl(unquote(hrefMatch[1]), url);
        var text = stripTags(match[2] || '');
        if (target && text) { links.push({ url: target, text: text.substring(0, 200) }); }
      }
      match = linkRegex.exec(html);
    }

    var imageLinks = [];
    if (truthyOption(opts.include_image_links)) {
      var imageSeen = {};
      var imageRegex = /<img\b[^>]*src\s*=\s*("[^"]*"|'[^']*'|[^\s>]+)/gi;
      var imageMatch = imageRegex.exec(html);
      while (imageMatch !== null) {
        var imageUrl = resolveUrl(unquote(imageMatch[1]), url);
        if (imageUrl && !imageSeen[imageUrl] && imageUrl.indexOf('.svg') < 0) {
          imageSeen[imageUrl] = true;
          imageLinks.push(imageUrl);
        }
        imageMatch = imageRegex.exec(html);
      }
    }

    var content = htmlToText(html);
    var result = { url: url, title: title, content: content, metadata: {} };
    if (links.length > 0) { result.links = links; }
    if (imageLinks.length > 0) { result.imageLinks = imageLinks; }
    var originalLength = content.length;
    if (originalLength > 200000) {
      result.content = content.substring(0, 200000);
      result.contentTruncated = true;
      result.originalContentLength = originalLength;
    }
    // visitKey 只在这里生成；缓存的是「这次返回给包的同一份链接表」，保证 link_number 不错位。
    var visitKey = newVisitKey();
    hostJson('net.visit.put', {
      key: visitKey,
      url: url,
      title: title,
      links: links,
      imageLinks: imageLinks
    });
    result.visitKey = visitKey;
    return result;
  }
  var Net = {
    visit: async function (input) {
      var options = (typeof input === 'string') ? { url: input } : (input || {});
      var visitKey = (options.visit_key === undefined || options.visit_key === null)
        ? ''
        : String(options.visit_key);
      var linkNumberRaw = (options.link_number === undefined || options.link_number === null)
        ? ''
        : String(options.link_number).trim();
      // visit_key + link_number 同时给了才走续页；只给 key 当没给（Operit 原语义）。
      if (visitKey.trim() !== '' && linkNumberRaw !== '') {
        if (!/^[0-9]+$/.test(linkNumberRaw)) { throw new Error('Invalid link number.'); }
        var entry = hostJson('net.visit.get', { key: visitKey });
        if (!entry || entry.ok !== true) { throw new Error('Invalid visit key.'); }
        var cachedLinks = Array.isArray(entry.links) ? entry.links : [];
        var index = parseInt(linkNumberRaw, 10);
        if (!(index >= 1) || index > cachedLinks.length) {
          throw new Error('Link number out of bounds.');
        }
        return visitFetch(String(cachedLinks[index - 1].url), options);
      }
      var url = String(options.url || '');
      if (!url) { throw new Error("Either 'url' or both 'visit_key' and 'link_number' must be provided."); }
      return visitFetch(url, options);
    },
    uploadFile: async function (options) {
      var opts = options || {};
      var files = [];
      var rawFiles = opts.files || [];
      for (var i = 0; i < rawFiles.length; i += 1) {
        var file = rawFiles[i] || {};
        files.push({
          field: file.field_name || file.field || 'file',
          path: file.file_path || file.path,
          contentType: file.content_type || file.contentType,
          name: file.name
        });
      }
      var payload = {
        url: String(opts.url || ''),
        method: String(opts.method || 'POST').toUpperCase(),
        fields: opts.form_data || opts.fields || {},
        files: files,
        headers: opts.headers || {}
      };
      var reply = hostOk('net.upload', payload);
      return {
        statusCode: reply.status,
        content: (reply.body === undefined || reply.body === null) ? '' : String(reply.body),
        url: reply.url || payload.url
      };
    }
  };
  Net.browser = unsupportedGroup('Net.browser', '浏览器自动化');
  Net.cookies = unsupportedGroup('Net.cookies', 'Cookie 管理');
  Net.http = async function () {
    throw new Error('Tools.Net.http 鲸歌暂不支持；可以用 fetch/OkHttp');
  };

  // ---------------- Tools.UI ----------------
  var UI = {
    tap: async function (x, y) {
      return bridgeCall('ui.tap', { x: Number(x), y: Number(y) });
    },
    doubleTap: async function (x, y) {
      var first = await UI.tap(x, y);
      await SystemTools.sleep(120);
      var second = await UI.tap(x, y);
      return { first: first, second: second };
    },
    longPress: async function (x, y) {
      return bridgeCall('ui.longPress', { x: Number(x), y: Number(y) });
    },
    swipe: async function (startX, startY, endX, endY, duration) {
      return bridgeCall('ui.swipe', {
        startX: Number(startX),
        startY: Number(startY),
        endX: Number(endX),
        endY: Number(endY),
        durationMs: Number(duration || 300)
      });
    },
    pressKey: async function (keyCode) {
      return bridgeCall('ui.pressKey', { keyCode: String(keyCode || '') });
    },
    setText: async function (text) {
      return bridgeCall('ui.setText', { text: String(text === undefined || text === null ? '' : text) });
    },
    clickElement: async function (params) {
      return bridgeCall('ui.clickElement', {
        text: params && params.text !== undefined ? String(params.text) : '',
        index: params && params.index !== undefined ? Number(params.index) : undefined
      });
    },
    captureScreenshot: async function () {
      var reply = bridgeCall('ui.captureScreenshot', {});
      return reply.path;
    },
    /**
     * 读屏（M68）：宿主把无障碍树映射成 Operit `UIPageResultData` 的形状，
     * 这里补一个 `toString()`（Operit 的包常把 `info.toString()` 直接 complete 出去）。
     * `activityName` 鲸歌取不到（无障碍只给包名），如实留空。
     */
    getPageInfo: async function () {
      var reply = bridgeCall('ui.pageInfo', {});
      function nodeText(node) {
        var parts = ['[' + String(node.className || '') + ']'];
        var text = String(node.text === undefined || node.text === null ? '' : node.text);
        if (text) { parts.push('T:"' + (text.length > 30 ? text.substring(0, 30) : text) + '"'); }
        var desc = String(node.contentDesc === undefined || node.contentDesc === null ? '' : node.contentDesc);
        if (desc) { parts.push('D:"' + (desc.length > 30 ? desc.substring(0, 30) : desc) + '"'); }
        var id = String(node.resourceId === undefined || node.resourceId === null ? '' : node.resourceId);
        if (id) { parts.push('ID:' + id); }
        if (node.isClickable) { parts.push('⮞'); }
        parts.push(String(node.bounds === undefined || node.bounds === null ? '' : node.bounds));
        return parts.join(' ');
      }
      function treeText(nodes, depth, out) {
        var list = Array.isArray(nodes) ? nodes : [];
        for (var i = 0; i < list.length; i += 1) {
          var node = list[i] || {};
          var hasChildren = Array.isArray(node.children) && node.children.length > 0;
          var indent = '';
          for (var d = 0; d < depth; d += 1) { indent += '  '; }
          out.push(indent + (hasChildren ? '◢ ' : '▶ ') + nodeText(node));
          if (hasChildren) { treeText(node.children, depth + 1, out); }
        }
        return out;
      }
      var info = {
        packageName: String(reply.packageName === undefined || reply.packageName === null ? '' : reply.packageName),
        activityName: String(reply.activityName === undefined || reply.activityName === null ? '' : reply.activityName),
        uiElements: Array.isArray(reply.uiElements) ? reply.uiElements : [],
        nodeCount: reply.nodeCount || 0,
        truncated: !!reply.truncated
      };
      info.toString = function () {
        var out = [];
        out.push('Current Application: ' + info.packageName);
        out.push('Current Activity: ' + (info.activityName || '(unknown)'));
        out.push('');
        out.push('UI Elements:');
        treeText(info.uiElements, 0, out);
        if (info.truncated) { out.push('… (truncated at ' + info.nodeCount + ' nodes)'); }
        return out.join('\n');
      };
      return info;
    },
    runSubAgent: async function () {
      throw new Error('Tools.UI.runSubAgent（子 Agent）鲸歌暂不支持');
    }
  };

  // ---------------- Tools.Memory（M68） ----------------
  //
  // Operit 里 `Tools.Memory.*` 是 `toolCall("create_memory", …)` 的语法糖，返回值是工具输出文本
  // （包体用 `result.length > 0` 判成功）。鲸歌宿主用内置的 8 个记忆工具 + MemoryStore 实现，
  // 走 `memory.*` 宿主方法（需要 `memory` 权限）。参数名按 Operit 的 camelCase 转成工具用的
  // snake_case；失败会抛错，由包的 wrapToolExecution 变成 `{success:false,message}`。
  function arrayToCsv(value) {
    if (value === undefined || value === null) { return undefined; }
    if (Object.prototype.toString.call(value) === '[object Array]') { return value.join(','); }
    return String(value);
  }
  function numberOrUndefined(value) {
    if (value === undefined || value === null || value === '') { return undefined; }
    var parsed = Number(value);
    return isNaN(parsed) ? undefined : parsed;
  }
  function stringOrUndefined(value) {
    if (value === undefined || value === null) { return undefined; }
    return String(value);
  }
  function memoryCall(method, payload) {
    var args = {};
    Object.keys(payload || {}).forEach(function (key) {
      if (payload[key] !== undefined) { args[key] = payload[key]; }
    });
    return bridgeCall('memory.' + method, args);
  }
  function memoryText(method, payload) {
    var reply = memoryCall(method, payload);
    if (!reply || reply.text === undefined || reply.text === null) { return ''; }
    return String(reply.text);
  }
  var Memory = {
    _normalizeCallerCardId: function (value) {
      var text = String(value === undefined || value === null ? '' : value).trim();
      return text ? text : undefined;
    },
    create: async function (input) {
      var opts = (input && typeof input === 'object') ? input : {};
      return memoryText('create', {
        title: stringOrUndefined(opts.title),
        content: stringOrUndefined(opts.content),
        content_type: stringOrUndefined(opts.contentType),
        source: stringOrUndefined(opts.source),
        folder_path: stringOrUndefined(opts.folderPath),
        tags: arrayToCsv(opts.tags)
      });
    },
    update: async function (input) {
      var opts = (input && typeof input === 'object') ? input : {};
      return memoryText('update', {
        old_title: stringOrUndefined(opts.oldTitle),
        new_title: stringOrUndefined(opts.newTitle),
        content: stringOrUndefined(opts.content),
        content_type: stringOrUndefined(opts.contentType),
        source: stringOrUndefined(opts.source),
        credibility: numberOrUndefined(opts.credibility),
        importance: numberOrUndefined(opts.importance),
        folder_path: opts.folderPath === undefined || opts.folderPath === null ? undefined : String(opts.folderPath),
        tags: arrayToCsv(opts.tags)
      });
    },
    deleteMemory: async function (input) {
      var opts = (input && typeof input === 'object') ? input : {};
      return memoryText('deleteMemory', { title: stringOrUndefined(opts.title) });
    },
    move: async function (input) {
      var opts = (input && typeof input === 'object') ? input : {};
      return memoryText('move', {
        target_folder_path: opts.targetFolderPath === undefined || opts.targetFolderPath === null
          ? undefined
          : String(opts.targetFolderPath),
        titles: arrayToCsv(opts.titles),
        source_folder_path: opts.sourceFolderPath === undefined || opts.sourceFolderPath === null
          ? undefined
          : String(opts.sourceFolderPath)
      });
    },
    link: async function (input) {
      var opts = (input && typeof input === 'object') ? input : {};
      return memoryText('link', {
        source_title: stringOrUndefined(opts.sourceTitle),
        target_title: stringOrUndefined(opts.targetTitle),
        link_type: stringOrUndefined(opts.linkType),
        weight: numberOrUndefined(opts.weight),
        description: stringOrUndefined(opts.description)
      });
    },
    queryLinks: async function (input) {
      var opts = (input && typeof input === 'object') ? input : {};
      return memoryText('queryLinks', {
        link_id: numberOrUndefined(opts.linkId),
        source_title: stringOrUndefined(opts.sourceTitle),
        target_title: stringOrUndefined(opts.targetTitle),
        link_type: stringOrUndefined(opts.linkType),
        limit: numberOrUndefined(opts.limit)
      });
    },
    updateLink: async function (input) {
      var opts = (input && typeof input === 'object') ? input : {};
      return memoryText('updateLink', {
        link_id: numberOrUndefined(opts.linkId),
        source_title: stringOrUndefined(opts.sourceTitle),
        target_title: stringOrUndefined(opts.targetTitle),
        link_type: stringOrUndefined(opts.linkType),
        new_link_type: stringOrUndefined(opts.newLinkType),
        weight: numberOrUndefined(opts.weight),
        description: stringOrUndefined(opts.description)
      });
    },
    deleteLink: async function (input) {
      var opts = (input && typeof input === 'object') ? input : {};
      return memoryText('deleteLink', {
        link_id: numberOrUndefined(opts.linkId),
        source_title: stringOrUndefined(opts.sourceTitle),
        target_title: stringOrUndefined(opts.targetTitle),
        link_type: stringOrUndefined(opts.linkType)
      });
    },
    query: async function (input) {
      var opts = (input && typeof input === 'object') ? input : {};
      return memoryText('query', {
        query: stringOrUndefined(opts.query),
        folder_path: stringOrUndefined(opts.folderPath),
        limit: numberOrUndefined(opts.limit),
        threshold: numberOrUndefined(opts.threshold),
        snapshot_id: stringOrUndefined(opts.snapshotId)
      });
    },
    getByTitle: async function (input) {
      var opts = (input && typeof input === 'object') ? input : {};
      return memoryText('getByTitle', {
        title: stringOrUndefined(opts.title),
        chunk_index: numberOrUndefined(opts.chunkIndex),
        chunk_range: stringOrUndefined(opts.chunkRange),
        query: stringOrUndefined(opts.query),
        limit: numberOrUndefined(opts.limit)
      });
    }
  };

  // ---------------- 明确不支持的组 ----------------
  function unsupportedGroup(name, detail) {
    return new Proxy({}, {
      get: function (_target, key) {
        throw new Error('Tools.' + name + '.' + String(key) + '：鲸歌暂不支持' + (detail ? '（' + detail + '）' : ''));
      }
    });
  }
  var unsupported = {
    Chat: unsupportedGroup('Chat', 'Operit 聊天数据库'),
    Workflow: unsupportedGroup('Workflow', 'Operit 工作流'),
    SoftwareSettings: unsupportedGroup('SoftwareSettings', 'Operit 软件设置'),
    FFmpeg: unsupportedGroup('FFmpeg', '随包 ffmpeg'),
    Music: unsupportedGroup('Music', '音乐播放')
  };
  var Tools = {
    Files: Files,
    System: SystemTools,
    Net: Net,
    UI: UI,
    Memory: Memory,
    Chat: unsupported.Chat,
    Workflow: unsupported.Workflow,
    SoftwareSettings: unsupported.SoftwareSettings,
    FFmpeg: unsupported.FFmpeg,
    Music: unsupported.Music
  };
  return Tools;
}
""".trimIndent()
