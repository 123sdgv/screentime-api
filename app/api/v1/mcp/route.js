import { handle } from "../../../../lib/annual/http.mjs";
export const runtime = "nodejs";
export const dynamic = "force-dynamic";
export function GET(request) { return handle(request); }
export function POST(request) { return handle(request); }
