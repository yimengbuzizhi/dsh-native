// 从上游 dsh-tool-goal/lib/index.js 里抽出 renderWrapupContext，逐字节生成两种收尾文案。
// 用途：core:session 的 GoalWrapup 单测夹具（契约 #1 提示词等价）。
import { readFileSync, writeFileSync, mkdirSync } from "node:fs";
import { dirname } from "node:path";

const src = readFileSync(
  "D:/dsh/native/.upstream/oracle/node_modules/.pnpm/node_modules/@deepseek-ai/dsh-tool-goal/lib/index.js",
  "utf8",
);

const start = src.indexOf("function renderWrapupContext");
if (start < 0) throw new Error("renderWrapupContext not found");
const end = src.indexOf("//#endregion", start);
if (end < 0) throw new Error("section end not found");
const body = src.slice(start, end);
// 去掉 JSDoc 与导出包装，只留函数体
const fn = body.slice(body.indexOf("function renderWrapupContext"));
const renderWrapupContext = new Function(`${fn}; return renderWrapupContext;`)();

const objective = '把 "hello.md" 写到工作区\n然后验证（中文 + 引号 + 换行）';
const reason = '缺少 API Key，"无法"继续\n第二行';

const outputs = {
  complete: renderWrapupContext(objective),
  blocked: renderWrapupContext(objective, reason),
};

const outDir = "D:/dsh/native/core/session/src/test/resources/goal-wrapup";
mkdirSync(outDir, { recursive: true });
writeFileSync(`${outDir}/objective.txt`, objective, "utf8");
writeFileSync(`${outDir}/blocked-reason.txt`, reason, "utf8");
writeFileSync(`${outDir}/complete.txt`, outputs.complete[0].text, "utf8");
writeFileSync(`${outDir}/blocked.txt`, outputs.blocked[0].text, "utf8");

// 占位符版：用来核对 Kotlin 里拼接的固定片段
const p1 = renderWrapupContext("__OBJECTIVE__")[0].text;
const p2 = renderWrapupContext("__OBJECTIVE__", "__REASON__")[0].text;
console.log(JSON.stringify({ complete: outputs.complete[0].text, blocked: outputs.blocked[0].text, p1, p2 }, null, 2));
