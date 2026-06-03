import { Redis } from "@upstash/redis";
import { NextResponse } from "next/server";

export const dynamic = "force-dynamic";

const redis = new Redis({
  url: process.env.KV_REST_API_URL || process.env.UPSTASH_REDIS_REST_URL,
  token: process.env.KV_REST_API_TOKEN || process.env.UPSTASH_REDIS_REST_TOKEN
});

const DAY_MS = 24 * 60 * 60 * 1000;

function json(data, status = 200) {
  return NextResponse.json(data, { status });
}

function checkKey(request) {
  const url = new URL(request.url);
  const inputKey = url.searchParams.get("key");
  const expectedKey = process.env.API_KEY;
  return !expectedKey || inputKey === expectedKey;
}

function minutes(ms) {
  return Math.round((ms / 60000) * 10) / 10;
}

export async function GET(request) {
  if (!checkKey(request)) {
    return json({ ok: false, error: "bad key" }, 401);
  }

  const url = new URL(request.url);
  const hours = Math.min(
    24,
    Math.max(1, Number(url.searchParams.get("hours") || 24))
  );

  const now = Date.now();
  const since = now - hours * 60 * 60 * 1000;

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

  return json({
    ok: true,
    hours,
    totalMinutes: Math.round(totalMinutes * 10) / 10,
    apps: appList
  });
}
