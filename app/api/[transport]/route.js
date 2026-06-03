import { createMcpHandler } from "mcp-handler";
import { Redis } from "@upstash/redis";
import { z } from "zod";

const redis = new Redis({
  url: process.env.KV_REST_API_URL || process.env.UPSTASH_REDIS_REST_URL,
  token: process.env.KV_REST_API_TOKEN || process.env.UPSTASH_REDIS_REST_TOKEN
});

const DAY_MS = 24 * 60 * 60 * 1000;

function minutes(ms) {
  return Math.round((ms / 60000) * 10) / 10;
}

async function getSummary(hours = 24) {
  const now = Date.now();
  const safeHours = Math.min(24, Math.max(1, Number(hours || 24)));
  const since = now - safeHours * 60 * 60 * 1000;

  await redis.zremrangebyscore("screentime:logs", 0, now - DAY_MS);

  const items = await redis.zrange("screentime:logs", since, now, {
    byScore: true
  });

  const logs = (items || [])
    .map((item) => (typeof item === "string" ? JSON.parse(item) : item))
    .filter((item) => item && item.end >= since);

  const apps = {};

  for (const log of logs) {
    if (!apps[log.app]) {
      apps[log.app] = {
        app: log.app,
        sessions: 0,
        durationMs: 0
      };
    }

    apps[log.app].sessions += 1;
    apps[log.app].durationMs += Number(log.durationMs || 0);
  }

  const appList = Object.values(apps)
    .map((item) => ({
      app: item.app,
      sessions: item.sessions,
      minutes: minutes(item.durationMs)
    }))
    .sort((a, b) => b.minutes - a.minutes);

  const totalMinutes = appList.reduce((sum, item) => sum + item.minutes, 0);

  return {
    hours: safeHours,
    totalMinutes: Math.round(totalMinutes * 10) / 10,
    apps: appList
  };
}

const handler = createMcpHandler(
  (server) => {
    server.registerTool(
      "get_screen_time_summary",
      {
        title: "Get Screen Time Summary",
        description: "查询过去一段时间内记录到的 App 使用次数和使用时长。",
        inputSchema: {
          hours: z.number().int().min(1).max(24).default(24)
        }
      },
      async ({ hours }) => {
        const summary = await getSummary(hours);

        const lines = summary.apps.length
          ? summary.apps.map(
              (item) =>
                `${item.app}: ${item.sessions} 次，共 ${item.minutes} 分钟`
            )
          : ["还没有记录到已关闭的 App 使用记录。"];

        return {
          content: [
            {
              type: "text",
              text:
                `过去 ${summary.hours} 小时总计 ${summary.totalMinutes} 分钟。\n\n` +
                lines.join("\n")
            }
          ]
        };
      }
    );
  },
  {},
  {
    basePath: "/api",
    maxDuration: 60,
    verboseLogs: true
  }
);

export { handler as GET, handler as POST };
