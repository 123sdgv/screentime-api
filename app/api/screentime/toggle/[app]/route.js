import { Redis } from "@upstash/redis";
import { NextResponse } from "next/server";

export const dynamic = "force-dynamic";

const redis = Redis.fromEnv();
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

export async function GET(request, context) {
  if (!checkKey(request)) {
    return json({ ok: false, error: "bad key" }, 401);
  }

  const params = await context.params;
  const app = decodeURIComponent(params.app || "").trim();

  if (!app) {
    return json({ ok: false, error: "missing app" }, 400);
  }

  const now = Date.now();
  const stateKey = `screentime:state:${app}`;

  await redis.sadd("screentime:apps", app);
  await redis.zremrangebyscore("screentime:logs", 0, now - DAY_MS);

  const raw = await redis.get(stateKey);
  const state = typeof raw === "string" ? JSON.parse(raw) : raw;

  if (state?.open && state?.startedAt) {
    const durationMs = Math.max(0, now - Number(state.startedAt));

    const log = {
      app,
      start: Number(state.startedAt),
      end: now,
      durationMs
    };

    await redis.zadd("screentime:logs", {
      score: now,
      member: JSON.stringify(log)
    });

    await redis.set(
      stateKey,
      JSON.stringify({
        open: false,
        lastAt: now
      })
    );

    return json({
      ok: true,
      app,
      action: "closed",
      seconds: Math.round(durationMs / 1000),
      minutes: Math.round(durationMs / 60000)
    });
  }

  await redis.set(
    stateKey,
    JSON.stringify({
      open: true,
      startedAt: now
    })
  );

  return json({
    ok: true,
    app,
    action: "opened",
    at: now
  });
}
