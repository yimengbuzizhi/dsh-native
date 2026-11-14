// M85-E 演示存储插件（storage/v1，只读）：接管 builtin.storage.jsonl 这个默认存储角色。
//
// 宿主解析存储时，启用 + 有 storage 授权的同 id 插件优先；之后会话列表 / 打开会话都走这里：
// - list() 用宿主原语 ctx.storage.exportList() 读内置 files/sessions（不递归活动存储），
//   给每条标题/预览加 `[导出] ` 前缀，证明数据确实经过插件；
// - load(id) 用 ctx.storage.exportLoad(id) 拿原始 JSONL 文档原样返回，宿主把它解成只读会话。
// 写入路径 v1 不开放：停用本插件（或点「恢复内置存储」）后回到可写的 builtin.storage.jsonl。

export const name = 'm85e.readonly-storage';

export function apply(ctx) {
  ctx.storage.register({
    id: 'builtin.storage.jsonl',
    label: '只读导出存储（演示）',
    readOnly: true,
    list() {
      const reply = ctx.storage.exportList();
      if (!reply || reply.ok !== true) return [];
      const sessions = Array.isArray(reply.sessions) ? reply.sessions : [];
      return sessions.map((item) => ({
        id: String(item.id || ''),
        title: item.title ? '[导出] ' + String(item.title) : '[导出] 未命名会话',
        preview: item.preview ? '[导出] ' + String(item.preview) : '',
        createdAt: item.createdAt || 0,
        updatedAt: item.updatedAt || 0,
        messageCount: item.messageCount || 0,
        cwd: item.cwd || '',
        path: item.path || '',
        parentSession: item.parentSession || '',
        delegationDepth: item.delegationDepth || 0,
        origin: item.origin || '',
        isSeeded: item.isSeeded === true
      }));
    },
    load(sessionId) {
      const reply = ctx.storage.exportLoad(sessionId);
      return (reply && reply.ok === true) ? String(reply.doc || '') : '';
    }
  });
}
