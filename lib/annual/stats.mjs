import { z } from "zod";

const DAY = 86400000;
export const date = z.string().regex(/^\d{4}-\d{2}-\d{2}$/).refine(s => {
  const t = Date.parse(s + "T00:00:00Z");
  return Number.isFinite(t) && new Date(t).toISOString().slice(0, 10) === s && s >= "1970-01-01";
}, "invalid calendar date");
export const pkg = z.string().min(1).max(255).regex(/^[a-zA-Z0-9_]+(?:\.[a-zA-Z0-9_]+)*$/);
export const metrics = z.enum(["usage_ms", "foreground_entries", "active_days"]);
export const year = z.number().int().min(1970).max(2199);
export const daySchema = z.object({
  schema: z.literal(1), device_id: z.string().uuid(), revision: z.number().int().positive().safe(),
  device_timezone:z.string().min(1).max(100).refine(v=>{try{new Intl.DateTimeFormat("en",{timeZone:v});return true;}catch{return false;}}),
  date, coverage: z.enum(["partial","complete","unknown"]), apps: z.array(z.object({
    package_name: pkg, name: z.string().min(1).max(200), system: z.boolean().nullable(),
    usage_ms: z.number().int().min(0).max(48 * 3600000).nullable(),
    foreground_entries: z.number().int().min(0).max(1000000).nullable(),
    source: z.enum(["system","events"]), timezone_id: z.string().min(1).max(100)
  }).strict()).max(750)
}).strict().refine(d => new Set(d.apps.map(a => a.package_name)).size === d.apps.length, "duplicate app")
.refine(d => !d.apps.length || d.coverage !== "unknown", "observed apps require coverage");
export function daysBetween(start, end, max = 366) {
  const n = (Date.parse(end) - Date.parse(start)) / DAY + 1;
  if (!Number.isInteger(n) || n < 1 || n > max) throw Error("Date range must contain 1–" + max + " days");
  return Array.from({length:n}, (_,i) => new Date(Date.parse(start) + i * DAY).toISOString().slice(0,10));
}
function sum(values) {
  const known = values.filter(n => n != null);
  return known.length ? known.reduce((a,b) => a+b,0) : null;
}
function dayMetric(app, coverage, field) {
  if (app) return app[field] ?? null;
  return coverage === "complete" ? 0 : null;
}
export function analyze(name, input, dates, records, lastSync, today) {
  const days = dates.map((date,i) => ({
    date, coverage: records[i]?.coverage || (date > today ? "future" : "unknown"),
    apps: records[i]?.apps || []
  }));
  const counts = {complete:0,partial:0,unknown:0,future:0};
  days.forEach(d => counts[d.coverage]++);
  const elapsed = days.length - counts.future;
  const coverage = {...counts, complete_fraction: elapsed ? counts.complete / elapsed : null};
  const meta = {start_date:dates[0],end_date:dates.at(-1),last_sync_at:lastSync,coverage,
    entry_metric:"estimated foreground sessions", totals:"observed values only; unknown metrics excluded"};
  if (name === "get_data_coverage") return {...meta,days:days.map(({date,coverage})=>({date,coverage}))};
  const appDays = p => days.map(d => {
    const a = d.apps.find(x => x.package_name === p);
    return {date:d.date,coverage:d.coverage,usage_ms:dayMetric(a,d.coverage,"usage_ms"),
      foreground_entries:dayMetric(a,d.coverage,"foreground_entries"),source:a?.source??null};
  });
  const aggregate = rows => ({usage_ms:sum(rows.map(r=>r.usage_ms)),
    foreground_entries:sum(rows.map(r=>r.foreground_entries)),active_days:rows.some(r=>r.usage_ms!=null) ? rows.filter(r=>r.usage_ms>0).length : null,
    known_duration_days:rows.filter(r=>r.usage_ms!=null).length,
    known_entry_days:rows.filter(r=>r.foreground_entries!=null).length});
  if (name === "get_app_usage" || name === "get_app_heatmap") {
    const rows = appDays(input.package_name);
    return {...meta,package_name:input.package_name,...aggregate(rows),days:name === "get_app_usage"?rows:
      rows.map(r=>({date:r.date,coverage:r.coverage,value:r[input.metric]})),metric:input.metric};
  }
  if (name === "compare_apps") {
    const groups = input.packages.map(p=>({package_name:p,rows:appDays(p)}));
    const field = input.metric === "active_days" ? "usage_ms" : input.metric;
    const common = days.map((_,i)=>groups.every(g=>g.rows[i][field]!=null));
    return {...meta,metric:input.metric,common_known_days:common.filter(Boolean).length,
      apps:groups.map(g=>({package_name:g.package_name,...aggregate(g.rows.filter((_,i)=>common[i]))}))};
  }
  const byPackage = new Map(), activeDates = new Set();
  for (const d of days) for (const app of d.apps) {
    if (!input.include_system && app.system === true) continue;
    const p=app.package_name, found=byPackage.get(p) || {package_name:p,name:app.name,rows:[]};
    found.name=app.name;
    found.rows.push({usage_ms:dayMetric(app,d.coverage,"usage_ms"),foreground_entries:dayMetric(app,d.coverage,"foreground_entries")});
    byPackage.set(p,found);
    if (app.usage_ms>0) activeDates.add(d.date);
  }
  const apps=Array.from(byPackage.values(),a=>({package_name:a.package_name,name:a.name,...aggregate(a.rows)}));
  apps.sort((a,b)=>(b[input.sort]??-1)-(a[input.sort]??-1)||a.package_name.localeCompare(b.package_name));
  return {...meta,include_system:input.include_system,app_count:apps.length || (counts.complete ? 0 : null),active_days:activeDates.size || (counts.complete || apps.some(a=>a.usage_ms!=null) ? 0 : null),
    usage_ms:sum(apps.map(a=>a.usage_ms)),foreground_entries:sum(apps.map(a=>a.foreground_entries)),ranking:apps.slice(0,input.limit)};
}
