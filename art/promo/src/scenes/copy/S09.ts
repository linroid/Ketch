// S09 Ready for agents: every word the scene shows, per locale, plus the text that stays the same
// in every locale (the MCP client config and the tool names, which are code). The scene file
// only lays it out and times it, so copy changes happen here.
//
// The tool count comes from facts.json (`mcpTools`), never a literal: the sub states it and the
// `+N more` pill makes the named tools add up to it.
//
// Line breaks (`\n`) are part of the copy, and the scene centres the copy column by them: the
// headline is one line, and the sub has as many lines as it has `\n` plus one, each of which must
// fit the sub's column (SUB_WIDTH in S09Agents.tsx, 700 px). Check a still in every locale after
// changing them (README.md, "Single frames").
import {FACTS} from '../../facts';
import {defineCopy} from '../../locale';

export interface S09Strings {
  eyebrow: string;
  /** One line. */
  headline: string;
  /** Sub for `tools` MCP tools; `backticks` set inline code, `\n` breaks the line. */
  sub: (tools: number) => string;
  /** Last pill, counting the `n` tools the pills do not name. */
  more: (n: number) => string;
}

export const S09_COPY = defineCopy<S09Strings>({
  en: {
    eyebrow: 'MCP server',
    headline: 'Ready for AI agents.',
    sub: (tools) =>
      `\`ketch mcp\` gives assistants ${tools} tools to start,\nwatch and manage downloads.`,
    more: (n) => `+${n} more`,
  },
  'zh-Hans': {
    eyebrow: 'MCP 服务器',
    headline: '让 AI 助手帮你下载。',
    // the break keeps the three verbs together on the second line
    sub: (tools) => `\`ketch mcp\` 给 AI 助手 ${tools} 个工具，\n开始、查看、管理下载都行。`,
    more: (n) => `还有 ${n} 个`,
  },
});

/** Text that is the same in every locale. */
export const S09_SHARED = {
  /** Number of tools `ketch mcp` exposes (library/mcp KetchToolSet). */
  toolCount: FACTS.mcpTools,
  /** Title of the editor card: a file name, so no MCP client's brand shows. */
  configTitle: 'mcp.json',
  /** The MCP client config `{"mcpServers":{"ketch":{"command":"ketch","args":["mcp"]}}}`, pretty-printed. */
  config: [
    '{',
    '  "mcpServers": {',
    '    "ketch": {',
    '      "command": "ketch",',
    '      "args": ["mcp"]',
    '    }',
    '  }',
    '}',
  ],
  /**
   * Tools named on the pills, one array per row, as in KetchToolSet.kt
   * (library/mcp/src/main/kotlin/com/linroid/ketch/mcp). The `+N more` pill follows the last row
   * and counts the rest (getDownload, removeDownload, updateConfig).
   */
  toolRows: [
    ['listDownloads', 'startDownload', 'pauseDownload'],
    ['resumeDownload', 'cancelDownload', 'setSpeedLimit'],
    ['setPriority', 'resolveUrl', 'getStatus'],
  ],
} as const;
